# CLAUDE.md

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
