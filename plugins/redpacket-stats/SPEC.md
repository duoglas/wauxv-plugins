# RedPacketStats SPEC（正式版 v1.0）

> 插件行为规格（C-PLUGIN-03 要求）。**改行为先改本文件，再改 `main.java`。**
> 当前版本：**v1.4.0（待部署）**——在已验证的 v1.3.0 之上做三件：① 发包人昵称修复（用群昵称 `getFriendDisplayName`→`getFriendNickName`→wxid，§17.1b）；② 每日定时统计 → 私聊（按群开关 `rp_daily_groups` + 一次性迁移默认 + 7点-7点窗口 + `rpWorkerTick` 触发 + 手动测试命令，§20）；③ 轻量 perf 埋点（`onHandleMsg` 只 nanoTime+累加，聚合落盘独立 `perf.log`，§21）。
>
> 历史版本：**v1.3.1（曾为开发版）** — v1.0.4 起仅处理拼手气红包（见 §11）。v1.0.6 新增**红包提醒排除名单（按群）**（§12）+ **反检测硬化**（§13）。v1.0.7 措辞随机（§14）。v1.1.0 调度层改"队列 + 单工作者（单飞）"（§15）。v1.1.2 发提醒改"引用群规条 + @ 条"两条（§16；实测引用条内 `[AtWx=]` 不解析、sendText 的 @ 真生效，故拆两条）。**v1.1.4 发两条修正（§16）：第一条（引用群规）改固定简洁、无随机无语气；第二条（@）保持随机，且第一条发出后隔 `rp_two_msg_gap_sec` 秒（默认 3，可配）再发以保证到达顺序。** **v1.2.0：两条消息定版，剥离统计（统计本地 DB §17、导出 §18、标题提取整体剥离，状态曾改「待实现」）。** **v1.3.0：在 v1.2.0（已真机验证的"队列 + 两条消息"定版）之上重新加回统计本地 DB（§17）、导出今日红包统计 → 私聊（§18）、红包标题提取（§17.3），并加 3 处嗅探调试日志（§17.5）以在真机一验时探明 3 个未知点（标题取自哪个字段 / DB 建库是否可行 / 私聊是否送达）。v1.2.0 的两条消息逻辑一行未动。** **v1.3.1（开发版，未部署）：三处增量——① 统计 `rp_record` 加 `group_name` 列（记录时仿 GroupAdmin `getGroupList()`→`getRoomId()`/`getName()` 取当前群名，导出每行行首带 `[群名]`，§17.1a）；② 第二条 @ 消息整体开头加 `【查包】` 前缀（§16）；③ `> rp_at_limit` 由"只发第一条"改为"第一条 + 隔 gap 秒发汇总 notice `【查包】本次过{t1}元共{Y}人，人数较多不逐一提醒，请大家自觉。`（无 @）"（§6.1/§16）。第一条/队列/统计写入点/热路径/两条消息的"隔 gap 秒"机制一行未动。** 从 v0.8 spike（检测→开封面→点"看看大家的手气"→反射 NewDetailUI，全真机验证通过）升级为正式插件。

## 1. 概述

- 名称：RedPacketStats（红包领取统计）
- 宿主：WAuxiliary（`me.hd.wauxv`），通过 `onHandleMsg` hook 微信 `com.tencent.mm`
- 部署目标：真机 `…/WAuxiliary/Plugin/RedPacketStats/main.java`（目录名必须 = `RedPacketStats`）
- 一句话：群里红包**领完后**，自动开页读明细、按金额阈值分档，在群里发**一条消息** @ 圈出"手气好"的人。

### 目标形态（v1.0 落地）

- **按群启用**（默认关），仅在白名单群处理红包。
- **配置化**：三档阈值（元）、三档文案、首次延迟、两次重试间隔，全部可配，带默认值。图形 Dialog（WAuxiliary 设置入口）+ 文字命令兜底。
- **完整流程**：检测红包 → 延迟 → 开封面页 → 判定是否领完 → 领完则点详情、没领完则关页+重试两次后放弃 → 反射读领取者明细 → 结构化提取昵称/金额/wxid → 分档 → 发一条 @ 消息。
- **绝不抢红包**：bot 不参与抢包，没领完只关页不点领取按钮，降低风控暴露面。

## 2. 按群启用（默认关）

- 存储 `rp_enabled_groups`（CSV 群 id），getter/setter 仿 GroupAdmin 的 `enabled_groups`。
- `onHandleMsg` 检测到红包后**先判该群是否启用**，未启用直接 return（不处理、不开页、不排 delay）。`onHandleMsg` **不处理任何配置命令、不发任何群内回执**。
- 命令（v1.0.2 起经 **`onClickSendBtn(String text)` 拦截**，bot 一点发送即拦下、**`return true` 阻止消息发到群里**、就地执行 + `toast()` 本地提示，**不再 `sendText` 回执到群**，不污染群；当前会话群用 `getTargetTalker()` 获取）：
  - `开启红包统计` → 启用本群 + toast 提示（不进群）
  - `关闭红包统计` → 停用本群 + toast 提示（不进群）

## 3. 配置项（存储 + 默认值）

| key | 含义 | 默认值 |
|---|---|---|
| `rp_t1` / `rp_t2` / `rp_t3` | 三档阈值（元，内部 ×100 按分比较） | 5 / 10 / 20 |
| `rp_txt1` / `rp_txt2` / `rp_txt3` | 三档文案 | 爆照 / 宣群 / 发视频 |
| `rp_first_sec` | 首次开页延迟（秒，内部 ×1000 换算 ms） | 120（2 分钟）|
| `rp_retry1_sec` | attempt0 失败后重试间隔（秒） | 300（5 分钟）|
| `rp_retry2_sec` | attempt1 失败后重试间隔（秒） | 600（10 分钟）|
| `rp_at_limit` | @ 人数上限：达标 1~此值发**两条（引用群规条 + @ 条）**；超过则**只发引用群规条**（不 @） | 10（v1.0.6，原 20）|
| `rp_exclude_<groupId>` | 按群红包提醒排除名单（CSV wxid，§12） | 空 |
| `rp_msg_min_gap_sec` | 同群两条红包提醒最小间隔（秒，§13.3） | 30 |
| `rp_two_msg_gap_sec` | 同一次提醒内第一条（引用群规）与第二条（@）之间的延迟（秒，§16，保证到达顺序） | 3 |
| `rp_export_target` | 导出今日红包统计私聊目标 wxid（§18；v1.3.0 恢复）。默认 `wxid_REDACTED`，可经命令/Dialog 覆盖。**公开提示**：此默认值为可配置默认（非密钥），真实采集数据落在本地 DB、不入仓 | `wxid_REDACTED` |
| `rp_daily_groups` | 开了每日定时统计的群（CSV groupId，§20），与红包统计开关 `rp_enabled_groups` 独立 | 空（首次加载由 `rp_daily_migrated` 迁移=当前 enabled_groups）|
| `rp_daily_migrated` | 一次性迁移 flag（§20）：首次加载把 `rp_daily_groups` 初始化 = 当前 `rp_enabled_groups`，跑过置 `1` 不再覆盖 | 空 → `1` |
| `rp_daily_hour` | 每日定时发送的小时（本地时间，§20）；窗口为 `[今天 hour:00 往前 24h, 今天 hour:00)` | 7 |
| `rp_daily_last_sent` | 上次每日发送的日期串 `yyyy-MM-dd`（§20）；抗重启不重发不漏发 | 空 |
| `rp_custom_on_<groupId>` | 按群「定制包」开关（§22）。**仅 Dialog 可改**。关→所有红包按普通包，行为与今天逐字节一致 | 空（关）|
| `rp_custom_kw_<groupId>` | 按群定制关键字（§22）。开启定制后用于判定红包祝福语是否为定制包（**包含匹配**：title 含该关键字即定制，如关键字「定制」命中标题「【定制】…」）；空串=视为未启用 | 空 |
| `rp_custom_rule_prefix_<groupId>` | 定制包第一条群规文本前缀（§22.6）。默认「定制包请按要求执行」，可经命令/Dialog 改 | 空→`定制包请按要求执行` |
| `rp_tiers_custom_<groupId>` | 按群定制包档表（§22），格式同 `rp_tiers_<groupId>`（`阈值\|动作;...` 升序）。未配置→回退普通档表 `getTiers(talker)` | 空 |

