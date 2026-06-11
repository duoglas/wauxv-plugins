#!/usr/bin/env bash
# 24 小时稳定性/性能监测驱动器：每小时跑一次 stability-cycle.sh，自动判定 + 落盘报告。
# 设计依据 CLAUDE.md「长跑守护任务用后台脚本+日志承载，不靠对话 session 一直开着」。
# 用法: stability-24h.sh [小时数=24] [间隔秒=3600]
#   后台跑: nohup setsid bash scripts/monitor/stability-24h.sh >/dev/null 2>&1 &
# 报告: scripts/monitor/reports/stability-<启动时间戳>.log（每小时追加一块）
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
HOURS="${1:-24}"
GAP="${2:-3600}"
STAMP="$(date '+%Y%m%d-%H%M%S')"
OUT="$HERE/reports/stability-$STAMP.log"
STATE="$HERE/.state-$STAMP"
mkdir -p "$HERE/reports" "$STATE"

{
  echo "════════════════════════════════════════════════════════"
  echo "GroupAdminPlus 24h 稳定性/性能监测  启动 $(date '+%Y-%m-%d %H:%M:%S')"
  echo "设备 44151JEKB08662 / 每小时 1 次 / 共 ${HOURS} 次 / 间隔 ${GAP}s"
  echo "监测: 微信存活 · onHandleMsg 热路径(norm_avg/max, C-PERF-01) · spike · plugin.log异常增量 · 伸手党summary"
  echo "判定: OK=正常 / WARN=需关注(性能回归或新异常) / ALERT=进程死亡"
  echo "════════════════════════════════════════════════════════"
  echo ""
} >> "$OUT"

i=0
while [ "$i" -lt "$HOURS" ]; do
  i=$((i + 1))
  echo "──── 第 $i/$HOURS 次  $(date '+%Y-%m-%d %H:%M:%S') ────" >> "$OUT"
  bash "$HERE/stability-cycle.sh" "$STATE" >> "$OUT" 2>&1
  # 末次不再 sleep
  [ "$i" -lt "$HOURS" ] && sleep "$GAP"
done

{
  echo "════════════════════════════════════════════════════════"
  echo "监测结束 $(date '+%Y-%m-%d %H:%M:%S')  共 ${i} 次采样"
  # 末尾汇总: 各 verdict 计数
  echo -n "汇总: "
  grep -o 'verdict=[A-Z]*' "$OUT" | sort | uniq -c | tr '\n' ' '
  echo ""
  echo "════════════════════════════════════════════════════════"
} >> "$OUT"
