#!/data/data/com.termux/files/usr/bin/bash
exec >"$HOME/foxhole-android/install_node.log" 2>&1
echo "install started $(date)"
yes | pkg install -y nodejs-lts
echo "pkg rc=$?"
node --version
echo "install done $(date)"
