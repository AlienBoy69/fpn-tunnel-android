# Changelog

All notable changes to this project are documented here.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/).

## [4.3.0]

### Added — multiple accounts (Fix Q)
- Sign in with several of your own Mozilla accounts and switch
  between them. Each account lives in its own file under
  `files/accounts/<uid>.json`; the prefs hold only which one is
  active. Sign-in (native and WebView) now ADDS the account to the
  store — signing in again with an already-stored account refreshes
  that entry instead of overwriting the others.
- The account button opens the account list: tap to switch (a
  running tunnel restarts on the new account and fetches a fresh,
  per-account proxy pass), "Add account" signs another one in, and
  "Remove…" deletes one from this device after a confirmation.
  Removing the active account moves you to a remaining account, or
  to a clean signed-out state when none remain.
- Migration is copy-only and one-time: your existing session.json
  becomes account #1 (a `session.json.bak-v420` backup is written
  first, the original stays in place, and session.json remains as a
  live mirror of the active account for older readers).
- Usage counters are now per-account — the main screen shows the
  active account's email and its own today / all-time totals.
  (Every account's plan still reads Unlimited: Mozilla's API has no
  per-account data cap, and switching accounts does not pool or
  extend any quota — there is none.)
- The optional biometric lock now seals the WHOLE account store
  (one encrypted bundle) instead of a single session file; the old
  single-session sealed form still unlocks and migrates forward.

## [4.2.1]

### Fixed — the Doctor could wedge every later connect (EADDRINUSE)
- ConnectionDoctor started a diagnostic engine that was not the
  service's engine and, on the health-OK and repair paths, never
  stopped it. The leaked engine held the proxy/control ports
  (127.0.0.1:11080/11081) for the life of the process, so the next
  Connect — and every one after — failed with `bind failed:
  EADDRINUSE`, the tunnel stayed down, and the Doctor misreported
  the collision as E3/E7. Reproduced live in 4.2.0 QA (a manual
  diagnosis while disconnected, then Connect). The Doctor now
  releases its diagnostic engine before any service-level tunnel
  start and unconditionally when a run ends.
- FpnEngine.start() no longer leaks the proxy socket when the
  control-socket bind fails (half-bound engine).
- FpnVpnService.tunnelOn() is serialized (Doctor R5, the watchdog
  and the UI can all request a start) and retries the engine bind
  once after a short wait instead of failing the connect outright.

### Changed
- Minimum Android version lowered to 7.1 (API 25) — verified on a
  Galaxy A3 (2015, LineageOS 14.1): install, launch, sign-in screen
  and the Doctor's E2 path all work. All API 26+ call sites in the
  codebase are version-guarded.

## [4.2.0]

### Fixed — the 502 disconnect (CONNECT-health failover)
- A Mozilla gateway that answers MASQUE CONNECTs with a gateway-side
  5xx is now quarantined for 10 minutes and excluded from server
  picks, and the dial path re-picks across countries (every country
  has exactly one gateway, so cross-country failover is the only
  failover). This is the fix for the 2026-11-12 01:14 incident, where
  one gateway 502'd every CONNECT for a minute while TCP probes still
  elected it. A CONNECT 407 (pass rejected) now force-refreshes the
  pass instead of quarantining the server.
- Destination-policy refusals ("502 Destination IP Prohibited" —
  Mozilla refuses direct IPs and some ports for every user) are NOT
  treated as gateway sickness and no longer trigger repick churn
  (found in live testing of this build).

### Iran-first connection (Fix I)
- New installs default to ⚡ Auto.
- Smart Connect: on first connect, real MASQUE CONNECT health dials
  (not TCP probes) race in Happy-Eyeballs waves of 3 (~300 ms
  stagger) across the Auto country set + CA + US; the first server
  that actually passes a CONNECT becomes the exit.
- FxA and Guardian API calls share the engine's DoH resolver (raced
  resolver set: 8.8.8.8 / 8.8.4.4 / 1.1.1.1 / 1.0.0.1 / 9.9.9.9), so
  poisoned DNS cannot break sign-in or pass fetch either.
- Initial upstream TLS+CONNECT is bounded to ~6 s so a filtered
  server is abandoned fast.
- fa-IR devices get a one-line Persian hint under the status card on
  first run.

### Native Mozilla sign-in (Fix H, beta)
- Sign in with email + password in the app's own form — no browser,
  no WebView. Implements Mozilla's onepw protocol including the v2
  credential upgrade (PBKDF2 650,000 iterations with the per-account
  clientSalt from /account/credentials/status), email-code and TOTP
  verification, recovery codes, and the unblock-code flow. Password
  Manager autofill hints are set, so saved credentials are offered.
  The password exists only in memory and is never logged or stored.
  Labeled beta: the key derivation is byte-verified against Mozilla's
  reference algorithm and the endpoints were live-checked against
  Mozilla's stage environment, whose edge WAF refuses scripted
  logins (HTTP 406) — the first full run is with your real account.
