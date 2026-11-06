#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# gen-changelog.sh — 从 conventional commits 生成 Markdown changelog
#
# 用法：
#   bash scripts/android/gen-changelog.sh                       # 最近 android-tv-v* tag..HEAD
#   bash scripts/android/gen-changelog.sh android-tv-v0.1.0     # 指定起点 tag
#   bash scripts/android/gen-changelog.sh android-tv-v0.1.0 HEAD
#   VERSION_NAME=0.2.0 bash scripts/android/gen-changelog.sh    # 带上标题版本号
#
# 输出（stdout，Markdown）：
#   ## 0.2.0 (2026-11-06)
#   ### 新增 / ### 修复 / ### 变更 / ### 性能 / ### 其他
#
# 供 android-release.yml 的 Release 正文与 bump-version-pr.sh 复用。
# ---------------------------------------------------------------------------
set -euo pipefail

log() { echo "[changelog] $*" >&2; }

TAG_PREFIX="android-tv-v"
FROM="${1:-}"
TO="${2:-HEAD}"

if [[ -z "${FROM}" ]]; then
  FROM="$(git tag --list "${TAG_PREFIX}*" --sort=-v:refname 2>/dev/null | head -n1)"
  if [[ -z "${FROM}" ]]; then
    log "未找到 ${TAG_PREFIX}* tag，全历史纳入"
    RANGE="${TO}"
  else
    RANGE="${FROM}..${TO}"
  fi
else
  RANGE="${FROM}..${TO}"
fi

VERSION_NAME="${VERSION_NAME:-}"
if [[ -z "${VERSION_NAME}" && "${TO}" != "HEAD" ]]; then
  VERSION_NAME="${TO#${TAG_PREFIX}}"
fi

# 取提交标题（不含 merge commit 噪音）
commits="$(git log "${RANGE}" --no-merges --pretty=format:'%s' 2>/dev/null || true)"

# 分组缓冲
added=""; fixed=""; changed=""; perf=""; other=""

while IFS= read -r subject; do
  [[ -z "${subject}" ]] && continue

  # 忽略纯版本号提交，避免 changelog 里出现 "chore: bump version"
  if printf '%s' "${subject}" | grep -qiE '^(chore|build|ci)(\(.*\))?: *(bump|release)'; then
    continue
  fi

  # 去掉 type(scope): 前缀，保留描述；scope 以 **[scope]** 形式保留
  body="$(printf '%s' "${subject}" | sed -E 's/^[a-zA-Z]+(\(([^)]*)\))?!?:[[:space:]]*//')"
  scope="$(printf '%s' "${subject}" | sed -nE 's/^[a-zA-Z]+\(([^)]*)\)!?:.*/\1/p')"
  item="- ${body}"
  [[ -n "${scope}" ]] && item="- **${scope}**: ${body}"

  case "${subject}" in
    feat*)                    added="${added}${item}"$'\n' ;;
    fix*|hotfix*)             fixed="${fixed}${item}"$'\n' ;;
    perf*)                    perf="${perf}${item}"$'\n' ;;
    refactor*|style*|chore*|docs*|build*|ci*|test*|revert*) changed="${changed}${item}"$'\n' ;;
    *)                        other="${other}${item}"$'\n' ;;
  esac
done <<< "${commits}"

DATE_UTC="$(date -u +%Y-%m-%d)"

if [[ -n "${VERSION_NAME}" ]]; then
  echo "## ${VERSION_NAME} (${DATE_UTC})"
else
  echo "## ${DATE_UTC}"
fi
echo

emit_section() {
  local title="$1" content="$2"
  [[ -z "${content// /}" ]] && return 0
  printf '### %s\n\n' "${title}"
  printf '%s' "${content}"
  printf '\n'
}

emit_section "新增 Added"      "${added}"
emit_section "修复 Fixed"      "${fixed}"
emit_section "变更 Changed"    "${changed}"
emit_section "性能 Performance" "${perf}"
emit_section "其他 Other"      "${other}"

if [[ -z "${added}${fixed}${changed}${perf}${other}" ]]; then
  echo "_本区间无用户可见的变更。_"
  echo
fi

if [[ -n "${FROM}" ]]; then
  echo "**完整对比**：\`${FROM}...${TO}\`"
fi