> **单位说明**：所有延迟配置项、命令、Dialog、状态显示统一用**秒**（v1.0.3 起；旧 `rp_*_ms` 毫秒键已废弃不再读，用新键名 `rp_first_sec`/`rp_retry1_sec`/`rp_retry2_sec` 避免误读旧值）。`delay()` 内部以 `sec * 1000L` 换算为 ms。
> **spike 临时短延迟**：测试期可把 `rp_first_sec` 临时设短（如 60=1 分钟）方便端到端验。main.java 默认值仍为正式 120，临时值通过命令/Dialog 设置。

### 配置入口

- **图形 Dialog（主通道）**：`openSettings()` = WAuxiliary 主面板点插件"设置"弹出。展示当前配置 + 可编辑（三档阈值 EditText 数字 / 三档文案 EditText / 三个延迟 EditText / 保存按钮）。参考 GroupAdmin 的 `openSettings` + `showGroupConfigDialog`（`getTopActivity()`、AlertDialog、LinearLayout、EditText、`_rpBtn`/`_rpC`/`_rpRound`）。
- **群内 Dialog**：`红包统计设置`（在群输入框打 → 点发送，经 `onClickSendBtn` 拦截，**消息不发到群**）→ 弹本群配置 Dialog（含本群启用开关）。因运行在 UI 线程上下文，`getTopActivity()` 拿得到前台 Activity，Dialog 能正常弹出（v1.0.2 修复在消息线程弹失败）。
- **文字命令**（v1.0.2 起全部经 `onClickSendBtn` 拦截，**命中即 `return true` 不发群** + toast 反馈；非配置命令 `return false` 正常发送）：
  - `红包阈值 5 10 20` → 设三档阈值（元）
  - `红包文案1 爆照` / `红包文案2 宣群` / `红包文案3 发视频` → 设对应档文案
  - `红包延迟 120` → 设首次延迟（秒）；`红包延迟 120 300 600` → 三参数分别设首次/重试1/重试2（秒）；空参显示当前三值（秒）
  - `红包at上限 20` → 设达标总人数上限 `rp_at_limit`（超过则改发无 @ 通用群规消息）
  - `红包统计状态` → toast 显示当前本群启用状态 + 本群每日定时开关（§20）+ 全部配置（含 `rp_at_limit`），不发群
  - `开启红包定时` / `关闭红包定时`（v1.4.0 §20）→ 对当前群开/关每日定时统计（独立于红包统计开关）
  - `红包每日测试`（v1.4.0 §20）→ bot 自己发；立即按"过去 24 小时（now-24h ~ now）"窗口对 `rp_daily_groups` 跑一次每日发送 → 私聊 `rp_export_target`（便于不等到 7 点验证）

## 4. 完整处理流程（核心状态机）

```
onHandleMsg(廉价): 群聊 + (type==436207665 或含 <nativeurl>)
  → 本群是否启用? 否 → return
  → 提 nativeurl/talker/sender/msgid → 存红包状态(key=nativeurl, attempt=0) → OPENED 去重
  → delay(rp_first_sec×1000) → hbProcess(nativeurl, attempt=0)

hbProcess(nativeurl): 用启动器拉 NewReceiveUI(封面页)。靠 onResume hook 接力。

NewReceiveUI.onResume (类名过滤 + identityHashCode 去重):
  → delay(RECEIVE_CLICK_DELAY) → UI 有界查找(≤800节点/≤50深) detail 入口:
       文本含 "看看大家的手气" 或 "查看领取详情" 且 **可点击**
    ├─ 找到可点(=已领完) → 单次 performClick → 微信流转 NewDetailUI
    └─ 找不到可点入口(=没领完) → **绝不点领取/不抢** → finish() 关封面页
         → 按 attempt 安排重试:
              attempt0 失败 → delay(rp_retry1_sec×1000) → hbProcess(attempt=1)
              attempt1 失败 → delay(rp_retry2_sec×1000) → hbProcess(attempt=2)
              attempt2 失败 → 放弃: 清 nativeurl 状态, log, 不再处理

NewDetailUI.onResume (类名过滤 + 去重):
  → delay(REFLECT_DELAY) → 后台线程反射读领取者 List
  → 对每元素结构化提取(见 §5): 昵称 + 金额(分) + wxid
  → finish() 关详情页(可连同封面)
  → 分档(见 §6) → 发一条 @ 消息到 talker
  → 清该 nativeurl 状态(每红包只成功处理一次, 去重)
```

**关页铁律**：无论成功还是没领完，都 `finish()` bot 自己弹开的页面（封面/详情），别留着影响后续红包/界面。finish 在 UI 线程调，try/catch。

## 5. 结构化字段提取（抗版本，别死写 d/f/n）

反射拿到 model 元素（本版本元素类 `com.tencent.mm.plugin.luckymoney.model.v4`）后，**结构 + 提示**提取三要素：

- **金额**：long/int 字段、值 >=0（分）。本版本 `f`，优先试名为 `f` 的 long；不存在/不合理则遍历找 long 字段取合理者。
- **wxid**：String 字段、值匹配 `^wxid_` 或像 wxid。本版本 `n`；否则遍历 String 找 `wxid_` 开头者。
- **昵称**：非空 String、非 wxid、非纯数字（排除时间戳 `g`）、非"手气最佳"标记（`m`）。本版本 `d`；否则遍历取符合的。

用本版本名（d/f/n）作首选 + 上述校验，校验不过再结构化兜底。**log 用了哪条路径**（便于版本漂移排查）。

> **隐私**：昵称/金额/wxid 是要用的**真实数据**（不是探针），正式提取出来用于发消息，但**绝不写进任何明文日志文件**（只在内存用；调试 log 仍脱敏 = wxid 截前 6 + len、昵称只记 len、金额可记）。

## 6. 分档 + 一条消息 @ 圈人

- 每人金额元 = `f / 100`。**严格大于**：`> rp_t3` → 档3；否则 `> rp_t2` → 档2；否则 `> rp_t1` → 档1；否则不提。**只归最高档**。
- 按档分组，发**一条消息**到该群（talker），每档一行（只列有人的档）。@ 用 GroupAdmin 同款 **`[AtWx=<wxid>]`** 语法。例：
  ```
  [AtWx=wxidA][AtWx=wxidB] 超过5元，爆照
  [AtWx=wxidC] 超过10元，宣群
  [AtWx=wxidD] 超过20元，发视频
  ```
  文案 = `超过<阈值>元，<rp_txtN>`。
