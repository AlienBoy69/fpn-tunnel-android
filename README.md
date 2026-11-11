# FPN Tunnel for Android

[![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Android-12%2B-blue.svg)](#requirements)
[![GitHub stars](https://img.shields.io/github/stars/msgornami/fpn-tunnel-android?style=social)](https://github.com/msgornami/fpn-tunnel-android/stargazers)

A whole-phone **Firefox Private Network** (Mozilla's FPN / Mozilla VPN
subscription) client for Android in a **single APK** — no Termux, no Node,
no root, no laptop. Sign in with your own Firefox account inside the app,
pick an exit country, and the whole phone routes through Mozilla's MASQUE
proxies.

Built and verified end to end on a Samsung Note10+ (Android 12): sign-in,
engine and tunnel all in-app; exit in Canada / Netherlands / Germany
confirmed by IP trace; split tunneling keeps chosen apps on the direct
connection.

> **Unofficial.** Not affiliated with Mozilla. Use it with your own paid
> Firefox Private Network / Mozilla VPN subscription. Mozilla can change
> or close the underlying service at any time.

## Features

- 📦 **One APK, nothing else** — the proxy engine is a Java port of the
  audited foxhole core and runs inside the app itself
- 🔑 **Sign-in in the app** — Mozilla's own login page in a WebView
  (WebChannel flow, the same method Mozilla's mobile clients use); the
  session never leaves the device and the login is one-time
- 🌍 **Whole-phone tunnel** — every app goes through the VPN slot, not
  just a browser
- 🗺️ **33-country location picker** — switch exit country live from the app
- 🔀 **Split tunneling** — keep banking apps and geo-sensitive services
  off the tunnel (edit the `SKIP` list in `FpnVpnService.java`)
- 🔄 **Self-maintaining engine** — 10-minute proxy passes refetch
  automatically
- 🛡️ **Hardened upstream path** — MASQUE hosts are resolved over
  DNS-over-HTTPS (the system resolver proved unreliable for them) and the
  upstream TLS chain is validated against the system store with a PKIX
  fallback anchored in a bundled Mozilla root store (some MASQUE chains
  anchor in newer ECC roots that older Android stores lack)

## Download

Grab **`FpnTunnel.apk`** from the
[Releases](https://github.com/msgornami/fpn-tunnel-android/releases) page,
install it, and follow the quick start below. That's all — there is no
separate engine to set up.

**Persian step-by-step guide (راهنمای فارسی):** [docs/guide-fa.md](docs/guide-fa.md)

## Quick start

1. **Install** the APK and open **FPN Tunnel**.
2. **Sign in:** tap the account button and log in on Mozilla's own page
   (Google sign-in works there). One-time — the app keeps the session.
3. **Pick a country** in the **Location** menu.
4. **Tap Connect** and grant the Android VPN permission once.

## How it works

```
┌─────────────┐   VpnService (hev-socks5-tunnel)   ┌──────────────────┐
│  all apps   │ ─────────────────────────────────▶ │  in-app engine   │
│  (minus     │        TUN, IPv4 only              │  proxy on        │
│  exclusions)│                                    │  127.0.0.1:11080 │
└─────────────┘                                    └────────┬─────────┘
                                                            │ TLS (CONNECT
                                                            │  + proxy pass)
                                                  ┌─────────▼─────────┐
                                                  │ Mozilla MASQUE    │
                                                  │ *.m1.fastly-      │
                                                  │ masque.net:2499   │
                                                  └───────────────────┘
```

The engine mints short-lived proxy passes from your Firefox session
(FxA token → Guardian API), keeps one alive at all times, and forwards
the local SOCKS5/HTTP proxy through the selected country's MASQUE
server. A loopback control API on `127.0.0.1:11081` (`/status`,
`/country?c=XX`, `/countries`) connects the UI's location picker to the
engine.

## Build from source

Needs a JDK and the Android SDK (build-tools 34 + platform android-34).
Point `ANDROID_TOOLCHAIN` at a directory containing `sdk/` and `jdk/`
(see the top of `build.sh`), then:

```sh
./build.sh   # downloads the hev AAR + the Mozilla root bundle, signs locally
```

The signing key is generated locally on first run (`keys/`, gitignored).
Output: `build/out/FpnTunnel.apk`.

## Legacy: Termux engine (v1)

The `engine/` folder keeps the original Node/Termux variant: a separate
engine process (`run-fpn.mjs`) driven by a session extracted from
Firefox, with the app as a thin tunnel shell. It still works and is
useful on rooted devices, but the single-APK build above is the
supported path for most users.

## Known limitations

- Mozilla's MASQUE proxies refuse raw-IP destinations and some ports
  (e.g. FCM 5228) — push notifications for some apps can lag, and
  direct-IP connections (some Telegram traffic) fail by design.
- A Mozilla/Firefox account with an active FPN / Mozilla VPN
  subscription is required; the app cannot create one for you.
- Android may drop the VPN permission when the app is updated; if
  Connect stops working after an update, open the app and tap Connect
  again (re-granting if asked).

## Credits

The engine is a port of the open-source
[foxhole](https://github.com/DierCodes/foxhole) core logic (MIT) to
Java, with the tunnel provided by
[hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel).

## License

MIT — see [LICENSE](LICENSE).
