# FPN Tunnel for Android

A whole-phone **Firefox Private Network** (Mozilla's FPN / Mozilla VPN
subscription) client for Android — no laptop, no desktop app, no dependency
on Mozilla's region-gated UI. It mints a proxy pass from *your own* Firefox
account session and routes the whole phone through Mozilla's MASQUE proxies,
with a **location picker (33 countries)**.

Built and verified end to end on a rooted Samsung Note10+ (Android 12):
exit in Germany/US/Netherlands confirmed by IP trace, X loading through the
tunnel, and split tunneling so chosen apps stay on the direct connection.

> Unofficial. Not affiliated with Mozilla. Use it with your own paid
> Firefox Private Network / Mozilla VPN subscription. Mozilla can change
> or close the underlying service at any time.

## How it works

```
┌─────────────┐   VpnService (hev-socks5-tunnel)   ┌──────────────────┐
│  all apps   │ ─────────────────────────────────▶ │  local proxy     │
│  (minus     │        TUN, IPv4 only              │  127.0.0.1:11080 │
│  exclusions)│                                    │  (Termux, Node)  │
└─────────────┘                                    └────────┬─────────┘
                                                            │ MASQUE CONNECT
                                                   Mozilla proxy pass  ▼
                                                   (10 min, auto-refreshed)
                                                   ┌──────────────────┐
                                                   │ *.m1.fastly-     │
                                                   │ masque.net:2499  │
                                                   │ exit: chosen     │
                                                   │ country          │
                                                   └──────────────────┘
```

Two parts:

1. **Engine (Termux, Node.js)** — `engine/run-fpn.mjs`
   - Reads your Firefox session from a `signedInUser.json`, derives FxA
     keys (HAWK/onepw, HKDF-SHA256), mints an OAuth access token, and
     fetches a short-lived proxy pass from Mozilla's Guardian API.
   - Serves SOCKS5 + HTTP on `127.0.0.1:11080` and forwards through a
     MASQUE proxy in the selected country (server list: Mozilla's public
     Remote Settings `vpn-serverlist`).
   - A loopback-only control API on `127.0.0.1:11081` (`/status`,
     `/country?c=XX`, `/countries`) lets the app switch location live;
     the choice persists in `country.txt`.

2. **Tunnel app** — `app/` (a small hand-built `VpnService` app)
   - Uses [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel)
     (the same TUN library Foxhole's Android app uses) to fill Android's
     VPN slot and forward everything to the local proxy.
   - **IPv4 only** — the MASQUE pass refuses raw-IP destinations, and
     tunneling IPv6 breaks apps; IPv4-only avoids that whole class.
   - **Split tunneling** — Termux, the app itself, and any packages you
     list in `FpnVpnService.SKIP` bypass the VPN. Keep your banking apps
     (a foreign exit can trip fraud locks) and any service that
     geo-blocks the exit countries out of the tunnel.
   - UI: Connect / Disconnect and a Location dropdown (33 countries).

The protocol work is a port of [foxhole-vpn](https://github.com/Diercohen/foxhole-vpn)
(MIT) — auth, Guardian and proxy logic follow its audited implementation.

## Requirements

- A Firefox account with an **FPN / Mozilla VPN subscription**.
- **Firefox Android, signed in** with that account (or a
  `signedInUser.json` copied from your desktop Firefox profile — see
  below).
- **Termux** with Node.js LTS (`engine/install_node.sh`).
- **Root**, for two things: extracting the session from Firefox
  Android's private store, and launching the engine so it survives
  (Termux `RUN_COMMAND`). Without root you can still run the engine
  manually in a Termux session, and you can supply the session file
  from a desktop profile instead of extracting it.
- To build the app: a JDK + Android SDK (build-tools 34, platform
  android-34). No Gradle — `build.sh` drives aapt2/javac/d8 directly.

## Setup

### 1. Engine (in Termux)

```sh
mkdir -p ~/foxhole-android
cp engine/* ~/foxhole-android/        # from this repo
bash ~/foxhole-android/install_node.sh   # installs nodejs-lts
```

Give the engine your Firefox session — pick **one**:

- **From Firefox Android (root):**
  `python3 ~/foxhole-android/extract_session.py`
  (Firefox Android has no `signedInUser.json`; the session lives in its
  accounts localStorage DB and is recovered from the raw bytes.)
- **From desktop Firefox (no root needed):** sign into Firefox on a
  computer with the same account, find `signedInUser.json` in your
  profile folder, and copy it to
  `~/.mozilla/firefox/foxhole.default/signedInUser.json` in Termux.

Start it (durable launch via RUN_COMMAND — processes started from an
SSH session get reaped when the session ends):

```sh
bash ~/foxhole-android/launch_engine.sh
curl http://127.0.0.1:11081/status     # {"country":"DE", ...}
curl --socks5-hostname 127.0.0.1:11080 https://www.cloudflare.com/cdn-cgi/trace
```

Optional: put `keepalive.sh` in `~/.termux/boot/` (Termux:Boot) — it
relaunches the engine and refreshes the extracted session about daily.

### 2. App

```sh
./build.sh        # produces build/out/FpnTunnel.apk, signed with a local key
adb install build/out/FpnTunnel.apk   # or copy it to the phone and open it
```

Open **FPN Tunnel**, grant the VPN permission, tap **Connect**, and pick
a location from the dropdown. Switching location applies to new
connections; for an instant full switch, Disconnect → Connect once.

## Known limits

- Mozilla's proxies refuse connections to **raw IP addresses** (only
  hostnames work) and block some ports (e.g. Google FCM `5228`), so a
  few apps' push notifications can lag while the tunnel is up.
- Passes live **10 minutes**; the engine refetches them automatically.
- One Android VPN slot: this tunnel replaces any other VPN app while
  connected.
- Speed is capped by Mozilla's MASQUE service, not by this code.

## Credits

- [foxhole-vpn](https://github.com/Diercohen/foxhole-vpn) by Dier Cohen
  (MIT) — the protocol implementation this engine's auth/Guardian/proxy
  core is ported from.
- [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel)
  (MIT) — the TUN forwarder; download its AAR (v2.18.0) from its
  releases — `build.sh` fetches it automatically.

## License

MIT — see [LICENSE](LICENSE). Third-party components keep their own
licenses (both MIT, above).
