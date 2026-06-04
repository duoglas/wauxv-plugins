# 反馈处理流程 / Feedback Workflow

用户/测试/Review 说"不行" → 执行以下 5 步，不可跳过。

## F1: 记录原话

原样记录，不解读不简化。

```markdown
- 来源：{用户反馈 / Review / 真机实测失败}
- 原话："……"
- 时间：YYYY-MM-DD
```

## F2: 分类问题层级

| 层级 | 典型问题 | 修改位置 |
|------|---------|---------|
| 规则层 | 系统性问题，多处可能出现 | docs/constraints.md |
| 工具层 | 插件/脚本 bug 或逻辑错误 | plugins/ 或 keepalive/（通过 Agent，再 adb 部署） |
| 配置层 | 项目/设备配置不当 | .claude/settings.json / device/ 配置副本 / 真机配置（adb） |
| 实例层 | 单点问题 | 具体文件 |

## F3: 提炼深层规则（关键步骤，禁止跳过）

- 不是"把 X 改成 Y"
- 而是"所有 X 类元素必须满足 Y 规范"
- 同类问题归一条规则

## F4: 写入正确层级的文件

- 全局规则 → `docs/constraints.md`（用 `C-{area}-{number}` 编号，area 见 constraints.md）
- **先写规则，再派 Agent。不是反过来。**
- 真机侧的不可逆改动（刷模块、改 service.sh、denylist）先在 device/ 或 keepalive/ 留可追溯副本，再部署。

## F5: 派 Agent 按规则修复

Agent prompt 必须：
1. 引用 Constraint ID
2. 指向 docs/constraints.md
3. 不包含具体实现代码
4. 要求 adb 部署真机自验（消息收发/logcat 实测）
