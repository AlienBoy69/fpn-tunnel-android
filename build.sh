#!/bin/bash
# Manual APK build, no Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner.
# Needs a JDK and the Android SDK (build-tools 34 + platform android-34);
# point ANDROID_TOOLCHAIN at a dir containing sdk/ and jdk/, or edit TC below.
# The signing key is generated locally on first run (keys/, gitignored).
#
# F-Droid / offline mode:
#   BUILD_ONLY=1   build + zipalign only, NEVER sign (F-Droid signs with its
#                  own key). Output: build/out/FpnTunnel-unsigned.apk
#   HEV_DIR=<dir>  hev artifacts built from source (classes.jar +
#                  jni/<abi>/libhev-socks5-tunnel.so). Nothing is downloaded.
#   ANDROID_HOME / ANDROID_SDK_ROOT are honoured for the SDK location, and a
#   preset JAVA_HOME is kept; BUILD_TOOLS_VERSION / PLATFORM_VERSION override
#   the 34.0.0 / android-34 defaults.
set -e
ROOT="$(cd "$(dirname "$0")" && pwd)"
BUILD_ONLY="${BUILD_ONLY:-0}"
TC="${ANDROID_TOOLCHAIN:-$HOME/android-toolchain}"  # dir holding sdk/ + jdk/ (see README)
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$TC/sdk}}"
BT="$SDK/build-tools/${BUILD_TOOLS_VERSION:-34.0.0}"
AJ="$SDK/platforms/${PLATFORM_VERSION:-android-34}/android.jar"
HEV="${HEV_DIR:-$ROOT/third-part/hev-flat}"
if [ -d "$TC/jdk" ] && [ -z "${JAVA_HOME:-}" ]; then
  export JAVA_HOME="$TC/jdk"
fi
export PATH="$JAVA_HOME/bin:$PATH"
cd "$ROOT"
mkdir -p build/classes build/gen build/out build/native/lib/arm64-v8a build/native/lib/armeabi-v7a
if [ "$BUILD_ONLY" != "1" ]; then
mkdir -p keys
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
fi  # BUILD_ONLY != 1 (signing setup)
# Mozilla root bundle for the engine's upstream TLS fallback. It is COMMITTED
# to the repo (app/assets/cacert.pem, Mozilla's public CA bundle); the build
# never downloads it — fail loudly if it is missing.
[ -f app/assets/cacert.pem ] || { echo "FATAL: app/assets/cacert.pem missing — it must be committed to the repo"; exit 1; }
# Fix P: bake the private reporting config into assets when present
# (gitignored; public builds ship without it and reporting is disabled)
if [ -f app/reporting.properties ]; then
  cp app/reporting.properties app/assets/reporting.properties
else
  rm -f app/assets/reporting.properties
fi
# hev artifacts (classes.jar + native libs) are built FROM SOURCE by the
# prepare step (see fdroid recipe: ndk-build of the pinned hev-socks5-tunnel
# submodule) and placed in $HEV_DIR. No prebuilt AAR, no download.
for f in "$HEV/classes.jar" "$HEV/jni/arm64-v8a/libhev-socks5-tunnel.so" "$HEV/jni/armeabi-v7a/libhev-socks5-tunnel.so"; do
  [ -f "$f" ] || { echo "FATAL: $f missing — build hev from source first (HEV_DIR=$HEV)"; exit 1; }
done
cp "$HEV/jni/arm64-v8a/libhev-socks5-tunnel.so" build/native/lib/arm64-v8a/
cp "$HEV/jni/armeabi-v7a/libhev-socks5-tunnel.so" build/native/lib/armeabi-v7a/
# 1) resources + manifest
"$BT/aapt2" compile --dir app/res -o build/res.zip
"$BT/aapt2" link -o build/out/app-unsigned.apk -I "$AJ" \
  --manifest app/AndroidManifest.xml -R build/res.zip -A app/assets \
  --version-code 24 --version-name 4.5.1 \
  --min-sdk-version 25 --target-sdk-version 34 \
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
if [ "$BUILD_ONLY" = "1" ]; then
  cp build/out/app-aligned.apk build/out/FpnTunnel-unsigned.apk
  echo "BUILD OK (unsigned, BUILD_ONLY): build/out/FpnTunnel-unsigned.apk"
  exit 0
fi
"$BT/apksigner" sign --ks keys/release.keystore --ks-pass "pass:$PW" \
  --key-pass "pass:$PW" --out build/out/FpnTunnel.apk build/out/app-aligned.apk
"$BT/apksigner" verify build/out/FpnTunnel.apk && echo "BUILD OK: build/out/FpnTunnel.apk"
