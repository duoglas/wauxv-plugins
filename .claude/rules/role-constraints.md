# 角色操作约束 / Role Constraints

## 阶段流转控制

- **PLAN 完成后必须暂停**，展示任务清单等用户确认，确认后自动执行后续阶段
- 如果环境变量 `HARNESS_AUTO=full`，则 PLAN 也不暂停，全程自动
- 如果环境变量 `HARNESS_AUTO=off`，则每个阶段切换都暂停等用户确认

## Director（人）禁止操作

- 直接编写插件实现代码（BeanShell/shell 应通过 Agent 执行）
- 直接对真机执行不可逆运维命令（如 `magisk --denylist`、覆盖 service.sh、刷模块），未经 PLAN 确认
- 在 Agent prompt 中写死具体实现代码
- 跳过 QA（真机验证）直接交付
- 用主观感觉做 QA（"应该好了"），不看 logcat / 实测

## Director 允许操作

- 通过 Agent tool 派发任务
- Git 操作
- 读取任何文件、`adb` 只读诊断（logcat、dumpsys、pull、查进程）
- 编辑：docs/constraints.md、.claude/rules/*、CLAUDE.md
- 最终验收决策（看真机实测结果）

## Implementer Agent 约束

- 严格按任务描述执行，不添加额外功能（YAGNI）
- 改动 onHandleMsg 等"每条消息"热路径时，必须先评估复杂度（见 C-PERF-01），不得引入 O(N) 全量读写
- 引用相关 Constraint ID
- 完成后必须 `adb` 部署到真机并自验（logcat 无新异常 + 目标行为生效）
- 遇到不确定的问题返回 NEEDS_CONTEXT，不要猜

## Reviewer Agent 约束

- 只做审查，不做修改
- 关注真正的问题（性能热路径、反检测暴露面、消息链路），不纠结风格
- 输出结构化报告（PASS/FAIL + 具体描述）
- Spec Reviewer 和 Code Reviewer 必须是不同 Agent 实例

## 违规自检

| 正在做的事 | 应该做的事 |
|-----------|-----------|
| 在消息热路径写全量读写 | 先评估复杂度，改增量/内存缓存+批量 flush（C-PERF-01） |
| 改完没部署真机就说完成 | adb 部署 + 实测消息收发再判定（C-PLUGIN-01） |
| 装新 root app 不管隐藏 | 同步加 HMA 白名单 + DenyList（C-SEC-01） |
| 用主观感觉做 QA | 用 logcat / 真机实测判断 |
