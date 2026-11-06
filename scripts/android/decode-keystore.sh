#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# decode-keystore.sh — 把 CI secret 里的 base64 keystore 还原成临时文件
#
# 用法：
#   ANDROID_KEYSTORE_BASE64=... ANDROID_KEYSTORE_PASSWORD=... \
#     bash scripts/android/decode-keystore.sh [输出路径]
#
# 行为：
#   1. 从环境变量 ANDROID_KEYSTORE_BASE64 解码到 ${RUNNER_TEMP:-/tmp}/release.keystore
#   2. chmod 600，只有当前用户可读
#   3. 用 keytool -list 试读，确认是合法的 JKS/PKCS12 且密码正确
#   4. 若在 GitHub Actions 中（存在 $GITHUB_ENV），把 ANDROID_KEYSTORE_PATH 写入 env
#
# 安全：
#   - 绝不 echo / log 任何密码，keytool 的输出全部重定向到 /dev/null
#   - 调用方必须在作业末尾用 `if: always()` 删除该临时文件（release 工作流已实现）
# ---------------------------------------------------------------------------
set -euo pipefail

log() { echo "[decode-keystore] $*" >&2; }
die() { echo "[decode-keystore] 错误：$*" >&2; exit 1; }

TARGET="${1:-${RUNNER_TEMP:-${TMPDIR:-/tmp}}/release.keystore}"

if [[ -z "${ANDROID_KEYSTORE_BASE64:-}" ]]; then
  cat >&2 <<'EOF'
[decode-keystore] 错误：环境变量 ANDROID_KEYSTORE_BASE64 为空。
  请在 GitHub 仓库 Settings → Secrets and variables → Actions 中配置该 secret：
    base64 -w 0 release.keystore        # Linux
    base64 -i release.keystore | tr -d '\n'   # macOS
EOF
  exit 1
fi

if [[ -z "${ANDROID_KEYSTORE_PASSWORD:-}" ]]; then
  die "环境变量 ANDROID_KEYSTORE_PASSWORD 为空，无法校验 keystore（secret 是否漏配？）"
fi

# 目标目录必须存在；RUNNER_TEMP 在 Actions 里一定存在，本地运行时兜底创建
mkdir -p "$(dirname "${TARGET}")"

# tr 去掉换行和空格，容忍 secret 里被编辑器折行的情况
if ! printf '%s' "${ANDROID_KEYSTORE_BASE64}" | tr -d '\r\n\t ' | base64 -d > "${TARGET}" 2>/dev/null; then
  rm -f "${TARGET}"
  die "base64 解码失败，ANDROID_KEYSTORE_BASE64 可能被截断或不是合法 base64"
fi

chmod 600 "${TARGET}"

if [[ ! -s "${TARGET}" ]]; then
  rm -f "${TARGET}"
  die "解码后的 keystore 为空文件，请检查 secret 内容"
fi

# 粗校验文件魔数：JKS 以 FE ED FE ED 开头，PKCS12 是一个 DER SEQUENCE（0x30）
magic="$(od -An -tx1 -N4 "${TARGET}" | tr -d ' \n')"
case "${magic}" in
  feedfeed*) : ;;                      # JKS
  3082*|3081*|30*) : ;;                # PKCS12 / DER
  *) log "警告：keystore 魔数为 ${magic}，不是典型的 JKS/PKCS12 头，继续交给 keytool 判定" ;;
esac

if ! command -v keytool >/dev/null 2>&1; then
  die "找不到 keytool，请确认 actions/setup-java 已执行且 JDK 在 PATH 中"
fi

# keytool 的 stdout/stderr 全部丢弃：其中可能包含别名、证书信息，但绝不希望密码泄漏
if ! keytool -list -keystore "${TARGET}" -storepass "${ANDROID_KEYSTORE_PASSWORD}" >/dev/null 2>&1; then
  rm -f "${TARGET}"
  die "keytool 无法读取 keystore：密码错误、store 类型不支持或文件损坏（未输出任何敏感信息）"
fi

log "keystore 已就绪：${TARGET}"

if [[ -n "${GITHUB_ENV:-}" ]]; then
  {
    echo "ANDROID_KEYSTORE_PATH=${TARGET}"
  } >> "${GITHUB_ENV}"
  log "已写入 GITHUB_ENV：ANDROID_KEYSTORE_PATH"
fi
