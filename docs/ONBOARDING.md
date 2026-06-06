# ONBOARDING — 换机器/新环境上手

本仓库随 **Dropbox** 在多台 Mac 间同步。目标：到任何一台机器，打开这个目录就能自动识别环境、接上之前的工作、继续开发。

## 0. 一句话上手

```bash
cd ~/Dropbox/<你的>-work/android-ops      # 进工作目录
bash scripts/bootstrap.sh                  # 环境自检 + 自动 seed 记忆
```

然后在这个目录打开 Claude Code —— 它会自动读 `CLAUDE.md`，并按其指示读 `docs/memory-snapshot/MEMORY.md` 接上下文。

## 1. 这是什么项目

通过 `adb` 管理一台 **Pixel 8a (akita / Android 16 / Magisk root)** 上的 WAuxiliary 微信增强插件（BeanShell `main.java`）、keepalive 保活模块、设备隐藏与网络配置。**没有编译链**，全程 adb 连真机开发+运维。详见 `CLAUDE.md`。

当前插件版本：**GroupAdmin v1.16.2**、**RedPacketStats v1.6.4**（以 `plugins/*/info.prop` 为准）。

## 2. 新机器需要装的工具（一次性）

```bash
brew install android-platform-tools   # adb
brew install scrcpy                    # 镜像/操作手机屏幕(你惯用的那个)
brew install sqlite                    # 本地分析从设备拉回的 .db (可选)
brew install openjdk                   # 部署前 BeanShell 语法自检 bsh-2.0b6 (可选)
```

`bash scripts/bootstrap.sh` 会逐项检查并提示缺什么。

## 3. 连接手机

- **USB**：插上 → 手机端允许 USB 调试 → `adb devices -l` 应看到 `44151JEKB08662 ... model:Pixel_8a`。
- **无线**（可选）：`adb tcpip 5555` 后 `adb connect <手机IP>:5555`。
- **看屏幕/操作**：直接运行 `scrcpy`（你惯用的镜像方式）。
- **root 校验**：`adb shell "su -c id"` 应含 `uid=0`；不行就到手机 Magisk 里确认 shell 授权。

## 4. 关键设备路径（速查）

```
插件目录:   /data/media/0/Android/media/com.tencent.mm/WAuxiliary/Plugin/<插件名>/main.java
插件日志:   同目录 plugin.log / rp.log
性能埋点:   同目录 perf.log
统计 DB:    同目录 *.db (groupadmin.db / redpacket_stats.db)  ← 含真实 wxid, 不入版本
LSPosed日志: adb shell "su -c 'cat /data/adb/lspd/log/verbose_*.log | tail'"
```

部署插件：`adb push <本地>/main.java /data/local/tmp/x.java` → `adb shell "su -c 'cp /data/local/tmp/x.java <设备插件目录>/main.java'"` → `adb shell "su -c 'am force-stop com.tencent.mm'"`（keepalive 拉起）。**GroupAdmin 重启即加载；RedPacketStats 懒加载（要红包活动才 eval）**。

## 5. 跨机器的"记忆"如何工作（重要）

Claude Code 的项目记忆默认存在 `~/.claude/projects/<目录slug>/memory/`，**不在工作目录、不随 Dropbox 同步**。为保证换机器能接上：

- **仓库内有快照**：`docs/memory-snapshot/`（随 Dropbox 走，是跨机器的权威副本）。
- `scripts/bootstrap.sh` 在本机记忆为空时，自动把快照 seed 到本机 `~/.claude/.../memory/`。
- `CLAUDE.md` 指示每个 session 直接读 `docs/memory-snapshot/MEMORY.md` 接上下文，**不依赖某台机器的 `~/.claude`**。
- **写了新记忆后**：把新增/改动的 `.md` 从 `~/.claude/.../memory/` 同步回 `docs/memory-snapshot/`（这样才会随 Dropbox 带到别的机器）。bootstrap 的反向同步留给收尾时手动做：
  ```bash
  cp ~/.claude/projects/"$(pwd | sed 's#[^a-zA-Z0-9]#-#g')"/memory/*.md docs/memory-snapshot/
  ```

## 6. Dropbox 同步注意

- **同一时间只在一台机器上工作**，避免 Dropbox 产生 "conflicted copy"。
- `.harness/observations*.jsonl`、`session-*.md`、`*.log`、`*.db`、`config.prop` 是运行期/隐私文件（`.gitignore` 已挡 git，但 **Dropbox 不读 .gitignore**）——它们会被 Dropbox 同步，体积偏大但无害；介意可在 Dropbox 里对这些做选择性排除。
- 设备上的真实数据（wxid/群名/金额）只在手机本地，从不进仓库。

## 7. 接上工作后怎么继续

1. `bash scripts/bootstrap.sh` 看环境与设备就绪、插件版本。
2. 读 `docs/memory-snapshot/MEMORY.md` + 相关记忆条目（架构决策、踩过的坑）。
3. 看 `git log --oneline -5`、各 `plugins/*/SPEC.md` 了解最近做到哪。
4. 当前挂起的真机收尾项见最近对话/记忆：RedPacketStats v1.6.4 发红包验 perf、enable/disable 即时生效、确认 WAuxiliary 里 RedPacketStats 开关为 ON。
5. 按 `CLAUDE.md` 的 6 阶段 Harness 工作流推进；真机不可逆动作先在 PLAN 列明确认。
