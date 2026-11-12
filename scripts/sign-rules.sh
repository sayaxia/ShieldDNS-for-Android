#!/usr/bin/env bash
# 给线上规则文件签名：生成/覆盖 <规则文件>.sig
# 私钥不入库（keystore/ 已被 .gitignore 忽略），由发布者本地保管。
#
#   bash scripts/sign-rules.sh                        # 给 rules/default-rules.txt 签名
#   bash scripts/sign-rules.sh rules/xxx.txt          # 指定文件
set -euo pipefail

cd "$(dirname "$0")/.."
KEY="keystore/rules-signing.pem"
FILE="${1:-rules/default-rules.txt}"

if [ ! -f "$KEY" ]; then
    echo "缺少签名私钥 $KEY（不入库，请从自己的备份恢复）" >&2
    exit 1
fi
if [ ! -f "$FILE" ]; then
    echo "找不到规则文件 $FILE" >&2
    exit 1
fi

openssl dgst -sha256 -sign "$KEY" -out "$FILE.sig" "$FILE"

# 立刻用公钥回验一遍，避免签错文件却没发现
PUB="$(mktemp)"
trap 'rm -f "$PUB"' EXIT
openssl ec -in "$KEY" -pubout -out "$PUB" 2>/dev/null
if openssl dgst -sha256 -verify "$PUB" -signature "$FILE.sig" "$FILE" >/dev/null 2>&1; then
    echo "已签名并回验通过：$FILE.sig（$(wc -c <"$FILE.sig" | tr -d ' ') 字节）"
else
    echo "签名生成后回验失败，请检查私钥" >&2
    exit 1
fi
