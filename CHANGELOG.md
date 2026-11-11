# Changelog

All notable changes to this project are documented here.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/).

## [3.2.0] - 2026-11-11

### Changed

- Full visual redesign: deep-navy gradient background, glassy gradient
  cards, a ring-style connect button with a soft state-coloured halo
  (blue when off, green when connected), cyan→violet accent bar and
  account pill, letter-spaced section labels, and no legacy title bar —
  the window now uses a matching dark status bar.

## [3.1.0] - 2026-11-11

### Added

- **Fastest-server selection:** on start and on every country change the
  engine probes all of the country's MASQUE servers in parallel (TCP
  connect time) and uses the fastest — previously the pick was random.
- **⚡ Auto mode:** a new "Auto (fastest)" location that probes the
  Iran-nearby set (DE/NL/AT/FR/BE/IT/DK/ES/GB/SE) and exits via the
  globally fastest server; the status API reports the effective country.
- **TLS connection pool:** up to 3 pre-handshaked upstream sockets are
  kept warm, so a new flow pays only the CONNECT round-trip.
- **Failure re-pick:** if dials keep failing, the engine re-probes and
  switches upstream (at most once per 30 s).
- Status API and UI show the active server and its measured RTT.

## [3.0.0] - 2026-11-11

### Added

- New UI: dark card layout, big round connect button (orange →
  green when connected), live status polling, exit IP + country shown
  through the tunnel itself, flag-prefixed country list, pass countdown.

## [2.0.0] - 2026-11-11

### Added

- Single-APK architecture: the proxy engine now runs **inside the app**
  (`FpnEngine.java`, a Java port of the foxhole core) — no Termux, no
  Node, no root required.
- In-app sign-in via Mozilla's own page in a WebView speaking WebChannel
  (the first-party mobile flow); one-time login, session kept on-device.
- In-engine upstream resolution: MASQUE hosts are resolved over
  DNS-over-HTTPS (8.8.8.8 / 1.1.1.1) with UDP-DNS fallback; non-public
  answers are rejected, and TLS connects by IP with SNI + hostname
  verification.
- Resilient upstream TLS validation: system trust store first, then a
  JCA PKIX validation anchored in the union of the system issuers and a
  bundled Mozilla root store (`cacert.pem`, downloaded at build time),
  with a Collection CertStore for cross-signed issuers.

### Changed

- The single APK is now the primary distribution; the Termux/Node
  engine (`engine/`) is kept as a legacy variant for rooted devices.

### Fixed

- Upstream TLS failures (`Trust anchor for certification path not
  found`) on devices whose system store cannot anchor the current
  GlobalSign ECC MASQUE chain.
- The Mozilla bundle parsing silently yielding zero roots on
  Conscrypt (`generateCertificates` over the multi-root PEM); the
  bundle is now parsed certificate block by block.

## [1.0.0] - 2026-11-11

### Added

- Termux engine (`engine/run-fpn.mjs`): Firefox Accounts auth (HAWK/onepw,
  HKDF-SHA256), Guardian proxy-pass minting, local SOCKS5 + HTTP proxy on
  `127.0.0.1:11080` forwarding through Mozilla MASQUE servers.
- Loopback control API on `127.0.0.1:11081` (`/status`, `/country`,
  `/countries`) with the country persisted in `country.txt`.
- Android tunnel app: `VpnService` + hev-socks5-tunnel, IPv4-only TUN,
  split tunneling via a configurable exclusion list, Connect/Disconnect UI.
- Location picker with 33 countries in the app; selection applied live
  to the engine.
- Session extraction from Firefox Android (`extract_session.py`, root)
  and a desktop-profile fallback; durable engine launch through Termux
  `RUN_COMMAND` (`launch_engine.sh`); `keepalive.sh` for Termux:Boot
  with about-daily session refresh.
- Prebuilt `FpnTunnel.apk` attached to the GitHub release.
- Persian step-by-step guide (`docs/guide-fa.md`).

### Known limitations

- Mozilla's proxies refuse raw-IP destinations and some ports
  (e.g. FCM 5228); some apps' push notifications can lag.
- One Android VPN slot: replaces any other VPN while connected.