- 没有任何人达标 → 不发消息。

### 6.1 发提醒按达标总人数分三种情况（v1.1.4 终版）

- **达标总人数** = 金额严格 `> rp_t1`、不在本群排除名单、且有 wxid 的所有领取者人数（三档之和）。
- **第一条群规文本（v1.1.4：固定简洁、无随机、无语气词）**（无 @，列全配置三档）= `rp_rule_prefix(原值)` + 各档 `过{阈值}元{动作核心(cfgTxt 原样)}` 拼接，连接词固定写 `过X元`，例：`领红包请遵守群规执行，过5元爆照，过10元宣群，过20元发视频`。**不调 `pickConnector`/`pickActionPhrase`**，≤ 上限与 > 上限两种发送情况都用同一条固定文本。
- **达标 0 人** → 两条都不发（维持现状）。
- **达标 1 ~ `rp_at_limit`** → 发**两条**（严格顺序：先第一条，隔 `rp_two_msg_gap_sec` 秒再发第二条；详见 §16）：
  1. **引用群规条**：`sendQuoteMsg(talker, CUR_JOB.msgid, 固定简洁群规文本)` —— 引用原红包，列全三档，无名字无 @，无随机无语气。
  2. **@ 条**（隔 `rp_two_msg_gap_sec` 秒延迟发，保证到达顺序）：`sendText(talker, @文本)` —— **整体开头加固定前缀 `【查包】`**（在所有 `[AtWx=]`/逐档文本之前），随后每档一行 `[AtWx=wxid]...{pickConnector}{pickActionPhrase}`（**仅第二条随机**），只 @ 达标且未排除的人。**v1.3.1：第二条 @ 文本统一加 `【查包】` 前缀。**
- **达标 > `rp_at_limit`** → 第一条照发（固定简洁引用群规条，同上），**v1.3.1 起也发第二条**（隔 `rp_two_msg_gap_sec` 秒，与 ≤ 上限同一延迟机制）：**纯文字、无 @**，格式 `【查包】本次过{t1}元共{Y}人，人数较多不逐一提醒，请大家自觉。`（`{t1}` = 最低档阈值 `cfgT1` 元；`{Y}` = 达标总人数 `totalQualified`，即超最低档、剔除排除名单后的人数）。**v1.3.1：> 上限不再"只发一条"，第二条为带 `【查包】` 的汇总 notice（无 @）。**
- **限频**（§13.3）对"这一次提醒"**整体判定一次**（发第一条之前判，不把两条算两次）。

## 7. ANR 铁律（已 ANR 两次，死守）

1. **开页 / 启动器**：startActivity / callStaticMethod，不遍历。
2. **查找点击**：UI 线程一次性有界 BFS（节点 ≤800 / 深度 ≤50，到界即停）+ 单次 performClick + 零文件 IO。
3. **反射**：全后台线程 + 强上界（MAX_VISIT=4000 / MAX_DEPTH=6）+ identityHashCode 去重防环。
4. **关页**：`finish()` 在 UI 线程调，try/catch。
5. **去重**：每 Activity 实例 `identityHashCode` 去重；每红包 `nativeurl` 去重。
6. **onHandleMsg**：只廉价判定（群聊闸门 + type/字符串包含）+ 判启用 + 存状态 + 排一个 delay。绝不在热路径开页/反射/遍历/读 SQLite/全文解析。
7. 全程 `try/catch(Throwable)`，任何异常不抛回微信。

## 8. 动作分层（C-ARCH-01）

| 层 | 含义 | 本插件动作 | 要求 |
|---|---|---|---|
| **L3 统计/分析** | 及时性低、可降级 | 红包领完检测、开页、读明细、分档 @ 圈人 | **全 L3 异步可降级**（C-ARCH-02）。绝不在消息线程同步开页/读详情/发消息；热路径压力大/出错可丢当前红包。圈人迟到几秒/几分钟可接受。 |

> 不做任何 L1 破坏性动作（不踢人/不拉黑），不抢红包。

## 9. 反检测注意（留 RP-D 复核）

- **新增暴露面 vs v0.8 spike**：(a) bot **自动开红包封面/详情页**（无用户手动操作）；(b) bot **自动发 @ 消息**（带 `[AtWx=]`）。两者都是"非人为操作"信号，理论上比纯被动观察暴露面高。
- **缓解**：(a) 绝不点领取按钮、不抢包（不改变抢包公平性）；(b) 延迟 5~10 分钟后才开页（等所有人领完，不与真人抢包时序重叠）；(c) 按群启用、默认关，只在指定群活动；(d) 发的 @ 消息走正常 sendText（与 GroupAdmin 同款，已验证不触发风控）。
- **RP-D 待复核**：自动开页 + 自动 @ 消息的频率/时序是否需要进一步抖动；本插件不装新 root app，沿用现有隐藏，无新增隐藏需求。

## 11. 仅处理拼手气红包（v1.0.4）

红包分三种：**拼手气**（金额随机各不同）、**普通/均分**（每人金额相等）、**专属**（指定某人领）。本插件 **只处理拼手气**，普通与专属一律不发任何 @ / 群规消息。

- **专属红包 → 检测阶段直接跳过**（不开页、不排程）：`onHandleMsg` 检测到红包后，从 `wcpayinfo` 取 `exclusive_recv_username`（同 `自动抢红包` 字段语义，用 `hbGetElement(content,"wcpayinfo","exclusive_recv_username")`）。**非空 = 专属红包** → log `skip exclusive` → return，不存运行态、不排 delay。
- **普通红包（均分）→ 反射后跳过**（不发任何消息）：在 `NewDetailUI` 后台反射**已成功读到领取者列表**后、**分档发消息之前**判断。若领取者 **≥2 人 且所有金额（分，`f` 字段）全部相等** → 判为普通红包 → log `skip normal(equal amounts)` → 清状态（`DONE`/`RP_STATE`/`SEEN`/`CUR_NATIVEURL`）+ 关页 + **不发任何消息**。金额有差异（拼手气）→ 正常走 §6 分档 + 发消息。
  - **边界**：领取者 1 人无法判定（普通/拼手气金额都是单值）→ **按正常处理**（继续发；概率极低且无害）。
  - **反射不到列表**：维持原逻辑（关页不重试），**不在此误判**为普通。
- **调试 log（不影响逻辑）**：检测红包时记一行脱敏 `detect-fields`：`exclusive_recv` 是否非空 + `totalnum`/`scenetext`/`type`/`hbtype`/`sceneid` 是否存在及短值/数字（**不记 wxid/sign 原文**），便于以后找"开页前就识别普通红包"的字段。

## 12. 红包提醒排除名单（按群，v1.0.6）

排除名单里的人**永不被红包提醒 @**（即使金额超额），也**不计入 @ 上限人数**（即不影响 `> rp_at_limit` 判定）。

