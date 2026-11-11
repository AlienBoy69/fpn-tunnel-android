#!/data/data/com.termux/files/usr/bin/bash
# FPN Tunnel keepalive: run from Termux:Boot (or manually).
# 1) If the engine is dead, relaunch it through launch_engine.sh
#    (the RUN_COMMAND door — the only launch that survives).
# 2) About once a day, or when the engine log shows errno 110 (FxA session
#    gone), re-extract the Firefox Android session into signedInUser.json
#    and restart the engine so it picks the fresh session up.
# The extraction needs root (Firefox's profile store is app-private).
exec >>"$HOME/foxhole-android/keepalive.log" 2>&1
echo "=== keepalive $(date) ==="
LOCK="$HOME/foxhole-android/.launching"

if ! pgrep -f "run-fpn.mjs" >/dev/null; then
  echo "engine down -> launching via RUN_COMMAND"
  touch "$LOCK"
  bash "$HOME/foxhole-android/launch_engine.sh"
  rm -f "$LOCK"
fi

python3 - <<'PYEOF'
import json, os, re, subprocess, sys, time

home = os.path.expanduser("~")
base = os.path.join(home, "foxhole-android")
lastf = os.path.join(base, ".last_refresh")
log = ""
try:
    log = open(os.path.join(base, "engine.log"), errors="ignore").read()[-20000:]
except Exception:
    pass
now = int(time.time())
last = 0
try:
    last = int(open(lastf).read().strip())
except Exception:
    pass
need = (now - last > 23 * 3600) or ("errno 110" in log)
print("refresh needed" if need else "no refresh needed")
if not need:
    sys.exit(0)

# Firefox Android profile(s) live under ~/.mozilla/firefox/*.default here
# (Firefox's data dir is bind-visible in this setup); the accounts store is
# the accounts.firefox.com localStorage sqlite.
dirs = [d for d in os.popen("ls -d ~/.mozilla/firefox/*.default 2>/dev/null").read().split()]
if not dirs:
    print("no firefox profile dir"); sys.exit(0)
db = os.path.join(dirs[0], "storage/default/https+++accounts.firefox.com/ls/data.sqlite")
tmp = "/data/local/tmp/fxa_ls.sqlite"
r = subprocess.run(["su", "-c", f"cp {db} {tmp} && chmod 644 {tmp}"],
                   capture_output=True, text=True)
if r.returncode != 0:
    # Fall back to the real Firefox data path via root.
    db2 = ("/data/data/org.mozilla.firefox/files/mozilla/*.default/"
           "storage/default/https+++accounts.firefox.com/ls/data.sqlite")
    r = subprocess.run(["su", "-c", f"cp {db2} {tmp} && chmod 644 {tmp}"],
                       capture_output=True, text=True)
    if r.returncode != 0:
        print("copy failed", r.stderr[-200:]); sys.exit(0)
raw = open(tmp, "rb").read()
tok = re.search(rb'"sessionToken":"([0-9a-f]{64})"', raw)
uid = re.search(rb'"\{([0-9a-f]{32})":\{"email"', raw)
if not tok or not uid:
    print("token/uid not found in store"); sys.exit(0)
acct = re.search(rb'\{"email":"([^"]+)"', raw[uid.start():uid.start() + 400])
doc = {"accountData": {
    "uid": uid.group(1).decode(),
    "email": acct.group(1).decode() if acct else "",
    "sessionToken": tok.group(1).decode(),
    "verified": True,
}}
target = os.path.join(home, ".mozilla/firefox/foxhole.default/signedInUser.json")
os.makedirs(os.path.dirname(target), exist_ok=True)
with open(target, "w") as f:
    json.dump(doc, f)
os.chmod(target, 0o600)
open(lastf, "w").write(str(now))
print("session file refreshed for", doc["accountData"]["email"])

os.system("pkill -f run-fpn.mjs")
time.sleep(2)
subprocess.run(["bash", os.path.join(base, "launch_engine.sh")])
print("engine restarted after refresh")
PYEOF
echo "=== keepalive done $(date) ==="
