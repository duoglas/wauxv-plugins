# 排行榜（Leaderboard / 趣味群英榜）功能设计 — 2026-06-11

> 状态：**设计已定 + 嗅探判据已坐实（真机）**，待 PLAN 拆任务开发。
> 关联：合并插件架构见 [2026-06-09-merge-groupadmin-redpacket-design.md]；落位接入点见各模块 `merged/src/`。

## 1. 需求（用户原话浓缩）

- 一组排行榜：**总发言榜** + **各种"之最/第一"**（话痨王 / 水王 / 红包王 / 手气王 / 拍一拍狂魔 / 熬夜冠军 …），用**趣味文案**做成一张"群英榜"。
- **手动出榜**（命令触发，不自动群发/不定时推送）。
- 想要的榜含：发言、红包、转账、拍一拍（用户确认**群聊有转账**）。

## 2. 数据建模决策（用户拍板）

- **A = 逐条原始事件日志 = 唯一真相源，全量保留。**
- **B = 榜单/聚合，全部从 A 派生**（rollup 物化做）。
- 文本消息**记字数**（复用 `val` 列，热路径白送：content 已在内存，`.length()` 即可，零额外反射/存储）。

### 2.1 表结构

```sql
-- A: 原始事件 (唯一真相源)
CREATE TABLE msg_event(
  grp TEXT, wxid TEXT, type INT, ts INTEGER,
  val INTEGER          -- 文本→字数; 红包/转账→金额分; 其它→NULL
);
CREATE INDEX idx_event_grp_ts ON msg_event(grp, ts);
-- 可选 ym(年月)列 + 索引: 窗口查询只扫目标月, 不扫历史全量

-- B: 月度 rollup 物化 (从 A 增量算, 总榜/历史月榜读它)
CREATE TABLE rank_rollup(
  grp TEXT, metric TEXT, wxid TEXT, ym TEXT, value INTEGER,
  PRIMARY KEY(grp, metric, wxid, ym)
);
```
- 不存 content（体积 + 隐私）。所有榜 = A/rollup 的一个查询；加新榜 = 加 type 常量 + 一句 WHERE，不动表。
- 金额一律存**分**（避免浮点）。

### 2.2 量级（真机锚点 + 估算）

- 现 `groupadmin.db` 仅 216KB（跑数日、3 群含 482 人）——因 speak 表是**每人 1 行聚合**。
- A 逐条估算 ~2万消息/天 ≈ 2–3MB/天 → ~1GB/年（含索引）。**必须配套**：`ym` 分区索引 + 月度 rollup（总榜读聚合不碰全量）+ 冷数据可选归档 + 体积看门狗（复用 24h 监测脚本加 `du`）+ 定期 WAL checkpoint / 谨慎 VACUUM。

## 3. 检测判据（2026-06-11 真机嗅探坐实）

嗅探方式：onHandleMsgBody 顶部临时只读探针（PROBE_ON 闸门 + self-disarm + try/catch），命中 `<wcpayinfo>/拍了拍/<appmsg>/<sysmsg>` 写 rp.log `[PROBE]`。嗅探完已还原 + 清日志。

| 类型 | **判据（content 子串，不靠 getType）** | 提取 |
|---|---|---|
| **拍一拍** | content 含 `拍了拍`，形如 `"${A}" 拍了拍 "${B}"` | 正则 `"\$\{(.+?)\}"\s*拍了拍\s*"\$\{(.+?)\}"` → A=拍方 wxid, B=被拍方 wxid |
| **转账** | content 含 `<wcpayinfo>` | `<feedesc>￥金额`、`<payer_username>`付款、`<receiver_username>`收款、`<transferid>`去重键、`<paysubtype>`(1发起/3收款) |
| **红包** | content 含 `<nativeurl>`（现成 RP 模块） | rp_record 已有 sender + qualified（金额埋在 qualifiers 文本，金额榜需另解析或走 A） |

### 3.1 两个关键坑（建议写进 constraints）

1. **`msg.getType()` 不可靠**：真机返回大数（拍一拍=922746929、转账=419430449、appmsg=822083633…），**非标准微信枚举** → 检测一律用 content 标签判据。
2. **"转账"二字会误命中聊天**（有人打字"我要转账"被抓为 type=1）→ 转账必须用 `<wcpayinfo>` 标签判据，禁用关键字。

