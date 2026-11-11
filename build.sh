#!/bin/bash
# Manual APK build, no Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner.
# Needs a JDK and the Android SDK (build-tools 34 + platform android-34);
# point ANDROID_TOOLCHAIN at a dir containing sdk/ and jdk/, or edit TC below.
# The signing key is generated locally on first run (keys/, gitignored).
set -e
ROOT="$(cd "$(dirname "$0")" && pwd)"
TC="${ANDROID_TOOLCHAIN:-$HOME/android-toolchain}"  # dir holding sdk/ + jdk/ (see README)
BT="$TC/sdk/build-tools/34.0.0"
AJ="$TC/sdk/platforms/android-34/android.jar"
HEV=/tmp/hev-aar
export JAVA_HOME="$TC/jdk"
export PATH="$JAVA_HOME/bin:$PATH"
cd "$ROOT"
mkdir -p build/classes build/gen build/out build/native/lib/arm64-v8a build/native/lib/armeabi-v7a keys
chmod 700 keys
if [ ! -f keys/release.keystore ]; then
  PW=$(head -c 18 /dev/urandom | base64 | tr -d '/+=' | head -c 20)
  echo -n "$PW" > keys/keystore_password.txt; chmod 600 keys/keystore_password.txt
  keytool -genkeypair -keystore keys/release.keystore -alias fpn \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass "$PW" -keypass "$PW" \
    -dname "CN=FPN Tunnel, O=FPN Tunnel" >/dev/null 2>&1
  echo "keystore created"
fi
PW=$(cat keys/keystore_password.txt)
# Mozilla root bundle for the engine's upstream TLS fallback (gitignored;
# build-time download, preamble stripped — the app parses it block by block)
[ -f app/assets/cacert.pem ] || { mkdir -p app/assets && curl -sL -o /tmp/cacert.pem https://curl.se/ca/cacert.pem && sed -n '/-----BEGIN CERTIFICATE-----/,$p' /tmp/cacert.pem > app/assets/cacert.pem; }
# classes.jar of hev (extract if needed)
[ -f "$HEV/classes.jar" ] || { curl -sL -o /tmp/hev.aar https://github.com/heiher/hev-socks5-tunnel/releases/download/2.18.0/hev-socks5-tunnel.aar && unzip -o -q /tmp/hev.aar -d "$HEV"; }
cp "$HEV/jni/arm64-v8a/libhev-socks5-tunnel.so" build/native/lib/arm64-v8a/
cp "$HEV/jni/armeabi-v7a/libhev-socks5-tunnel.so" build/native/lib/armeabi-v7a/
# 1) resources + manifest
"$BT/aapt2" compile --dir app/res -o build/res.zip
"$BT/aapt2" link -o build/out/app-unsigned.apk -I "$AJ" \
  --manifest app/AndroidManifest.xml -R build/res.zip -A app/assets \
  --version-code 16 --version-name 4.1.2 \
  --java build/gen --auto-add-overlay
# 2) compile (app + hev classes.jar as lib + generated R)
find app/src -name '*.java' > build/sources.txt
find build/gen -name 'R.java' >> build/sources.txt
javac -encoding UTF-8 -cp "$AJ:$HEV/classes.jar" -d build/classes @build/sources.txt
# 3) dex (app classes + hev TProxyService classes)
rm -rf build/dex && mkdir -p build/dex
"$BT/d8" --lib "$AJ" --output build/dex $(find build/classes -name '*.class') "$HEV/classes.jar"
# 4) pack dex + native lib, align, sign
cp build/out/app-unsigned.apk build/out/app-tmp.apk
(cd build/dex && zip -q -X ../out/app-tmp.apk classes.dex)
(cd build/native && zip -q -X -0 -r ../out/app-tmp.apk lib)
"$BT/zipalign" -f -p 4 build/out/app-tmp.apk build/out/app-aligned.apk
"$BT/apksigner" sign --ks keys/release.keystore --ks-pass "pass:$PW" \
  --key-pass "pass:$PW" --out build/out/FpnTunnel.apk build/out/app-aligned.apk
"$BT/apksigner" verify build/out/FpnTunnel.apk && echo "BUILD OK: build/out/FpnTunnel.apk"