- **存储**：按群 key `rp_exclude_<groupId>`（CSV wxid），getter/setter 仿现有按群配置（`rp_enabled_groups` 同款 parse/join）。
- **@ 增删（用 atList）**：在 `onHandleMsg` 里，当 `sender == getLoginWxid()`（bot 自己）**且** 消息 `getAtUserList()` 非空时：
  - 文本含 `红包排除`（且**不**含"取消") → 把 atList 里的 wxid（排除 bot 自己）加入本群 `rp_exclude_` → `toast("✅ 已加入红包排除名单: N 人")`。
  - 文本含 `取消红包排除` **或** `红包取消排除` → 从本群排除名单移除这些 wxid → toast。
  - **路径选择**：`onClickSendBtn(String text)` 只收到纯文本、**拿不到 @ 目标的 wxid**（WAuxiliary 该回调无 msg/atList 入参），故走 **`onHandleMsg` 路径**。此时 `@某人 红包排除` 这条命令本身会显示在群里（一次性设置动作，可接受）；处理后**不再 sendText 回执到群**（只 toast）。命中即 `return`，不当红包处理。
- **界面管理**：在配置 Dialog（`红包统计设置` / `openSettings`，仅 groupId 非空时）加一块"红包提醒排除名单"，列出本群 `rp_exclude_` 的人（`lookupName` 显示昵称，拿不到显示 wxid 截断），每人一个"移除"按钮（仿 GroupAdmin `_gaRow`）。这是界面配置管理。
- **过滤**：在 `hbTierAndSend` 分档**之前**，把本群排除名单里的 wxid 从领取者列表剔除（不 @、不计入达标总人数 → 也影响 `> rp_at_limit` 判定）。排除名单按 `talker`（群 id）取。

## 13. 反检测硬化（v1.0.6，用户已认可）

1. **随机抖动（去零方差机器指纹）**：所有定时延迟改为 `基准 + Math.random()*抖动`，加 `hbJitter(baseMs, spanMs)` helper（`baseMs + (long)(Math.random()*spanMs)`，BeanShell `Math.random()` 可用）。
   - 首次延迟 / 两次重试间隔：±25%（即 `base*0.75 ~ base*1.25`，实现为 `hbJitter((long)(sec*1000*0.75), (long)(sec*1000*0.5))`）。
   - 封面点击延迟 `RECEIVE_CLICK_DELAY_MS`：原 2000ms → 1500~2800ms 随机。
   - 反射延迟 `REFLECT_DELAY_MS`：原 3500ms → 3000~4500ms 随机。
2. **@ 上限默认 → 10**（原 20）：`DEF_AT_LIMIT = 10`。
3. **同群 @ 限频**：同一群两条红包提醒消息间隔 < `rp_msg_min_gap_sec`（默认 30 秒）则**跳过本次发送**（避免红包雨连发）。内存 `Map<groupId, lastSentMs>` 记每群上次发提醒时间，发送成功后更新。
4. **移除 detect-fields 调试解析**：`onHandleMsg` 里那段 `detect-fields` 调试 log（重复解析 5 个 wcpayinfo 字段）删除，省无谓 XML parse。**专属判定保留**（只解析 `exclusive_recv_username` 一个）。
5. **finish 尽量精确**：`finishActivityByHash` / `finishDetailAndCover` 在 finish 前先确认 `getTopActivity()` 的类名含 `luckymoney`（bot 开的红包页）才 finish，否则不 finish（避免误关用户正在看的非红包页面）。

> **主干不动**：检测主流程、专属过滤、开页启动器、封面三分支（手气/查看领取详情/无入口）、后台反射、结构化提取、重试放弃、`onClickSendBtn` 现有配置命令、秒级延迟单位、ANR 上界 —— 全部不动，仅在上述 §12/§13/§14 指定处增/调。ANR 铁律（§7）不变。

## 14. 措辞随机（v1.0.7，去固定模板指纹）

**用户自定义的「动作核心词」（爆照 / 宣群 / 发视频）与「金额档位」（5/10/20）恒定不变，原样使用；只在它们周围的措辞上随机**，避免每次发同一句话形成可识别的固定模板指纹。

不再对动作文案 / 群规前缀做多变体（撤销 v1.0.6 的 `|` 分隔 + `pickVariant`）。动作 / 前缀 / 档位均为**用户原样录入的单值**。随机仅发生在两处：

- **金额连接词**（带 `{X}`=该档阈值占位）：内置默认变体
  `超过{X}元，` / `过{X}元，` / `过{X}，` / `{X}元以上，` / `领超{X}元，`
- **动作语气包裹**（带 `{A}`=动作核心原文占位）：内置默认变体
  `{A}` / `快点{A}啊` / `记得{A}哦` / `需要{A}` / `该{A}了` / `麻烦{A}一下`

**每行 = 随机连接词（填入阈值） + 随机语气包裹（填入动作原文）**。
例：`过5元，记得爆照哦` / `超过5元，快点爆照啊`。动作核心「爆照」和阈值「5」始终原样，变的只是连接词和语气词。

- **存储**：每档动作单值键（`rp_txt1`/`rp_txt2`/`rp_txt3`，默认 `爆照`/`宣群`/`发视频`）；群规前缀单值键（`rp_rule_prefix`，默认 `领红包请遵守群规执行`）。命令/Dialog 录入什么就原样存什么，不 split、不随机。
- **@ 消息每行（仅第二条 @ 条用，≤ `rp_at_limit`）**：`{@这些人}` + `pickConnector(该档阈值)` + `pickActionPhrase(该档动作原文)`。措辞随机**只作用于第二条**。
- **第一条引用群规条（v1.1.4 改固定简洁，不再随机）**：`rp_rule_prefix(原值)` + 各档 `过{阈值}元{动作核心(原值)}` 拼接（如 `领红包请遵守群规执行，过5元爆照，过10元宣群，过20元发视频`）—— **不调 `pickConnector`/`pickActionPhrase`，无语气词无随机**；`≤ rp_at_limit` 与 `> rp_at_limit` 两种情况都用同一条固定文本。
- **命令配置**：`红包文案1 X`（单个动作核心原值，不再讲 `|` 分隔）、`红包群规前缀 X`（单值原值）直接存原串；Dialog 动作字段说明「动作核心词（原样，周围措辞会自动随机）」。
- **helper**：`pickConnector(threshold)` 从内置连接词变体随机选并填入 `{X}`=阈值；`pickActionPhrase(actionCore)` 从内置语气包裹变体随机选并填入 `{A}`=动作原文。各用 `Math.random()`，异常各自退回安全默认。**仅在 `hbTierAndSend` 发送时调用，不动检测/反射/分档主干。**

## 15. 调度层：队列 + 单工作者（单飞，v1.1.0）

**问题（v1.0.x）**：每个红包各自 `delay(首次延迟, hbProcess)`。红包雨时多个红包并发开封面页 → 互相对撞，全局态（`SEEN` / `CUR_NATIVEURL` / `COVER_ACT`）被踩 → 错乱 / 发错群 / ANR。

**改为单工作者串行驱动**（仿 GroupAdmin `l3FlushLoop` 的自重调度 tick）：

### 15.1 Job 模型
`Job = {nativeurl(=去重 key), talker, sender, msgid, dueAt(ms), attempt}`。内存对象，不落盘。

### 15.2 生产者（`onHandleMsg`，仍廉价）
检测到红包（本群启用、非专属）后：
- `dueAt = now + 首次延迟（cfgDelay 秒 ×1000，±25% 抖动）`，`attempt = 0`。
- **入队**（`RP_QUEUE`，`synchronized`）。**按 nativeurl/去重 key 去重**：若该 key 已在队 / 在飞（`CUR_JOB`）/ 已完成（`DONE`）→ 不重复入队。
- **队列上限 `RP_QUEUE_MAX = 50`**：满了 → 丢弃**最旧**一条 + log，再入新的。
- **不再 per-红包 `delay(hbProcess)`**（彻底移除生产者侧定时）。

