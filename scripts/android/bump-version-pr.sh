#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# bump-version-pr.sh — 计算下一版本并更新版本文件，供 CI 开 PR
#
# 用法：
#   bash scripts/android/bump-version-pr.sh
#   bash scripts/android/bump-version-pr.sh --dry-run     # 只打印，不落盘
#
# 做的事：
#   1. 调用 compute-version.sh --next 得到下一个版本（基于最近 android-tv-v* tag + conventional commits）
#   2. 更新 android/version.properties 的 versionMajor / versionMinor / versionPatch
#      （versionCodeOffset 保持不变——它只用于"重发同一版本号给不同渠道"的极少数场景）
#   3. 在 android/CHANGELOG.md 顶部插入新版本段落（复用 gen-changelog.sh）
#   4. 把 NEW_VERSION / NEW_VERSION_CODE / BRANCH_NAME 输出到 stdout（并写 $GITHUB_OUTPUT）
#
# 为什么不引入 release-please / semantic-release：
#   它们的文件更新器不支持 .properties 格式，强行接入会把 version.properties 写成
#   JSON/TOML 或直接改坏；而本项目的版本单一可信源是 **git tag**，CI 用 tag 派生版本，
#   version.properties 只是本地开发的默认值。因此用 30 行 shell 更稳、更可审计。
#
# 注意：本脚本不执行 git commit / push / gh pr create —— 由调用方（人或 workflow）决定。
# ---------------------------------------------------------------------------
set -euo pipefail

log() { echo "[bump] $*" >&2; }
die() { echo "[bump] 错误：$*" >&2; exit 1; }

DRY_RUN="false"
[[ "${1:-}" == "--dry-run" ]] && DRY_RUN="true"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
VERSION_FILE="${REPO_ROOT}/android/version.properties"
CHANGELOG_FILE="${REPO_ROOT}/android/CHANGELOG.md"

[[ -f "${VERSION_FILE}" ]] || die "找不到 ${VERSION_FILE}（Gradle 骨架是否尚未生成？）"

# --- 1. 推算下一版本 --------------------------------------------------------
next_out="$(bash "${SCRIPT_DIR}/compute-version.sh" --next)"
NEW_VERSION_NAME="$(printf '%s\n' "${next_out}" | sed -n 's/^VERSION_NAME=//p')"
NEW_VERSION_CODE="$(printf '%s\n' "${next_out}" | sed -n 's/^VERSION_CODE=//p')"

[[ -n "${NEW_VERSION_NAME}" ]] || die "compute-version.sh 未返回 VERSION_NAME"
[[ -n "${NEW_VERSION_CODE}" ]] || die "compute-version.sh 未返回 VERSION_CODE"

# 预发布版本不写进 version.properties（properties 只承载稳定版默认值）
BASE="${NEW_VERSION_NAME%%-*}"
IFS='.' read -r V_MAJOR V_MINOR V_PATCH <<<"${BASE}"

log "下一版本：${NEW_VERSION_NAME} (versionCode=${NEW_VERSION_CODE})"

# --- 2. 更新 version.properties --------------------------------------------
if [[ "${DRY_RUN}" == "true" ]]; then
  log "dry-run：跳过写 ${VERSION_FILE} 与 ${CHANGELOG_FILE}"
else
  # 用临时文件替换，避免 sed -i 在 macOS/GNU 上的行为差异
  tmp_file="$(mktemp)"
  sed -E \
    -e "s/^versionMajor=.*/versionMajor=${V_MAJOR}/" \
    -e "s/^versionMinor=.*/versionMinor=${V_MINOR}/" \
    -e "s/^versionPatch=.*/versionPatch=${V_PATCH}/" \
    "${VERSION_FILE}" > "${tmp_file}"

  # 若文件里原本缺字段，补上（保持四字段齐全）
  for kv in "versionMajor=${V_MAJOR}" "versionMinor=${V_MINOR}" "versionPatch=${V_PATCH}"; do
    key="${kv%%=*}"
    grep -q "^${key}=" "${tmp_file}" || echo "${kv}" >> "${tmp_file}"
  done
  grep -q '^versionCodeOffset=' "${tmp_file}" || echo "versionCodeOffset=0" >> "${tmp_file}"

  mv "${tmp_file}" "${VERSION_FILE}"
  log "已更新 ${VERSION_FILE}"

  # --- 3. 在 CHANGELOG.md 顶部插入新段落 ----------------------------------
  section="$(VERSION_NAME="${NEW_VERSION_NAME}" bash "${SCRIPT_DIR}/gen-changelog.sh")"

  tmp_cl="$(mktemp)"
  if [[ -f "${CHANGELOG_FILE}" ]]; then
    # 保留一级标题，其余内容整体后移
    if head -n1 "${CHANGELOG_FILE}" | grep -qE '^#\s'; then
      {
        head -n1 "${CHANGELOG_FILE}"
        echo
        printf '%s\n' "${section}"
        echo
        tail -n +2 "${CHANGELOG_FILE}"
      } > "${tmp_cl}"
    else
      {
        printf '%s\n' "${section}"
        echo
        cat "${CHANGELOG_FILE}"
      } > "${tmp_cl}"
    fi
  else
    {
      echo "# Changelog"
      echo
      printf '%s\n' "${section}"
    } > "${tmp_cl}"
  fi
  mv "${tmp_cl}" "${CHANGELOG_FILE}"
  log "已更新 ${CHANGELOG_FILE}"
fi

# --- 4. 输出给 CI ----------------------------------------------------------
BRANCH_NAME="chore/android-tv-release-${NEW_VERSION_NAME}"

printf 'NEW_VERSION=%s\n' "${NEW_VERSION_NAME}"
printf 'NEW_VERSION_CODE=%s\n' "${NEW_VERSION_CODE}"
printf 'BRANCH_NAME=%s\n' "${BRANCH_NAME}"

if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
  {
    echo "new_version=${NEW_VERSION_NAME}"
    echo "new_version_code=${NEW_VERSION_CODE}"
    echo "branch_name=${BRANCH_NAME}"
  } >> "${GITHUB_OUTPUT}"
fi

cat >&2 <<EOF

[bump] 下一步（由人或 workflow 执行）：
  git checkout -b ${BRANCH_NAME}
  git add android/version.properties android/CHANGELOG.md
  git commit -m "chore(android): release ${NEW_VERSION_NAME}"
  git push -u origin ${BRANCH_NAME}
  gh pr create --title "chore(android): release ${NEW_VERSION_NAME}" --fill
  # PR 合并后打 tag（tag 才是真正的版本可信源）：
  git tag android-tv-v${NEW_VERSION_NAME}
  git push origin android-tv-v${NEW_VERSION_NAME}
EOF
