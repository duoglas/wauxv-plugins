#!/system/bin/sh
# 输出当前实时状态 (key=val 每行一条), 供 WebUI 解析展示
# 注意: 每个 dumpsys 只调用一次缓存复用, 保证快速返回 (WebUI exec 有超时)
DIR=$(dirname "$0")
. "$DIR/common.sh"

# 把所有输出写进文件 (WebUI 随后用裸 cat 读, 捕获更稳); 同时 tee 不可靠故只写文件
exec > "$KA_DIR/status.txt" 2>/dev/null

echo "enabled=$ENABLED"
echo "stay_on=$(settings get global stay_on_while_plugged_in 2>/dev/null)"
echo "app_standby=$(settings get global app_standby_enabled 2>/dev/null)"
echo "adaptive=$(settings get global adaptive_battery_management_enabled 2>/dev/null)"
echo "low_power=$(settings get global low_power 2>/dev/null)"

# deviceidle 一次抓全
DI=$(dumpsys deviceidle 2>/dev/null)
echo "deep_doze_enabled=$(echo "$DI" | grep -o 'mDeepEnabled=[a-z]*' | head -1 | sed 's/.*=//')"
echo "light_doze_enabled=$(echo "$DI" | grep -o 'mLightEnabled=[a-z]*' | head -1 | sed 's/.*=//')"
echo "doze_state=$(echo "$DI" | grep -o 'mState=[A-Z_]*' | head -1 | sed 's/.*=//')"
echo "screen=$(echo "$DI" | grep -o 'mScreenOn=[a-z]*' | head -1 | sed 's/.*=//')"

# battery 一次抓
BAT=$(dumpsys battery 2>/dev/null)
echo "ac_powered=$(echo "$BAT" | grep -m1 'AC powered' | sed 's/.*: //')"
echo "battery_level=$(echo "$BAT" | grep -m1 '  level' | sed 's/.*: //')"

# whitelist 单独子命令
echo "wx_whitelisted=$(dumpsys deviceidle whitelist 2>/dev/null | grep -c "$WX_PKG")"
echo "wx_bucket=$(am get-standby-bucket "$WX_PKG" 2>/dev/null)"
echo "wx_main_pid=$(pidof "$WX_PKG" 2>/dev/null)"
echo "wx_push_pid=$(pidof "$WX_PKG:push" 2>/dev/null)"

# 保活循环存活
ALIVE=0
if [ -f "$KA_DIR/loops.pid" ]; then
  for p in $(cat "$KA_DIR/loops.pid" 2>/dev/null); do
    kill -0 "$p" 2>/dev/null && ALIVE=$((ALIVE+1))
  done
fi
echo "loops_alive=$ALIVE"
echo "last_log=$(tail -1 "$LOG" 2>/dev/null)"
