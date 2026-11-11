#!/usr/bin/env python3
"""Extract your Firefox Accounts session from Firefox Android's store and
write it as a desktop-format signedInUser.json for the FPN engine.

Why: Firefox Android keeps no signedInUser.json (that file is desktop-only).
Its session lives in the accounts.firefox.com localStorage database
(ls/data.sqlite); the values are compressed, but the sessionToken and uid
survive as ASCII in the raw bytes and can be recovered with a regex.

Needs root (the database is in Firefox's private data). Run in Termux:

    python3 extract_session.py [/path/to/data.sqlite]

Without an argument it copies the database out of Firefox's data dir with
su first. Output: ~/.mozilla/firefox/foxhole.default/signedInUser.json
(mode 0600) — the file run-fpn.mjs reads.
"""
import glob
import json
import os
import re
import subprocess
import sys

def get_raw(argv):
    if len(argv) > 1:
        return open(argv[1], "rb").read()
    pats = glob.glob(
        "/data/data/org.mozilla.firefox/files/mozilla/*.default/storage/"
        "default/https+++accounts.firefox.com/ls/data.sqlite")
    if not pats:
        print("Firefox accounts store not found — is Firefox installed "
              "and signed in?"); sys.exit(1)
    tmp = "/data/local/tmp/fxa_ls.sqlite"
    r = subprocess.run(["su", "-c", f"cp {pats[0]} {tmp} && chmod 644 {tmp}"],
                       capture_output=True, text=True)
    if r.returncode != 0:
        print("root copy failed:", r.stderr[-200:]); sys.exit(1)
    return open(tmp, "rb").read()

def main():
    raw = get_raw(sys.argv)
    tok = re.search(rb'"sessionToken":"([0-9a-f]{64})"', raw)
    uid = re.search(rb'"\{([0-9a-f]{32})":\{"email"', raw)
    if not tok or not uid:
        print("NO_SESSION_FOUND — sign into Firefox first."); sys.exit(1)
    acct = re.search(rb'\{"email":"([^"]+)"', raw[uid.start():uid.start() + 400])
    doc = {"accountData": {
        "uid": uid.group(1).decode(),
        "email": acct.group(1).decode() if acct else "",
        "sessionToken": tok.group(1).decode(),
        "verified": True,
    }}
    target = os.path.expanduser(
        "~/.mozilla/firefox/foxhole.default/signedInUser.json")
    os.makedirs(os.path.dirname(target), exist_ok=True)
    with open(target, "w") as f:
        json.dump(doc, f)
    os.chmod(target, 0o600)
    print("WROTE", target, "for", doc["accountData"]["email"])

if __name__ == "__main__":
    main()
