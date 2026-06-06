#!/usr/bin/env bash
# android-ops 环境自检 / bootstrap
# 任何机器上、每次开始工作先跑一遍: bash scripts/bootstrap.sh
# 只读诊断 + 缺失时按需 seed memory。不改设备、不部署。
set -u
cd "$(dirname "$0")/.." || exit 1
ROOT="$(pwd)"
ok(){ printf "  \033[32m✓\033[0m %s\n" "$1"; }
no(){ printf "  \033[31m✗\033[0m %s\n" "$1"; }
hd(){ printf "\n\033[1m%s\033[0m\n" "$1"; }

printf "\033[1m═══ android-ops 环境自检 ═══\033[0m\n"
echo "机器: $(hostname)   用户: $(whoami)   时间: $(date '+%F %T')"
echo "工作目录: $ROOT"

hd "工具链"
if command -v adb >/dev/null 2>&1; then ok "adb $(adb --version 2>/dev/null | sed -n '1s/Android Debug Bridge version //p')  ($(command -v adb))"; else no "adb 未安装 → brew install android-platform-tools"; fi
if command -v scrcpy >/dev/null 2>&1; then ok "scrcpy $(scrcpy --version 2>/dev/null | head -1 | awk '{print $2}')  (镜像手机屏幕: 直接运行 scrcpy)"; else no "scrcpy 未安装 → brew install scrcpy"; fi
command -v sqlite3 >/dev/null 2>&1 && ok "sqlite3 (本地分析设备拉回的 .db 用)" || no "sqlite3 未装(可选) → brew install sqlite"
command -v java >/dev/null 2>&1 && ok "java (BeanShell 语法预检 bsh-2.0b6 用)" || no "java 未装(可选, 部署前语法自检用) → brew install openjdk"

hd "设备连接 (Pixel 8a / akita)"
DEV="$(adb devices -l 2>/dev/null | awk 'NR>1 && $1!=""{print; n++} END{exit !n}')"
if [ -n "${DEV:-}" ]; then
  echo "$DEV" | sed 's/^/  /'
  if adb shell 'su -c id' 2>/dev/null | grep -q 'uid=0'; then ok "root (su) 可用"; else no "su 不可用 → 手机端确认 Magisk 授权"; fi
  WA="/data/media/0/Android/media/com.tencent.mm/WAuxiliary/Plugin"
  for P in GroupAdmin RedPacketStats; do
    V="$(adb shell "su -c 'head -1 \"$WA/$P/main.java\" 2>/dev/null'" 2>/dev/null | grep -oE 'v[0-9]+\.[0-9]+[a-z]?' | head -1)"
    [ -n "$V" ] && ok "设备 $P = $V" || no "设备 $P 未找到 main.java (插件可能未装/路径变化)"
  done
else
  no "无设备连接 → USB 插上手机 + 手机端允许 USB 调试; 或 adb connect <ip>:<port>"
fi

hd "仓库状态"
[ -f CLAUDE.md ] && ok "CLAUDE.md 在位 (项目说明+工作流)" || no "CLAUDE.md 缺失?!"
ok "本地仓库版本: GroupAdmin=$(grep -m1 version plugins/group-admin/info.prop 2>/dev/null | awk '{print $3}')  RedPacketStats=$(grep -m1 version plugins/redpacket-stats/info.prop 2>/dev/null | awk '{print $3}')"
if [ -d .git ]; then ok "git: $(git log --oneline -1 2>/dev/null)"; fi

hd "记忆 (跨机器上下文)"
SNAP="$ROOT/docs/memory-snapshot"
# Claude Code 把项目记忆放在 ~/.claude/projects/<cwd 转 - 的 slug>/memory/
SLUG="$(printf '%s' "$ROOT" | sed 's#[^a-zA-Z0-9]#-#g')"
MEMDIR="$HOME/.claude/projects/$SLUG/memory"
if [ -d "$SNAP" ]; then
  ok "仓库内记忆快照: docs/memory-snapshot/ ($(ls "$SNAP"/*.md 2>/dev/null | wc -l | tr -d ' ') 个)"
  if [ ! -f "$MEMDIR/MEMORY.md" ]; then
    mkdir -p "$MEMDIR" && cp "$SNAP"/*.md "$MEMDIR"/ 2>/dev/null && ok "本机 Claude 记忆为空 → 已从快照 seed 到 $MEMDIR"
  else
    ok "本机 Claude 记忆已存在: $MEMDIR"
  fi
else
  no "docs/memory-snapshot/ 缺失"
fi

hd "下一步"
echo "  • 让 Claude 读 docs/memory-snapshot/MEMORY.md 接上下文 (CLAUDE.md 已指示)"
echo "  • 看 docs/ONBOARDING.md 了解迁移/环境细节"
echo "  • 继续开发前: 改 main.java → adb 部署 → 真机验证 (见 CLAUDE.md 工作流)"
printf "\033[1m═══ 自检结束 ═══\033[0m\n"
