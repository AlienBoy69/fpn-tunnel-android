#!/data/data/com.termux/files/usr/bin/bash
# Engine entry point, run by launch_engine.sh via Termux RUN_COMMAND.
# The env preamble mirrors what a real Termux session provides — node and
# its native parts fail without it (same pattern as a start_sshd script).
export HOME=/data/data/com.termux/files/home
export PREFIX=/data/data/com.termux/files/usr
export TERMUX__PREFIX=/data/data/com.termux/files/usr
export TERMUX__HOME=/data/data/com.termux/files/home
export TMPDIR=/data/data/com.termux/files/usr/tmp
export PATH=/data/data/com.termux/files/usr/bin
export LD_PRELOAD=/data/data/com.termux/files/usr/lib/libtermux-exec-ld-preload.so
export LANG=en_US.UTF-8
cd "$HOME/foxhole-android"
exec node run-fpn.mjs >> "$HOME/foxhole-android/engine.log" 2>&1
