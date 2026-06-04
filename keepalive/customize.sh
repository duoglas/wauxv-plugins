#!/system/bin/sh
# 安装时执行: 种入默认配置(仅当不存在,保留用户已有配置) + 设权限
ui_print "- 初始化 WeChat Keepalive WebUI v2"

mkdir -p /data/adb/keepalive
if [ ! -f /data/adb/keepalive/config.conf ]; then
  cp "$MODPATH/config.default.conf" /data/adb/keepalive/config.conf
  ui_print "- 已写入默认配置 /data/adb/keepalive/config.conf"
else
  ui_print "- 检测到已有配置, 保留不覆盖"
fi

set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/service.sh"        0 0 0755
set_perm "$MODPATH/scripts/apply.sh"  0 0 0755
set_perm "$MODPATH/scripts/status.sh" 0 0 0755
set_perm "$MODPATH/scripts/common.sh" 0 0 0755

ui_print "- 用 MMRL 或 WebUI X 打开本模块即可进入配置界面"
