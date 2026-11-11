package com.fpn.tunnel;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Shared HTTP transport for Mozilla API traffic (FxA, Guardian, the
 * server list) — Fix I. System DNS in Iran is poisoned for Mozilla
 * hosts too, so these requests resolve through the same public-resolver
 * path as the MASQUE upstreams (DoH first, UDP DNS second, system last)
 * and speak TLS to the resolved IP with SNI + hostname verification,
 * using the caller's socket factory (the engine's system-store +
 * Mozilla-roots factory, so validation strength matches the tunnel's).
 * No Android dependencies: this class also runs in off-device tests.
 */
public final class FpnNet {
    private FpnNet() {}

    public static final class Resp {
        public int status;
        public String body = "";
        /** Raw Set-Cookie header values from the response (may be empty). */
        public final java.util.List<String> setCookies = new java.util.ArrayList<>();
    }

    private static final java.util.Map<String, Object[]> DNS_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();

    // ── resolution (mirrors the engine's resolver strategy) ────────────────
    public static InetAddress resolvePublic(File logFile, String host) throws IOException {
        try {
            InetAddress lit = InetAddress.getByName(host);
            if (host.matches("[0-9.]+")) return lit;
        } catch (Exception ignored) { }
        Object[] hit = DNS_CACHE.get(host);
        if (hit != null && System.currentTimeMillis() < (Long) hit[1])
            return (InetAddress) hit[0];
        // Race the DoH resolver set (research: current + 8.8.4.4 /
        // 1.0.0.1 / 9.9.9.9): first public answer wins, so one filtered
        // or dead resolver cannot stall sign-in or pass fetch.
        String[] dohUrls = {
                "https://8.8.8.8/resolve?name=" + host + "&type=A",
                "https://8.8.4.4/resolve?name=" + host + "&type=A",
                "https://1.1.1.1/dns-query?name=" + host + "&type=A",
                "https://1.0.0.1/dns-query?name=" + host + "&type=A",
                "https://9.9.9.9/dns-query?name=" + host + "&type=A"};
        final java.util.concurrent.atomic.AtomicReference<InetAddress> dohHit =
                new java.util.concurrent.atomic.AtomicReference<>();
        final java.util.concurrent.CountDownLatch dohDone =
                new java.util.concurrent.CountDownLatch(1);
        for (final String u : dohUrls) {
            Thread t = new Thread(() -> {
                try {
                    InetAddress ip = dohResolve(u);
                    if (ip != null && dohHit.compareAndSet(null, ip)) dohDone.countDown();
                } catch (Exception ignored) { }
            }, "fpn-doh");
            t.setDaemon(true);
            t.start();
        }
        try { dohDone.await(5, java.util.concurrent.TimeUnit.SECONDS); }
        catch (Exception ignored) { }
        InetAddress raced = dohHit.get();
        if (raced != null) {
            DNS_CACHE.put(host, new Object[]{raced, System.currentTimeMillis() + 300000});
            log(logFile, "resolved " + host + " -> " + raced.getHostAddress() + " via DoH race");
            return raced;
        }
        String[] udpServers = {"8.8.8.8", "8.8.4.4", "1.1.1.1", "1.0.0.1", "9.9.9.9"};
        for (String srv : udpServers) {
            try {
                InetAddress ip = udpResolve(host, srv);
                if (ip != null) {
                    DNS_CACHE.put(host, new Object[]{ip, System.currentTimeMillis() + 300000});
                    log(logFile, "resolved " + host + " -> " + ip.getHostAddress() + " via UDP " + srv);
                    return ip;
                }
            } catch (Exception ignored) { }
        }
        InetAddress sys = InetAddress.getByName(host);
        log(logFile, "resolved " + host + " -> " + sys.getHostAddress() + " via SYSTEM (last resort)");
        return sys;
    }

