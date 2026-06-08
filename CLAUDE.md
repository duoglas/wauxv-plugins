# CLAUDE.md

## 环境自检 / 跨机器接力（每个新 session 先做）

本仓库随 **Dropbox** 在多台 Mac 间同步，工作目录可能在任意机器的 `~/Dropbox/<…>-work/android-ops`。**新 session 开始时：**

1. 先跑环境自检：`bash scripts/bootstrap.sh`（识别本机、检查 adb/scrcpy/手机/root、显示插件版本、本机记忆为空时自动从快照 seed）。
2. **读 `docs/memory-snapshot/MEMORY.md` 及其引用的记忆条目接上下文**——这是跨机器权威记忆副本（随 Dropbox 走），不依赖某台机器的 `~/.claude`。
3. 迁移/环境细节看 `docs/ONBOARDING.md`。
4. 写了新记忆后，把 `~/.claude/.../memory/*.md` 同步回 `docs/memory-snapshot/`（否则别的机器接不到）。

设备：Pixel 8a (`44151JEKB08662` / akita)，USB + `scrcpy` 看屏幕。

## 项目简介

安卓设备运维总仓——通过 `adb` 管理一台 Pixel 8a (akita / Android 16 / Magisk root) 上的：WAuxiliary 微信增强模块的自定义 BeanShell 插件、keepalive 保活 Magisk 模块、以及设备隐藏与网络配置。开发与运维全程靠 adb 连真机进行，没有传统应用代码与编译链。

## 技术栈

- BeanShell（WAuxiliary 插件 `main.java`，解释执行，hook 微信）
- POSIX sh（keepalive Magisk 模块 `service.sh` + scripts）
- adb / Magisk / Zygisk / LSPosed(JingMatrix→lsposed.zip v2.0.4) / Shamiko / HideMyApplist / clash.meta

## 目录结构

```
plugins/         WAuxiliary 自定义插件源码（部署目标: 真机 WAuxiliary/Plugin/<名>/main.java）
  group-admin/     GroupAdmin 群管插件（main.java + versions/ 历史版本）
  group-stats/     群聊统计分析插件（含 AI 分析，调硅基流动 API）
keepalive/       keepalive_buckets 保活模块（service.sh / scripts / config）
device/          设备隐藏与网络配置副本（clash 访问控制 / HMA 白名单 / DenyList / 模拟定位）
docs/            constraints.md 等
scripts/hooks/   Harness hook 脚本
```

## 常用命令

```bash
# 设备连接 / 诊断（只读）
adb devices -l
adb shell "su -c 'cat /data/adb/lspd/log/verbose_*.log | tail'"      # LSPosed/插件运行日志
adb shell "su -c 'cat .../WAuxiliary/Plugin/GroupAdmin/plugin.log'"   # 插件自身日志

# 部署插件（改完 main.java 后）
adb push plugins/group-admin/main.java /data/local/tmp/ga.java
adb shell "su -c 'cp /data/local/tmp/ga.java <真机 Plugin 路径>/main.java'"   # 再在 WAuxiliary 内重载或重启微信

# 保活模块部署
adb push keepalive/service.sh /data/local/tmp/ && adb shell "su -c 'cp ... ; 重起循环'"

# 验证（真机实测，无自动化测试）
adb logcat -d | grep LSPosedFramework        # 看 hook 加载/异常
# + 真机发收消息观察是否延迟/积压
```

## 工作流（最高优先级）

**本项目已初始化 Harness Engineering。所有开发工作必须遵循 6 阶段 Loop：**

```
PLAN → SETUP → EXECUTE → VERIFY → REVIEW → FEEDBACK
```

- PLAN 完成后暂停等用户确认，确认后自动执行后续阶段
- 真机不可逆动作（刷模块、改系统设置、批量踢人、改 denylist）必须在 PLAN 列明并确认

### 会话级开关

- `/harness-off` — 临时关闭 Harness 模式
- `/harness-on` — 重新启用

## 工具调用稳定性（避免格式出错中断）

### 头号根因：超长 session 上下文退化 → tool-call 信封格式崩坏（2026-06-08 排查）

