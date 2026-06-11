#!/usr/bin/env bash
# 单次稳定性/性能采样 + 自动判定，打印一个报告块到 stdout（驱动器负责落盘）。
# 全程 read-only adb 诊断：只把日志 cat 回本机，所有 grep/解析在 Mac 本地做
#   （避开 `su -c '...'` 外层单引号与 grep 模式内引号/管道符冲突的坑）。
# 监测对象: GroupAdminPlus 合并插件（UI 重建版 2026-06-11 部署）。
#   · 微信进程存活
#   · onHandleMsg 热路径性能 norm_avg/norm_max（C-PERF-01: 普通消息应保持廉价；基线 ~22-26ms）
#   · ohm_max / spike（红包重活在 worker，偶发 spike 正常）
#   · plugin.log 异常增量（Exception/NoSuchMethod/StackOverflow/[E]/[W] 级别）
#   · rp.log 伸手党 summary 增量（功能是否在跑）
# 用法: stability-cycle.sh <state-dir>
set -u
STATE="${1:?usage: stability-cycle.sh <state-dir>}"
mkdir -p "$STATE"
DIR="/data/media/0/Android/media/com.tencent.mm/WAuxiliary/Plugin/GroupAdminPlus"
SERIAL="44151JEKB08662"
TS="$(date '+%Y-%m-%d %H:%M:%S')"

# 单参、双引号、无内层单引号 → 引号安全。$DIR 无空格在 Mac 端先展开。
su_get() { adb -s "$SERIAL" shell su -c "$1" 2>/dev/null | tr -d '\r'; }
num() { case "${1:-}" in ''|*[!0-9]*) echo 0 ;; *) echo "$1" ;; esac; }

# ---- 采集（一次性 cat 回本机）----
PID="$(su_get "pgrep -f com.tencent.mm" | head -1)"
PERF_LINE="$(su_get "cat $DIR/perf.log" | tail -1)"
PLUGIN_LOG="$(su_get "cat $DIR/plugin.log")"
RP_LOG="$(su_get "cat $DIR/rp.log")"

# ---- 1) 微信进程 ----
if [ -n "$PID" ]; then PROC="alive(pid=$PID)"; PROC_OK=1; else PROC="DEAD"; PROC_OK=0; fi

# ---- 2) perf.log 热路径埋点（本地解析）----
g() { echo "$PERF_LINE" | grep -o "$1=[0-9]*" | cut -d= -f2 | head -1; }
PERF_ISO="$(echo "$PERF_LINE" | sed -n 's/.*iso=\([0-9: -]*\) n=.*/\1/p')"
N_MSG="$(num "$(g n)")"
OHM_AVG="$(num "$(g ohm_avg_us)")"
OHM_MAX="$(num "$(g ohm_max_us)")"
NORM_AVG="$(num "$(g norm_avg_us)")"
NORM_MAX="$(num "$(g norm_max_us)")"
SPIKE_N="$(num "$(g spike_n)")"
SPIKE_NORM="$(num "$(g spike_norm)")"

# 基线锚点（首个非零 norm_avg）
BASE=0; [ -f "$STATE/norm_base" ] && BASE="$(num "$(cat "$STATE/norm_base")")"
if [ "$BASE" -eq 0 ] && [ "$NORM_AVG" -gt 0 ]; then echo "$NORM_AVG" > "$STATE/norm_base"; BASE="$NORM_AVG"; fi

# perf.log 是否推进
PREV_ISO=""; [ -f "$STATE/perf_iso" ] && PREV_ISO="$(cat "$STATE/perf_iso")"
echo "$PERF_ISO" > "$STATE/perf_iso"
if [ -n "$PERF_ISO" ] && [ "$PERF_ISO" = "$PREV_ISO" ]; then PERF_ADV="未推进(低流量或worker停摆?)"; else PERF_ADV="推进"; fi

# ---- 3) plugin.log 异常累计 + 增量（本地 grep，引号安全）----
EXC_NOW="$(num "$(echo "$PLUGIN_LOG" | grep -cE 'Exception|NoSuchMethod|StackOverflow|Throwable|Caused by|\]\[E\]|\]\[W\]')")"
EXC_PREV=0; [ -f "$STATE/plugin_exc" ] && EXC_PREV="$(num "$(cat "$STATE/plugin_exc")")"
echo "$EXC_NOW" > "$STATE/plugin_exc"
EXC_DELTA=$((EXC_NOW - EXC_PREV))
LAST_EXC="$(echo "$PLUGIN_LOG" | grep -E 'Exception|NoSuchMethod|StackOverflow|Throwable|Caused by' | tail -2)"

# ---- 4) rp.log 伸手党 summary 累计 + 增量 ----
RP_NOW="$(num "$(echo "$RP_LOG" | grep -c 'freeloader judged')")"
RP_PREV=0; [ -f "$STATE/rp_free" ] && RP_PREV="$(num "$(cat "$STATE/rp_free")")"
echo "$RP_NOW" > "$STATE/rp_free"
RP_DELTA=$((RP_NOW - RP_PREV))
LAST_FREE="$(echo "$RP_LOG" | grep 'freeloader judged' | tail -1)"

# ---- 5) 自动判定 ----
VERDICT="OK"; REASONS=""
add() { REASONS="${REASONS}${REASONS:+; }$1"; }
escalate() { case "$VERDICT/$1" in OK/WARN|OK/ALERT|WARN/ALERT) VERDICT="$1" ;; esac; }
[ "$PROC_OK" -eq 0 ] && { add "微信进程死亡(keepalive 应拉起，连续死=异常)"; escalate ALERT; }
if [ "$NORM_AVG" -gt 45000 ]; then add "norm_avg=${NORM_AVG}us 绝对超阈(>45ms)"; escalate WARN; fi
if [ "$BASE" -gt 0 ] && [ "$NORM_AVG" -gt $((BASE * 2)) ] && [ "$NORM_AVG" -gt 40000 ]; then add "norm_avg=${NORM_AVG}us 超基线2倍(base=${BASE}us)"; escalate WARN; fi
[ "$EXC_DELTA" -gt 0 ] && { add "plugin.log 新增 ${EXC_DELTA} 条异常/警告"; escalate WARN; }

# ---- 6) 输出报告块 ----
echo "[$TS] verdict=$VERDICT"
echo "  进程:     $PROC"
echo "  热路径:   norm_avg=${NORM_AVG}us norm_max=${NORM_MAX}us ohm_avg=${OHM_AVG}us ohm_max=${OHM_MAX}us (样本 n=${N_MSG}, base=${BASE}us)"
echo "  spike:    spike_n=${SPIKE_N} spike_norm=${SPIKE_NORM}  perf.log=${PERF_ADV} (flush@${PERF_ISO:-?})"
echo "  异常:     累计 ${EXC_NOW} 增量 ${EXC_DELTA}"
[ "$EXC_DELTA" -gt 0 ] && [ -n "$LAST_EXC" ] && echo "    最近: $(echo "$LAST_EXC" | tr '\n' '|')"
echo "  伸手党:   summary 累计 ${RP_NOW} 增量 ${RP_DELTA}"
[ -n "$LAST_FREE" ] && echo "    最近: $LAST_FREE"
[ -n "$REASONS" ] && echo "  ⚠ 原因:   $REASONS"
echo ""
