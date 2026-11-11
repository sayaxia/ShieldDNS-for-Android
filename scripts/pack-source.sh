#!/usr/bin/env bash
# 打源码包：排除私钥、本机签名参数、构建产物与 IDE 文件。
# 用法: scripts/pack-source.sh [版本号] [输出目录]
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
NAME=$(basename "$ROOT")
OUT_DIR="${2:-$ROOT/dist}"
VER="${1:-}"

if [ -z "$VER" ]; then
  VER=$(grep -oP "appVersionName = .*?: '\K[^']+" "$ROOT/build.gradle" | head -1)
fi
if [ -z "$VER" ]; then
  echo "ERROR: 解析不到版本号，请显式传参" >&2
  exit 1
fi

# 输出目录必须在校验版本号前转成绝对路径：后面会 cd 到父目录打包
case "$OUT_DIR" in
  /*) ;;
  *) OUT_DIR="$ROOT/$OUT_DIR" ;;
esac

mkdir -p "$OUT_DIR"
ZIP="$OUT_DIR/ShieldDNS-Android-${VER}-source.zip"
rm -f "$ZIP"

# 在父目录打包，保证解压后是 ShieldDNS-Android/ 一个顶层目录
cd "$(dirname "$ROOT")"
zip -qr "$ZIP" "$NAME" \
  -x "$NAME/.git/*" "$NAME/.gradle/*" "$NAME/build/*" "$NAME/app/build/*" \
     "$NAME/keystore/*" "$NAME/signing.properties" "$NAME/truststore.jks" \
     "$NAME/local.properties" "$NAME/.idea/*" "$NAME/*.iml" \
     "$NAME"/*.apk "$NAME"/*.zip "$NAME/dist/*"

# 保险：包里一旦出现私钥或签名参数就直接失败，别等发布出去才发现
if unzip -l "$ZIP" | grep -qiE "\.jks|\.keystore|signing\.properties"; then
  echo "ERROR: 源码包混入了签名材料，已中止" >&2
  exit 1
fi

echo "源码包: $ZIP"
echo "文件数: $(unzip -l "$ZIP" | tail -1 | awk '{print $2}')"