- The WebView sign-in stays as the fallback (and is the only place
  "Sign in with Google" exists — Google federation lives inside
  Mozilla's web page; there is no native Google→FxA token exchange).
  It is also streamlined (Fix E): persistent cookies + form data make
  it one-time, and the account email is pre-filled on return visits.
- A completed sign-in now connects immediately — no extra tap.
- Optional biometric lock: seal the stored session with an
  AndroidKeyStore key that requires your fingerprint/lockscreen.

### Self-diagnostics (Fix G)
- ConnectionDoctor: when a connect fails to reach "tunnel up" within
  ~15 s — or on demand via "Diagnose connection" — it checks sign-in,
  device network, pass fetch, a real CONNECT health dial and DNS
  sinkholing, then repairs in order (refresh pass → re-pick server →
  Auto fallback → Smart Connect → restart engine → restart service)
  and shows a plain-language report with a stable code (E1–E7) you
  can quote in a GitHub issue.

### Easier everyday connection (Fixes B, C)
- Auto-connect: if you left the VPN on, opening the app reconnects
  with no tap; after a reboot the service restarts by itself
  (skipped while the biometric lock seals the session). Signed-out
  users go straight to the sign-in screen.
- Quick Settings tile: toggle the tunnel from the notification shade.

### Live usage (Fix L)
- The main screen shows this session's ↓/↑ bytes with a live rate,
  plus today's and all-time totals for THIS device, next to the plan
  line ("Plan: Unlimited"). Mozilla exposes no per-user usage meter
  for FPN and the plan carries no quota, so there is deliberately no
  "remaining balance" — only real, local numbers. The foreground
  notification mirrors the session totals.

### Privacy (Fix K)
- All app-originated calls to Mozilla/Fastly endpoints send neutral
  client metadata (Accept-Language: en-US, a generic user agent, no
  locale/timezone headers). The native sign-in removes the WebView
  JavaScript fingerprint (Intl timezone/locale/screen) entirely.
  This minimizes client-side metadata leaks; it cannot hide your
  source IP from the VPN server itself (no VPN can) or change
  account-side records held by Mozilla.

### Root mode (Fix N, optional)
- On rooted phones, a Root mode panel offers: silent self-update
  (pm install via su), a battery-optimization whitelist, and a
  read-only Doctor leak test (tun interface + route check). The
  kernel kill switch is NOT shipped: an app-owned iptables chain can
  outlive the app and block all traffic, so the toggle stays disabled
  until a design with a cleanup guarantee exists. Root is probed only
  when the panel is opened — never at app start. The unrooted app is
  complete without any of this.

### Automatic anonymous error reports (Fix P)
- Crashes, Doctor failures and fatal engine errors can be reported
  automatically and anonymously: payload is only app/Android version,
  device model, error code/class, a sanitized message and a sanitized
  log tail (emails, tokens and IP addresses stripped; no device or
  install ID — a random per-report nonce only). Reports queue on disk
  and are sent ONLY while the tunnel is up, so your real IP never
  reaches the relay; the same error is sent at most once per day.
  Toggle on the main screen, default on, with a first-run notice.
  The relay credentials live in a gitignored config file; builds
  without it (including all public builds) have reporting disabled.

### Install trust (Fix M)
- The APK now actually targets SDK 34 (previous builds silently
  shipped without a targetSdk, which made Android attach legacy
  implied permissions like READ_PHONE_STATE). Declared permissions
  are only the six the code uses; there are no SMS/contacts/
  location/call-log permissions. The self-updater additionally
  verifies, before installing, that a downloaded APK is this same
  package signed with this same certificate, and only downloads from
  GitHub's own release hosts over HTTPS. No dynamic code loading
  exists anywhere in the app.

### Interface (Fix O)
- The connect button is now a low-poly fox (Firefox's animal) drawn
  on Canvas: dim and slow when disconnected, warming up while
  connecting, and glowing — warm halo, teal-lit eyes, shine sweep —
  when connected, over its orbit ring.

### Not shipped
- FxA pairing-code sign-in was cancelled on evidence: pairing yields
  only a Sync-scoped OAuth code, never a sessionToken, and the
  channel must be created by a signed-in Firefox Desktop (research
  notes in the project's research log, 2026-11-12).

## [4.1.2]

### Fixed
- In-app update stalled silently after the download: the PackageInstaller
  status callback was sent as an action-only broadcast, which a manifest
  receiver without an intent-filter never receives, so the system install
  prompt never appeared. The callback intent is now component-explicit

## [4.1.1]

### Fixed
- In-app update could dead-end on the one-time "Install unknown apps"
  permission prompt on some devices (notably Samsung), where the system
  keeps reporting the permission as missing even after it was granted.
  The prompt is now advisory and shown at most once; afterwards the
  update proceeds and Android's installer makes the final call
- Update events (check result, permission state, download and installer
  status) are now written to the app log, so they appear in diagnostics
  bundles and failures are diagnosable

## [4.1.0]

### In-app updates
- The app now checks the latest GitHub release on start-up
- When a newer version exists, a dialog offers to download the APK and
  install it in place via Android's PackageInstaller — no browser, no
  manual download
- "Check for updates" button (shows the current version) for manual checks
- First use asks for Android's one-time "Install unknown apps" permission
  for FPN Tunnel, with a shortcut to the right settings page

## [4.0.0] - 2026-11-12

### Added

- **Shareable diagnostics:** a "Send diagnostics" button composes a
  sanitized bundle (app/Android/device info, connection state, tails of
  the service and engine logs) with emails, JWTs, session tokens and
  long hex strings redacted, and offers it through the system share
  sheet. If a connect attempt fails (30 s without a tunnel), the
  button lights up and asks for the report. Nothing is ever sent
  automatically — a VPN app must not phone home.
- **Self-healing tunnel:** a watchdog in the VPN service checks the
  engine and the TUN every 25 s and rebuilds whichever half died
  (in-place engine swap under a live TUN, full rebuild otherwise),
  giving up after 6 restarts.
- Session uptime ("up M:SS") on the status card.

### Changed

- The client now stands fully on its own: the sign-in WebChannel shim
  and FxA entrypoint carry the app's own names, the Mozilla root bundle
  is vendored in the repo (no build-time download), and the docs
  describe the engine as what it is — an independent implementation
  (protocol behaviour informed by the MIT-licensed foxhole project,
  credited in the README).
- Notification text no longer hardcodes an exit country.

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