    private static InetAddress dohResolve(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(6000);
        c.setReadTimeout(6000);
        c.setRequestProperty("Accept", "application/dns-json");
        String body = readAll(c.getInputStream());
        // Tiny hand parse: find "type":1 answers with an IPv4 "data".
        for (String part : body.split("\\},\\{")) {
            if (!part.contains("\"type\":1") && !part.contains("\"type\": 1")) continue;
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\"data\":\"([0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+)\"").matcher(part);
            if (m.find()) {
                InetAddress ip = InetAddress.getByName(m.group(1));
                if (isPublic(ip)) return ip;
            }
        }
        return null;
    }

    private static InetAddress udpResolve(String host, String server) throws Exception {
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

    private static InetAddress parseDnsAnswers(byte[] m, int len) {
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

    private static int skipDnsName(byte[] m, int pos) {
        while (pos < m.length) {
            int l = m[pos] & 0xff;
            if (l == 0) return pos + 1;
            if ((l & 0xc0) == 0xc0) return pos + 2;
            pos += 1 + l;
        }
        return pos;
    }

    public static boolean isPublic(InetAddress ip) {
        if (ip.isAnyLocalAddress() || ip.isLoopbackAddress() || ip.isLinkLocalAddress()
                || ip.isSiteLocalAddress() || ip.isMulticastAddress()) return false;
        byte[] b = ip.getAddress();
        if (b.length == 4) {
            int o1 = b[0] & 0xff, o2 = b[1] & 0xff;
            if (o1 == 100 && o2 >= 64 && o2 <= 127) return false;
            if (o1 == 198 && (o2 == 18 || o2 == 19)) return false;
            if (o1 == 192 && o2 == 0) return false;
            if (o1 == 198 && o2 == 51) return false;
            if (o1 == 203 && o2 == 0) return false;
        }
        return true;
    }

    // ── raw HTTPS over the resolved IP ─────────────────────────────────────
    public static Resp request(SSLSocketFactory factory, File logFile, String method,
                               String urlStr, Map<String, String> headers, byte[] body)
            throws Exception {
        URL url = new URL(urlStr);
        String host = url.getHost();
        int port = url.getPort() != -1 ? url.getPort() : 443;
        String path = url.getPath().isEmpty() ? "/" : url.getPath();
        if (url.getQuery() != null) path += "?" + url.getQuery();
        InetAddress ip = resolvePublic(logFile, host);
        SSLSocket s = (SSLSocket) (factory != null ? factory : SSLSocketFactory.getDefault())
                .createSocket();
        try {
            s.connect(new java.net.InetSocketAddress(ip, port), 10_000);
            s.setSoTimeout(20_000);
            SSLParameters params = s.getSSLParameters();
            params.setServerNames(java.util.Collections.singletonList(new SNIHostName(host)));
            try { params.setApplicationProtocols(new String[]{"http/1.1"}); }
            catch (Exception ignored) { }
            s.setSSLParameters(params);
            s.startHandshake();
            if (!verifyHostname(host, s.getSession()))
                throw new IOException("hostname mismatch: " + host);
            // Fix K: neutral client metadata on every Mozilla/Fastly API
            // call — a fixed en-US language and a generic user agent with
            // no locale, OS build or device strings, unless the caller
            // explicitly set its own.
            java.util.Map<String, String> hdrs = new java.util.LinkedHashMap<>();
            hdrs.put("Accept", "application/json");
            hdrs.put("Accept-Language", "en-US");
            hdrs.put("User-Agent", "FPN-Tunnel/4.2");
            if (headers != null) hdrs.putAll(headers);
            StringBuilder head = new StringBuilder();
            head.append(method).append(' ').append(path).append(" HTTP/1.1\r\n");
            head.append("Host: ").append(host);
            if (port != 443) head.append(':').append(port);
            head.append("\r\n");
            for (Map.Entry<String, String> e : hdrs.entrySet())
                head.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
            if (body != null) head.append("Content-Length: ").append(body.length).append("\r\n");
            head.append("Connection: close\r\n\r\n");
            OutputStream out = s.getOutputStream();
            out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
            if (body != null) out.write(body);
            out.flush();
            return readResponse(new BufferedInputStream(s.getInputStream()));
        } finally {
            try { s.close(); } catch (Exception ignored) { }
        }
    }

    /**
     * Hostname verification against the leaf certificate: dNSName SANs
     * (exact or single-label wildcard), CN only when no SANs exist.
     * Implemented locally because the platform default verifier's
     * behaviour differs between Conscrypt and desktop JDKs — this one
     * is deterministic everywhere.
     */
    static boolean verifyHostname(String host, javax.net.ssl.SSLSession session) {
        try {
            java.security.cert.Certificate[] certs = session.getPeerCertificates();
            java.security.cert.X509Certificate leaf =
                    (java.security.cert.X509Certificate) certs[0];
            boolean sawDnsSan = false;
            java.util.Collection<java.util.List<?>> sans = leaf.getSubjectAlternativeNames();
            if (sans != null) {
                for (java.util.List<?> san : sans) {
                    if (san.size() >= 2 && Integer.valueOf(2).equals(san.get(0))) {
                        sawDnsSan = true;
                        if (hostMatches(String.valueOf(san.get(1)), host)) return true;
                    }
                }
            }
            if (sawDnsSan) return false;
            String dn = leaf.getSubjectX500Principal().getName();
            for (String part : dn.split(",")) {
                String p = part.trim();
                if (p.startsWith("CN=") && hostMatches(p.substring(3).trim(), host)) return true;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean hostMatches(String pattern, String host) {
        pattern = pattern.toLowerCase(Locale.US);
        host = host.toLowerCase(Locale.US);
        if (pattern.equals(host)) return true;
        if (pattern.startsWith("*.")) {
            String suffix = pattern.substring(1); // ".example.com"
            if (!host.endsWith(suffix)) return false;
            String left = host.substring(0, host.length() - suffix.length());
            return !left.isEmpty() && !left.contains(".");
        }
        return false;
    }

    private static Resp readResponse(InputStream in) throws IOException {
        ByteArrayOutputStream headBuf = new ByteArrayOutputStream();
        int matched = 0, b;
        while (matched < 4 && (b = in.read()) != -1) {
            headBuf.write(b);
            if ((matched == 0 || matched == 2) && b == '\r') matched++;
            else if ((matched == 1 || matched == 3) && b == '\n') matched++;
            else matched = (b == '\r') ? 1 : 0;
            if (headBuf.size() > 65536) throw new IOException("oversized response head");
        }
        String head = new String(headBuf.toByteArray(), StandardCharsets.ISO_8859_1);
        String[] lines = head.split("\r\n");
        Resp r = new Resp();
        String[] sp = lines[0].split(" ");
        r.status = sp.length > 1 ? Integer.parseInt(sp[1]) : 0;
        long contentLength = -1;
        boolean chunked = false;
        for (int i = 1; i < lines.length; i++) {
            String ln = lines[i].toLowerCase(Locale.US);
            if (ln.startsWith("content-length:")) {
                try { contentLength = Long.parseLong(ln.substring(15).trim()); }
                catch (Exception ignored) { }
            } else if (ln.startsWith("transfer-encoding:") && ln.contains("chunked")) {
                chunked = true;
            } else if (ln.startsWith("set-cookie:")) {
                r.setCookies.add(lines[i].substring(lines[i].indexOf(':') + 1).trim());
            }
        }
        ByteArrayOutputStream bodyBuf = new ByteArrayOutputStream();
        if (chunked) {
            while (true) {
                String sizeLine = readLine(in);
                int semi = sizeLine.indexOf(';');
                if (semi >= 0) sizeLine = sizeLine.substring(0, semi);
                int n = Integer.parseInt(sizeLine.trim(), 16);
                if (n == 0) { // trailers until empty line
                    String l;
                    while (!(l = readLine(in)).isEmpty()) { /* skip */ }
                    break;
                }
                byte[] chunk = readN(in, n);
                bodyBuf.write(chunk, 0, chunk.length);
                readLine(in); // trailing CRLF
            }
        } else if (contentLength >= 0) {
            byte[] buf = new byte[8192];
            long left = contentLength;
            while (left > 0) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                if (n == -1) break;
                bodyBuf.write(buf, 0, n);
                left -= n;
            }
        } else {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) bodyBuf.write(buf, 0, n);
        }
        r.body = new String(bodyBuf.toByteArray(), StandardCharsets.UTF_8);
        return r;
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') break;
            if (b != '\r') buf.write(b);
        }
        return new String(buf.toByteArray(), StandardCharsets.ISO_8859_1);
    }

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

    private static void log(File logFile, String msg) {
        if (logFile == null) return;
        try {
            String line = new java.text.SimpleDateFormat("HH:mm:ss", Locale.US)
                    .format(new java.util.Date()) + " " + msg + "\n";
            synchronized (FpnNet.class) {
                java.io.FileOutputStream fos = new java.io.FileOutputStream(logFile, true);
                fos.write(line.getBytes("UTF-8"));
                fos.close();
            }
        } catch (Exception ignored) { }
    }
}
