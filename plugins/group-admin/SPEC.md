# GroupAdmin SPEC

> 插件行为规格（C-PLUGIN-03 要求）。**改行为先改本文件，再改 `main.java`。**
> 状态标记：`[现状]` = 当前 main.java 已实现；`[目标]` = 按 constraints 待重构，尚未落地。

## 1. 概述

- 名称：GroupAdmin（群管理）
- 当前版本：v1.14.0（`main.java` 2881 行，BeanShell）
- 宿主：WAuxiliary（`me.hd.wauxv`），通过 `onHandleMsg` 等回调 hook 微信 `com.tencent.mm`
- 职责：群消息驱动的群管理——踢人 / 拉黑 / 警告 / 潜水清理 / 白名单 / 全局名单 / 三层权限
- 部署目标：真机 `…/WAuxiliary/Plugin/GroupAdmin/main.java`

## 2. Hook 点

| 回调 | 触发 | 用途 | 性能敏感度 |
|---|---|---|---|
| `onLoad()` | 插件加载 | 兜底已启用群、建立潜水基线、native 角色探测 | 一次性，低 |
| `onHandleMsg(msg)` | **每条消息** | 命令分发 + 潜水追踪 | **P0 热路径** |
| `onMemberChange(...)` | 成员进出 | first_seen 维护 | 中（事件频率低） |
| `onUnload()` | 卸载 | 收尾 | 低 |

## 3. 动作分层（C-ARCH-01，本插件的核心设计基线）

| 层 | 含义 | 本插件动作 | 要求 |
|---|---|---|---|
| **L1 管理动作** | 破坏性 / 需即时可靠生效 | 踢人（`#踢潜水`/`踢潜水`）、警告、拉黑（`addsb`/`addgb`）、严格模式、开启/关闭群管、保护名单写入 | 必须可靠执行；失败要有反馈（如无 bot 权限提示）；优先级最高 |
| **L2 用户交互** | 查询 / 响应，需要及时但无破坏性 | 各查询（黑名单/白名单/管理员/警告名单/保护名单/潜水名单/群管状态/群管群列表）、帮助、`群管设置`/`#群管设置` Dialog、`#群权限自检` | 及时响应；可同步但要轻；重计算（如 native cursor 扫群成员）仅在该命令触发时执行，不进普通消息路径 |
| **L3 统计/被动追踪** | 及时性低、可降级 | `recordSpeak`（潜水发言时间 `lsg_`）、first_seen（`fsg_`）维护 | **必须异步化 + 可降级**（C-ARCH-02）；绝不在消息线程同步做全量持久化；热路径压力大/出错时可丢弃当前批次 |

> 关键认知：潜水**清理**（踢潜水）是 L1，但潜水**数据采集**（recordSpeak）是 L3。VH-01 的错误正是把 L3 采集同步压在了热路径上。

## 4. 命令清单（按权限）

权限层级：`super_owners` > `super_admins` / 原生群主(`nat_owner_`) > 群 `admins_`。判定：`isOwner` / `isAdmin` / `isAuthorized`。非授权者发命令一律静默忽略（v1.9 起，防探测）。

- 开关（仅 owner）：`开启群管`/`启用群管`、`关闭群管`/`禁用群管`
- 查询（admin+）：`黑名单`、`管理员`、`警告名单`、`保护名单`、`白名单`、`全局黑名单`、`全局白名单`、`群管状态`、`群管群列表`、`帮助 群管`/`群管帮助`
- 管理（admin+，L1）：`addsb <wxid>`(加黑)、`delbk`(删黑)、`addwt`/`delwt`(白名单)、`addgb`/`delgb`(全局黑)、`addgw`/`delgw`(全局白)、`清除警告名单`、`严格模式 [on/off]`
- 潜水：`#潜水 N`/`潜水`(列名单 L2)、`#踢潜水`/`踢潜水 1,3,5-8`(批量踢 L1)
- 自检/设置：`#群权限自检`、`群管设置`/`#群管设置`（弹 Dialog，`onClickSendBtn` 拦截）

## 5. 数据存储（fastkv / config.prop）