### 3.2 转账特性（重要）

- **每笔完成的转账 = 2 条群消息**：发起 `paysubtype=1` + 收款 `paysubtype=3`，**同一 `transferid`**（已真机验证配对）。
- 计数：用 `transferid` 去重，按单一 paysubtype 计一次（收款=3 代表交易完成，建议用它；发起=1 代表有意图）。
- payer/receiver/金额全在 XML 里 → **转出榜(payer Σ) + 转入榜(receiver Σ)** 都能做，不依赖 msg sender。
- 转账确认是**群消息**（talker=...@chatroom），群内转账成立。

## 4. 采集层（热路径 O(1)）

- onHandleMsgBody 拿到 `groupId/sender/type/content` → append 进内存脏缓冲 `RANK_DIRTY` → 后台 flush **单事务批量 INSERT**（复用现有 ANR 批量底座 commit ce62507）。
- 热路径只内存 append，零盘 IO（C-PERF-01）。
- 金额事件（红包/转账）的 val 解析（XML）放 **worker**，不上热路径。
- 拍一拍/转账检测在热路径只做廉价 `content.indexOf(标签)`。

## 5. 展示层（后台线程，手动触发）

- 命令 `群龙榜/排行榜/趣味榜` → `commands.bsh:routeCommand` 加分支 → 后台线程跑类目查询 → 解析昵称（反射 getGroupMemberList，**后台 + 缓存名字**，唯一贵步骤）→ 渲染趣味文本 → sendText 发群。
- **趣味"之最" = 配置驱动**：每类目 = (emoji+头衔, metric, `ORDER BY 指标 DESC LIMIT 1`, 单位格式化)；加榜 = 加一行配置。总发言榜 = 同引擎 `LIMIT N`。
  - 例：👑话痨王=COUNT(*) / 💧水王=SUM(val) WHERE 文本 / 🧧红包王=COUNT WHERE 红包 / 🎰手气王=MAX(val) WHERE 红包抢 / 👋拍一拍狂魔=COUNT WHERE pat / 🦉熬夜冠军=COUNT WHERE hour(ts)∈[0,5] / 🤿潜水冠军=speak 表现成。

## 6. 模块落位（按现有三层）

```
src/domain/rank.bsh         bump/聚合/topN 纯逻辑 + SQL  → L1/L2 可离线测
src/app/rank_collect.bsh    RANK_DIRTY 内存缓冲 + flush(接 l3 循环)
src/app/rank_render.bsh     查询 + 名字解析 + 趣味渲染(后台线程)
  + manifest.prod.txt 登记 + onLoad 建表(msg_event/rank_rollup) + commands/dialogs 挂入口
  + tests/rank_*_unit.bsh / _integration.bsh (sqlite-jdbc 真库验 SQL)
```
- 开关门：仿 `enable_state.bsh` 全局门 + 单群门（60s TTL 缓存、空键=开升级安全）。

## 7. type 取值表（趣味榜最小集，可扩）

`text / image / sticker / voice / video / redpacket_send / redpacket_grab / transfer_out / transfer_in / pat / other`

## 8. 待办 / 开放项

- **type 细分粒度**：是否每种消息类型各一榜（影响 type 维度大小）——倾向"总发言榜 + 各专项之最"。
- **转账计数口径**：按收款(3)还是发起(1)；转出/转入分别做。
- **rollup 周期**：月度；今日/本周直接查 A。
- 拍一拍多人/自拍场景样本可再嗅探补充（当前 1 条干净样本已够定判据）。

## 9. 建议构建顺序

1. domain/rank.bsh（SQL + 聚合 + topN）+ L1/L2 测 → check.sh 绿。
2. 采集：msg_event 建表 + onHandleMsgBody append + 批量 flush（先只接发言，端到端打通）。
3. 展示：一个命令出"总发言榜"（手动）。
4. 逐个加 metric 采集：红包（worker 旁）→ 拍一拍（content 判据）→ 转账（wcpayinfo 解析，transferid 去重）。
5. 趣味"之最"类目配置 + rollup 物化 + 体积看门狗。
6. 真机准出 + Santa 双审（热路径采集属 medium+）。
