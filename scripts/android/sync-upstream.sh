#!/usr/bin/env bash
#
# 把本地的 master 分支对齐到真实上游历史。
#
# 为什么需要这个脚本：
#   本仓库的 master 是上游 iFei7/doubao-murmur-android 的“快照单提交”。创建快照的沙箱环境
#   代理拒绝对 github.com:443 的 CONNECT，无法 git clone/fetch，因此本地历史被压平成一个提交，
#   与真实上游没有共同的 commit 祖先。Android TV 的工作全部在 feat/android-tv-ime 分支的
#   android/ 子目录里，与上游文件零重叠，所以恢复真实历史不会丢掉任何 Android 改动。
#
#   在能直连 GitHub 的环境中执行本脚本后，master 就与上游一致，后续可以直接
#   `git rebase upstream/master` 或向 fork 提 PR。
#
# 用法：
#   bash scripts/android/sync-upstream.sh            # 默认：git reset --hard upstream/master（丢弃本地 master 独有提交）
#   bash scripts/android/sync-upstream.sh --rebase   # 保留本地 master 独有提交，rebase 到上游之上
#
# 可选环境变量：
#   UPSTREAM_URL   上游仓库地址（默认 https://github.com/iFei7/doubao-murmur-android.git）
#   UPSTREAM_REF   要同步的上游引用（默认 master）
#
set -euo pipefail

UPSTREAM_URL="${UPSTREAM_URL:-https://github.com/iFei7/doubao-murmur-android.git}"
UPSTREAM_REF="${UPSTREAM_REF:-master}"

MODE="reset"
for arg in "$@"; do
    case "${arg}" in
        --rebase)
            MODE="rebase"
            ;;
        -h|--help)
            sed -n '2,30p' "${BASH_SOURCE[0]}"
            exit 0
            ;;
        *)
            echo "错误：未知参数 ${arg}（可用：--rebase、--help）" >&2
            exit 2
            ;;
    esac
done

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "${REPO_ROOT}"

if [ ! -d .git ]; then
    echo "错误：${REPO_ROOT} 不是 git 仓库" >&2
    exit 1
fi

# 有未提交改动时直接拒绝，避免 reset --hard 误删工作。
if [ -n "$(git status --porcelain)" ]; then
    echo "错误：工作区有未提交的改动，请先 commit 或 stash："
    git status --short
    exit 1
fi

ORIGINAL_BRANCH="$(git rev-parse --abbrev-ref HEAD)"

# upstream remote 不存在时补上；已存在但地址不同则提示。
if git remote get-url upstream >/dev/null 2>&1; then
    EXISTING_URL="$(git remote get-url upstream)"
    if [ "${EXISTING_URL}" != "${UPSTREAM_URL}" ]; then
        echo "提示：已有 upstream remote 指向 ${EXISTING_URL}，保留不改（如需修改请手动 git remote set-url）"
    fi
else
    echo "==> 添加 upstream remote: ${UPSTREAM_URL}"
    git remote add upstream "${UPSTREAM_URL}"
fi

echo "==> 拉取 upstream/${UPSTREAM_REF} 与 tags"
git fetch upstream "${UPSTREAM_REF}" --tags

if ! git rev-parse --verify --quiet "upstream/${UPSTREAM_REF}" >/dev/null; then
    echo "错误：upstream/${UPSTREAM_REF} 不存在，请检查网络或 UPSTREAM_REF" >&2
    exit 1
fi

echo "==> 切换到 master"
git checkout master

if [ "${MODE}" = "rebase" ]; then
    echo "==> 模式 rebase：保留本地 master 独有提交"
    git rebase "upstream/${UPSTREAM_REF}"
else
    echo "==> 模式 reset：master 直接指向 upstream/${UPSTREAM_REF}（本地 master 独有提交会被丢弃）"
    git reset --hard "upstream/${UPSTREAM_REF}"
fi

if [ "${ORIGINAL_BRANCH}" != "master" ]; then
    echo "==> 切回原分支 ${ORIGINAL_BRANCH}"
    git checkout "${ORIGINAL_BRANCH}"
    if [ "${MODE}" = "rebase" ]; then
        echo "==> 把 ${ORIGINAL_BRANCH} rebase 到新的 master 之上"
        git rebase master
    else
        echo "提示：${ORIGINAL_BRANCH} 未自动 rebase，如需重建历史请手动执行："
        echo "     git rebase --onto master <旧 master> ${ORIGINAL_BRANCH}"
        echo "     或 git rebase master（若两者本就有共同祖先）"
    fi
fi

echo "==> 完成。当前 master: $(git rev-parse --short master)"
echo "    git log --oneline -5 master 可查看真实上游历史"
