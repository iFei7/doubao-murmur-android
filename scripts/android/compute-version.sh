#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# compute-version.sh — 由 tag 或版本号派生 versionName / versionCode
#
# 用法：
#   bash scripts/android/compute-version.sh android-tv-v1.2.3
#   bash scripts/android/compute-version.sh 1.2.3
#   bash scripts/android/compute-version.sh --next          # 基于最近 tag + conventional commits 推算下一版
#   bash scripts/android/compute-version.sh android-tv-v1.2.3 --next
#
# 输出（写 stdout，可直接 >> "$GITHUB_ENV"）：
#   VERSION_NAME=1.2.3
#   VERSION_CODE=10203
#
# 所有日志/告警走 stderr，保证 stdout 是纯粹的 env 文件内容。
#
# versionCode 公式：major * 10000 + minor * 100 + patch + versionCodeOffset
#   - 本脚本只计算 major*10000 + minor*100 + patch 这一部分；
#     versionCodeOffset 是"同一版本号重发给不同渠道"的本地逃生开关（默认 0），
#     由 Gradle 在拿不到 -PversionCode 时从 version.properties 里加。
#     CI 发版一律以 tag 为准，不叠加 offset——否则同一 tag 两次构建会得到不同 versionCode。
#   - 因此 major < 1000、minor < 100、patch < 100，否则编码会互相碰撞（脚本会报错）。
#   - 例：0.1.0 -> 100；1.2.3 -> 10203。
#
# 预发布版本（1.2.3-beta.1）：
#   versionName 保留后缀，versionCode = 基础版本编码 * 10 + 预发布序号。
#   例：1.2.3-beta.1 -> versionName=1.2.3-beta.1, versionCode=102031。
#   ⚠ 这条规则只适用于 sideload / 第三方渠道 / GitHub Release：
#     Play Console 不接受预发布后缀，且 102031 > 10203 会导致稳定版"回退"，
#     因此 **Play 渠道禁止使用预发布 versionCode**（发 Play 前必须发正式版号）。
# ---------------------------------------------------------------------------
set -euo pipefail

log() { echo "[compute-version] $*" >&2; }
die() { echo "[compute-version] 错误：$*" >&2; exit 1; }

usage() {
  sed -n '2,28p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
  exit "${1:-0}"
}

TAG_PREFIX="android-tv-v"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
VERSION_PROPS="${REPO_ROOT}/android/version.properties"

ARG="${1:-}"
NEXT_MODE="false"

# --next 既可以作为唯一参数，也可以跟在版本号后面
if [[ "${ARG}" == "--next" ]]; then
  NEXT_MODE="true"
  ARG=""
elif [[ "${2:-}" == "--next" ]]; then
  NEXT_MODE="true"
fi

case "${ARG}" in
  -h|--help) usage 0 ;;
esac

# ---------------------------------------------------------------------------
# 工具函数
# ---------------------------------------------------------------------------

# 取版本号最高的 android-tv-v* tag（--sort=-v:refname 是 git 自带的版本序排序）
latest_tag() {
  git tag --list "${TAG_PREFIX}*" --sort=-v:refname 2>/dev/null | head -n1
}

# 去掉前缀，保留 x.y.z[-pre] 形式
strip_prefix() {
  local v="$1"
  v="${v#"${TAG_PREFIX}"}"
  v="${v#v}"
  printf '%s' "${v}"
}