### 15.3 单工作者 `rpWorkerTick()`
- `onLoad` 启动一次；每次 tick 末尾 **`delay(RP_TICK_MS=3000, rpWorkerTick)` 自重调度**。整体 `try { … } catch(Throwable) {} finally { 必重调度 }` —— **任何异常都不能让 tick 停摆**。
- 每 tick 逻辑：
  1. **看门狗**：若有在飞 job 且 `now - inflightStartMs > RP_WATCHDOG_MS(30000)` → 强清在飞（`IN_FLIGHT=false; CUR_JOB=null`，log `watchdog`），让卡死的流水线被解开。
  2. **单飞**：若仍有在飞（`IN_FLIGHT==true`）→ `return`（同一时刻只跑一个红包流水线）。
  3. 否则从队列里挑 **`dueAt ≤ now` 中 `dueAt` 最早**的 job：出队 → `IN_FLIGHT=true; inflightStartMs=now; CUR_JOB=job` → `hbProcess(CUR_JOB)` 开封面。队列里没有到期 job → `return` 等下个 tick。

### 15.4 CUR_JOB 归属
把"当前正在处理的红包"上下文从旧的 `CUR_NATIVEURL`（裸字符串）统一成 **`CUR_JOB`（Job 对象，含 nativeurl/talker/msgid/attempt）**。`onResume` hook（路由 / 反射 / 发送 / 关页）全部从 `CUR_JOB` 取归属信息。单飞保证不会被踩；`SEEN.clear()` 只影响当前 job 的页面去重。

### 15.5 退出点必清在飞（否则工作者卡死）
**每一个流水线退出点都必须 `IN_FLIGHT=false; CUR_JOB=null`**：
- 发完提醒成功 / 普通红包跳过（封面"查看领取详情"或反射金额全等）/ `attempt≥2` 放弃 / 任何异常 → **清在飞**（不重新入队，本红包终结）。
- **"没领完"重试**：不再用独立 `delay`，改为 **job 重新入队**（`dueAt = now + 重试间隔(cfgRetry1/2 秒 ×1000，±25% 抖动)`，`attempt+1`）+ 清在飞。由工作者下个到期 tick 再领出来。
- 重试上界仍是 attempt0→retry1、attempt1→retry2、attempt2 放弃（语义不变，只是改走队列）。

### 15.6 ANR / 单飞正确性
- `onHandleMsg` 仍只廉价判定 + 入队（O(1) `synchronized`），不开页/不反射。
- 单飞 → 同一时刻只有一个红包页面流转 → 全局态不再被并发踩。
- 看门狗保证：即使某个流水线异常未清在飞（理论上不该发生，因退出点已全覆盖），30s 后强清，工作者不会永久卡死。

## 16. 发提醒：引用群规条 + @ 条（两条，v1.1.4 终版）

让群里看到提醒是针对哪个红包（引用原红包），同时真 @ 到达标的人。**第一条固定简洁（无随机无语气），第二条随机；两条严格顺序，第二条延迟发以保序。**

- **实测结论（2026-06-05，用户真机）**：
  - `sendQuoteMsg(talker, long localMsgId, content)` 用**本地 msgId**（`msg.getMsgId()`，小数字，已存入 Job；**不是 srvid**）**能成功引用原红包消息**；
  - 但 `content` 里的 `[AtWx=wxid]` 在引用消息里**不会被解析成真 @**（@ 不到人）；
  - `sendText(talker, "[AtWx=wxid] ...")` 的 @ **真生效**（被 @ 者收到提醒）。
  - **结论：引用与 @ 不能一条兼得 → 拆两条发。**
- **发送规则**（达标 0 上面已不发）：
  - **达标 1 ~ `rp_at_limit`** → 发**两条**（严格顺序）：
    1. **第一条（引用群规条，固定简洁）**：`rpSendQuote(talker, CUR_JOB.msgid, 固定简洁群规文本)` —— `sendQuoteMsg` 引用原红包；文本 = `rp_rule_prefix(原值)` + `过{阈值}元{动作核心(cfgTxt 原样)}` 三档拼接（连接词固定 `过X元`），**不调 `pickConnector`/`pickActionPhrase`，无随机无语气词**。
    2. **第二条（@ 条，随机，延迟发）**：第一条发出后 `delay(rp_two_msg_gap_sec × 1000, Runnable)` 再 `rpSendAt(talker, @文本)` —— `sendText` 解析 `[AtWx=]` 真 @ 到人（每档一行，`{pickConnector}{pickActionPhrase}` **仍随机**），**整体开头加 `【查包】` 前缀**（v1.3.1）。隔几秒保证到达顺序。
  - **达标 > `rp_at_limit`** → 第一条照发（固定简洁引用群规条），**v1.3.1 起也发第二条**：第一条发出后同样 `delay(rp_two_msg_gap_sec × 1000, Runnable)` 再 `rpSendAt(talker, notice)`，`notice` = `【查包】本次过{t1}元共{Y}人，人数较多不逐一提醒，请大家自觉。`（`{t1}`=`cfgT1` 元，`{Y}`=`totalQualified`），**无 @、纯文字**。走与 ≤ 上限同一"第一条后隔 gap 秒发第二条"机制（`final` 捕获进闭包，`fire-and-forget`，不进在飞锁）。
- **延迟与顺序/单飞**：
  - `rp_two_msg_gap_sec`（默认 **3** 秒，可配）= 第一条与第二条间隔。
  - 第二条为延迟 `fire-and-forget`：@文本 / talker 用 **final 捕获**进闭包（BeanShell 闭包坑），回调整段 `try/catch`。**不进在飞锁**——主流程（`rpClearInFlight` / `finishDetailAndCover`）发完第一条后照常继续，不等第二条。
- **健壮性回退**：
  - `rpSendQuote`：`msgId > 0` 时 `try sendQuoteMsg(...)`；**抛异常 / `msgId ≤ 0` → catch 回退 `sendText`（发不带引用的同一固定群规文本）**，绝不漏发。
  - `rpSendAt`：始终 `sendText`（@ 由它保证）。引用条失败**绝不影响** @ 条。
- **限频**（§13.3）对"这一次提醒"整体判定一次（在发第一条之前），不把两条算两次。措辞随机（§14）仅作用于第二条 @ 条。
- **历史**：v1.1.0/v1.1.1 曾试图"引用 + @ 同一条"（`rpSend`），实测引用条内 `[AtWx=]` 不解析、@ 未到人，v1.1.2 改为拆两条。**v1.1.4**：第一条改固定简洁（去随机去语气）、第二条延迟 `rp_two_msg_gap_sec` 秒发以保序。**v1.3.1**：两种第二条都加 `【查包】` 前缀、都隔 gap 秒延迟发；`> rp_at_limit` 由"只发第一条"改为"第一条 + 隔 gap 秒发汇总 notice（`【查包】本次过{t1}元共{Y}人…`，无 @）"。即——达标 0 不发；达标 ≥1 都发第一条；第二条 ≤ 上限=`【查包】`逐档 @、> 上限=`【查包】`汇总 notice，两种都带 `【查包】`、都隔 gap 秒。

