#!/bin/sh
# akita-connect.command — 双击即用: 连接 Pixel 8a (akita) 并息屏投屏
#   -S  手机自身屏幕黑掉 (电脑窗口照常实时控制, 省电+防窥)
#   -w  插着 USB 时不让设备休眠
#   --power-off-on-close  关闭投屏窗口时顺手把手机熄屏
# 关闭投屏: 直接关窗口或在窗口里按 Ctrl/Cmd 组合; 手机屏幕按一下电源键即可重新亮起.

# Finder 双击启动时 PATH 很精简, 手动补上 brew 的 bin
export PATH="/usr/local/bin:/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin:$PATH"

AKITA_SERIAL="44151JEKB08662"

echo "=== akita 连接器 ==="

# 1) 工具自检
if ! command -v adb >/dev/null 2>&1; then
  echo "✗ 找不到 adb。请先: brew install android-platform-tools"
  echo "按回车关闭"; read _; exit 1
fi
if ! command -v scrcpy >/dev/null 2>&1; then
  echo "✗ 找不到 scrcpy。请先: brew install scrcpy"
  echo "按回车关闭"; read _; exit 1
fi

# 2) 启动 adb server + 等设备
adb start-server >/dev/null 2>&1

# 优先用 akita 序列号; 不在就退而求其次用任意已连设备
SERIAL="$AKITA_SERIAL"
state=$(adb -s "$SERIAL" get-state 2>/dev/null)
if [ "$state" != "device" ]; then
  echo "akita ($AKITA_SERIAL) 未就绪 (当前: ${state:-未连接})，等待 USB 连接 + 授权…"
  echo "  • 手机用 USB 插上这台 Mac"
  echo "  • 手机弹「允许 USB 调试」点允许 (勾选一律允许)"
  # 等任意设备最多 ~30s
  adb wait-for-device 2>/dev/null
  # 重新解析: akita 在就用 akita, 否则取第一台 device
  if [ "$(adb -s "$AKITA_SERIAL" get-state 2>/dev/null)" = "device" ]; then
    SERIAL="$AKITA_SERIAL"
  else
    SERIAL=$(adb devices | awk '$2=="device"{print $1; exit}')
  fi
fi

if [ -z "$SERIAL" ] || [ "$(adb -s "$SERIAL" get-state 2>/dev/null)" != "device" ]; then
  echo "✗ 仍无已授权设备。检查: USB 插好 / 手机点了允许调试 / 换个数据口。"
  echo "按回车关闭"; read _; exit 1
fi

MODEL=$(adb -s "$SERIAL" shell getprop ro.product.model 2>/dev/null | tr -d '\r')
echo "✓ 已连接: ${MODEL:-device} ($SERIAL)"
echo "→ 启动息屏投屏 (手机会黑屏, 电脑窗口实时控制)…"

# 3) 息屏投屏
exec scrcpy -s "$SERIAL" \
  --turn-screen-off \
  --stay-awake \
  --power-off-on-close \
  --window-title "Pixel 8a (akita) — 息屏镜像"
