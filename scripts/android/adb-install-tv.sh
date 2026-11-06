#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# adb-install-tv.sh — 向多台 TV 盒子/投影批量安装 APK（sideload 分发场景）
#
# 用法：
#   bash scripts/android/adb-install-tv.sh --apk dist/universal.apk \
#     --hosts "192.168.1.20:5555 192.168.1.21:5555"
#
#   bash scripts/android/adb-install-tv.sh --apk dist/universal.apk --hosts-file hosts.txt
#
#   # 降级安装（versionCode 比设备上的小，仅 sideload 可用；Play 渠道做不到）
#   bash scripts/android/adb-install-tv.sh --apk old.apk --hosts-file hosts.txt --downgrade
#
# 参数：
#   --apk <path>          必填，要安装的 APK
#   --hosts "<a b c>"     空格分隔的 host:port 列表
#   --hosts-file <path>   每行一个 host:port，支持 # 注释
#   --downgrade           传 adb install -d（允许 versionCode 回退）
#   --connect-timeout <s> adb connect 超时，默认 10 秒（macOS 上 adb 无此参数时自动忽略）
#
# 行为：
#   - 单台设备失败不中断其余设备，最后汇总成功/失败清单
#   - 有任何一台失败则脚本以非 0 退出（CI 里能看出来）
#
# 前置：TV 盒子需开启「开发者选项 → USB/网络调试」，并与本机在同一局域网。
# ---------------------------------------------------------------------------
set -euo pipefail

log() { echo "[adb-install-tv] $*"; }
err() { echo "[adb-install-tv] 错误：$*" >&2; }

APK=""
HOSTS=""
HOSTS_FILE=""
DOWNGRADE="false"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --apk)        APK="${2:-}"; shift 2 ;;
    --hosts)      HOSTS="${2:-}"; shift 2 ;;
    --hosts-file) HOSTS_FILE="${2:-}"; shift 2 ;;
    --downgrade)  DOWNGRADE="true"; shift ;;
    -h|--help)    sed -n '2,28p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) err "未知参数：$1"; exit 2 ;;
  esac
done

[[ -n "${APK}" ]] || { err "缺少 --apk"; exit 2; }
[[ -f "${APK}" ]] || { err "APK 不存在：${APK}"; exit 2; }

command -v adb >/dev/null 2>&1 || { err "找不到 adb，请安装 platform-tools 并加入 PATH"; exit 2; }

# 合并 host 列表
if [[ -n "${HOSTS_FILE}" ]]; then
  [[ -f "${HOSTS_FILE}" ]] || { err "hosts 文件不存在：${HOSTS_FILE}"; exit 2; }
  HOSTS="${HOSTS} $(grep -vE '^\s*(#|$)' "${HOSTS_FILE}" | tr '\n' ' ')"
fi

# 去重 + 去空
HOSTS="$(printf '%s\n' ${HOSTS} | awk 'NF && !seen[$0]++')"
[[ -n "${HOSTS}" ]] || { err "没有可用的 host，请用 --hosts 或 --hosts-file 指定"; exit 2; }

INSTALL_FLAGS="-r"
if [[ "${DOWNGRADE}" == "true" ]]; then
  INSTALL_FLAGS="-r -d"
  log "已启用降级安装（-d）：仅适用于 sideload，Play 渠道的 versionCode 不可回退"
fi

OK_LIST=""
FAIL_LIST=""

for host in ${HOSTS}; do
  log "---- ${host} ----"

  # adb connect 对已在线的设备是幂等的；超时用 timeout 命令兜底
  if command -v timeout >/dev/null 2>&1; then
    timeout 20 adb connect "${host}" >/dev/null 2>&1 || true
  else
    adb connect "${host}" >/dev/null 2>&1 || true
  fi

  if ! adb -s "${host}" get-state >/dev/null 2>&1; then
    err "${host} 无法连接（检查 TV 是否开启网络调试、同网段、端口 5555）"
    FAIL_LIST="${FAIL_LIST} ${host}(connect)"
    continue
  fi

  if adb -s "${host}" install ${INSTALL_FLAGS} "${APK}" 2>&1 | tail -n 3; then
    # adb install 在失败时也可能返回 0，需要看输出里的 Success / Failure
    if adb -s "${host}" shell pm list packages 2>/dev/null | grep -q 'com.doubao.murmur.tv'; then
      log "${host} 安装成功"
      OK_LIST="${OK_LIST} ${host}"
    else
      err "${host} 安装后未发现 com.doubao.murmur.tv"
      FAIL_LIST="${FAIL_LIST} ${host}(verify)"
    fi
  else
    err "${host} 安装失败"
    FAIL_LIST="${FAIL_LIST} ${host}(install)"
  fi
done

echo
log "================ 汇总 ================"
log "成功 ${OK_LIST:-<无>}"
log "失败 ${FAIL_LIST:-<无>}"

if [[ -n "${FAIL_LIST// /}" ]]; then
  exit 1
fi

log "全部设备安装完成。首次使用需在 TV 设置里启用本输入法："
log "  设置 → 系统 → 语言和输入法 → 当前输入法 → 豆包语音输入法（TV）"