## 10. 数据存储

- 配置 + 启用名单走 `getString`/`putString`（WAuxiliary KV）。
- 红包运行态（nativeurl→talker/sender/msgid/attempt）存**内存 Map**（红包生命周期短，无需落盘；进程重启丢弃可接受 = 降级）。
- **运行日志（`rp.log`）不落任何昵称/金额/wxid，仍只记计数（脱敏）。**
- **统计明细本地 SQLite（§17）：v1.3.0 已恢复。** 这是**本地数据存储**（与脱敏日志是两回事），DB 里存真实昵称/金额/wxid 是允许的（用于导出）；但 `hbBgLog` 日志**仍只记计数，绝不打明文 wxid/昵称/金额**。§17.5 的嗅探调试日志同样脱敏（只记字段名/长度/计数/目标尾4位）。

## 17. 统计本地 DB（SQLite）— ✅ v1.3.0 已实现

> **状态（v1.3.0）：本节统计 DB 功能已在 v1.2.0 定版之上重新加回并生效。** 写入点严格遵守下面铁律（仅后台反射线程、可降级）。§17.5 新增 3 处嗅探调试日志，真机一验即可探明此前的未知点。
>
> 设计依据：独立 perf review 结论——热路径（`onHandleMsg`）保持干净、不碰 VH-01；统计 DB 写入**只在后台反射线程、拿到领取者明细 + 算出达标人数之后**进行，SQLite 单行 `INSERT OR REPLACE`，整段 `try/catch(Throwable)` 可降级，**绝不影响关页 / `rpClearInFlight` / 发提醒**，失败只 `hbBgLog` 一行（脱敏）。

### 17.1 库与表

- 库文件：插件目录下 `redpacket_stats.db`（路径 = `…/WAuxiliary/Plugin/RedPacketStats/redpacket_stats.db`）。
- 打开方式：`android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(path, null)`（仿 GroupAdmin `l3Db()`）。WAL 在 FUSE 文件系统可能回退 `delete` 模式，无碍（GroupAdmin 经验）。连接懒打开单例，**只允许后台反射线程写**。
- 表 schema（**一红包一行**，便于"每红包一行"导出）：

  ```sql
  CREATE TABLE IF NOT EXISTS rp_record(
      key TEXT,              -- 红包 nativeurl 或 msgid（去重，同一红包只记一次）
      date TEXT,             -- yyyy-MM-dd（本地）
      ts INTEGER,            -- 领完处理时刻 epoch ms
      grp TEXT,              -- 群 id
      group_name TEXT,       -- 群名（v1.3.1；记录时取当前群显示名，取不到留空串或 grp）
      sender_wxid TEXT,      -- 发红包的人 wxid
      sender_name TEXT,      -- 发红包的人【群昵称】（v1.4.0：getFriendDisplayName 群名片/群昵称 → getFriendNickName → wxid；见 §17.1b）
      title TEXT,            -- 红包标题/祝福语（见 17.3）
      qualified_count INTEGER, -- 超最低档（档1阈值）人数（全量，不截断）
      qualifiers TEXT,       -- 超最低档的人明细序列化文本（见 17.4）
      PRIMARY KEY(key)
  );
  ```

### 17.1a 群名 `group_name`（v1.3.1）

- `rp_record` 新增列 `group_name TEXT`（群 id 已有 `grp=talker`）。`CREATE TABLE` 已含该列；对**老库无该列**的情况，`rpDb()` 建表后额外 `try { ALTER TABLE rp_record ADD COLUMN group_name TEXT } catch`（已存在则忽略），保证升级安全（本库尚未在真机建过，CREATE 直接含列即可，ALTER 仅作稳妥兜底）。
- 记录时取**当前群显示名**：在 `rpRecordStats` 里用 `talker`（群 id）查群名。实现仿 GroupAdmin —— 遍历 `getGroupList()`，对每个 info 调 `getRoomId()` 匹配 `talker`，命中则取 `getName()`。取不到 → 存空串（导出时回退用群 id）。整段 `try/catch`，绝不因取群名失败而漏记/抛异常。
- 导出（§18）每行行首带 `[群名]`，便于多群区分。

### 17.1b 发包人昵称 `sender_name` 用群昵称（v1.4.0，bug 修复）

- **现状 bug**：v1.3.x 的 `rpRecordStats` 用 `lookupName(senderWxid, talker)` 取发包人名，在本设备上显示成了 wxid（统计/导出里发包人显示为 wxid 而非群昵称）。
- **修复**：改用 `rpSenderName(senderWxid, talker)`，优先 `getFriendDisplayName(senderWxid, talker)`（群名片/群昵称）→ 取不到退 `getFriendNickName(senderWxid)`（微信昵称）→ 再退 `senderWxid`（wxid 原值）。整段 `try/catch`，绝不抛。
- **领取者昵称不动**：领取者昵称走反射 `d` 字段（`hbExtractTriple`），那条路径正常，本次不改。
- 仅后台反射线程调用（`rpRecordStats` 内），不碰热路径。

### 17.2 记录范围（铁律）

- 每个**有 ≥1 人超过最低档（档1阈值 `rp_t1`）**的红包记一行；**达标 0 人的不记**。
- **超过 at 上限（如 >10 人）也要完整记录**：at 上限只影响"发不发 @ 条"，**不影响 DB 记录**——`qualified_count` 与 `qualifiers` 都是**全量、不截断**。
- `key` 去重：用 `INSERT OR REPLACE`（同一红包只占一行，重跑覆盖）。

### 17.3 红包标题 `title`（在 `onHandleMsg` 提取，存进 Job）

- 从红包消息 `wcpayinfo` 取祝福语/标题。候选字段（取首个非空）：`sendertitle` / `receivertitle` / `scenetext` / `des`——通常是"恭喜发财，大吉大利"那类祝福语文本。
- 提取**只在红包消息上做（低频）**，不影响普通消息热路径。
- 提取出的 `title` 随 `msgid/sender` 一起存进 **Job（`JOB_TITLE` 第 7 槽位）**，后台写 DB 时取用。**v1.3.0：已恢复 `rpExtractTitle` + `JOB_TITLE` 槽，Job 为 7 元组（两处 Job 构造——首次入队、重试重新入队——都带 title）。**
- **存疑（已加嗅探日志，待真机确认）**：到底哪个字段是用户可见的祝福语，需真机确认（不同微信版本字段可能不同）；代码按 `sendertitle→receivertitle→scenetext→des` 顺序取首个非空，取不到留空串。`rpExtractTitle` 命中时会 `hbBgLog` 一行 `stats title: usedField=<字段名> len=<长度>`（脱敏，只记字段名 + 长度，不打原文，见 §17.5①），真机发个红包即知标题取自哪个字段、对不对。

### 17.4 写入点（铁律）

- 位置：`hbDetailExtract` 的**后台反射线程**里，在拿到 `receivers` + 算出"超最低档人数"之后；达标 ≥1 才写一行。
- 该写入**复用 §6 分档已算出的达标信息**（避免重复遍历）：超最低档（`cent > c1`）的人即 §6 三档之和的人；`qualifiers` 序列化为 `昵称|金额元|档次;昵称|金额元|档次;...`（**全部列出，>10 也全列**；档次=1/2/3）。
- **整段 `try/catch(Throwable)`**：失败只 `hbBgLog` 一行（脱敏，只记计数 + 异常摘要），**绝不抛、绝不卡住关页 / `rpClearInFlight` / 发提醒**。
- DB 连接懒打开单例，**只后台线程写**。**绝不在 `onHandleMsg` / `rpEnqueue` / `rpWorkerTick` / UI 线程写 DB。**
- 与 §11 普通红包过滤的关系：普通红包（金额全等）在写 DB 之前已 return，不入 DB（它们也不发提醒，无统计意义）。

