#!/system/bin/sh
# 开机入口: 等系统 ready -> 应用配置 -> 起两个后台循环 (reapply / 微信前台同步)
until [ "$(getprop sys.boot_completed)" = "1" ]; do sleep 2; done
sleep 15

MODDIR=${0%/*}
DIR="$MODDIR/scripts"
. "$DIR/common.sh"
kalog "=== keepalive boot (v3) ==="

# 清理旧的 service.d 脉冲脚本, 避免和本模块双重怼前台
if [ -f /data/adb/service.d/wechat_keepalive_noscreen.sh ]; then
  mv /data/adb/service.d/wechat_keepalive_noscreen.sh \
     /data/adb/service.d/wechat_keepalive_noscreen.sh.disabled 2>/dev/null
  OLDPID=$(cat /data/local/tmp/wechat_keepalive.pid 2>/dev/null)
  [ -n "$OLDPID" ] && kill "$OLDPID" 2>/dev/null
  kalog "legacy service.d pulse disabled"
fi

# 首次应用
sh "$DIR/apply.sh"

# 单例: 杀掉上一次的循环, 防重复启动
if [ -f "$KA_DIR/loops.pid" ]; then
  for p in $(cat "$KA_DIR/loops.pid" 2>/dev/null); do kill "$p" 2>/dev/null; done
fi

# 循环1: 周期 reapply (每轮重读配置, WebUI 改了无需重启)
(
  while true; do
    . "$DIR/common.sh"
    sleep "${REAPPLY_SEC:-900}"
    sh "$DIR/apply.sh"
  done
) &
RPID=$!

# 循环2: 微信前台同步 (根因: 微信非前台约 2 分钟后挂起长连接)
# v3 改动: 不再因"充电"或"亮屏"无脑跳过(充电常亮≠微信在前台, 会漏消息);
#          统一判据=微信是否已在前台, 在前台才跳过, 否则一律切前台拉积压.
(
  while true; do
    . "$DIR/common.sh"
    if [ "$ENABLED" != "1" ] || [ "$WXSYNC_ENABLED" != "1" ]; then
      sleep 120; continue
    fi
    R=$(od -An -N2 -tu2 /dev/urandom 2>/dev/null | tr -d ' '); [ -z "$R" ] && R=0
    SPAN=$((WXSYNC_MAX - WXSYNC_MIN)); [ "$SPAN" -le 0 ] && SPAN=1
    sleep $((WXSYNC_MIN + R % SPAN))

    # 微信已在前台(在用 / 充电常亮已停在微信) -> 本就在收消息, 不打扰
    case "$(dumpsys activity activities 2>/dev/null | grep -m1 topResumedActivity)" in
      *com.tencent.mm/*) kalog "wx-sync skip (微信已前台)"; continue;;
    esac

    # 微信不在前台 -> 切前台拉积压. 记录原屏幕状态决定收尾方式
    WAS_AWAKE=0
    case "$(dumpsys power 2>/dev/null | grep -m1 mWakefulness=)" in
      *Awake*) WAS_AWAKE=1;;
    esac

    # 仅息屏场景压暗(避免黑屏闪白), 亮屏(含充电常亮)不动亮度
    DIMMED=0
    if [ "$WAS_AWAKE" = "0" ] && [ "$WXSYNC_DIM" -ge 0 ] 2>/dev/null; then
      OBR=$(settings get system screen_brightness 2>/dev/null)
      OBM=$(settings get system screen_brightness_mode 2>/dev/null)
      settings put system screen_brightness_mode 0 2>/dev/null
      settings put system screen_brightness "$WXSYNC_DIM" 2>/dev/null
      DIMMED=1
    fi

    [ "$WAS_AWAKE" = "0" ] && { input keyevent 224 2>/dev/null; sleep 1; }  # 息屏才唤醒
    am start -n "$WX_ACT" >/dev/null 2>&1              # 拉微信前台
    sleep "${WXSYNC_HOLD:-30}"                         # 停留拉积压

    # 收尾: 原本息屏的 -> 切回息屏并还原亮度; 原本亮屏(充电常亮)的 -> 保持微信前台不动
    if [ "$WAS_AWAKE" = "0" ]; then
      input keyevent 223 2>/dev/null                   # SLEEP
      if [ "$DIMMED" = "1" ]; then
        settings put system screen_brightness "$OBR" 2>/dev/null
        settings put system screen_brightness_mode "$OBM" 2>/dev/null
      fi
    fi
    kalog "wx-sync done (fg-switch, was_awake=$WAS_AWAKE, hold=${WXSYNC_HOLD}s)"
  done
) &
WPID=$!

echo "$RPID $WPID" > "$KA_DIR/loops.pid"
kalog "=== bootstrap done (loops: $RPID $WPID) ==="
