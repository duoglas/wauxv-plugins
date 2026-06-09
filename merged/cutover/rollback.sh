#!/system/bin/sh
# ============================================================
# rollback.sh —— 撤销 cutover：恢复旧 GroupAdmin + RedPacketStats，停用 GroupAdminPlus
#
# ★★ P2-b 产物：只设计不执行。配 cutover.sh 用。★★
#
# 在设备上以 root 运行：
#   adb push merged/cutover/rollback.sh /data/local/tmp/rollback.sh
#   adb shell "su -c 'ROLLBACK_CONFIRM=yes BACKUP_DIR=<cutover 打印的备份路径> sh /data/local/tmp/rollback.sh'"
#
# 恢复后旧插件读自己原地未动的 DB → 数据完好；仅丢 GroupAdminPlus 运行期间写入的增量（设计 C3 已知代价）。
# ============================================================
set -e

PLUGIN_BASE="/storage/emulated/0/Android/media/com.tencent.mm/WAuxiliary/Plugin"
GA="$PLUGIN_BASE/GroupAdmin"
RP="$PLUGIN_BASE/RedPacketStats"
GAP="$PLUGIN_BASE/GroupAdminPlus"

log() { echo "[rollback] $1"; }
die() { echo "[rollback][ABORT] $1" >&2; exit 1; }

# ---- 0) 硬确认 + 入参校验 ----
[ "$ROLLBACK_CONFIRM" = "yes" ] || die "未确认。必须 ROLLBACK_CONFIRM=yes 才执行。"
[ -n "$BACKUP_DIR" ]            || die "缺 BACKUP_DIR=<cutover.sh 打印的备份路径>。"
[ -d "$BACKUP_DIR" ]           || die "BACKUP_DIR 不存在：$BACKUP_DIR"
[ -d "$BACKUP_DIR/GroupAdmin.disabled" ]      || die "备份里缺 GroupAdmin.disabled，路径可能错。"
[ -d "$BACKUP_DIR/RedPacketStats.disabled" ]  || die "备份里缺 RedPacketStats.disabled，路径可能错。"

# ---- 1) 停用 GroupAdminPlus（移出 Plugin/，不删，留查证）----
if [ -d "$GAP" ]; then
  log "停用 GroupAdminPlus（移出到 $BACKUP_DIR/GroupAdminPlus.rolledback）…"
  rm -rf "$BACKUP_DIR/GroupAdminPlus.rolledback"
  mv "$GAP" "$BACKUP_DIR/GroupAdminPlus.rolledback"
fi

# ---- 2) 恢复旧插件目录回 Plugin/ ----
[ -d "$GA" ] && die "目标 $GA 已存在，拒绝覆盖。请人工核对后再恢复。"
[ -d "$RP" ] && die "目标 $RP 已存在，拒绝覆盖。请人工核对后再恢复。"
log "恢复 GroupAdmin / RedPacketStats 回 Plugin/ …"
mv "$BACKUP_DIR/GroupAdmin.disabled"     "$GA"
mv "$BACKUP_DIR/RedPacketStats.disabled" "$RP"

# ---- 3) 完成 ----
log "================ ROLLBACK 完成 ================"
log "旧 GroupAdmin / RedPacketStats 已恢复（读各自原地 DB，数据完好）。"
log "下一步：重启微信使加载生效： am force-stop com.tencent.mm  然后拉起。"
log "GroupAdminPlus 已移到 $BACKUP_DIR/GroupAdminPlus.rolledback（留查证，确认无误后人工删）。"
