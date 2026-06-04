#!/system/bin/sh
# 公共: 加载配置 + 默认值 + 日志函数。被 service/apply/status 共同 source。
# 显式补全 PATH (WebUI X 的 exec 环境可能缺少这些目录, 导致 settings/dumpsys/am 找不到)
export PATH=/system/bin:/system/xbin:/vendor/bin:/odm/bin:$PATH
KA_DIR=/data/adb/keepalive
CONF="$KA_DIR/config.conf"
LOG="$KA_DIR/keepalive.log"
mkdir -p "$KA_DIR" 2>/dev/null

# ---- 默认值 (config.conf 缺失或缺项时兜底) ----
ENABLED=1
WHITELIST="com.tencent.mm me.hd.wauxv com.tsng.hidemyapplist com.github.metacubex.clash.meta org.lsposed.manager com.topjohnwu.magisk com.wengui.hook com.tencent.mobileqq lin.xposed"
KILL_ADAPTIVE=1
DISABLE_DEEP_DOZE=1
DISABLE_LIGHT_DOZE=0
STAY_ON_CHARGING=1
REAPPLY_SEC=900
WXSYNC_ENABLED=1
WXSYNC_MIN=300
WXSYNC_MAX=600
WXSYNC_HOLD=30
WXSYNC_ONLY_SCREENOFF=1
WXSYNC_DIM=4
WX_PKG=com.tencent.mm
WX_ACT=com.tencent.mm/.ui.LauncherUI

# 加载用户配置 (覆盖默认)
[ -f "$CONF" ] && . "$CONF"

kalog() {
  echo "[$(date '+%F %T')] $*" >> "$LOG"
  if [ "$(wc -c < "$LOG" 2>/dev/null || echo 0)" -gt 200000 ]; then
    tail -300 "$LOG" > "$LOG.tmp" 2>/dev/null && mv "$LOG.tmp" "$LOG"
  fi
}
