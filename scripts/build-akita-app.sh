#!/bin/sh
# build-akita-app.sh — 生成「akita 连接.app」(Mac 原生 .app, 双击即息屏投屏)
# 用法: bash scripts/build-akita-app.sh [输出目录]   (默认 ~/Desktop)
# 行为: 双击 app → 自动起 adb / 等设备授权 / scrcpy 息屏镜像(手机黑屏, 电脑窗口控制)。
#       出错(没装 adb/scrcpy、没连设备)弹 macOS 原生对话框提示, 不需要终端。

set -e
OUT_DIR="${1:-$HOME/Desktop}"
APP="$OUT_DIR/akita 连接.app"
MACOS="$APP/Contents/MacOS"
RES="$APP/Contents/Resources"

rm -rf "$APP"
mkdir -p "$MACOS" "$RES"

cat > "$APP/Contents/Info.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>CFBundleName</key>            <string>akita 连接</string>
  <key>CFBundleDisplayName</key>     <string>akita 连接</string>
  <key>CFBundleIdentifier</key>      <string>ops.androidops.akita-connect</string>
  <key>CFBundleVersion</key>         <string>1.0</string>
  <key>CFBundleShortVersionString</key><string>1.0</string>
  <key>CFBundlePackageType</key>     <string>APPL</string>
  <key>CFBundleExecutable</key>      <string>akita-connect</string>
  <key>LSMinimumSystemVersion</key>  <string>10.13</string>
  <key>NSHighResolutionCapable</key> <true/>
</dict>
</plist>
PLIST

cat > "$MACOS/akita-connect" <<'RUN'
#!/bin/sh
export PATH="/usr/local/bin:/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin:$PATH"
AKITA_SERIAL="44151JEKB08662"

err() {
  /usr/bin/osascript -e "display dialog \"$1\" with title \"akita 连接\" buttons {\"好\"} default button 1 with icon caution" >/dev/null 2>&1
  exit 1
}

command -v adb    >/dev/null 2>&1 || err "找不到 adb。请先在终端运行: brew install android-platform-tools"
command -v scrcpy >/dev/null 2>&1 || err "找不到 scrcpy。请先在终端运行: brew install scrcpy"

adb start-server >/dev/null 2>&1

SERIAL="$AKITA_SERIAL"
i=0
while [ $i -lt 8 ]; do
  [ "$(adb -s "$AKITA_SERIAL" get-state 2>/dev/null)" = "device" ] && { SERIAL="$AKITA_SERIAL"; break; }
  sleep 1; i=$((i+1))
done
if [ "$(adb -s "$SERIAL" get-state 2>/dev/null)" != "device" ]; then
  SERIAL=$(adb devices | awk '$2=="device"{print $1; exit}')
fi

if [ -z "$SERIAL" ] || [ "$(adb -s "$SERIAL" get-state 2>/dev/null)" != "device" ]; then
  err "没检测到已授权的设备。请确认: 1) USB 插上手机  2) 手机点「允许 USB 调试」  3) 直插机身、别走 Hub, 必要时换个数据口。"
fi

exec scrcpy -s "$SERIAL" \
  --turn-screen-off \
  --stay-awake \
  --power-off-on-close \
  --window-title "Pixel 8a (akita) — 息屏镜像"
RUN
chmod +x "$MACOS/akita-connect"

/usr/bin/xattr -dr com.apple.quarantine "$APP" 2>/dev/null || true

echo "OK 已生成: $APP"
