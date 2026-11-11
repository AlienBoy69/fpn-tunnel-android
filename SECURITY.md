# Security Policy

## Supported versions

| Version | Supported |
| ------- | --------- |
| 1.x     | ✅        |
| < 1.0   | ❌        |

## The one rule that matters

`~/.mozilla/firefox/foxhole.default/signedInUser.json` contains a **live
Firefox Accounts session token** — it is a credential, on par with a
password. Never share it, never paste it into an issue, never commit it
to any repository. It is listed in `.gitignore`, and the engine only
ever uses it on your own device, talking to Mozilla's own servers.

The engine's proxy (`127.0.0.1:11080`) and control API
(`127.0.0.1:11081`) bind to loopback only; nothing is exposed to the
local network by design.

## Reporting a vulnerability

Please report security problems privately through GitHub's
**"Report a vulnerability"** button (Security → Advisories) on this
repository instead of opening a public issue. If that is unavailable,
open a public issue that says only "security report — please contact
me", with no technical details, and wait for a reply before disclosing
anything else.

When reporting, scrub all tokens, session files, email addresses and
device identifiers from logs and screenshots.
