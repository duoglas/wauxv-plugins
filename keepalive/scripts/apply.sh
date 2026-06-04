#!/system/bin/sh
# 一次性应用配置 (开机 / 周期 reapply / WebUI 点"立即应用" 都调它)
DIR=$(dirname "$0")
. "$DIR/common.sh"

if [ "$ENABLED" != "1" ]; then
  kalog "apply skipped (ENABLED=0)"
  exit 0
fi

# 1. 关自适应电池 / app standby 学习 / 省流
if [ "$KILL_ADAPTIVE" = "1" ]; then
  settings put global app_standby_enabled 0 2>/dev/null
  settings put global adaptive_battery_management_enabled 0 2>/dev/null
  settings put global data_saver_mode 0 2>/dev/null
fi

# 2. 充电常亮 (7 = AC+USB+Wireless 全开)
if [ "$STAY_ON_CHARGING" = "1" ]; then
  settings put global stay_on_while_plugged_in 7 2>/dev/null
else
  settings put global stay_on_while_plugged_in 0 2>/dev/null
fi

# 3. 白名单: 提桶 + Doze 白名单 + 后台运行 appops
for PKG in $WHITELIST; do
  am set-standby-bucket "$PKG" active 2>/dev/null
  dumpsys deviceidle whitelist +"$PKG" >/dev/null 2>&1
  cmd appops set "$PKG" RUN_IN_BACKGROUND allow 2>/dev/null
  cmd appops set "$PKG" RUN_ANY_IN_BACKGROUND allow 2>/dev/null
done

# 4. 关后台数据限制
cmd netpolicy set restrict-background false >/dev/null 2>&1

# 5. Doze 开关
[ "$DISABLE_DEEP_DOZE" = "1" ]  && dumpsys deviceidle disable deep  >/dev/null 2>&1
[ "$DISABLE_LIGHT_DOZE" = "1" ] && dumpsys deviceidle disable light >/dev/null 2>&1

kalog "apply done (whitelist=$(echo $WHITELIST | wc -w) pkgs, stay_on=$STAY_ON_CHARGING, deep_off=$DISABLE_DEEP_DOZE, light_off=$DISABLE_LIGHT_DOZE)"
