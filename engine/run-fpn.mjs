// FPN Tunnel engine for Android (Termux): a Firefox Private Network client.
// Port of foxhole-vpn's audited auth.ts + guardian.ts + proxy.ts core,
// minus desktop-only features (split tunneling, relay mode, global TUN).
// Auth: the sign-in at ~/.mozilla/firefox/foxhole.default/signedInUser.json
// (kept fresh by keepalive.sh from Firefox Android's own account store).
import net from "node:net";
import tls from "node:tls";
import https from "node:https";
import http from "node:http";
import { createHash, createHmac, hkdfSync, randomBytes } from "node:crypto";
import { readFileSync, writeFileSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";

const PROFILE_DIR = join(homedir(), ".mozilla", "firefox", "foxhole.default");
const PORT = Number(process.env.FPN_PORT || 11080);
const CTRL_PORT = Number(process.env.FPN_CTRL_PORT || 11081);
const COUNTRY_FILE = join(homedir(), "foxhole-android", "country.txt");
let country = (() => {
  try {
    const c = readFileSync(COUNTRY_FILE, "utf8").trim().toUpperCase();
    if (/^[A-Z]{2}$/.test(c)) return c;
  } catch {}
  return (process.env.FPN_COUNTRY || "DE").toUpperCase();
})();
const UPSTREAM_TIMEOUT_MS = 15_000;
const log = (...a) => console.log(new Date().toISOString(), ...a);

// ── FxA auth (onepw/HAWK), from foxhole's auth.ts ────────────────────────────
const FXA_API = "https://api.accounts.firefox.com/v1";
const FIREFOX_CLIENT_ID = "5882386c6d801776";
const VPN_SCOPES = "profile https://identity.mozilla.com/apps/vpn";

function sessionCredentials(sessionToken) {
  const out = Buffer.from(hkdfSync("sha256", Buffer.from(sessionToken, "hex"), Buffer.alloc(0),
    "identity.mozilla.com/picl/v1/sessionToken", 64));
  return { id: out.subarray(0, 32).toString("hex"), key: out.subarray(32) };
}

function hawkHeader(url, body, creds, ts) {
  const nonce = randomBytes(6).toString("base64");
  const hash = createHash("sha256").update(`hawk.1.payload\napplication/json\n${body}\n`).digest("base64");
  const port = url.port || (url.protocol === "https:" ? "443" : "80");
  const normalized = `hawk.1.header\n${ts}\n${nonce}\nPOST\n${url.pathname}${url.search}\n${url.hostname}\n${port}\n${hash}\n\n`;
  const mac = createHmac("sha256", creds.key).update(normalized).digest("base64");
  return `Hawk id="${creds.id}", ts="${ts}", nonce="${nonce}", hash="${hash}", mac="${mac}"`;
}

async function fxaPost(path, payload, sessionToken) {
  const url = new URL(FXA_API + path);
  const body = JSON.stringify(payload);
  const creds = sessionToken ? sessionCredentials(sessionToken) : null;
  let ts = Math.floor(Date.now() / 1000);
  for (let attempt = 0; ; attempt++) {
    const headers = { "content-type": "application/json" };
    if (creds) headers.authorization = hawkHeader(url, body, creds, ts);
    const res = await fetch(url, { method: "POST", headers, body });
    const data = (await res.json().catch(() => ({}))) ?? {};
    if (creds && data.errno === 111 && typeof data.serverTime === "number" && attempt === 0) {
      ts = data.serverTime; continue;
    }
    return { ok: res.ok, status: res.status, data };
  }
}

let tokenCache = null; // { token, expires }
async function getAccessToken() {
  if (tokenCache && tokenCache.expires - Date.now() > 300_000) return tokenCache.token;
  const data0 = JSON.parse(readFileSync(join(PROFILE_DIR, "signedInUser.json"), "utf8"));
  const sessionToken = data0.accountData?.sessionToken;
  if (!sessionToken) throw new Error("no session token in sign-in file");
  const { ok, status, data } = await fxaPost("/oauth/token",
    { client_id: FIREFOX_CLIENT_ID, grant_type: "fxa-credentials", scope: VPN_SCOPES, ttl: 86400 },
    sessionToken);
  if (ok && typeof data.access_token === "string") {
    const expiresIn = typeof data.expires_in === "number" ? data.expires_in : 86400;
    tokenCache = { token: data.access_token, expires: Date.now() + expiresIn * 1000 };
    return tokenCache.token;
  }
  if (data.errno === 110) throw new Error("Firefox sign-in has ended (errno 110): sign in to Firefox again");
  throw new Error(`FxA refused the VPN token: ${data.message ?? `HTTP ${status}`} (errno ${data.errno ?? "?"})`);
}

// ── Guardian (proxy pass), from foxhole's guardian.ts ────────────────────────
const GUARDIAN_HOST = "vpn.mozilla.org";
let passCache = null; // { token, expires }

function guardianGet(path, accessToken) {
  return new Promise((resolve, reject) => {
    const req = https.request(
      { host: GUARDIAN_HOST, path, method: "GET",
        headers: { Host: GUARDIAN_HOST, Authorization: `Bearer ${accessToken}`, "Content-Type": "application/json" },
        timeout: 10_000 },
      (res) => {
        const chunks = [];
        res.on("data", (c) => chunks.push(c));
        res.on("end", () => resolve({ status: res.statusCode, headers: res.headers,
          body: chunks.length ? Buffer.concat(chunks).toString() : "" }));
      });
    req.on("timeout", () => req.destroy(new Error("timed out")));
    req.on("error", (e) => reject(new Error(`can't reach ${GUARDIAN_HOST}`, { cause: e })));
    req.end();
  });
}

async function currentPass() {
  if (passCache && Date.now() / 1000 < passCache.expires - 120) return passCache.token;
  const accessToken = await getAccessToken();
  const res = await guardianGet("/api/v1/fpn/token", accessToken);
  if (res.status !== 200) throw new Error(`proxy pass: HTTP ${res.status}`);
  const data = JSON.parse(res.body);
  if (!data.token) throw new Error("proxy pass response had no token");
  const body = JSON.parse(Buffer.from(data.token.split(".")[1], "base64url").toString());
  passCache = { token: data.token, expires: body.exp };
  log("pass refreshed; expires in", body.exp - Math.floor(Date.now() / 1000), "s");
  return passCache.token;
}

// ── Server list (public Mozilla Remote Settings), foxhole's serverlist.ts ───
let serverList = null; // [{ code, name, servers: [{hostname, port}] }]
let serverListAt = 0;
async function loadServerList() {
  if (serverList && Date.now() - serverListAt < 3600_000) return serverList;
  const res = await fetch("https://firefox.settings.services.mozilla.com/v1/buckets/main/collections/vpn-serverlist/records");
  if (!res.ok) throw new Error(`server list: HTTP ${res.status}`);
  const body = await res.json();
  const seen = new Set();
  const out = [];
  for (const raw of body.data ?? []) {
    if (!raw.code || raw.code === "REC" || seen.has(raw.code)) continue;
    seen.add(raw.code);
    const servers = (raw.cities ?? []).flatMap((c) => c.servers ?? [])
      .filter((s) => s.hostname && !s.quarantined)
      .map((s) => ({ hostname: s.hostname, port: s.port ?? 443 }));
    if (servers.length) out.push({ code: raw.code, name: raw.name ?? raw.code, servers });
  }
  serverList = out;
  serverListAt = Date.now();
  return out;
}

async function pickUpstreamFor(code) {
  const list = await loadServerList();
  const entry = list.find((c) => c.code === code);
  if (!entry) throw new Error(`no usable server in ${code}`);
  const s = entry.servers[Math.floor(Math.random() * entry.servers.length)];
  return { hostname: s.hostname, port: s.port, name: entry.name };
}

async function setCountry(code) {
  code = String(code || "").toUpperCase();
  const up = await pickUpstreamFor(code); // throws on an unknown country
  country = code;
  upstream = { hostname: up.hostname, port: up.port };
  try { writeFileSync(COUNTRY_FILE, code + "\n"); } catch {}
  log(`country -> ${code} (${up.name}) via ${up.hostname}:${up.port}`);
  return { country, name: up.name, upstream: `${up.hostname}:${up.port}` };
}

// ── Local SOCKS5 + HTTP proxy, from foxhole's proxy.ts (essentials) ──────────
const SOCKS_VERSION = 0x05, CMD_CONNECT = 0x01;
const ATYP_IPV4 = 0x01, ATYP_DOMAIN = 0x03, ATYP_IPV6 = 0x04;

function nextChunk(socket) {
  return new Promise((resolve, reject) => { socket.once("data", resolve); socket.once("error", reject); });
}
function replyFailure(socket) {
  socket.write(Buffer.from([SOCKS_VERSION, 0x01, 0x00, ATYP_IPV4, 0, 0, 0, 0, 0, 0]));
}
function replySuccess(socket) {
  socket.write(Buffer.from([SOCKS_VERSION, 0x00, 0x00, ATYP_IPV4, 0, 0, 0, 0, 0, 0]));
}

function readRequest(socket) {
  return new Promise((resolve, reject) => {
    socket.once("data", (buf) => {
      if (buf[0] !== SOCKS_VERSION || buf[1] !== CMD_CONNECT) {
        socket.write(Buffer.from([SOCKS_VERSION, 0x07, 0x00, ATYP_IPV4, 0, 0, 0, 0, 0, 0]));
        return reject(new Error("only CONNECT is supported"));
      }
      const atyp = buf[3];
      let host, offset;
      if (atyp === ATYP_IPV4) { host = `${buf[4]}.${buf[5]}.${buf[6]}.${buf[7]}`; offset = 8; }
      else if (atyp === ATYP_DOMAIN) { const len = buf[4]; host = buf.toString("utf8", 5, 5 + len); offset = 5 + len; }
      else if (atyp === ATYP_IPV6) {
        const parts = [];
        for (let i = 0; i < 16; i += 2) parts.push(buf.readUInt16BE(4 + i).toString(16));
        host = parts.join(":"); offset = 20;
      } else {
        socket.write(Buffer.from([SOCKS_VERSION, 0x08, 0x00, ATYP_IPV4, 0, 0, 0, 0, 0, 0]));
        return reject(new Error("unknown address type"));
      }
      resolve({ host, port: buf.readUInt16BE(offset) });
    });
    socket.once("error", reject);
  });
}

let upstream = null;
function openUpstream(passToken, target) {
  return new Promise((resolve, reject) => {
    let settled = false;
    const fail = (err) => { if (settled) return; settled = true; clearTimeout(timer); up.destroy(); reject(err); };
    const timer = setTimeout(() => fail(new Error("upstream timed out")), UPSTREAM_TIMEOUT_MS);
    const up = tls.connect(
      { host: upstream.hostname, port: upstream.port, servername: upstream.hostname, ALPNProtocols: ["http/1.1"] },
      () => {
        const hh = target.host.includes(":") ? `[${target.host}]` : target.host;
        up.write(`CONNECT ${hh}:${target.port} HTTP/1.1\r\nHost: ${hh}:${target.port}\r\nProxy-Authorization: Bearer ${passToken}\r\n\r\n`);
      });
    let header = Buffer.alloc(0);
    const onData = (chunk) => {
      header = Buffer.concat([header, chunk]);
      const end = header.indexOf("\r\n\r\n");
      if (end === -1) { if (header.length > 65536) fail(new Error("oversized CONNECT response")); return; }
      up.removeListener("data", onData);
      const statusLine = header.toString("latin1", 0, header.indexOf("\r\n")).trim();
      const code = Number(statusLine.split(" ")[1]);
      if (code !== 200) return fail(new Error(`upstream: ${statusLine}`));
      settled = true; clearTimeout(timer);
      const leftover = header.subarray(end + 4);
      resolve(up);
      if (leftover.length) up.emit("data", leftover);
    };
    up.on("data", onData);
    up.once("error", fail);
    up.once("close", () => fail(new Error("upstream closed")));
  });
}

async function dial(target) {
  const passToken = await currentPass();
  let lastErr;
  for (let attempt = 1; attempt <= 3; attempt++) {
    try { return await openUpstream(passToken, target); }
    catch (err) {
      lastErr = err;
      if (/HTTP\/1\.1 4/.test(err.message)) break; // a 4xx won't change on retry
      if (attempt < 3) await new Promise((r) => setTimeout(r, 250 * attempt));
    }
  }
  throw lastErr;
}

function splice(a, b) {
  if (a.destroyed) return void b.destroy();
  a.pipe(b); b.pipe(a);
  const close = () => { a.destroy(); b.destroy(); };
  a.once("close", close); b.once("close", close); b.once("error", close);
}

async function handleSocks(socket, greeting) {
  const methods = [...greeting.subarray(2, 2 + greeting[1])];
  if (!methods.includes(0x00)) {
    if (!methods.includes(0x02)) return void socket.end(Buffer.from([SOCKS_VERSION, 0xff]));
    socket.write(Buffer.from([SOCKS_VERSION, 0x02]));
    const login = await nextChunk(socket); // accept any user/pass (local-only proxy)
    if (login[0] !== 0x01) return void socket.end(Buffer.from([0x01, 0x01]));
    socket.write(Buffer.from([0x01, 0x00]));
  } else socket.write(Buffer.from([SOCKS_VERSION, 0x00]));
  const target = await readRequest(socket);
  try {
    const up = await dial(target);
    replySuccess(socket);
    splice(socket, up);
  } catch (err) {
    log("dial failed", target.host + ":" + target.port, "-", err.message);
    replyFailure(socket);
    socket.destroy();
  }
}

const HTTP_MAX_HEAD = 64 * 1024;
function httpError(socket, status) {
  socket.end(`HTTP/1.1 ${status}\r\nContent-Length: 0\r\nConnection: close\r\n\r\n`);
}

async function handleHttp(socket, first) {
  let head = first;
  while (head.indexOf("\r\n\r\n") < 0) {
    if (head.length > HTTP_MAX_HEAD) return httpError(socket, "431 Request Header Fields Too Large");
    head = Buffer.concat([head, await nextChunk(socket)]);
  }
  socket.pause();
  const end = head.indexOf("\r\n\r\n");
  const lines = head.toString("latin1", 0, end).split("\r\n");
  const rest = head.subarray(end + 4);
  const [method, url, version = "HTTP/1.1"] = lines[0].split(" ");
  let target, request = null;
  if (method === "CONNECT") {
    const m = url?.match(/^(?:\[([^\]]+)\]|([^:]+)):(\d+)$/);
    if (!m) return httpError(socket, "400 Bad Request");
    target = { host: m[1] ?? m[2], port: Number(m[3]) };
  } else {
    let u;
    try { u = new URL(url ?? ""); } catch { return httpError(socket, "400 Bad Request"); }
    if (u.protocol !== "http:") return httpError(socket, "400 Bad Request");
    target = { host: u.hostname.replace(/^\[|\]$/g, ""), port: Number(u.port || 80) };
    const headers = lines.slice(1).filter((l) => !/^(proxy-[^:]*|connection|keep-alive):/i.test(l));
    request = Buffer.concat([
      Buffer.from(`${method} ${u.pathname}${u.search} ${version}\r\n${[...headers, "Connection: close"].join("\r\n")}\r\n\r\n`, "latin1"),
      rest,
    ]);
  }
  let up;
  try { up = await dial(target); }
  catch (err) { log("dial failed", target.host + ":" + target.port, "-", err.message); return httpError(socket, "502 Bad Gateway"); }
  if (request) up.write(request);
  else { socket.write("HTTP/1.1 200 Connection Established\r\n\r\n"); if (rest.length) up.write(rest); }
  splice(socket, up);
}