### 17.5 嗅探调试日志（v1.3.0，真机一验即探明 3 个未知）

> 全部脱敏（只记字段名 / 长度 / 计数 / 目标尾 4 位，绝不打标题原文 / 明文 wxid / 金额 / 内容）。目的：真机发个红包 + 点一次导出，即可从 `rp.log` 读出三个此前未确认的答案。

- **① 标题字段来源**：`rpExtractTitle` 命中某字段时记 `[RP] stats title: usedField=<sendertitle/receivertitle/scenetext/des> len=<长度>`；全部字段空记 `usedField=none len=0`。`rpRecordStats` 写库时另记 `stats title: writing record, title len=<长度>` 确认该长度带进了本次写库。→ 真机即知标题取自哪个字段、对不对。
- **② DB 建库**：`rpDb()` 打开/建表成功记 `[RP] stats-db opened (<库文件名>)`；失败记 `[RP] stats-db: open fail (degrade, no record this time): <异常>`。→ 真机即知 GroupAdmin 同级目录建库是否可行。
- **③ 私聊送达**：`rpExportToday` 后台线程发完后记 `[RP] export: sent <N> msg to target(尾4位=<尾4位>) (rows=<行数>)`。→ 真机即知导出是否真送到该私聊。
- **④ 群名取到（v1.3.1）**：`rpRecordStats` 取群名后记 `[RP] stats group_name len=<长度>`（只记长度，不打群名原文）。→ 真机即知 `rpGroupName(getGroupList()→getRoomId()→getName())` 是否取到当前群显示名（len>0 即取到）。

## 18. 导出今日红包统计 → 私聊 — ✅ v1.3.0 已实现

> **状态（v1.3.0）：导出按钮与导出逻辑已随统计 DB 一并恢复并生效。** `rp_export_target` 默认 `wxid_REDACTED`（可经命令/Dialog 覆盖）；此为可配置默认值（非密钥），真实采集数据落在本地 DB、不入仓。

- 配置 Dialog（`showConfigDialog`）新增按钮「📤 导出今日红包统计 → 私聊」。
- 点击（UI 事件回调，**非热路径**）：查 `rp_record` 当天（`date=今天 yyyy-MM-dd`，本地）所有行 → 组装文本，**每个红包一行**：
  - 格式（v1.3.1 行首带群名）：`[{group_name}] HH:mm 发红包:{sender_name} 标题:{title} 达标{n}人:{qualifiers}`（`HH:mm` 由 `ts` 格式化；`group_name` 取不到则回退群 id 或留空 `[]`）。
- 私聊发送给目标 wxid `rp_export_target`（默认 `wxid_REDACTED`），用 `sendText(targetWxid, text)`，**放后台线程发**（UI 回调里别阻塞）。
- **长文本分条**：超过单条上限（约 **1800** 字符，留余量）就分多条发（每条若干完整行，不切断单行），避免超微信单消息上限。
- 当天无记录 → `toast("今日无红包统计")`，不发送。
- 全程 `try/catch`，导出是**只读查询 + 发消息**，不影响采集。发完记 §17.5③ 脱敏日志。
- **公开提示**：`rp_export_target` 默认值 `wxid_REDACTED` 是**可配置默认值（非密钥）**，仅作收件目标；真实采集的昵称/金额/wxid 落在本地 DB（不入仓），导出消息内容不写日志。

## 19. 热路径命令闸门顺序优化（v1.1.3，perf review 建议）

- `onHandleMsg` 排除命令闸门（原"红包排除"判定）：把 `content.indexOf("红包排除") >= 0` 提到 `sender.equals(getLoginWxid())` **之前**判断。
- 收益：普通消息（绝大多数不含"红包排除"）**不再每条调 `getLoginWxid()`**（短路在更廉价的字符串 `indexOf` 上）。
- **纯顺序调整、零行为变化**：命中条件仍是"含'红包排除' 且 发送者是 bot 自己 且 atList 非空"，只是 `&&` 子表达式顺序换了一下。

## 20. 每日定时统计 → 私聊（v1.4.0）

每天固定时刻把"过去 24 小时（7 点 - 7 点窗口）各群达标红包"汇总私聊给 `rp_export_target`（默认 `wxid_REDACTED`）。

### 20.1 按群开关 + 迁移默认

- **开关**：`rp_daily_groups`（CSV groupId），独立于红包统计开关 `rp_enabled_groups`。helper：`getDailyGroups` / `isDailyEnabled` / `enableDailyGroup` / `disableDailyGroup`。
- **迁移默认**：用一次性 flag `rp_daily_migrated`。`onLoad` 调 `rpDailyMigrateOnce()`——若 flag 未置，则把 `rp_daily_groups` 初始化 = 当前 `rp_enabled_groups`（现在开了红包统计的群默认打开定时），随后置 flag=`1`；跑过后用户对 `rp_daily_groups` 的增删不再被覆盖。
- **联动开启**：`enableGroup`（`开启红包统计`）时也调 `enableDailyGroup`，把该群加进 `rp_daily_groups`（默认开，可单独关）。

### 20.2 开关命令 / Dialog / 状态

- 命令（`onClickSendBtn` 拦截不发群，toast 反馈）：`开启红包定时` / `关闭红包定时`（对当前群）。
- 设置 Dialog（`showConfigDialog`，groupId 非空时）在"启用开关"下加一个**每日定时开关按钮**，显示/切换本群定时状态。
- `红包统计状态` 显示本群每日定时开关状态。

### 20.3 触发机制（抗重启，不用精确定时器）

- 在 `rpWorkerTick`（每 `RP_TICK_MS`=3s 跑一次）里调 `rpDailyCheck()`（独立 `try/catch`，异常不影响红包流水线）。
- `rpDailyCheck`：配 `rp_daily_hour`（默认 7）。若**当前本地小时 ≥ `rp_daily_hour`** 且 `rp_daily_last_sent`（持久化日期串）**≠ 今天日期** → 先把 `rp_daily_last_sent` 置今天（即使发送出错也不重发刷屏）→ 执行每日发送。
- 效果：**每天只发一次、重启不重发不漏发**（7 点后启动当天补发）。

### 20.4 每日发送内容

- 窗口 = `[今天 rp_daily_hour:00 往前 24 小时（即昨天 hour:00）, 今天 hour:00)`。
- 查 `rp_record` 中 `ts >= windowStart AND ts < windowEnd AND grp IN (rp_daily_groups)`——**用 `ts` 范围查，别用 `date` 列**（窗口跨两个日历日）。`grp IN (...)` 动态拼占位符。按 `ts` 升序。
- **每红包一行**，行首带 `[群名]`（`group_name`，取不到回退 `grp`），格式同手动导出：`[群名] HH:mm 发红包:{sender_name} 标题:{title} 达标{n}人:{qualifiers}`。
- 发给 `rp_export_target`，超长按约 1800 字符分多条（不切断单行）。
- **Header**：`📊 每日红包统计 (MM-dd HH:mm ~ MM-dd HH:mm)`（手动测试标注 `[手动测试]`）。
- **无记录也发一条** header + `(过去24h无达标红包)`（作每日心跳）。
- 整段**后台线程** + `try/catch` 可降级；发完记脱敏日志（条数 + 目标尾 4 位 + 行数）。

