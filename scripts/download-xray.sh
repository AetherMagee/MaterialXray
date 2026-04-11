#!/bin/bash
set -euo pipefail

VERSION="${1:-v26.3.27}"
BASE_URL="https://github.com/XTLS/Xray-core/releases/download/${VERSION}"

mkdir -p app/src/main/jniLibs/arm64-v8a
mkdir -p app/src/main/jniLibs/armeabi-v7a
mkdir -p app/src/main/jniLibs/x86_64

echo "Downloading xray-core ${VERSION}..."

curl -sL "${BASE_URL}/Xray-android-arm64-v8a.zip" -o /tmp/xray-arm64.zip
unzip -o /tmp/xray-arm64.zip xray -d /tmp/xray-arm64
mv /tmp/xray-arm64/xray app/src/main/jniLibs/arm64-v8a/libxray.so
rm -rf /tmp/xray-arm64 /tmp/xray-arm64.zip

curl -sL "${BASE_URL}/Xray-android-arm32-v7a.zip" -o /tmp/xray-arm32.zip
unzip -o /tmp/xray-arm32.zip xray -d /tmp/xray-arm32
mv /tmp/xray-arm32/xray app/src/main/jniLibs/armeabi-v7a/libxray.so
rm -rf /tmp/xray-arm32 /tmp/xray-arm32.zip

curl -sL "${BASE_URL}/Xray-android-x86_64.zip" -o /tmp/xray-x86_64.zip
unzip -o /tmp/xray-x86_64.zip xray -d /tmp/xray-x86_64
mv /tmp/xray-x86_64/xray app/src/main/jniLibs/x86_64/libxray.so
rm -rf /tmp/xray-x86_64 /tmp/xray-x86_64.zip

echo "Done."
ls -lh app/src/main/jniLibs/*/libxray.so
