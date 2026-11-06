#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# make-ota-manifest.sh — 生成 TV 侧 OTA 升级用的 manifest.json
#
# 用法：
#   bash scripts/android/make-ota-manifest.sh \
#     --version-code 100 --version-name 0.1.0 \
#     --apk dist/universal.apk \
#     --base-url https://example.com/doubao-murmur-tv/0.1.0/ \
#     --notes "首次内测版本" \
#     [--min-sdk 23] [--out tv-ota/manifest.json]
#
# 也可以用环境变量代替参数（CI 里更顺手）：
#   VERSION_CODE VERSION_NAME APK_PATH BASE_URL RELEASE_NOTES MIN_SDK OTA_OUT
#
# 输出字段（TV 端升级器按此解析）：
#   versionCode / versionName / minSdk / sha256 / sizeBytes / apkUrl / releaseNotes / publishedAt
#
# 依赖 jq 保证 JSON 合法性（CI 的 ubuntu-latest 自带）。
# ---------------------------------------------------------------------------
set -euo pipefail

log() { echo "[ota-manifest] $*" >&2; }
die() { echo "[ota-manifest] 错误：$*" >&2; exit 1; }

VERSION_CODE="${VERSION_CODE:-}"
VERSION_NAME="${VERSION_NAME:-}"
APK_PATH="${APK_PATH:-}"
BASE_URL="${BASE_URL:-}"
RELEASE_NOTES="${RELEASE_NOTES:-}"
MIN_SDK="${MIN_SDK:-23}"
OTA_OUT="${OTA_OUT:-tv-ota/manifest.json}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --version-code) VERSION_CODE="$2"; shift 2 ;;
    --version-name) VERSION_NAME="$2"; shift 2 ;;
    --apk)          APK_PATH="$2"; shift 2 ;;
    --base-url)     BASE_URL="$2"; shift 2 ;;
    --notes)        RELEASE_NOTES="$2"; shift 2 ;;
    --min-sdk)      MIN_SDK="$2"; shift 2 ;;
    --out)          OTA_OUT="$2"; shift 2 ;;
    -h|--help)      sed -n '2,25p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) die "未知参数：$1" ;;
  esac
done

[[ -n "${VERSION_CODE}" ]] || die "缺少 --version-code"
[[ -n "${VERSION_NAME}" ]] || die "缺少 --version-name"
[[ -n "${APK_PATH}" ]] || die "缺少 --apk"
[[ -f "${APK_PATH}" ]] || die "APK 不存在：${APK_PATH}"
[[ -n "${BASE_URL}" ]] || die "缺少 --base-url（TV 端需要绝对下载地址）"

command -v jq >/dev/null 2>&1 || die "找不到 jq，请先安装（apt-get install -y jq）"

# macOS 用 shasum，Linux / Git Bash 用 sha256sum
if command -v sha256sum >/dev/null 2>&1; then
  SHA256="$(sha256sum "${APK_PATH}" | cut -d' ' -f1)"
elif command -v shasum >/dev/null 2>&1; then
  SHA256="$(shasum -a 256 "${APK_PATH}" | cut -d' ' -f1)"
else
  die "找不到 sha256sum / shasum，无法计算校验和"
fi

SIZE_BYTES="$(wc -c < "${APK_PATH}" | tr -d ' ')"
PUBLISHED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

# apkUrl：baseUrl 末尾的斜杠归一化后拼接文件名
APK_NAME="$(basename "${APK_PATH}")"
APK_URL="${BASE_URL%/}/${APK_NAME}"

mkdir -p "$(dirname "${OTA_OUT}")"

jq -n \
  --argjson versionCode "${VERSION_CODE}" \
  --arg versionName "${VERSION_NAME}" \
  --argjson minSdk "${MIN_SDK}" \
  --arg sha256 "${SHA256}" \
  --argjson sizeBytes "${SIZE_BYTES}" \
  --arg apkUrl "${APK_URL}" \
  --arg releaseNotes "${RELEASE_NOTES}" \
  --arg publishedAt "${PUBLISHED_AT}" \
  '{
     versionCode: $versionCode,
     versionName: $versionName,
     minSdk: $minSdk,
     sha256: $sha256,
     sizeBytes: $sizeBytes,
     apkUrl: $apkUrl,
     releaseNotes: $releaseNotes,
     publishedAt: $publishedAt
   }' > "${OTA_OUT}"

log "已生成 ${OTA_OUT}"
log "  versionCode=${VERSION_CODE} versionName=${VERSION_NAME} size=${SIZE_BYTES}B"
log "  sha256=${SHA256}"
log "  apkUrl=${APK_URL}"
