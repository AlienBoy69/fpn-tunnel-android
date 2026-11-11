# Changelog

All notable changes to this project are documented here.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/).

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