### 20.5 手动测试命令

- `红包每日测试`（`onClickSendBtn`，bot 自己）→ 立即按"过去 24 小时（now-24h ~ now）"窗口对 `rp_daily_groups` 跑一次每日发送到 `rp_export_target`。便于不等到 7 点验证。`rpDailyTest()` → `rpDailySend(now-24h, now, manual=true)`。log 一行。

## 21. 轻量 perf 埋点（v1.4.0，为性能分析）

仿 GroupAdmin `perf.log`，用于验证 `onHandleMsg` 没拖慢消息收发。

- **热路径只测不写**：`onHandleMsg` 包装层（`onHandleMsg` 调 `onHandleMsgBody`）入口 `System.nanoTime()`，在 `finally` 里**只做 nanoTime + 顶层整型累加**：`PERF_N`（样本数）、`PERF_SUM_NS`（累计耗时）、`PERF_MAX_NS`（最大耗时）。**不每条写文件**。
- **聚合落盘**：`perfFlush()` 把聚合行写独立 `perf.log`（插件目录，**与 `rp.log` 分开**），行含 `ts/iso/n/ohm_avg_us/ohm_max_us`；写文件放**后台线程**，先快照清零再写。
- **落盘触发**：由 `rpWorkerTick` 定时驱动（每 tick 若 `PERF_N >= PERF_FLUSH_EVERY`=200 则 flush）；热路径仅在窗口异常膨胀（`PERF_N >= PERF_FLUSH_EVERY*50`）时兜底 flush 一次（防计数溢出/失真）。
- 全程 `try/catch`，埋点异常**绝不影响消息**；**绝不把埋点变成热路径负担**（热路径零文件 IO，落盘后台化）。
- `perf.log` 只写耗时/计数，**绝不写 wxid/群名/群ID/消息内容**。

## 22. 包类型：普通包 / 定制包（v1.7.0）

按红包「包类型」用不同档表圈人。两类：**普通包**（默认）/ **定制包**（祝福语命中定制关键字）。**默认全关 → 没开定制的群与今天逐字节一致**。

### 22.1 配置（per-group，见 §3 新增 key）

- `rp_custom_on_<gid>`：定制开关。**仅经 `红包统计设置` Dialog 改**（不提供文字命令开关）。关 → 跳过全部定制逻辑，所有红包按普通包。
- `rp_custom_kw_<gid>`：定制关键字。开启定制后用于判定；空串视为未启用（回退普通）。
- `rp_tiers_custom_<gid>`：定制档表，格式同 `rp_tiers_<gid>`（`阈值|动作;...` 升序，复用现有 tier 存取/解析/校验）。未配置 → 回退普通档表 `getTiers(talker)`。

### 22.2 判定（worker 内，零热路径）

- 在后台 worker（`hbProcess`，title 已在此提取，§17.3）拿到 `title` 后判类型：
  1. 若该群 `rp_custom_on` = 关 → **普通包**。
  2. 否则取 `kw = rp_custom_kw`；`kw`（trim 后）非空且 `title` **包含**（`title.contains(kw)`）`kw` → **定制包**；否则 → **普通包**。
     - **包含而非前缀**（v1.7.1 修正 VH 现象）：真实定制包祝福语形如「【定制】…」，关键字「定制」被【】包裹不在串首，前缀匹配会漏判；用包含匹配命中。关键字由用户配置，2 字以上误命中概率低。
- 将 `isCustom` 透传到发送链路（随 Job 槽位或 `hbTierAndSend` 参数）。
- **`onHandleMsg` 热路径一行不加**（C-PERF-01/03，VH-01 教训）。

### 22.3 选档（其余逻辑全不变）

- `getTiersByType(talker, isCustom)`：`isCustom` → 读 `rp_tiers_custom_<gid>`（未配回退 `getTiers(talker)`）；否则 `getTiers(talker)`。
- `tierOf` 归档、§6/§6.1 分档、排除名单、限频、两条消息（引用群规 + @）机制**全部不变**，仅档表来源按类型切换。

### 22.4 录入（与普通包一致）

- **命令**（`onClickSendBtn`，作用当前群，复用普通解析器 + 逐行校验 + 不破坏原表，仅多一个 type/key 形参）：
  - `定制阈值 5 10 20` ↔ `红包阈值`
  - `定制文案K 文字` ↔ `红包文案K`
  - `定制增加档 <阈值> <动作>` / `定制减少档` ↔ `红包增加档` / `红包减少档`
  - `定制关键字 <kw>`：设当前群 `rp_custom_kw`
- **`红包统计设置` Dialog**：加「定制」区块，与普通区块同构——
  - ☑ **是否开启定制**（`rp_custom_on`，**仅此处可改**）
  - 📝 **定制关键字** EditText（`rp_custom_kw`）
  - 📋 **定制档表** 多行 EditText（每行「阈值 动作」，与普通档表编辑器同款逐行解析校验），保存时一并写。
- **`红包统计状态`**：增显 定制开关 / 定制关键字 / 定制档表。

### 22.5 兼容 / 范围外

- 默认全关 → 完全向后兼容；改动落在 worker + 发送 + 命令 + Dialog，全程 `try/catch`。
- **范围外**（已与用户确认）：不自动识别好友/恶搞/怪包（按普通包，个别用 `红包排除`）；不查「写清」关键词降级；不算照片张数；不退款/不投诉/不踢人（纯提醒圈人）。

### 22.6 定制包第一条群规文本（v1.7.1）

- 普通包第一条（引用群规条，§6.1）= `cfgRulePrefix()`（`rp_rule_prefix`，默认「领红包请遵守群规执行」）+ 各档 `，过{阈值}元{动作}`。
- **定制包第一条** = `cfgCustomRulePrefix()`（`rp_custom_rule_prefix_<gid>`，默认「**定制包请按要求执行**」）+ 各档 `，过{阈值}元{动作}`（档动作取**定制档表** `getTiersByType(talker,true)`）。
  - 例（定制档 5爆照/10爆照宣群/20宣群+定制）：`定制包请按要求执行，过5元爆照，过10元爆照宣群，过20元宣群+定制`。
- **第二条（@ 条）前缀（v1.7.2）**：普通包仍 `【查包】`；**定制包 → `【查包】定制包`**（`checkPrefix = isCustom ? "【查包】定制包" : "【查包】"`，≤上限的逐档@条与 >上限的汇总 notice 两种都用此前缀）。逐档 @ 达标者、档表/文案来自定制档表，其余机制不变（§6.1）。
- 选用哪条前缀**仅取决于 `isCustom`**（已在 worker 判定并随 Job 透传），与普通包共用同一发送骨架（限频/排除/两条消息/ANR 一行不动）。
- 配置：命令 `定制群规前缀 <文字>`（作用当前群，仿 `rp_rule_prefix` 的设定方式）+ `红包统计设置` Dialog 定制区块加一项「定制群规前缀」EditText；`红包统计状态` 增显。
