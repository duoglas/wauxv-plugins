# RedPacketStats SPEC（正式版 v1.0）

> 插件行为规格（C-PLUGIN-03 要求）。**改行为先改本文件，再改 `main.java`。**
> 当前版本：**v1.0.6** — v1.0.4 起仅处理拼手气红包（见 §11）。v1.0.6 新增**红包提醒排除名单（按群）**（§12）+ **反检测硬化**（§13）。从 v0.8 spike（检测→开封面→点"看看大家的手气"→反射 NewDetailUI，全真机验证通过）升级为正式插件。

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
| `rp_at_limit` | 需提醒的达标总人数上限：超过则改发**无 @ 通用群规消息** | 10（v1.0.6，原 20）|
| `rp_exclude_<groupId>` | 按群红包提醒排除名单（CSV wxid，§12） | 空 |
| `rp_msg_min_gap_sec` | 同群两条红包提醒最小间隔（秒，§13.3） | 30 |

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
  - `红包统计状态` → toast 显示当前本群启用状态 + 全部配置（含 `rp_at_limit`），不发群

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

### 6.1 达标总人数超 `rp_at_limit`（默认 20）→ 改发无 @ 通用群规消息

- **达标总人数** = 金额严格 `> rp_t1` 的所有领取者人数（三档之和，即上面会被圈进任意一档的人数）。
- **≤ rp_at_limit**：走 §6 现有逻辑（一条消息、按档分组、`[AtWx=]` 单独 @）。
- **> rp_at_limit**：发**一条通用群规消息，无任何 @**，拼三档规则，格式：
  ```
  领红包请遵守群规执行，过{rp_t1}元{rp_txt1}，过{rp_t2}元{rp_txt2}，过{rp_t3}元{rp_txt3}
  ```
  一条 `sendText` 到该群（talker）。
- **达标 0 人** → 不发（维持现状）。

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
- **@ 消息每行**（≤ `rp_at_limit`）：`{@这些人}` + `pickConnector(该档阈值)` + `pickActionPhrase(该档动作原文)`。
- **通用群规消息**（`> rp_at_limit` 时发，无 @）：`rp_rule_prefix(原值)` + 各档 `pickConnector(阈值)+pickActionPhrase(动作)` 拼接（如 `领红包请遵守群规执行，过5元记得爆照哦，超过10元宣群，10元以上该发视频了`）—— 前缀和各动作核心恒定，连接词/语气随机。
- **命令配置**：`红包文案1 X`（单个动作核心原值，不再讲 `|` 分隔）、`红包群规前缀 X`（单值原值）直接存原串；Dialog 动作字段说明「动作核心词（原样，周围措辞会自动随机）」。
- **helper**：`pickConnector(threshold)` 从内置连接词变体随机选并填入 `{X}`=阈值；`pickActionPhrase(actionCore)` 从内置语气包裹变体随机选并填入 `{A}`=动作原文。各用 `Math.random()`，异常各自退回安全默认。**仅在 `hbTierAndSend` 发送时调用，不动检测/反射/分档主干。**

## 10. 数据存储

- 配置 + 启用名单走 `getString`/`putString`（WAuxiliary KV）。
- 红包运行态（nativeurl→talker/sender/msgid/attempt）存**内存 Map**（红包生命周期短，无需落盘；进程重启丢弃可接受 = 降级）。
- **不落任何昵称/金额/wxid 到文件**。
