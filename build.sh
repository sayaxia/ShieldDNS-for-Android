#!/usr/bin/env bash
# 一键构建 APK（release，已签名）。默认工具链路径就是本沙箱里的那套，可覆盖。
set -euo pipefail

cd "$(dirname "$0")"

export JAVA_HOME="${JAVA_HOME:-/home/user/toolchain/jdk17}"
export ANDROID_HOME="${ANDROID_HOME:-/home/user/toolchain/sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-/home/user/toolchain/gradle-home}"
GRADLE_BIN="${GRADLE_BIN:-/home/user/toolchain/gradle/bin/gradle}"

"$GRADLE_BIN" --no-daemon testDebugUnitTest assembleRelease

APK=app/build/outputs/apk/release/app-release.apk
echo "APK: $APK"
ls -l "$APK"