群级 key（`<前缀><groupId>`）：`bl_`(黑名单) `wt_`(白名单) `protected_`(保护) `admins_`(管理员) `nat_owner_`(原生群主) `wl_`/`wn_`/`wnf_`/`wnt_`(警告：列表/计数/时间) `bp_`(bot权限状态) `lsg_`(最后发言) `fsg_`(首次见) `pending_kick_`/`pending_kick_ts_`(待踢暂存)

全局 key：`bl_global` `wt_global` `enabled_groups` `strict_mode` `super_admins` `super_owners` `default_inactive_days`

存储问题与要求：
- `[现状]` `lsg_<group>` / `fsg_<group>` 是整群 CSV（`wxid|ts,...`），单群最大已见 ~500 人 16KB，config.prop 总 66KB。
- `[目标]` C-PERF-02：单 key 体量要有上界。CSV 读写复杂度 O(群人数)，每条消息全量读改写 = VH-01 根因。

## 6. 消息热路径（onHandleMsg）

`[现状]` 流程：取 content → 非文本/非群聊 return → 命令匹配（L1/L2，匹配即处理并 return）→ 普通消息走 `recordSpeak`（**同步全量 CSV 读改写，VH-01**）。

`[目标]`（C-PERF-01/03 + C-ARCH-02/03）：
1. 最廉价判定：非文本/非群聊/空 → 立即 return
2. 命令判定走廉价前缀匹配；命中 L1/L2 才进对应处理
3. 普通消息的 L3 采集只做**内存入队**（更新内存 Map），立即返回；不在消息线程做任何磁盘写
4. 后台定时（如每 30s）或退出时，把内存增量批量 flush 到分片 key；flush 失败/超时不影响收发
5. `[目标]` C-PERF-04：onHandleMsg 入口埋点，记录各分支耗时与调用数到独立性能日志（不写 plugin.log 主流），支持每日聚合

## 7. 降级策略（C-ARCH-02）

- L3（潜水采集/统计）在以下情况优先降级：内存缓冲超阈值、flush 连续失败、检测到消息处理耗时超标 → 暂停采集，保收发
- L1/L2 永不因 L3 降级而受影响
- 降级要留痕（性能日志记一条），便于事后分析

## 8. 已知问题 / 重构待办

| 编号 | 问题 | 约束 | 优先级 |
|---|---|---|---|
| RB-1 | `recordSpeak` 每条消息同步全量 CSV 读改写（VH-01） | C-PERF-01, C-ARCH-02 | P0 |
| RB-2 | `lsg_/fsg_` 单 key 无上界，config.prop 膨胀 | C-PERF-02 | P0 |
| RB-3 | 无性能埋点，问题靠用户实测才暴露 | C-PERF-04 | P0 |
| RB-4 | 缺降级机制，L3 出问题会拖垮收发 | C-ARCH-02 | P1 |
| RB-5 | 红包个数显示插件 `ArrayIndexOutOfBoundsException`（属 group-stats / WAuxiliary，非本插件，但同宿主需关注微信版本兼容） | C-PLUGIN-04 | P2 |

## 9. 测试矩阵（C-PLUGIN-04，无单测框架→真机）

每次改动后在真机回归，留 logcat/计时证据：

| 场景 | 验证点 |
|---|---|
| 普通群消息高频涌入（积压模拟） | onHandleMsg 不阻塞、收发无延迟、内存缓冲正常 flush |
| 大群（≥500人）普通消息 | 热路径耗时不随群人数线性增长（验证去 O(N)） |
| L1 各管理命令（踢/警告/拉黑/严格模式） | 可靠执行 + 正确反馈；无 bot 权限有提示 |
| L2 各查询命令 | 响应正确、及时 |
| 潜水采集 + `#潜水 N` + `#踢潜水` | 采集异步不丢关键数据；名单与清理正确 |
| 边界/异常消息（非文本、撤回、系统消息） | 不崩、不误处理 |
| 性能日志 | 埋点有数据、可聚合、能看出热点 |

## 10. 给新 session 的 PLAN 起点

建议拆分（每步 ≤15 分钟可独立真机验证）：
1. 先加 onHandleMsg 入口埋点（RB-3），拿到改造前的真实基线耗时
2. recordSpeak 改内存缓冲 + 后台定时 flush（RB-1）
3. lsg_/fsg_ 存储分片 + 上界（RB-2）
4. L3 降级开关与阈值（RB-4）
5. 真机测试矩阵回归 + 埋点前后对比
