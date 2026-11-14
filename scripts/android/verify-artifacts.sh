#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# verify-artifacts.sh — 发布前产物自检（签名 / 版本号 / IME 声明）
#
# 用法：
#   bash scripts/android/verify-artifacts.sh
#   APK_PATH=... AAB_PATH=... bash scripts/android/verify-artifacts.sh
#
# 校验项：
#   1. release APK 能通过 apksigner verify（签名完整、v1/v2/v3 至少一种）
#   2. 打印签名证书 SHA-256 指纹（写入 step summary，便于比对是否用了错误的 key）
#   3. AAB 内 package / versionCode / versionName 与 $VERSION_CODE / $VERSION_NAME 一致
#   4. IME service 声明校验（leanback 不再要求，仅做检测提示）
#   5. APK 内含输入法 Service 声明（DoubaoVoiceInputMethodService）
#   6. minSdk / targetSdk 打印；若设置了 MIN_SDK_EXPECTED / TARGET_SDK_EXPECTED 则做等值校验
#
# 依赖环境变量：
#   VERSION_CODE / VERSION_NAME   必填（由 compute-version.sh 产出）
#   ANDROID_HOME / ANDROID_SDK_ROOT  定位 apksigner、aapt2
#   BUNDLETOOL_JAR                读 AAB manifest 用（未设置则跳过 AAB 深度校验）
#
# 任何一项失败都以非 0 退出，阻止后续发布步骤。
# ---------------------------------------------------------------------------
set -euo pipefail

log() { echo "[verify] $*" >&2; }
warn() { echo "[verify] 警告：$*" >&2; }
fail() { echo "[verify] 失败：$*" >&2; exit 1; }

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

: "${VERSION_CODE:?必须设置 VERSION_CODE（由 compute-version.sh 产出）}"
: "${VERSION_NAME:?必须设置 VERSION_NAME（由 compute-version.sh 产出）}"

APK_PATH="${APK_PATH:-${REPO_ROOT}/android/app/build/outputs/apk/release/app-release.apk}"
AAB_PATH="${AAB_PATH:-${REPO_ROOT}/android/app/build/outputs/bundle/release/app-release.aab}"

SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"

# --- 定位 build-tools 里的命令行工具 ---------------------------------------
find_build_tool() {
  local name="$1"
  [[ -n "${SDK_ROOT}" && -d "${SDK_ROOT}/build-tools" ]] || return 1
  # 按版本号排序取最新的一个
  find "${SDK_ROOT}/build-tools" -maxdepth 2 -type f -name "${name}" 2>/dev/null \
    | sort -V | tail -n1
}

APKSIGNER="$(find_build_tool apksigner || true)"
AAPT2="$(find_build_tool aapt2 || true)"

summary_file="${GITHUB_STEP_SUMMARY:-/dev/null}"

# ---------------------------------------------------------------------------
# 1. APK 存在性与签名
# ---------------------------------------------------------------------------
[[ -f "${APK_PATH}" ]] || fail "找不到 release APK：${APK_PATH}"
log "待校验 APK：${APK_PATH}（$(du -h "${APK_PATH}" | cut -f1)）"

if [[ -z "${APKSIGNER}" ]]; then
  fail "找不到 apksigner（ANDROID_HOME=${SDK_ROOT:-<未设置>}），无法验签"
fi

verify_out="$(mktemp)"
if ! "${APKSIGNER}" verify --verbose --print-certs "${APK_PATH}" > "${verify_out}" 2>&1; then
  cat "${verify_out}" >&2
  rm -f "${verify_out}"
  fail "apksigner verify 未通过：APK 未签名或签名损坏"
fi

cert_digest="$(grep -i 'Signer #1 certificate SHA-256 digest' "${verify_out}" | head -n1 | sed 's/.*: //' || true)"
rm -f "${verify_out}"

if [[ -z "${cert_digest}" ]]; then
  warn "未能从 apksigner 输出中提取证书指纹（v3 单独签名时属正常，请人工确认）"
else
  log "签名证书 SHA-256：${cert_digest}"
fi

{
  echo "### APK 验签"
  echo ""
  echo "- 文件：\`$(basename "${APK_PATH}")\`"
  echo "- 证书 SHA-256：\`${cert_digest:-未知}\`"
} >> "${summary_file}"

