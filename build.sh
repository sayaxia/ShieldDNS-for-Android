#!/usr/bin/env bash
# 一键构建：单测 + release APK。工具链路径可用环境变量覆盖（CI 用 runner 自带的那套）。
set -euo pipefail

cd "$(dirname "$0")"

export JAVA_HOME="${JAVA_HOME:-/home/user/toolchain/jdk17}"
export ANDROID_HOME="${ANDROID_HOME:-/home/user/toolchain/sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-/home/user/toolchain/gradle-home}"
GRADLE_BIN="${GRADLE_BIN:-/home/user/toolchain/gradle/bin/gradle}"

# 内网 HTTPS 中间人环境才需要额外 CA；找不到就不带，正常机器无感。
TS="${SHIELDDNS_TRUSTSTORE:-}"
if [ -z "$TS" ]; then
  for c in "$PWD/truststore.jks" "${ANDROID_HOME%/sdk}/truststore.jks" /home/user/toolchain/truststore.jks; do
    if [ -f "$c" ]; then TS="$c"; break; fi
  done
fi
if [ -n "$TS" ]; then
  export SHIELDDNS_TRUSTSTORE="$TS"
  export GRADLE_OPTS="${GRADLE_OPTS:-} -Djavax.net.ssl.trustStore=$TS -Djavax.net.ssl.trustStorePassword=${SHIELDDNS_TRUSTSTORE_PASS:-changeit}"
  echo "trustStore: $TS"
fi

if [ ! -f signing.properties ]; then
  echo "警告：没有 signing.properties，release 会产出未签名包，不能发布。"
  echo "      本地请按 docs/BUILD.md 生成；CI 由 Secrets 注入。"
fi

"$GRADLE_BIN" --no-daemon testDebugUnitTest assembleRelease

APK=app/build/outputs/apk/release/app-release.apk
echo "APK: $APK"
ls -l "$APK"
