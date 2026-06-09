#!/system/bin/sh
# ============================================================
# cutover.sh —— GroupAdmin + RedPacketStats → GroupAdminPlus 最终切换
#
# ★★ P2-b 产物：只设计不执行。禁止在 P3 完成前运行。★★
# 真机不可逆：合并 config / 拷贝 DB / 移走旧插件目录。先备份、可回滚（rollback.sh）。
#
# 在设备上以 root 运行：
#   adb push merged/cutover/cutover.sh /data/local/tmp/cutover.sh
#   adb shell "su -c 'CUTOVER_CONFIRM=yes sh /data/local/tmp/cutover.sh'"
#
# 前置（脚本会校验，缺则中止）：
#   - GroupAdminPlus/main.java 已推送（merged/out/main.java，由 host 先 push）
#   - 旧 GroupAdmin/ 与 RedPacketStats/ 目录存在
#   - CUTOVER_CONFIRM=yes（硬闸门，防误跑）
# ============================================================
set -e

PLUGIN_BASE="/storage/emulated/0/Android/media/com.tencent.mm/WAuxiliary/Plugin"
GA="$PLUGIN_BASE/GroupAdmin"
RP="$PLUGIN_BASE/RedPacketStats"
GAP="$PLUGIN_BASE/GroupAdminPlus"

# 备份区：放在 Plugin 同级（不在 Plugin/ 内，避免被 WAuxiliary 当插件扫描加载）
TS="$(date +%Y%m%d-%H%M%S)"
BACKUP_DIR="$PLUGIN_BASE/../Plugin.cutover-backup-$TS"

log() { echo "[cutover] $1"; }
die() { echo "[cutover][ABORT] $1" >&2; exit 1; }

# ---- 0) 硬确认闸门 ----
[ "$CUTOVER_CONFIRM" = "yes" ] || die "未确认。必须 CUTOVER_CONFIRM=yes 才执行（这是真机不可逆切换）。"

# ---- 1) 前置校验（任何一条不满足都不动手）----
log "校验前置条件…"
[ -f "$GAP/main.java" ] || die "缺 $GAP/main.java —— 先 build.sh + adb push 合并产物到 GroupAdminPlus/。"
[ -d "$GA" ]            || die "缺旧插件目录 $GA。"
[ -d "$RP" ]            || die "缺旧插件目录 $RP。"
[ -f "$GA/config.prop" ] || die "缺 $GA/config.prop。"
[ -f "$RP/config.prop" ] || die "缺 $RP/config.prop。"
# 防重复执行：GroupAdminPlus 已合过 config 就别再来一遍
if [ -f "$GAP/config.prop" ] && grep -q '^rp_' "$GAP/config.prop" 2>/dev/null; then
  die "$GAP/config.prop 里已有 rp_ 键，疑似已 cutover。如需重来请先 rollback.sh。"
fi

# ---- 2) 备份（回滚锚点）----
log "备份到 $BACKUP_DIR …"
mkdir -p "$BACKUP_DIR"
cp -a "$GA" "$BACKUP_DIR/GroupAdmin"
cp -a "$RP" "$BACKUP_DIR/RedPacketStats"
[ -d "$GAP" ] && cp -a "$GAP" "$BACKUP_DIR/GroupAdminPlus" || true
echo "$TS" > "$BACKUP_DIR/CUTOVER_TS"
log "备份完成。回滚用 BACKUP_DIR=$BACKUP_DIR"

# ---- 3) config union：GA 全量 + RP 的 rp_ 键（零冲突，等价 union，设计 C2）----
log "合并 config（GA 全量 + RP rp_ 键）→ $GAP/config.prop …"
cp -a "$GA/config.prop" "$GAP/config.prop"
# RP 键全部 rp_ 前缀（设计 §6 已核实），只取 rp_ 行追加；幂等：上面已挡重复执行
grep '^rp_' "$RP/config.prop" >> "$GAP/config.prop" || true
RP_KEYS="$(grep -c '^rp_' "$RP/config.prop" 2>/dev/null || echo 0)"
GAP_RP_KEYS="$(grep -c '^rp_' "$GAP/config.prop" 2>/dev/null || echo 0)"
[ "$RP_KEYS" = "$GAP_RP_KEYS" ] || die "config union 校验失败：RP rp_ 键 $RP_KEYS != 合并后 $GAP_RP_KEYS。已备份，未停旧插件，安全。"
log "config union OK（rp_ 键 $GAP_RP_KEYS 个）。"

# ---- 4) DB：拷贝（非移动）两旧库进 GroupAdminPlus/（数据原地不动，设计 C3）----
log "拷贝 DB 进 $GAP/ …"
[ -f "$GA/groupadmin.db" ]      && cp -a "$GA/groupadmin.db"      "$GAP/groupadmin.db"      || log "（无 groupadmin.db，跳过）"
[ -f "$RP/redpacket_stats.db" ] && cp -a "$RP/redpacket_stats.db" "$GAP/redpacket_stats.db" || log "（无 redpacket_stats.db，跳过）"
# 一并带上 WAL/SHM（若开了 WAL，缺了会丢未 checkpoint 的写）
for ext in -wal -shm; do
  [ -f "$GA/groupadmin.db$ext" ]      && cp -a "$GA/groupadmin.db$ext"      "$GAP/groupadmin.db$ext"      || true
  [ -f "$RP/redpacket_stats.db$ext" ] && cp -a "$RP/redpacket_stats.db$ext" "$GAP/redpacket_stats.db$ext" || true
done
log "DB 拷贝完成（旧库原地保留，未动）。"

# ---- 5) 停旧插件：整目录移出 Plugin/（两个都停，设计 C4）----
log "停用旧插件（移出 Plugin/ 到备份区）…"
mv "$GA" "$BACKUP_DIR/GroupAdmin.disabled"
mv "$RP" "$BACKUP_DIR/RedPacketStats.disabled"
log "旧 GroupAdmin / RedPacketStats 已移出，WAuxiliary 不再加载。"

# ---- 6) 完成 ----
log "================ CUTOVER 完成 ================"
log "下一步（手动）："
log "  1) 重启微信使加载生效： am force-stop com.tencent.mm  然后拉起"
log "  2) 按 README §4 真机验收（加载/群管/红包/伸手党/热路径/反检测）"
log "  3) 验收不过 → rollback.sh，BACKUP_DIR=$BACKUP_DIR"
log "备份路径（回滚要用，记下来）：$BACKUP_DIR"