# 校验并拆分版本号，设置全局 BASE_MAJOR / BASE_MINOR / BASE_PATCH / PRE_SUFFIX
parse_version() {
  local raw="$1"
  raw="$(strip_prefix "${raw}")"

  BASE_MAJOR=""; BASE_MINOR=""; BASE_PATCH=""; PRE_SUFFIX=""
  if [[ "${raw}" == *-* ]]; then
    PRE_SUFFIX="${raw#*-}"
  fi
  local base="${raw%%-*}"

  [[ "${base}" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] \
    || die "无法解析版本号 '${raw}'，期望 x.y.z 或 android-tv-vx.y.z"

  IFS='.' read -r BASE_MAJOR BASE_MINOR BASE_PATCH <<<"${base}"

  (( BASE_MAJOR < 1000 )) || die "major 必须 < 1000（当前 ${BASE_MAJOR}），否则 versionCode 编码会溢出"
  (( BASE_MINOR < 100 ))  || die "minor 必须 < 100（当前 ${BASE_MINOR}），否则 versionCode 编码会碰撞"
  (( BASE_PATCH < 100 ))  || die "patch 必须 < 100（当前 ${BASE_PATCH}），否则 versionCode 编码会碰撞"
}

# 基础版本编码（不含预发布偏移）
base_code() {
  echo $(( BASE_MAJOR * 10000 + BASE_MINOR * 100 + BASE_PATCH ))
}

# 预发布序号：取后缀里最后一段数字，如 beta.1 -> 1、rc3 -> 3；取不到按 1 处理
prerelease_index() {
  local idx=""
  idx="$(printf '%s' "${PRE_SUFFIX}" | grep -oE '[0-9]+$' || true)"
  [[ -z "${idx}" ]] && idx="1"
  printf '%s' "${idx}"
}

# 由 conventional commits 判定语义化升级幅度，输出 major|minor|patch
detect_bump() {
  local range="$1"
  local log_text
  log_text="$(git log --format='%s%n%b' "${range}" 2>/dev/null || true)"

  if [[ -z "${log_text}" ]]; then
    log "提交区间 '${range}' 无新提交，默认按 patch 升级"
    echo "patch"; return
  fi

  # 破坏性变更：footer 里的 BREAKING CHANGE / BREAKING-CHANGE，或标题里的 type(scope)!:
  if printf '%s' "${log_text}" | grep -qE 'BREAKING[ -]CHANGE|^[a-zA-Z]+(\([^)]*\))?!:'; then
    echo "major"; return
  fi
  # 新功能
  if printf '%s' "${log_text}" | grep -qE '^feat(\([^)]*\))?!?:'; then
    echo "minor"; return
  fi
  echo "patch"
}

# ---------------------------------------------------------------------------
# --next 模式：从最近 tag 推算下一个版本
# ---------------------------------------------------------------------------
if [[ "${NEXT_MODE}" == "true" ]]; then
  base_tag="$(latest_tag)"
  if [[ -z "${base_tag}" ]]; then
    # 首次发版：没有历史 tag 时以 version.properties 的当前值为基线，
    # 而不是粗暴地从 0.0.0 起算（否则骨架里的 0.1.0 会被算成 0.0.1）。
    if [[ -f "${VERSION_PROPS}" ]]; then
      p_major="$(sed -n 's/^versionMajor=//p' "${VERSION_PROPS}" | head -n1)"
      p_minor="$(sed -n 's/^versionMinor=//p' "${VERSION_PROPS}" | head -n1)"
      p_patch="$(sed -n 's/^versionPatch=//p' "${VERSION_PROPS}" | head -n1)"
      base_version="${p_major:-0}.${p_minor:-0}.${p_patch:-0}"
      log "仓库中没有任何 ${TAG_PREFIX}* tag，以 version.properties 为基线：${base_version}"
    else
      base_version="0.0.0"
      log "仓库中没有任何 ${TAG_PREFIX}* tag 且无 version.properties，从 0.0.0 起算"
    fi
    commit_range="HEAD"
  else
    base_version="$(strip_prefix "${base_tag}")"
    commit_range="${base_tag}..HEAD"
    log "最近 tag：${base_tag}（基础版本 ${base_version}）"
  fi

  parse_version "${base_version}"
  bump="$(detect_bump "${commit_range}")"
  log "语义化升级判定：${bump}"

  case "${bump}" in
    major) n_major=$(( BASE_MAJOR + 1 )); n_minor=0; n_patch=0 ;;
    minor) n_major=${BASE_MAJOR}; n_minor=$(( BASE_MINOR + 1 )); n_patch=0 ;;
    *)     n_major=${BASE_MAJOR}; n_minor=${BASE_MINOR}; n_patch=$(( BASE_PATCH + 1 )) ;;
  esac

  ARG="${n_major}.${n_minor}.${n_patch}"
  log "推算出的下一版本：${ARG}"
fi

[[ -n "${ARG}" ]] || usage 1

# ---------------------------------------------------------------------------
# 计算并输出
# ---------------------------------------------------------------------------
parse_version "${ARG}"

VERSION_NAME="${BASE_MAJOR}.${BASE_MINOR}.${BASE_PATCH}"
if [[ -n "${PRE_SUFFIX}" ]]; then
  VERSION_NAME="${VERSION_NAME}-${PRE_SUFFIX}"
fi

CODE_BASE="$(base_code)"
if [[ -n "${PRE_SUFFIX}" ]]; then
  PRE_IDX="$(prerelease_index)"
  (( PRE_IDX < 10 )) || die "预发布序号必须 < 10（当前 ${PRE_IDX}），否则 versionCode 会与更高基础版本碰撞"
  VERSION_CODE=$(( CODE_BASE * 10 + PRE_IDX ))
  log "预发布版本：versionCode=${VERSION_CODE}（仅限非 Play 渠道，见脚本头部说明）"
else
  VERSION_CODE="${CODE_BASE}"
fi

printf 'VERSION_NAME=%s\n' "${VERSION_NAME}"
printf 'VERSION_CODE=%s\n' "${VERSION_CODE}"