实测教训：在**一个跨多天、从不清空的长 session** 里，模型会越来越频繁地把工具调用的标记（`<invoke …>`）当成**纯文本**吐出来，而不是真正的结构化调用 → 工具不执行、回合空转 end_turn 结束 → 看起来「做一会就停下来 / 卡住」，被迫一遍遍打「继续」。模型自述症状是「老在开标签上漏字」。

证据：单 session（claude-opus-4-8）死回合数随长度暴增 **06-06=1 → 06-07=10 → 06-08=28**，且聚集在上下文最深处（后 25% 占一多半）。峰值上下文 ~570K tokens。

关键认知：**「等它自动 compact」救不了**——auto-compact 只在逼近 1M 窗口（~80%+）才触发，~570K 远没到阈值，于是卡在「已退化、却没触发安全网」的中间地带。

应对（按优先级）：

- **主动 compact / 开新 session，别等自动**：到 ~400K tokens（或察觉开始出现空转回合）就 `/compact` 或开新 session 继续。
- **单 session 不要跨多天**：长跑的运维/守护任务（watchdog、连续观察）用后台脚本 + 日志承载，不要靠让一个对话 session 一直开着。
- **降低每次工具调用的上下文灬注**：harness `harness-stage-guard.js` 已改为整段阶段 directive + LOG_REMINDER 每 (session, stage) 只注入一次（C-CTX-01），后续同阶段调用只留一行 `[Harness ON] 当前阶段` 简提醒（每次注入 ~1262B→124B）。
- 一旦看到模型回复里出现裸 `<invoke>`/`<parameter>` 文本而工具没执行，立即 `/compact` 或重开，而不是反复「继续」。

### 次要：`AskUserQuestion` payload 折叠

实测教训（2026-06-06 session 复盘）：**`AskUserQuestion` 是反复出格式错的工具**，报错 `questions type is expected as array but provided as string`——模型把整个 `questions` 数组折叠成了一个（且往往已损坏的）JSON 字符串。触发条件高度一致：**payload 过大、过深嵌套**（长段中文 option 描述 + `preview` 里塞带换行和转义引号的多行命令）。

避免方式：

- `questions` / `options` 必须是真数组，**绝不整体序列化成字符串**
- option 的 `description` 写短（一句话），**不要在 `preview` 里塞带转义引号的多行 shell/git 命令**——那串转义最容易让 JSON 写崩
- 一个超大问题拆成多个小问题，单次 payload 越小越不会折叠
- 真要展示多行命令，放进正文文本里问，别塞进 `preview` 字段

> 注：这类错误模型会在 ~8s 后自动重试成功，不是硬中断，但浪费一轮、看着像卡住。缩小输入是最有效的规避。

## Harness 规则（自动加载，`.claude/rules/`）

- `harness-entry.md` — 新 session banner + 入口
- `role-constraints.md` — Director/Implementer/Reviewer 职责
- `qa-standards.md` — QA 标准（以真机验证为 Layer 2）
- `feedback-workflow.md` — F1-F5 反馈处理

## Hooks（自动执行，最小集）

- `harness-session-start.js` — 新 session 重置阶段
- `harness-stage-guard.js` — 强制声明阶段，PLAN 禁写
- `safety-guard.js` — 拦截危险命令
- `session-logger.js` — 全过程记录
- `stage-since-autofill.js` — since sentinel 回填
- `session-end.js` — SessionEnd 收尾
- `find-root.js` — hook 共享依赖（定位项目根）

## 关键文件索引

- `docs/constraints.md` — 约束系统（SINGLE SOURCE OF TRUTH）；当前重点 C-PERF-01（消息热路径性能）、VH-01（GroupAdmin recordSpeak）
- `plugins/group-admin/main.java` — 当前生效的 GroupAdmin（待优化 recordSpeak）
- `keepalive/service.sh` — 保活 v3（微信不在前台才切前台）

## AI 工具内测试准出

涉及插件/脚本变更时，必须在 VERIFY 阶段做真机准出：adb 部署 + logcat 无异常 + 目标行为实测 + 不拖慢消息收发。热路径（onHandleMsg）改动属 medium 以上风险，必须有真机实测证据，不能用"应该没问题"代替。交付说明先说结果、再说测了什么、最后说限制。