# ---------------------------------------------------------------------------
# 2. AAB 版本元数据
# ---------------------------------------------------------------------------
if [[ -f "${AAB_PATH}" ]]; then
  log "待校验 AAB：${AAB_PATH}"
  if [[ -n "${BUNDLETOOL_JAR:-}" && -f "${BUNDLETOOL_JAR:-}" ]]; then
    aab_manifest="$(mktemp)"
    java -jar "${BUNDLETOOL_JAR}" dump manifest --bundle="${AAB_PATH}" > "${aab_manifest}" 2>/dev/null \
      || { rm -f "${aab_manifest}"; fail "bundletool dump manifest 执行失败，AAB 可能损坏"; }

    aab_pkg="$(grep -oE 'package="[^"]+"' "${aab_manifest}" | head -n1 | sed 's/package="//;s/"//' || true)"
    aab_vc="$(grep -oE 'android:versionCode="[0-9]+"' "${aab_manifest}" | head -n1 | grep -oE '[0-9]+' || true)"
    aab_vn="$(grep -oE 'android:versionName="[^"]+"' "${aab_manifest}" | head -n1 | sed 's/.*="//;s/"//' || true)"
    aab_ms="$(grep -oE 'android:minSdkVersion="[0-9]+"' "${aab_manifest}" | head -n1 | grep -oE '[0-9]+' || true)"
    aab_ts="$(grep -oE 'android:targetSdkVersion="[0-9]+"' "${aab_manifest}" | head -n1 | grep -oE '[0-9]+' || true)"

    log "AAB → package=${aab_pkg:-?} versionCode=${aab_vc:-?} versionName=${aab_vn:-?} minSdk=${aab_ms:-?} targetSdk=${aab_ts:-?}"

    [[ "${aab_vc}" == "${VERSION_CODE}" ]] \
      || fail "AAB versionCode=${aab_vc:-<空>} 与期望 ${VERSION_CODE} 不一致"
    [[ "${aab_vn}" == "${VERSION_NAME}" ]] \
      || fail "AAB versionName=${aab_vn:-<空>} 与期望 ${VERSION_NAME} 不一致"
    [[ "${aab_pkg}" == "com.doubao.murmur.tv" ]] \
      || fail "AAB package=${aab_pkg:-<空>} 与 applicationId com.doubao.murmur.tv 不一致"

    if [[ -n "${MIN_SDK_EXPECTED:-}" && -n "${aab_ms}" ]]; then
      [[ "${aab_ms}" == "${MIN_SDK_EXPECTED}" ]] || fail "AAB minSdk=${aab_ms} 与期望 ${MIN_SDK_EXPECTED} 不一致"
    fi
    if [[ -n "${TARGET_SDK_EXPECTED:-}" && -n "${aab_ts}" ]]; then
      [[ "${aab_ts}" == "${TARGET_SDK_EXPECTED}" ]] || fail "AAB targetSdk=${aab_ts} 与期望 ${TARGET_SDK_EXPECTED} 不一致"
    fi
    [[ -n "${aab_ms}" ]] || warn "AAB manifest 中未找到 minSdkVersion（可能写在 defaultConfig 但未合并，请人工确认）"

    {
      echo ""
      echo "### AAB 元数据"
      echo ""
      echo "| 字段 | 值 |"
      echo "|---|---|"
      echo "| package | \`${aab_pkg:-?}\` |"
      echo "| versionCode | \`${aab_vc:-?}\` |"
      echo "| versionName | \`${aab_vn:-?}\` |"
      echo "| minSdk | \`${aab_ms:-?}\` |"
      echo "| targetSdk | \`${aab_ts:-?}\` |"
    } >> "${summary_file}"

    rm -f "${aab_manifest}"
  else
    warn "未设置 BUNDLETOOL_JAR，跳过 AAB 深度校验（APK 校验仍然生效）"
  fi
else
  warn "未找到 AAB：${AAB_PATH}，跳过 AAB 校验（仅在只出 APK 的场景可接受）"
fi

# ---------------------------------------------------------------------------
# 3. TV 声明与 IME 声明（从 APK 的二进制 manifest 解出）
# ---------------------------------------------------------------------------
if [[ -z "${AAPT2}" ]]; then
  fail "找不到 aapt2（ANDROID_HOME=${SDK_ROOT:-<未设置>}），无法校验 TV / IME 声明"
fi

badging="$(mktemp)"
"${AAPT2}" dump badging "${APK_PATH}" > "${badging}" 2>/dev/null \
  || { rm -f "${badging}"; fail "aapt2 dump badging 失败，APK 结构异常"; }

grep -qi 'leanback' "${badging}" \
  && log "leanback 已声明" \
  || log "leanback 未声明（纯手机版，符合预期）"

tree="$(mktemp)"
"${AAPT2}" dump xmltree --file AndroidManifest.xml "${APK_PATH}" > "${tree}" 2>/dev/null \
  || { rm -f "${tree}" "${badging}"; fail "aapt2 dump xmltree 失败，无法读取 manifest"; }

if ! grep -q 'DoubaoVoiceInputMethodService' "${tree}"; then
  rm -f "${tree}" "${badging}"
  fail "APK manifest 中未找到 DoubaoVoiceInputMethodService —— 输入法 Service 声明丢失，安装后不可用"
fi

if ! grep -q 'android.inputmethodservice.InputMethodService\|android.permission.BIND_INPUT_METHOD' "${tree}"; then
  rm -f "${tree}" "${badging}"
  fail "APK manifest 中未找到 IME 绑定声明（BIND_INPUT_METHOD / InputMethodService）"
fi

ime_services="$(grep -c 'BIND_INPUT_METHOD' "${tree}" || true)"

if grep -qi 'leanback' "${badging}"; then
  leanback_status="leanback 已声明"
else
  leanback_status="leanback 未声明（纯手机版）"
fi
rm -f "${tree}" "${badging}"

log "manifest 校验通过：${leanback_status}，IME service 已声明（BIND_INPUT_METHOD 出现 ${ime_services} 次）"

{
  echo ""
  echo "### Manifest 校验"
  echo ""
  echo "- android.software.leanback：${leanback_status#leanback }"
  echo "- DoubaoVoiceInputMethodService：已声明"
} >> "${summary_file}"

log "全部校验通过：${VERSION_NAME} (${VERSION_CODE})"