async function handleClient(socket) {
  socket.on("error", () => socket.destroy());
  const first = await nextChunk(socket);
  return first[0] === SOCKS_VERSION ? handleSocks(socket, first) : handleHttp(socket, first);
}

// ── boot ─────────────────────────────────────────────────────────────────────
{
  const up = await pickUpstreamFor(country);
  upstream = { hostname: up.hostname, port: up.port };
}
await currentPass(); // warm the pass so auth errors surface at startup
const server = net.createServer((socket) => { handleClient(socket).catch(() => socket.destroy()); });
await new Promise((resolve, reject) => {
  server.once("error", reject);
  server.listen(PORT, "127.0.0.1", resolve);
});
log(`FPN proxy on 127.0.0.1:${PORT} (SOCKS5+HTTP) -> ${upstream.hostname}:${upstream.port} (${country})`);

// ── Local control (loopback only): the FPN Tunnel app picks the location here ─
const ctrl = http.createServer(async (req, res) => {
  const send = (code, obj) => {
    res.writeHead(code, { "content-type": "application/json" });
    res.end(JSON.stringify(obj));
  };
  try {
    const u = new URL(req.url, "http://127.0.0.1");
    if (u.pathname === "/status") {
      return send(200, { country, upstream: `${upstream.hostname}:${upstream.port}`,
        passExpiresInSec: passCache ? Math.max(0, passCache.expires - Math.floor(Date.now() / 1000)) : null });
    }
    if (u.pathname === "/country") {
      const c = u.searchParams.get("c");
      if (!c) return send(400, { error: "missing c" });
      return send(200, await setCountry(c));
    }
    if (u.pathname === "/countries") {
      const list = await loadServerList();
      return send(200, list.map((c) => ({ code: c.code, name: c.name })));
    }
    return send(404, { error: "unknown path" });
  } catch (err) {
    return send(400, { error: String(err.message ?? err) });
  }
});
await new Promise((resolve, reject) => { ctrl.once("error", reject); ctrl.listen(CTRL_PORT, "127.0.0.1", resolve); });
log(`FPN control on 127.0.0.1:${CTRL_PORT} (status / country?c=XX / countries)`);
