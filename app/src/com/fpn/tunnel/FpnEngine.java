package com.fpn.tunnel;

import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * The FPN engine, ported from the Termux/Node build (run-fpn.mjs) to plain
 * Java so the whole client lives in this one app: Firefox Accounts auth
 * (onepw/HAWK) -> Guardian proxy pass -> local SOCKS5+HTTP proxy on
 * 127.0.0.1:11080 forwarding through Mozilla's MASQUE servers, plus the
 * loopback control API on 127.0.0.1:11081 the UI uses for the location
 * picker. No dependencies beyond the Android runtime.
 *
 * The engine runs inside the VPN app's own process; the app is on the
 * tunnel's exclusion list, so all of the engine's own traffic (FxA,
 * Guardian, the MASQUE upstream) takes the direct connection, exactly
 * like the Termux process did.
 */
public class FpnEngine {
    private static final String TAG = "fpnengine";
    private static final int PORT = 11080;
    private static final int CTRL_PORT = 11081;
    private static final int UPSTREAM_TIMEOUT_MS = 15_000;

    private static final String FXA_API = "https://api.accounts.firefox.com/v1";
    private static final String FIREFOX_CLIENT_ID = "5882386c6d801776";
    private static final String VPN_SCOPES = "profile https://identity.mozilla.com/apps/vpn";
    private static final String GUARDIAN_HOST = "vpn.mozilla.org";
    private static final String SERVERLIST_URL =
            "https://firefox.settings.services.mozilla.com/v1/buckets/main/collections/vpn-serverlist/records";

    private final File sessionFile;
    private final File countryFile;
    private final File logFile;
    private final SSLSocketFactory upstreamFactory;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final Random random = new Random();

    private volatile boolean running;
    private ServerSocket proxyServer;
    private ServerSocket ctrlServer;

    private volatile String country = "AUTO"; // Fix I: new installs default to AUTO
    private volatile Upstream upstream;

    private static final class Upstream {
        final String hostname; final int port; final String name;
        long rttMs = -1;
        Upstream(String h, int p, String n) { hostname = h; port = p; name = n; }
    }

    private volatile String effectiveCountry;
    private volatile long lastRepickMs;
    private final java.util.concurrent.BlockingQueue<SSLSocket> tlsPool =
            new java.util.concurrent.LinkedBlockingQueue<>();

    // Fix A: per-host CONNECT health. A gateway that answers MASQUE
    // CONNECTs with 5xx is "sick" for a while and excluded from server
    // picks, even though its TCP/TLS probe still looks fast — that
    // mismatch stranded users on a dead exit in the 2026-11-12 01:14
    // incident (ca.m1 502-ing every CONNECT for a minute while the
    // probe-based repick kept re-electing it).
    private final java.util.Map<String, Long> sickUntilMs =
            new java.util.concurrent.ConcurrentHashMap<>();
    // Research constant (iran.md): a 502/503-ing gateway is quarantined
    // 5–15 min — long enough to ride out a sick-gateway episode like
    // the 2026-11-12 01:14 one (~1 min of 502s, but such episodes recur
    // in clusters), short enough to rejoin once it is healthy again.
    private static final long SICK_MS = 600_000;

    /** Mozilla destination-policy refusals are not gateway sickness. */
    private static boolean isDestRefusal(String statusLine) {
        return statusLine != null && (statusLine.contains("Prohibited")
                || statusLine.contains("Destination"));
    }

    private void markSick(Upstream up, int code) {
        if (up == null) return;
        sickUntilMs.put(up.hostname, System.currentTimeMillis() + SICK_MS);
        log("upstream sick: " + up.hostname + " (" + code + ")");
    }

    private boolean isSick(Upstream u) {
        Long t = sickUntilMs.get(u.hostname);
        return t != null && System.currentTimeMillis() < t;
    }

    private void clearSick(Upstream up) {
        if (up != null) sickUntilMs.remove(up.hostname);
    }

    /** Drop the cached Guardian pass so the next currentPass() refetches. */
    public synchronized void forcePassRefresh() {
        passCache = null;
        passExpiresSec = 0;
        tokenCache = null;
        tokenExpiresMs = 0;
        log("pass force-refreshed");
    }

    // ── caches ──────────────────────────────────────────────────────────────
    private String tokenCache; private long tokenExpiresMs;
    private String passCache; private long passExpiresSec;
    private List<Country> serverList; private long serverListAtMs;

    private static final class Country {
        final String code; final String name; final List<Upstream> servers;
        Country(String c, String n, List<Upstream> s) { code = c; name = n; servers = s; }
    }

    public FpnEngine(File sessionFile, File countryFile, File logFile, SSLSocketFactory upstreamFactory) {
        this.sessionFile = sessionFile;
        this.countryFile = countryFile;
        this.logFile = logFile;
        this.upstreamFactory = upstreamFactory != null ? upstreamFactory
                : (SSLSocketFactory) SSLSocketFactory.getDefault();
        try {
            String c = readFile(countryFile).trim().toUpperCase();
            if (c.matches("[A-Z]{2}") || c.equals("AUTO")) country = c;
        } catch (Exception ignored) {}
    }

    // ── lifecycle ───────────────────────────────────────────────────────────
    public synchronized void start() throws Exception {
        if (running) return;
        Upstream up = pickUpstreamFor(country);
        upstream = up;
        currentPass(); // warm the pass so auth errors surface at startup
        proxyServer = new ServerSocket(PORT, 50, InetAddress.getByName("127.0.0.1"));
        try {
            ctrlServer = new ServerSocket(CTRL_PORT, 50, InetAddress.getByName("127.0.0.1"));
        } catch (Exception e) {
            // Never leak a half-bound engine: if the control socket
            // cannot bind, release the proxy socket before failing.
            try { proxyServer.close(); } catch (Exception ignored) {}
            proxyServer = null;
            throw e;
        }
        running = true;
        new Thread(this::acceptLoop, "fpn-proxy").start();
        new Thread(this::ctrlLoop, "fpn-ctrl").start();
        log("proxy on 127.0.0.1:" + PORT + " -> " + up.hostname + ":" + up.port + " (" + country + ")"
                + " rtt=" + up.rttMs + "ms");
        startPoolWarmer();
    }

    public synchronized void stop() {
        running = false;
        try { if (proxyServer != null) proxyServer.close(); } catch (Exception ignored) {}
        try { if (ctrlServer != null) ctrlServer.close(); } catch (Exception ignored) {}
        proxyServer = null; ctrlServer = null;
    }

    public boolean isRunning() { return running; }

    private void acceptLoop() {
        while (running) {
            try {
                Socket s = proxyServer.accept();
                pool.execute(() -> handleClient(s));
            } catch (IOException e) {
                if (running) log("accept failed: " + e);
                return;
            }
        }
    }

