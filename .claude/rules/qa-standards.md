# QA 标准

> 本项目无传统编译/单测/类型链（插件是 BeanShell `main.java`，保活是 POSIX sh，运维靠 adb）。
> 因此 Layer 2 的"自动化工具检查"以**真机验证**为主：adb 部署 + logcat 观察 + 实际消息收发/行为实测。

## 5 层 QA 金字塔

```
Layer 5: Human Final Review（人工最终审查）— 看报告 + 关键决策，5 项交付前复盘
Layer 4: Santa Method（AI 对抗验证）— 两个独立 Reviewer Agent 同时检查（高风险改动）
Layer 3: Spec Compliance Review（AI 规格审查）— 独立 Agent 对照需求逐项检查
Layer 2: Verification Loop（真机验证）— 部署 → logcat → 行为实测 → diff
Layer 1: Agent Self-Verification（Agent 自验）— 改完即 adb 部署自测
```

## Layer 1: Agent Self-Verification

**谁执行：** 实现任务的 Agent 自己
**何时执行：** 每个任务完成后立即执行

1. 改动后用 `adb push` 部署到真机对应位置（WAuxiliary 插件 → `WAuxiliary/Plugin/<名>/main.java`；keepalive → 模块目录 + 重起循环）
2. 触发目标场景，观察 logcat / plugin.log 无新异常
3. 热路径改动（onHandleMsg 等）必须确认未引入 O(N) 全量读写（C-PERF-01）

**Gate 条件：** 真机行为符合预期 + 无新异常日志

## Layer 2: Verification Loop（真机验证）

**谁执行：** adb + 真机观察（不依赖 LLM 主观判断）
**何时执行：** Agent 自验通过后

```
Phase 1: 部署      adb push 到真机目标路径；插件需在 WAuxiliary 内重载或重启微信进程
Phase 2: 加载检查  logcat / plugin.log 确认模块加载成功、无 NoSuchMethod / 解析错误
Phase 3: 行为实测  触发目标功能(发消息/群命令/保活切换)，确认行为正确
Phase 4: 副作用    确认未拖慢消息收发、未增加反检测暴露面、未误杀进程
Phase 5: Security  grep -rn "api_key\|wxid_\|token\|silicon\|Bearer" plugins/ device/ keepalive/
Phase 6: Diff      git diff --stat
```

**Gate 条件：** Phase 1-6 全部 PASS（真机实测为准，不能用"应该没问题"代替）

**输出格式：**

```
VERIFICATION REPORT
===================
部署:     [PASS/FAIL]
加载:     [PASS/FAIL] (logcat 有无异常)
行为实测: [PASS/FAIL] (测了什么场景)
副作用:   [PASS/FAIL] (消息延迟/暴露面/进程)
Security: [PASS/FAIL] (N issues)
Diff:     [N files changed, +X/-Y]

Overall:  [READY/NOT READY]
```

## Layer 3: Spec Compliance Review

独立 Reviewer Agent（≠ 写代码的 Agent）对照需求逐项检查，输出 PASS/FAIL 结构化报告。消除 author-bias。

## Layer 4: Santa Method（对抗验证）

高风险改动（消息热路径、刷框架/模块、反检测配置、批量踢人等破坏性群管）必须双独立 Reviewer：

```
A passes AND B passes → NICE → Layer 5
否则 → NAUGHTY → Fix Cycle（最多 3 轮，超过升级人工）
```

**何时跳过：** 纯文档/配置注释、只读诊断脚本。跳过需记录降级决策。

## Layer 5: Human Final Review

交付前 5 项复盘：
1. **流程合规**：是否按 6 阶段 Loop？有无跳过阶段？
2. **QA 达标**：真机验证报告是否完整？
3. **需求完整**：所有需求点是否处理？
4. **规则升级**：新发现问题是否写入 constraints.md？
5. **改进机会**：哪些步骤下次可优化？

## 量化指标体系

| 指标 | 含义 | 目标 |
|------|------|------|
| pass@1 | 一次部署即达预期的比例 | >70% |
| Mean iterations | 平均修复轮数 | <1.5 |
| Escape rate | 部署后才暴露的问题 | 0 |
| 热路径回归 | 改动导致消息延迟/卡顿 | 0 |

## 核心指标（项目定制）

| 指标 | 阈值 | 度量方法 |
|------|------|---------|
| 真机加载 | 无 NoSuchMethod / 解析错误 | `adb logcat \| grep LSPosedFramework` / 看 plugin.log |
| 消息链路 | 收发无明显延迟 | 真机发收实测 + 看是否积压 |
| 反检测暴露 | 目标 app 查不到 root/xposed 包 | HMA 白名单 + DenyList 覆盖检查 |
| 敏感信息 | 0 泄漏 | `grep -rn "api_key\|wxid_\|token\|Bearer" plugins/ device/ keepalive/` |
