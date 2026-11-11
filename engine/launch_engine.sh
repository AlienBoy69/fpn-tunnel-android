#!/data/data/com.termux/files/usr/bin/bash
# Launch the engine through Termux's RUN_COMMAND service (root) so it runs
# in the Termux app's own context. Processes started from an SSH session are
# reaped when the session ends; the RUN_COMMAND door is the durable launch —
# the same pattern Termux's own sshd needs (see start_engine.sh).
H=/data/data/com.termux/files/home
if pgrep -f "run-fpn.mjs" >/dev/null; then
  echo "engine already running" > "$H/foxhole-android/launch_status.txt"
  exit 0
fi
su -c "/system/bin/am startservice --user 0 -n com.termux/com.termux.app.RunCommandService -a com.termux.RUN_COMMAND --es com.termux.RUN_COMMAND_PATH $H/foxhole-android/start_engine.sh --ez com.termux.RUN_COMMAND_BACKGROUND true"
sleep 9
{ echo "launcher ran $(date)"; pgrep -af "run-fpn.mjs" || echo "ENGINE NOT RUNNING"; tail -3 "$H/foxhole-android/engine.log"; } > "$H/foxhole-android/launch_status.txt" 2>&1