    // ── FxA auth (onepw/HAWK) ───────────────────────────────────────────────
    private static byte[] hkdfSha256(byte[] ikm, byte[] salt, byte[] info, int len) throws Exception {
        if (salt == null || salt.length == 0) salt = new byte[32];
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(salt, "HmacSHA256"));
        byte[] prk = mac.doFinal(ikm);
        ByteArrayOutputStream okm = new ByteArrayOutputStream();
        byte[] t = new byte[0];
        int counter = 1;
        while (okm.size() < len) {
            mac.init(new SecretKeySpec(prk, "HmacSHA256"));
            mac.update(t);
            mac.update(info);
            mac.update((byte) counter++);
            t = mac.doFinal();
            okm.write(t, 0, t.length);
        }
        byte[] out = new byte[len];
        System.arraycopy(okm.toByteArray(), 0, out, 0, len);
        return out;
    }

    private static final class Creds { String id; byte[] key; }

    private Creds credsFor(String sessionTokenHex) throws Exception {
        byte[] out = hkdfSha256(hexToBytes(sessionTokenHex), null,
                "identity.mozilla.com/picl/v1/sessionToken".getBytes(StandardCharsets.UTF_8), 64);
        Creds c = new Creds();
        byte[] id = new byte[32];
        System.arraycopy(out, 0, id, 0, 32);
        c.id = bytesToHex(id);
        c.key = new byte[32];
        System.arraycopy(out, 32, c.key, 0, 32);
        return c;
    }

    private String hawkHeader(URL url, String body, Creds creds, long ts) throws Exception {
        byte[] nonceBytes = new byte[6];
        new SecureRandom().nextBytes(nonceBytes);
        String nonce = Base64.encodeToString(nonceBytes, Base64.NO_WRAP);
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        String hash = Base64.encodeToString(
                sha.digest(("hawk.1.payload\napplication/json\n" + body + "\n").getBytes(StandardCharsets.UTF_8)),
                Base64.NO_WRAP);
        int port = url.getPort() != -1 ? url.getPort() : 443;
        String path = url.getPath() + (url.getQuery() != null ? "?" + url.getQuery() : "");
        String normalized = "hawk.1.header\n" + ts + "\n" + nonce + "\nPOST\n" + path + "\n"
                + url.getHost() + "\n" + port + "\n" + hash + "\n\n";
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(creds.key, "HmacSHA256"));
        String macB64 = Base64.encodeToString(mac.doFinal(normalized.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
        return "Hawk id=\"" + creds.id + "\", ts=\"" + ts + "\", nonce=\"" + nonce
                + "\", hash=\"" + hash + "\", mac=\"" + macB64 + "\"";
    }

    /**
     * Fix I transport for Mozilla APIs: DoH-resolved raw TLS via FpnNet
     * first (Iranian DNS poisoning cannot break it, and TLS validation
     * is the engine's own factory); the classic URLConnection (system
     * DNS) remains as a fallback if the raw path fails outright.
     */
    private FpnNet.Resp httpTransport(String method, URL url, String authHeader,
                                      byte[] body) throws Exception {
        java.util.Map<String, String> headers = new java.util.LinkedHashMap<>();
        if (body != null) headers.put("Content-Type", "application/json");
        if (authHeader != null) headers.put("Authorization", authHeader);
        try {
            return FpnNet.request(upstreamFactory, logFile, method, url.toString(), headers, body);
        } catch (Exception netErr) {
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setRequestMethod(method);
            c.setConnectTimeout(15_000); c.setReadTimeout(15_000);
            c.setRequestProperty("Accept-Language", "en-US"); // Fix K
            c.setRequestProperty("User-Agent", "FPN-Tunnel/4.2");
            for (java.util.Map.Entry<String, String> e : headers.entrySet())
                c.setRequestProperty(e.getKey(), e.getValue());
            if (body != null) {
                c.setDoOutput(true);
                c.getOutputStream().write(body);
            }
            FpnNet.Resp r = new FpnNet.Resp();
            r.status = c.getResponseCode();
            InputStream in = r.status >= 400 ? c.getErrorStream() : c.getInputStream();
            r.body = in != null ? readAll(in) : "";
            return r;
        }
    }

    private JSONObject fxaPost(String path, JSONObject payload, String sessionToken) throws Exception {
        URL url = new URL(FXA_API + path);
        String body = payload.toString();
        Creds creds = sessionToken != null ? credsFor(sessionToken) : null;
        long ts = System.currentTimeMillis() / 1000;
        for (int attempt = 0; ; attempt++) {
            String auth = creds != null ? hawkHeader(url, body, creds, ts) : null;
            FpnNet.Resp r = httpTransport("POST", url, auth, body.getBytes(StandardCharsets.UTF_8));
            JSONObject data = new JSONObject(r.body == null || r.body.isEmpty() ? "{}" : r.body);
            if (creds != null && data.optInt("errno", -1) == 111 && data.has("serverTime") && attempt == 0) {
                ts = data.getLong("serverTime");
                continue;
            }
            data.put("_status", r.status);
            return data;
        }
    }

    private synchronized String getAccessToken() throws Exception {
        if (tokenCache != null && tokenExpiresMs - System.currentTimeMillis() > 300_000) return tokenCache;
        JSONObject doc = new JSONObject(readFile(sessionFile));
        String sessionToken = doc.optJSONObject("accountData") != null
                ? doc.getJSONObject("accountData").optString("sessionToken", null) : null;
        if (sessionToken == null || sessionToken.isEmpty())
            throw new Exception("no session token: sign in first");
        JSONObject payload = new JSONObject()
                .put("client_id", FIREFOX_CLIENT_ID)
                .put("grant_type", "fxa-credentials")
                .put("scope", VPN_SCOPES)
                .put("ttl", 86400);
        JSONObject data = fxaPost("/oauth/token", payload, sessionToken);
        if (data.has("access_token")) {
            tokenCache = data.getString("access_token");
            tokenExpiresMs = System.currentTimeMillis() + data.optLong("expires_in", 86400) * 1000;
            return tokenCache;
        }
        if (data.optInt("errno", -1) == 110)
            throw new Exception("Firefox sign-in has ended (errno 110): sign in again");
        throw new Exception("FxA refused the VPN token: " + data.optString("message", "HTTP " + data.optInt("_status"))
                + " (errno " + data.optInt("errno", -1) + ")");
    }

    // ── Guardian (proxy pass) ───────────────────────────────────────────────
    private synchronized String currentPass() throws Exception {
        if (passCache != null && System.currentTimeMillis() / 1000 < passExpiresSec - 120) return passCache;
        String accessToken = getAccessToken();
        FpnNet.Resp r = httpTransport("GET",
                new URL("https://" + GUARDIAN_HOST + "/api/v1/fpn/token"),
                "Bearer " + accessToken, null);
        if (r.status != 200) throw new Exception("proxy pass: HTTP " + r.status);
        JSONObject data = new JSONObject(r.body);
        String token = data.optString("token", null);
        if (token == null) throw new Exception("proxy pass response had no token");
        String[] parts = token.split("\\.");
        JSONObject body = new JSONObject(new String(
                Base64.decode(parts[1], Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING), StandardCharsets.UTF_8));
        passCache = token;
        passExpiresSec = body.getLong("exp");
        log("pass refreshed; expires in " + (passExpiresSec - System.currentTimeMillis() / 1000) + " s");
        return passCache;
    }

    // ── Server list ─────────────────────────────────────────────────────────
    private List<Country> loadServerList() throws Exception {
        if (serverList != null && System.currentTimeMillis() - serverListAtMs < 3600_000) return serverList;
        FpnNet.Resp r = httpTransport("GET", new URL(SERVERLIST_URL), null, null);
        if (r.status != 200) throw new Exception("server list: HTTP " + r.status);
        JSONObject body = new JSONObject(r.body);
        JSONArray data = body.optJSONArray("data");
        List<Country> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (data != null) {
            for (int i = 0; i < data.length(); i++) {
                JSONObject raw = data.getJSONObject(i);
                String code = raw.optString("code", null);
                if (code == null || code.equals("REC") || !seen.add(code)) continue;
                List<Upstream> servers = new ArrayList<>();
                JSONArray cities = raw.optJSONArray("cities");
                if (cities != null) {
                    for (int j = 0; j < cities.length(); j++) {
                        JSONArray ss = cities.getJSONObject(j).optJSONArray("servers");
                        if (ss == null) continue;
                        for (int k = 0; k < ss.length(); k++) {
                            JSONObject s = ss.getJSONObject(k);
                            String host = s.optString("hostname", null);
                            if (host == null || s.optBoolean("quarantined", false)) continue;
                            servers.add(new Upstream(host, s.optInt("port", 443), raw.optString("name", code)));
                        }
                    }
                }
                if (!servers.isEmpty()) out.add(new Country(code, raw.optString("name", code), servers));
            }
        }
        serverList = out; serverListAtMs = System.currentTimeMillis();
        return out;
    }

    /**
     * Pick the FASTEST reachable server, measured by TCP connect time.
     * For a specific country: probe all of its servers in parallel.
     * For "AUTO": probe the Iran-nearby set (DE/NL/AT/FR/BE/IT/DK/ES/GB/SE)
     * and take the global best — the returned server's country becomes
     * the effective exit country.
     */
    private Upstream pickUpstreamFor(String code) throws Exception {
        List<Country> all = loadServerList();
        if ("AUTO".equals(code)) return pickAuto(all);
        for (Country c : all) {
            if (c.code.equals(code)) {
                // Fix A: if every server of the chosen country is sick
                // (CONNECT 5xx), fall back to the AUTO set rather than
                // hammering a gateway that is erroring.
                boolean allSick = !c.servers.isEmpty();
                for (Upstream u : c.servers) if (!isSick(u)) { allSick = false; break; }
                if (allSick) {
                    log("failover: all " + code + " servers sick — falling back to AUTO");
                    try {
                        Upstream alt = pickAuto(all);
                        if (alt != null && !isSick(alt)) return alt;
                    } catch (Exception ignored) { }
                    // No healthy fallback anywhere: a fresh pass sometimes
                    // clears gateway state — force one and keep the pick.
                    forcePassRefresh();
                    drainPool();
                }
                effectiveCountry = code;
                return probeBest(c.servers);
            }
        }
        throw new Exception("no usable server in " + code);
    }

    private Upstream pickAuto(List<Country> all) throws Exception {
        String[] near = {"DE", "NL", "AT", "FR", "BE", "IT", "DK", "ES", "GB", "SE"};
        List<Upstream> cands = new ArrayList<>();
        java.util.Map<Upstream, String> owner = new java.util.HashMap<>();
        for (String cc : near) {
            for (Country c : all) {
                if (!c.code.equals(cc)) continue;
                for (Upstream u : c.servers) { cands.add(u); owner.put(u, cc); }
            }
        }
        if (cands.isEmpty()) throw new Exception("no usable server for AUTO");
        Upstream best = probeBest(cands);
        effectiveCountry = owner.get(best);
        return best;
    }

    private Upstream probeBest(List<Upstream> servers) {
        // Fix A: CONNECT-sick servers are not candidates while any
        // healthy one exists (their TCP probe still answers fast).
        List<Upstream> healthy = new ArrayList<>();
        for (Upstream u : servers) if (!isSick(u)) healthy.add(u);
        if (!healthy.isEmpty() && healthy.size() < servers.size()) {
            log("probe: skipping " + (servers.size() - healthy.size()) + " sick server(s)");
            servers = healthy;
        }
        if (servers.size() == 1) {
            Upstream u = servers.get(0);
            u.rttMs = probeRtt(u);
            return u;
        }
        final java.util.Map<Upstream, Long> results = new java.util.concurrent.ConcurrentHashMap<>();
        List<Thread> ts = new ArrayList<>();
        for (final Upstream u : servers) {
            Thread t = new Thread(() -> {
                long r = probeRtt(u);
                if (r >= 0) results.put(u, r);
            });
            t.setDaemon(true);
            t.start();
            ts.add(t);
        }
        for (Thread t : ts) {
            try { t.join(3500); } catch (InterruptedException ignored) { }
        }
        Upstream best = null;
        long bestR = Long.MAX_VALUE;
        for (java.util.Map.Entry<Upstream, Long> e : results.entrySet()) {
            if (e.getValue() < bestR) { bestR = e.getValue(); best = e.getKey(); }
        }
        if (best == null) {
            best = servers.get(random.nextInt(servers.size()));
            best.rttMs = -1;
            return best;
        }
        best.rttMs = bestR;
        log("probe: best " + best.hostname + " rtt=" + bestR + "ms of "
                + results.size() + "/" + servers.size() + " candidates");
        return best;
    }

    private long probeRtt(Upstream u) {
        long t0 = System.currentTimeMillis();
        try {
            InetAddress ip = resolvePublic(u.hostname);
            Socket s = new Socket();
            s.connect(new java.net.InetSocketAddress(ip, u.port), 2500);
            long r = System.currentTimeMillis() - t0;
            try { s.close(); } catch (Exception ignored) { }
            return r;
        } catch (Exception e) {
            return -1;
        }
    }

    /** Keep a few TLS-handshaked sockets to the current upstream ready, so
     *  a new flow pays only the CONNECT round-trip, not TCP+TLS setup. */
    private void startPoolWarmer() {
        final Upstream up = upstream;
        if (up == null) return;
        new Thread(() -> {
            while (running && upstream == up) {
                try {
                    if (tlsPool.size() < 3) {
                        SSLSocket s = handshakeTls(up);
                        if (s != null) tlsPool.offer(s);
                        else Thread.sleep(1200);
                    } else {
                        Thread.sleep(400);
                    }
                } catch (Exception e) {
                    try { Thread.sleep(1200); } catch (InterruptedException ignored) { }
                }
            }
        }, "fpn-pool").start();
    }

    private void drainPool() {
        SSLSocket s;
        while ((s = tlsPool.poll()) != null) {
            try { s.close(); } catch (Exception ignored) { }
        }
    }

    /** If dials keep failing, re-probe and switch upstream (≤ once / 30 s). */
    private void maybeRepick() {
        long now = System.currentTimeMillis();
        if (now - lastRepickMs < 30000) return;
        lastRepickMs = now;
        new Thread(() -> { repickNow(); }, "fpn-repick").start();
    }

    /**
     * Synchronous re-pick (Fix A/G): probe again with sick servers
     * excluded and switch upstream if a different one wins. Returns
     * true when the upstream changed.
     */
    public boolean repickNow() {
        try {
            Upstream best = pickUpstreamFor(country);
            if (best != null && upstream != null && !best.hostname.equals(upstream.hostname)) {
                Upstream old = upstream;
                upstream = best;
                drainPool();
                startPoolWarmer();
                if (isSick(old)) log("failover: " + old.hostname + " -> " + best.hostname);
                log("repick: upstream -> " + best.hostname + " rtt=" + best.rttMs + "ms");
                return true;
            }
        } catch (Exception ignored) { }
        return false;
    }

    public String currentUpstreamHost() { return upstream != null ? upstream.hostname : null; }
    public String requestedCountry() { return country; }
    public String effectiveCountry() { return effectiveCountry; }

    /** Public wrapper so the service/doctor can switch country live. */
    public JSONObject changeCountry(String code) throws Exception { return setCountry(code); }

    private synchronized JSONObject setCountry(String code) throws Exception {
        code = code.toUpperCase();
        Upstream up = pickUpstreamFor(code); // throws on an unknown country
        country = code;
        upstream = up;
        drainPool();
        startPoolWarmer();
        try {
            java.io.FileWriter w = new java.io.FileWriter(countryFile);
            w.write(code + "\n"); w.close();
        } catch (Exception ignored) {}
        log("country -> " + code + " (" + up.name + ") via " + up.hostname + ":" + up.port
                + " rtt=" + up.rttMs + "ms");
        return new JSONObject().put("country", code).put("name", up.name)
                .put("effectiveCountry", effectiveCountry)
                .put("upstream", up.hostname + ":" + up.port)
                .put("rttMs", up.rttMs);
    }

    // ── Proxy core ──────────────────────────────────────────────────────────
    private void handleClient(Socket socket) {
        try {
            socket.setTcpNoDelay(true);
            PushbackInputStream in = new PushbackInputStream(new BufferedInputStream(socket.getInputStream()), 65536);
            int first = in.read();
            if (first == -1) { socket.close(); return; }
            in.unread(first);
            if (first == 0x05) handleSocks(socket, in);
            else handleHttp(socket, in);
        } catch (Exception e) {
            try { socket.close(); } catch (Exception ignored) {}
        }
    }

    private void handleSocks(Socket socket, PushbackInputStream in) throws Exception {
        OutputStream out = socket.getOutputStream();
        int ver = in.read();
        int nMethods = in.read();
        if (ver != 0x05 || nMethods < 0) throw new IOException("bad greeting");
        byte[] methods = readN(in, nMethods);
        boolean hasNoAuth = false, hasUserPass = false;
        for (byte m : methods) { if (m == 0x00) hasNoAuth = true; if (m == 0x02) hasUserPass = true; }
        if (!hasNoAuth) {
            if (!hasUserPass) { out.write(new byte[]{0x05, (byte) 0xff}); out.flush(); throw new IOException("no auth method"); }
            out.write(new byte[]{0x05, 0x02}); out.flush();
            int v = in.read(); // subnegotiation version
            int ulen = in.read();
            readN(in, ulen);
            int plen = in.read();
            readN(in, plen);
            if (v != 0x01) { out.write(new byte[]{0x01, 0x01}); out.flush(); throw new IOException("bad subnegotiation"); }
            out.write(new byte[]{0x01, 0x00}); out.flush();
        } else {
            out.write(new byte[]{0x05, 0x00}); out.flush();
        }
        // request
        byte[] head = readN(in, 4);
        if (head[0] != 0x05 || head[1] != 0x01) {
            out.write(new byte[]{0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0}); out.flush();
            throw new IOException("only CONNECT is supported");
        }
        String host;
        int atyp = head[3] & 0xff;
        if (atyp == 0x01) {
            byte[] a = readN(in, 4);
            host = (a[0] & 0xff) + "." + (a[1] & 0xff) + "." + (a[2] & 0xff) + "." + (a[3] & 0xff);
        } else if (atyp == 0x03) {
            int len = in.read();
            host = new String(readN(in, len), StandardCharsets.UTF_8);
        } else if (atyp == 0x04) {
            byte[] a = readN(in, 16);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 16; i += 2) {
                if (i > 0) sb.append(':');
                sb.append(Integer.toHexString(((a[i] & 0xff) << 8) | (a[i + 1] & 0xff)));
            }
            host = sb.toString();
        } else {
            out.write(new byte[]{0x05, 0x08, 0x00, 0x01, 0, 0, 0, 0, 0, 0}); out.flush();
            throw new IOException("unknown address type");
        }
        byte[] p = readN(in, 2);
        int port = ((p[0] & 0xff) << 8) | (p[1] & 0xff);
        try {
            UpConn up = dial(host, port);
            out.write(new byte[]{0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0}); out.flush();
            splice(socket, in, out, up);
        } catch (Exception e) {
            log("dial failed " + host + ":" + port + " - " + e.getMessage());
            try { out.write(new byte[]{0x05, 0x01, 0x00, 0x01, 0, 0, 0, 0, 0, 0}); out.flush(); } catch (Exception ignored) {}
            socket.close();
        }
    }

    private static final int HTTP_MAX_HEAD = 64 * 1024;
    private static final Pattern CONNECT_RE = Pattern.compile("^(?:\\[([^\\]]+)\\]|([^:]+)):(\\d+)$");
    private static final Pattern STRIP_HEADERS = Pattern.compile("^(proxy-[^:]*|connection|keep-alive):", Pattern.CASE_INSENSITIVE);

    private void handleHttp(Socket socket, PushbackInputStream in) throws Exception {
        OutputStream out = socket.getOutputStream();
        ByteArrayOutputStream headBuf = new ByteArrayOutputStream();
        int matched = 0; // bytes of "\r\n\r\n" matched so far
        while (matched < 4) {
            int b = in.read();
            if (b == -1) throw new IOException("eof in head");
            headBuf.write(b);
            if (headBuf.size() > HTTP_MAX_HEAD) { httpError(out, "431 Request Header Fields Too Large"); return; }
            if ((matched == 0 || matched == 2) && b == '\r') matched++;
            else if ((matched == 1 || matched == 3) && b == '\n') matched++;
            else matched = (b == '\r') ? 1 : 0;
        }
        String head = new String(headBuf.toByteArray(), StandardCharsets.ISO_8859_1);
        String[] lines = head.split("\r\n");
        String[] firstLine = lines[0].split(" ");
        if (firstLine.length < 2) { httpError(out, "400 Bad Request"); return; }
        String method = firstLine[0], url = firstLine[1];
        String version = firstLine.length > 2 ? firstLine[2] : "HTTP/1.1";
        String host; int port; byte[] request = null;
        if (method.equals("CONNECT")) {
            Matcher m = CONNECT_RE.matcher(url);
            if (!m.matches()) { httpError(out, "400 Bad Request"); return; }
            host = m.group(1) != null ? m.group(1) : m.group(2);
            port = Integer.parseInt(m.group(3));
        } else {
            URL u;
            try { u = new URL(url); } catch (Exception e) { httpError(out, "400 Bad Request"); return; }
            if (!"http".equals(u.getProtocol())) { httpError(out, "400 Bad Request"); return; }
            host = u.getHost().replaceAll("^\\[|\\]$", "");
            port = u.getPort() != -1 ? u.getPort() : 80;
            StringBuilder sb = new StringBuilder();
            sb.append(method).append(' ').append(u.getPath().isEmpty() ? "/" : u.getPath());
            if (u.getQuery() != null) sb.append('?').append(u.getQuery());
            sb.append(' ').append(version).append("\r\n");
            for (int i = 1; i < lines.length; i++) {
                if (lines[i].isEmpty() || STRIP_HEADERS.matcher(lines[i]).find()) continue;
                sb.append(lines[i]).append("\r\n");
            }
            sb.append("Connection: close\r\n\r\n");
            request = sb.toString().getBytes(StandardCharsets.ISO_8859_1);
        }
        UpConn up;
        try { up = dial(host, port); }
        catch (Exception e) {
            log("dial failed " + host + ":" + port + " - " + e.getMessage());
            httpError(out, "502 Bad Gateway");
            return;
        }
        if (request != null) {
            up.out.write(request); up.out.flush();
        } else {
            out.write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
        }
        splice(socket, in, out, up);
    }

    private static void httpError(OutputStream out, String status) throws IOException {
        out.write(("HTTP/1.1 " + status + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                .getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }

    private static final class UpConn {
        SSLSocket socket; PushbackInputStream in; OutputStream out; byte[] leftover;
    }

    // ── upstream resolution ────────────────────────────────────────────────
    // The system resolver's answers for the MASQUE hosts proved unreliable
    // in the VPN app's context (a sinkhole 100.64.0.1 + connection resets
    // in one test window, an intercepting certificate in another), while
    // direct resolver paths from the same phone returned the real Fastly
    // anycast addresses. So the engine resolves upstream hosts itself:
    // DoH to 8.8.8.8 / 1.1.1.1 by IP literal first, then plain UDP DNS to
    // public resolvers; system DNS only as a last resort. Non-public
    // answers are rejected. TLS then connects to the resolved IP with
    // SNI + hostname verification against the real hostname.
    private final java.util.Map<String, Object[]> dnsCache = new java.util.concurrent.ConcurrentHashMap<>();

    private InetAddress resolvePublic(String host) throws IOException {
        Object[] hit = dnsCache.get(host);
        if (hit != null && System.currentTimeMillis() < (Long) hit[1])
            return (InetAddress) hit[0];
        String[] dohUrls = {
                "https://8.8.8.8/resolve?name=" + host + "&type=A",
                "https://1.1.1.1/dns-query?name=" + host + "&type=A"};
        for (String u : dohUrls) {
            try {
                InetAddress ip = dohResolve(u);
                if (ip != null) {
                    dnsCache.put(host, new Object[]{ip, System.currentTimeMillis() + 300000});
                    log("resolved " + host + " -> " + ip.getHostAddress() + " via DoH");
                    return ip;
                }
            } catch (Exception e) { Log.d(TAG, "doh failed: " + e); }
        }
        String[] udpServers = {"8.8.8.8", "1.1.1.1", "9.9.9.9"};
        for (String srv : udpServers) {
            try {
                InetAddress ip = udpResolve(host, srv);
                if (ip != null) {
                    dnsCache.put(host, new Object[]{ip, System.currentTimeMillis() + 300000});
                    log("resolved " + host + " -> " + ip.getHostAddress() + " via UDP " + srv);
                    return ip;
                }
            } catch (Exception e) { Log.d(TAG, "udp dns failed " + srv + ": " + e); }
        }
        InetAddress sys = InetAddress.getByName(host);
        log("resolved " + host + " -> " + sys.getHostAddress() + " via SYSTEM (last resort)");
        return sys;
    }

    private InetAddress dohResolve(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(6000);
        c.setReadTimeout(6000);
        c.setRequestProperty("Accept", "application/dns-json");
        String body = readAll(c.getInputStream());
        JSONArray answers = new JSONObject(body).optJSONArray("Answer");
        if (answers == null) return null;
        for (int i = 0; i < answers.length(); i++) {
            JSONObject a = answers.getJSONObject(i);
            if (a.optInt("type") == 1) {
                InetAddress ip = InetAddress.getByName(a.getString("data"));
                if (isPublic(ip)) return ip;
            }
        }
        return null;
    }

    private InetAddress udpResolve(String host, String server) throws Exception {
        ByteArrayOutputStream q = new ByteArrayOutputStream();
        int id = (int) (Math.random() * 65536);
        q.write((id >> 8) & 0xff); q.write(id & 0xff);
        q.write(0x01); q.write(0x00);
        q.write(0x00); q.write(0x01);
        q.write(0x00); q.write(0x00);
        q.write(0x00); q.write(0x00);
        q.write(0x00); q.write(0x00);
        for (String label : host.split("\\.")) {
            q.write(label.length());
            q.write(label.getBytes("UTF-8"));
        }
        q.write(0x00);
        q.write(0x00); q.write(0x01);
        q.write(0x00); q.write(0x01);
        byte[] query = q.toByteArray();
        java.net.DatagramSocket sock = new java.net.DatagramSocket();
        try {
            sock.setSoTimeout(4000);
            sock.send(new java.net.DatagramPacket(query, query.length,
                    InetAddress.getByName(server), 53));
            byte[] buf = new byte[1024];
            java.net.DatagramPacket resp = new java.net.DatagramPacket(buf, buf.length);
            sock.receive(resp);
            return parseDnsAnswers(buf, resp.getLength());
        } finally { sock.close(); }
    }

    private InetAddress parseDnsAnswers(byte[] m, int len) {
        if (len < 12) return null;
        int qd = ((m[4] & 0xff) << 8) | (m[5] & 0xff);
        int an = ((m[6] & 0xff) << 8) | (m[7] & 0xff);
        int pos = 12;
        for (int i = 0; i < qd; i++) { pos = skipDnsName(m, pos); pos += 4; }
        for (int i = 0; i < an; i++) {
            pos = skipDnsName(m, pos);
            if (pos + 10 > len) break;
            int type = ((m[pos] & 0xff) << 8) | (m[pos + 1] & 0xff);
            int rdlen = ((m[pos + 8] & 0xff) << 8) | (m[pos + 9] & 0xff);
            int rdata = pos + 10;
            if (type == 1 && rdlen == 4 && rdata + 4 <= len) {
                try {
                    InetAddress ip = InetAddress.getByAddress(
                            new byte[]{m[rdata], m[rdata + 1], m[rdata + 2], m[rdata + 3]});
                    if (isPublic(ip)) return ip;
                } catch (Exception ignored) { }
            }
            pos = rdata + rdlen;
        }
        return null;
    }

    private int skipDnsName(byte[] m, int pos) {
        while (pos < m.length) {
            int l = m[pos] & 0xff;
            if (l == 0) return pos + 1;
            if ((l & 0xc0) == 0xc0) return pos + 2;
            pos += 1 + l;
        }
        return pos;
    }

    private boolean isPublic(InetAddress ip) {
        if (ip.isAnyLocalAddress() || ip.isLoopbackAddress() || ip.isLinkLocalAddress()
                || ip.isSiteLocalAddress() || ip.isMulticastAddress()) return false;
        byte[] b = ip.getAddress();
        if (b.length == 4) {
            int o1 = b[0] & 0xff, o2 = b[1] & 0xff;
            if (o1 == 100 && o2 >= 64 && o2 <= 127) return false;   // CGNAT 100.64/10
            if (o1 == 198 && (o2 == 18 || o2 == 19)) return false; // 198.18/15
            if (o1 == 192 && o2 == 0) return false;
            if (o1 == 198 && o2 == 51) return false;
            if (o1 == 203 && o2 == 0) return false;
        }
        return true;
    }

    /** TCP + TLS to the upstream, fully handshaked and hostname-verified. */
    private SSLSocket handshakeTls(Upstream up) throws Exception {
        InetAddress upIp = resolvePublic(up.hostname);
        SSLSocket s = (SSLSocket) upstreamFactory.createSocket();
        try {
            // Fix I: bound the connect phase to ~6 s — on a filtered
            // network a dead exit must be abandoned fast, not after 15 s.
            s.connect(new java.net.InetSocketAddress(upIp, up.port), 6000);
            s.setSoTimeout(UPSTREAM_TIMEOUT_MS);
            // SNI for the real hostname + ALPN http/1.1 — the MASQUE
            // frontend expects both; the Node build set them explicitly.
            javax.net.ssl.SSLParameters params = s.getSSLParameters();
            params.setServerNames(java.util.Collections.singletonList(
                    new javax.net.ssl.SNIHostName(up.hostname)));
            if (android.os.Build.VERSION.SDK_INT >= 29)
                params.setApplicationProtocols(new String[]{"http/1.1"});
            s.setSSLParameters(params);
            s.startHandshake();
            if (!javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier()
                    .verify(up.hostname, s.getSession()))
                throw new IOException("upstream hostname mismatch: " + up.hostname);
            return s;
        } catch (Exception e) {
            try { s.close(); } catch (Exception ignored) { }
            throw e;
        }
    }

    private UpConn openUpstream(String passToken, String host, int port) throws Exception {
        Upstream up = upstream;
        if (up == null) throw new IOException("no upstream picked");
        return openUpstream(up, passToken, host, port, true);
    }

    private UpConn openUpstream(Upstream up, String passToken, String host, int port,
                                boolean usePool) throws Exception {
        SSLSocket s = usePool ? tlsPool.poll() : null;
        if (s != null && (s.isClosed() || !s.isConnected())) s = null;
        if (s == null) {
            s = handshakeTls(up);
        } else {
            try { s.setSoTimeout(UPSTREAM_TIMEOUT_MS); }
            catch (Exception e) {
                try { s.close(); } catch (Exception ignored) { }
                s = handshakeTls(up);
            }
        }
        try {
            String hh = host.contains(":") ? "[" + host + "]" : host;
            OutputStream out = s.getOutputStream();
            out.write(("CONNECT " + hh + ":" + port + " HTTP/1.1\r\nHost: " + hh + ":" + port
                    + "\r\nProxy-Authorization: Bearer " + passToken + "\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            PushbackInputStream in = new PushbackInputStream(new BufferedInputStream(s.getInputStream()), 65536);
            ByteArrayOutputStream headBuf = new ByteArrayOutputStream();
            int matched = 0;
            while (matched < 4) {
                int b = in.read();
                if (b == -1) throw new IOException("upstream closed during CONNECT");
                headBuf.write(b);
                if (headBuf.size() > 65536) throw new IOException("oversized CONNECT response");
                if ((matched == 0 || matched == 2) && b == '\r') matched++;
                else if ((matched == 1 || matched == 3) && b == '\n') matched++;
                else matched = (b == '\r') ? 1 : 0;
            }
            String head = new String(headBuf.toByteArray(), StandardCharsets.ISO_8859_1);
            String statusLine = head.substring(0, head.indexOf("\r\n")).trim();
            String[] sp = statusLine.split(" ");
            int code = sp.length > 1 ? Integer.parseInt(sp[1]) : 0;
            if (code != 200) {
                // Fix A: a 5xx from the CONNECT marks this gateway sick
                // in the dial path itself (not gated by any throttle) —
                // EXCEPT a destination-policy refusal ("502 Destination
                // IP Prohibited": direct IPs and some ports are refused
                // by Mozilla for every user). That 502 says nothing
                // about gateway health; quarantining on it would exile
                // a healthy gateway (seen live 2026-11-12 01:56, when
                // mtalk:5228 and direct-IP dials 502'd through the
                // working CA gateway).
                if (code >= 500 && code <= 599 && !isDestRefusal(statusLine))
                    markSick(up, code);
                // CONNECT-health taxonomy (iran.md): 407 means the pass
                // itself was rejected — the repair is a fresh pass, not
                // a server quarantine.
                if (code == 407) forcePassRefresh();
                throw new IOException("upstream: " + statusLine);
            }
            clearSick(up);
            s.setSoTimeout(0);
            UpConn c = new UpConn();
            c.socket = s; c.in = in; c.out = out;
            return c;
        } catch (Exception e) {
            try { s.close(); } catch (Exception ignored) {}
            throw e;
        }
    }

    private UpConn dial(String host, int port) throws Exception {
        String passToken = currentPass();
        Exception lastErr = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try { return openUpstream(passToken, host, port); }
            catch (Exception e) {
                lastErr = e;
                // 407: the pass was rejected and forcePassRefresh() has
                // already run inside openUpstream — refetch and retry
                // with the fresh pass instead of giving up.
                if (e.getMessage() != null && e.getMessage().contains("HTTP/1.1 407")) {
                    try { passToken = currentPass(); } catch (Exception ignored) { }
                    if (attempt < 3) { Thread.sleep(250L * attempt); continue; }
                    break;
                }
                if (e.getMessage() != null && e.getMessage().matches(".*HTTP/1\\.1 4.*")) break;
                // A destination refusal will fail identically on every
                // retry and every server — stop at once, no repick churn.
                if (e.getMessage() != null && isDestRefusal(e.getMessage())) break;
                // Fix A: on a gateway 5xx, give the (throttled) repick a
                // chance to move us before burning the remaining retries
                // on the same sick host.
                if (e.getMessage() != null && e.getMessage().contains("upstream: HTTP/1.1 5"))
                    maybeRepick();
                if (attempt < 3) Thread.sleep(250L * attempt);
            }
        }
        maybeRepick();
        throw lastErr;
    }

    // ── Fix I/G: real CONNECT health checks ──────────────────────────────────
    /** Outcome of one real MASQUE CONNECT health dial. */
    public static final class Health {
        public boolean ok;
        public String code = "E7";   // Doctor reason code when !ok
        public String detail = "";
        public String host;
    }

    private Health classifyDialError(Exception e) {
        Health h = new Health();
        String m = String.valueOf(e.getMessage());
        h.detail = m;
        if (isDestRefusal(m)) h.code = "E7"; // policy refusal, not a health signal
        else if (m.contains("upstream: HTTP/1.1 5") || m.contains("proxy pass: HTTP 5")) h.code = "E4";
        else if (m.contains("upstream: HTTP/1.1 407")) h.code = "E5";
        else if (m.contains("no session token") || m.contains("errno 110")
                || m.contains("sign-in has ended")) h.code = "E2";
        else if (m.contains("proxy pass: HTTP 401") || m.contains("proxy pass: HTTP 403")
                || m.contains("FxA refused")) h.code = "E5";
        else if (m.contains("errno 138")) h.code = "E2";
        else h.code = "E3";
        return h;
    }

    /**
     * One REAL health dial: a full MASQUE CONNECT to a well-known host
     * through the current upstream (Fix G check 4). Unlike the TCP probe
     * this proves the gateway actually forwards traffic.
     */
    public Health healthDial() {
        Health h = new Health();
        h.host = currentUpstreamHost();
        try {
            UpConn c = dial("www.cloudflare.com", 443);
            try { c.socket.close(); } catch (Exception ignored) { }
            h.ok = true;
            h.code = "OK";
            h.detail = "CONNECT www.cloudflare.com:443 succeeded via " + h.host;
            return h;
        } catch (Exception e) {
            Health f = classifyDialError(e);
            f.host = h.host;
            return f;
        }
    }

    /** Fix G check 3: fetch (or reuse) the Guardian pass; throws on failure. */
    public String passCheck() throws Exception {
        String p = currentPass();
        long left = passExpiresSec - System.currentTimeMillis() / 1000;
        return "pass ok, expires in " + left + " s";
    }

    /** Classify a pass-fetch failure into a Doctor code (E2/E5/E3/E4). */
    public String classifyPassError(Exception e) {
        return classifyDialError(e).code;
    }

    /**
     * Fix G check 5: compare the system resolver's answer for the current
     * MASQUE host against the engine's own DoH answer. A private/CGNAT
     * system answer is the sinkhole signature seen in the field.
     */
    public String dnsCheck() {
        Upstream up = upstream;
        if (up == null) return "no upstream picked yet";
        StringBuilder sb = new StringBuilder();
        try {
            InetAddress sys = InetAddress.getByName(up.hostname);
            sb.append("system DNS ").append(up.hostname).append(" -> ")
              .append(sys.getHostAddress()).append(isPublic(sys) ? " (public)" : " (PRIVATE — sinkhole signature)");
        } catch (Exception e) {
            sb.append("system DNS failed: ").append(e.getMessage());
        }
        try {
            InetAddress good = resolvePublic(up.hostname);
            sb.append("; engine resolver -> ").append(good.getHostAddress());
        } catch (Exception e) {
            sb.append("; engine resolver failed: ").append(e.getMessage());
        }
        return sb.toString();
    }

    /**
     * Fix I — Smart Connect: real MASQUE CONNECT health dials, in
     * parallel, against a shortlist (the AUTO set + CA + US). The first
     * server that actually passes a CONNECT becomes the upstream —
     * TCP reachability alone (the old probe) is not enough on filtered
     * networks. Returns the winning description, or null when no
     * candidate passed (caller keeps the probe-based pick).
     */
    public String smartConnect() {
        try {
            List<Country> all = loadServerList();
            String[] codes = {"DE", "NL", "AT", "FR", "BE", "IT", "DK", "ES", "GB", "SE", "CA", "US"};
            final java.util.Map<Upstream, String> owner = new java.util.HashMap<>();
            List<Upstream> cands = new ArrayList<>();
            for (String cc : codes) {
                for (Country c : all) {
                    if (!c.code.equals(cc)) continue;
                    for (Upstream u : c.servers) {
                        if (isSick(u)) continue;
                        cands.add(u); owner.put(u, cc);
                        if (cands.size() >= 16) break;
                    }
                }
                if (cands.size() >= 16) break;
            }
            if (cands.isEmpty()) return null;
            final String pass = currentPass();
            final java.util.concurrent.atomic.AtomicReference<Upstream> winner =
                    new java.util.concurrent.atomic.AtomicReference<>();
            final java.util.concurrent.CountDownLatch done =
                    new java.util.concurrent.CountDownLatch(1);
            // Happy-Eyeballs probing (iran.md): waves of 3 candidates
            // at ~300 ms stagger, so a filtered network is not flooded
            // with a dozen simultaneous TLS handshakes and the first
            // healthy server stops the later waves.
            for (int waveStart = 0; waveStart < cands.size() && winner.get() == null;
                    waveStart += 3) {
                int waveEnd = Math.min(waveStart + 3, cands.size());
                for (int i = waveStart; i < waveEnd; i++) {
                    final Upstream u = cands.get(i);
                    Thread t = new Thread(() -> {
                        try {
                            UpConn c = openUpstream(u, pass, "www.cloudflare.com", 443, false);
                            try { c.socket.close(); } catch (Exception ignored) { }
                            if (winner.compareAndSet(null, u)) done.countDown();
                        } catch (Exception ignored) { }
                    }, "fpn-smart");
                    t.setDaemon(true);
                    t.start();
                }
                if (waveEnd < cands.size() && winner.get() == null)
                    done.await(300, java.util.concurrent.TimeUnit.MILLISECONDS);
            }
            done.await(9, java.util.concurrent.TimeUnit.SECONDS);
            Upstream w = winner.get();
            if (w == null) {
                log("smartconnect: no candidate passed a real CONNECT");
                return null;
            }
            upstream = w;
            effectiveCountry = owner.get(w);
            drainPool();
            startPoolWarmer();
            log("smartconnect: " + w.hostname + " (" + effectiveCountry + ") healthy");
            return w.hostname + " (" + effectiveCountry + ")";
        } catch (Exception e) {
            log("smartconnect failed: " + e);
            return null;
        }
    }

    // Fix L: byte counters for the local proxy. Every tunneled byte
    // crosses one of the two splice pumps, so counting here counts all
    // traffic the tunnel carries — session-scoped, reset per connect;
    // FpnVpnService folds them into the persisted today/total counters.
    private final java.util.concurrent.atomic.AtomicLong sessionUp =
            new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong sessionDown =
            new java.util.concurrent.atomic.AtomicLong();

    public long sessionBytesUp() { return sessionUp.get(); }
    public long sessionBytesDown() { return sessionDown.get(); }
    public void resetSessionCounters() { sessionUp.set(0); sessionDown.set(0); }

    private void splice(Socket client, InputStream clientIn, OutputStream clientOut, UpConn up) {
        Thread a = new Thread(() -> pump(clientIn, up.out, client, up.socket, true), "fpn-up");
        Thread b = new Thread(() -> pump(up.in, clientOut, up.socket, client, false), "fpn-down");
        a.setDaemon(true); b.setDaemon(true);
        a.start(); b.start();
    }

    private void pump(InputStream in, OutputStream out, Socket s1, Socket s2, boolean isUp) {
        byte[] buf = new byte[32768];
        try {
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                out.flush();
                if (isUp) sessionUp.addAndGet(n); else sessionDown.addAndGet(n);
            }
        } catch (Exception ignored) {}
        try { s1.close(); } catch (Exception ignored) {}
        try { s2.close(); } catch (Exception ignored) {}
    }

    // ── Control API (loopback) ──────────────────────────────────────────────
    private void ctrlLoop() {
        while (running) {
            try {
                Socket s = ctrlServer.accept();
                pool.execute(() -> handleCtrl(s));
            } catch (IOException e) {
                if (running) log("ctrl accept failed: " + e);
                return;
            }
        }
    }

    private void handleCtrl(Socket s) {
        try {
            s.setSoTimeout(10_000);
            BufferedInputStream in = new BufferedInputStream(s.getInputStream());
            ByteArrayOutputStream headBuf = new ByteArrayOutputStream();
            int matched = 0, b;
            while (matched < 4 && (b = in.read()) != -1) {
                headBuf.write(b);
                if ((matched == 0 || matched == 2) && b == '\r') matched++;
                else if ((matched == 1 || matched == 3) && b == '\n') matched++;
                else matched = (b == '\r') ? 1 : 0;
                if (headBuf.size() > 16384) break;
            }
            String requestLine = new String(headBuf.toByteArray(), StandardCharsets.ISO_8859_1).split("\r\n")[0];
            String[] parts = requestLine.split(" ");
            URL u = new URL("http://127.0.0.1" + (parts.length > 1 ? parts[1] : "/"));
            int code = 200;
            JSONObject obj;
            try {
                if (u.getPath().equals("/status")) {
                    obj = new JSONObject()
                            .put("country", effectiveCountry != null ? effectiveCountry : country)
                            .put("requestedCountry", country)
                            .put("upstream", upstream != null ? upstream.hostname + ":" + upstream.port : null)
                            .put("server", upstream != null ? upstream.hostname : null)
                            .put("rttMs", upstream != null ? upstream.rttMs : -1)
                            .put("passExpiresInSec", passCache != null
                                    ? Math.max(0, passExpiresSec - System.currentTimeMillis() / 1000) : JSONObject.NULL)
                            .put("bytesUp", sessionUp.get())
                            .put("bytesDown", sessionDown.get());
                } else if (u.getPath().equals("/country")) {
                    String c = queryParam(u.getQuery(), "c");
                    if (c == null) { code = 400; obj = new JSONObject().put("error", "missing c"); }
                    else obj = setCountry(c);
                } else if (u.getPath().equals("/countries")) {
                    JSONArray arr = new JSONArray();
                    for (Country cn : loadServerList()) arr.put(new JSONObject().put("code", cn.code).put("name", cn.name));
                    OutputStream out = s.getOutputStream();
                    writeJson(out, 200, arr.toString());
                    s.close();
                    return;
                } else {
                    code = 404;
                    obj = new JSONObject().put("error", "unknown path");
                }
            } catch (Exception e) {
                code = 400;
                obj = new JSONObject().put("error", String.valueOf(e.getMessage()));
            }
            writeJson(s.getOutputStream(), code, obj.toString());
            s.close();
        } catch (Exception e) {
            try { s.close(); } catch (Exception ignored) {}
        }
    }

    private static void writeJson(OutputStream out, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + code + " OK\r\nContent-Type: application/json\r\nContent-Length: "
                + bytes.length + "\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        out.write(bytes);
        out.flush();
    }

    private static String queryParam(String query, String name) {
        if (query == null) return null;
        for (String kv : query.split("&")) {
            int eq = kv.indexOf('=');
            if (eq > 0 && kv.substring(0, eq).equals(name)) return kv.substring(eq + 1);
        }
        return null;
    }

    /**
     * The upstream TLS factory. Validation order inside the trust manager:
     * (1) the system trust store via Conscrypt; (2) if that cannot anchor
     * the chain, a manual JCA PKIX validation whose trust anchors are the
     * union of the system issuers and the Mozilla root bundle shipped in
     * assets (cacert.pem — the root program the Node build validates
     * with), with a Collection CertStore of the bundle so the builder can
     * substitute cross-signed issuers. Every step is logged to the engine
     * log file — no silent fallbacks.
     */
    private static java.security.cert.PKIXParameters pkixPrototype;
    private static final java.util.concurrent.atomic.AtomicBoolean trustOkLogged =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    public static SSLSocketFactory buildUpstreamFactory(android.content.Context ctx, final File logFile) {
        try {
            javax.net.ssl.TrustManagerFactory sysTmf = javax.net.ssl.TrustManagerFactory
                    .getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
            sysTmf.init((java.security.KeyStore) null);
            final javax.net.ssl.X509TrustManager sysTm = pickX509(sysTmf);

            java.security.cert.CertificateFactory cf =
                    java.security.cert.CertificateFactory.getInstance("X.509");
            InputStream in = ctx.getAssets().open("cacert.pem");
            String pem = readAll(in);
            in.close();
            // Parse the bundle block by block: Conscrypt's
            // generateCertificates(InputStream) returned ZERO certificates
            // for the multi-root PEM (its comment preamble / stream format),
            // which silently left the fallback anchor set empty in the
            // field. Per-block generateCertificate is deterministic.
            final java.util.List<java.security.cert.Certificate> mozilla = new java.util.ArrayList<>();
            int idx = 0;
            while (true) {
                int b = pem.indexOf("-----BEGIN CERTIFICATE-----", idx);
                if (b < 0) break;
                int e = pem.indexOf("-----END CERTIFICATE-----", b);
                if (e < 0) break;
                e += "-----END CERTIFICATE-----".length();
                try {
                    mozilla.add(cf.generateCertificate(new java.io.ByteArrayInputStream(
                            pem.substring(b, e).getBytes("UTF-8"))));
                } catch (Exception ignored) { }
                idx = e;
            }

            java.util.Set<java.security.cert.TrustAnchor> anchors = new java.util.HashSet<>();
            for (java.security.cert.X509Certificate c : sysTm.getAcceptedIssuers())
                anchors.add(new java.security.cert.TrustAnchor(c, null));
            for (java.security.cert.Certificate c : mozilla)
                anchors.add(new java.security.cert.TrustAnchor((java.security.cert.X509Certificate) c, null));
            java.security.cert.PKIXParameters proto = new java.security.cert.PKIXParameters(anchors);
            proto.setRevocationEnabled(false);
            proto.addCertStore(java.security.cert.CertStore.getInstance("Collection",
                    new java.security.cert.CollectionCertStoreParameters(mozilla)));
            pkixPrototype = proto;
            trustLog(logFile, "factory built: mozilla roots=" + mozilla.size()
                    + ", anchors=" + anchors.size());

            javax.net.ssl.X509TrustManager combined = new javax.net.ssl.X509TrustManager() {
                public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType)
                        throws java.security.cert.CertificateException {
                    try { sysTm.checkServerTrusted(chain, authType); return; }
                    catch (java.security.cert.CertificateException sysErr) {
                        try {
                            java.security.cert.CertificateFactory cf2 =
                                    java.security.cert.CertificateFactory.getInstance("X.509");
                            java.security.cert.CertPath path =
                                    cf2.generateCertPath(java.util.Arrays.asList(chain));
                            java.security.cert.PKIXParameters params =
                                    (java.security.cert.PKIXParameters) pkixPrototype.clone();
                            java.security.cert.CertPathValidator.getInstance("PKIX").validate(path, params);
                            if (trustOkLogged.compareAndSet(false, true))
                                trustLog(logFile, "trust: PKIX fallback anchored chain of "
                                        + chain.length + " (system store could not)");
                        } catch (Exception e) {
                            trustLog(logFile, "trust: PKIX fallback FAILED: " + e
                                    + " | system error was: " + sysErr);
                            throw new java.security.cert.CertificateException(
                                    "PKIX fallback failed: " + e.getMessage(), e);
                        }
                    }
                }
                public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType)
                        throws java.security.cert.CertificateException {
                    sysTm.checkClientTrusted(chain, authType);
                }
                public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                    return sysTm.getAcceptedIssuers();
                }
            };
            javax.net.ssl.SSLContext sc = javax.net.ssl.SSLContext.getInstance("TLS");
            sc.init(null, new javax.net.ssl.TrustManager[]{combined}, null);
            return sc.getSocketFactory();
        } catch (Exception e) {
            trustLog(logFile, "factory FAILED, default factory in use: " + e);
            Log.w(TAG, "upstream factory build failed: " + e);
            return null;
        }
    }

    private static void trustLog(File logFile, String msg) {
        if (logFile == null) return;
        try {
            String line = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                    .format(new java.util.Date()) + " " + msg + "\n";
            synchronized (FpnEngine.class) {
                java.io.FileOutputStream fos = new java.io.FileOutputStream(logFile, true);
                fos.write(line.getBytes("UTF-8"));
                fos.close();
            }
        } catch (Exception ignored) { }
    }

    private static javax.net.ssl.X509TrustManager pickX509(javax.net.ssl.TrustManagerFactory tmf) {
        for (javax.net.ssl.TrustManager tm : tmf.getTrustManagers())
            if (tm instanceof javax.net.ssl.X509TrustManager) return (javax.net.ssl.X509TrustManager) tm;
        throw new IllegalStateException("no X509 trust manager");
    }

    // ── small helpers ───────────────────────────────────────────────────────
    private static byte[] readN(InputStream in, int n) throws IOException {
        byte[] buf = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(buf, off, n - off);
            if (r == -1) throw new IOException("eof");
            off += r;
        }
        return buf;
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
        in.close();
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String readFile(File f) throws IOException {
        return readAll(new FileInputStream(f));
    }

    private static byte[] hexToBytes(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++)
            out[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
        return out;
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b & 0xff));
        return sb.toString();
    }

    private void log(String s) {
        Log.i(TAG, s);
        if (logFile != null) {
            try (java.io.FileWriter w = new java.io.FileWriter(logFile, true)) {
                w.write(new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                        .format(new java.util.Date()) + " " + s + "\n");
            } catch (Exception ignored) {}
        }
    }
}
