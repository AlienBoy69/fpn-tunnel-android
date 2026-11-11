# Contributing

Issues and pull requests are welcome.

## Before you start

- For anything bigger than a small fix, open an issue first so the
  approach can be agreed before you spend time on code.
- This project runs against Mozilla's live FPN service with the user's
  own subscription. Do not propose features that share, pool or
  resell accounts or passes, or that bypass Mozilla's entitlement
  checks.

## Ground rules for every contribution

- **No credentials, ever.** Never commit or paste a `signedInUser.json`,
  a session token, an API key, or a signing keystore. They are
  gitignored for a reason; check your diff before pushing.
- **Scrub your logs.** Issues, PRs and screenshots must not contain
  email addresses, tokens, or device identifiers.
- Keep changes small and focused; match the existing style (the engine
  is a single dependency-free Node file on purpose; the app is plain
  Java with no Gradle on purpose).

## Building

- Engine: plain Node.js (18+) — no dependencies, no build step.
  `node --check engine/run-fpn.mjs` before submitting.
- App: `./build.sh` with a JDK and the Android SDK (build-tools 34,
  platform android-34). It downloads the hev-socks5-tunnel AAR and
  generates a local signing key on first run.

## Testing a change

Describe in your PR what you ran on a real device: engine status
output, a trace through the tunnel (`loc=` of the chosen country),
and the app connecting/disconnecting cleanly. Emulator-only testing
is acceptable for UI changes; say so explicitly.
