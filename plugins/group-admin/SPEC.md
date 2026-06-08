# GroupAdmin SPEC

> 插件行为规格（C-PLUGIN-03 要求）。**改行为先改本文件，再改 `main.java`。**
> 状态标记：`[现状]` = 当前 main.java 已实现；`[目标]` = 按 constraints 待重构，尚未落地。

## 1. 概述

- 名称：GroupAdmin（群管理）
- 当前版本：v1.18.0（`main.java`，BeanShell；T1 埋点 + T2 内存缓冲 + T2′ SQLite 落盘；v1.18.0 非文本消息也计入发言活动）
- 宿主：WAuxiliary（`me.hd.wauxv`），通过 `onHandleMsg` 等回调 hook 微信 `com.tencent.mm`
- 职责：群消息驱动的群管理——踢人 / 黑名单 / 警告 / 潜水清理 / 白名单 / 全局名单 / 三层权限
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
| **L1 管理动作** | 破坏性 / 需即时可靠生效 | 踢人（`#踢潜水`/`踢潜水`）、警告、加黑名单（`添加黑名单 @xx`/`addgb`）、严格模式、开启/关闭群管、保护名单写入 | 必须可靠执行；失败要有反馈（如无 bot 权限提示）；优先级最高 |
| **L2 用户交互** | 查询 / 响应，需要及时但无破坏性 | 各查询（黑名单/白名单/管理员/警告名单/保护名单/潜水名单/群管状态/群管群列表）、帮助、`群管设置`/`#群管设置` Dialog、`#群权限自检` | 及时响应；可同步但要轻；重计算（如 native cursor 扫群成员）仅在该命令触发时执行，不进普通消息路径 |
| **L3 统计/被动追踪** | 及时性低、可降级 | `recordSpeak`（潜水发言时间 `lsg_`）、first_seen（`fsg_`）维护 | **必须异步化 + 可降级**（C-ARCH-02）；绝不在消息线程同步做全量持久化；热路径压力大/出错时可丢弃当前批次 |

> **活动判定 = 任何消息类型（v1.18.0 修复）**：「冒泡 / 不潜水」的判据是**在群里发过任意消息**——文本、引用回复、**图片、表情、语音、视频、红包、文件、位置**等一律算活跃。`recordSpeak` 必须覆盖所有非空 sender 的群消息，**不能只记带可读文本的消息**。v1.17.2 及以前的 bug：非文本且 `getContent()` 为空的消息（图片/表情/语音/红包）在入口 isText 闸门处被早退（见 §6.10），走不到 `recordSpeak` → 只发非文本的人 `last_speak` 永远为空 → 被 `#潜水 N` 误判为「从未发言」列入潜水名单。

> 关键认知：潜水**清理**（踢潜水）是 L1，但潜水**数据采集**（recordSpeak）是 L3。VH-01 的错误正是把 L3 采集同步压在了热路径上。

## 4. 命令清单（按权限）

权限层级：`super_owners` > `super_admins` / 原生群主(`nat_owner_`) > 群 `admins_`。判定：`isOwner` / `isAdmin` / `isAuthorized`。非授权者发命令一律静默忽略（v1.9 起，防探测）。

### 4.3 警告自动踢上限（每群可调，默认 10）

- 默认上限 **10**（无自定义的群用之）。常量 `WARN_AUTO_KICK` 仅作默认值，**不再直接用于显示/判定**。
- per-群持久化 key：`wk_<groupId>`（fastkv/config.prop）。getter `getWarnKick(groupId)`：有自定义值则用之并**夹取到 1~99**，否则返回默认 10。setter `setWarnKick(groupId,n)` 同样夹取 1~99 后写入。
- **所有警告显示与踢判定一律用 `getWarnKick(本群)`**（`doWarn` 显示 n/X + 踢判定 `n>=X`、`doWarnDec`、`showWarnList`、`doWarnDetail`、Dialog 警告名单行）。无群上下文的全局帮助（`openSettings`）显示"默认 10，各群可调"。
- 改阈值入口：(a) 文字命令 `警告上限 N`（admin+）；(b) 设置 Dialog（`showGroupConfigDialog`）中一个数字输入控件（仿默认潜水天数控件）写 `wk_<group>`。
- 边界：N 为 0/负/超大/非数字 → 夹取到 1~99 或拒绝并提示，不得除零/永不踢/秒踢。
- **满上限自动踢出后清零（`doWarn`，L1）**：警告累计到本群上限（`n >= getWarnKick(本群)`）触发自动移除时：
  - 无群管权限（`botPrivState=="none"`）→ 发"机器人无群管权限, 未能踢出"，**保留计数**（不清零），待有权限后下次警告即踢。
  - 有权限 → `boolean kicked = tryKickSilent(...)`；**仅当 `kicked==true`（踢成功）才清零该成员警告**：`setWarn(...,0)` + `delWarnList(...)`（并清 `wnt_`/`wnf_` 记录），使其重新进群后从 0 重新累计。踢失败（含权限丢失）→ **保留计数**，按"未能踢出"提示。
  - 语义关键：踢成才算"踢完清零"，没踢成不清零。

---

- 开关（仅 owner）：`开启群管`/`启用群管`、`关闭群管`/`禁用群管`
- 查询（admin+）：`黑名单`(每项带序号【01】)、`管理员`、`警告名单`、`保护名单`、`白名单`、`全局黑名单`、`全局白名单`、`群管状态`、`群管群列表`、`帮助 群管`/`群管帮助`
- 黑名单（admin+，L1，**v1.16 显式命令**）：`添加黑名单 @xx [@yy...]`(加黑+踢，复用 doBan，可 @ 多人)、`去除黑名单 @xx [@yy...]`(@ 路径移除)、`去除黑名单【N】[【M】...]`(按 `黑名单` 列表 1-based 序号移除，容错 `【】`/`[]` 全半角 + 前导 0)。旧入口 `addsb`/`delbk`/`@TA 拉黑`/`@TA 黑名单` 保留识别但只回引导提示，不再直接增删名单。
- 管理（admin+，L1）：`addwt`/`delwt`(白名单)、`addgb`/`delgb`(全局黑)、`addgw`/`delgw`(全局白)、`清除警告名单`、`严格模式 [on/off]`、`警告上限 N`(设本群警告自动踢上限，夹取 1~99)
- 潜水：`#潜水 N`/`潜水`(列名单 L2)、`#踢潜水`/`踢潜水 1,3,5-8`(批量踢 L1)
- 自检/设置：`#群权限自检`、`群管设置`/`#群管设置`（弹 Dialog，`onClickSendBtn` 拦截）

### 4.1 @TA 动作（admin+，目标=被 @ 者）

`@TA 踢/请 · 警告 · 警告-1/警告减1 · 清零 · 警告详情 · 保护/取消保护 · 白名单/移除白名单 · 管理员/移除管理 · 群主/移除群主 · 微信群主/移除微信群主`。动作关键词为消息**最后一个 token**。`管理员/移除管理/群主/移除群主/微信群主/移除微信群主` 为 ownerOnly。

> **v1.16**：`@TA 拉黑`/`@TA 黑名单`（含引用回复路径）仍被 `isActionTok` 识别（保证过 isAuthorized 闸门），但 `dispatchAction` 中不再调 `doBan`，改回引导提示 → 拉黑必须用显式命令 `添加黑名单 @xx`。这样既防误触又不破坏权限/防探测语义。

### 4.2 引用回复触发管理动作（admin+，目标=被引用消息发送者）

- **消息形态（真机 [QDIAG] 实测确诊，2026-06-05）**：微信引用回复在 `onHandleMsg` 里**不是文本**——`isText()=false`、`getType()=822083633`（appmsg）、`getContent()` 返回**整段 XML**（~1200 字符）：`<msg><appmsg><title>回复文本</title>…<refermsg><chatusr>被引用者wxid</chatusr>…</refermsg></appmsg></msg>`。管理员实际打的动作词（如『警告』）在 **`<title>`** 里，**不在** `getContent()` 的末尾 token（末尾是 XML 碎片）。
- **入口（按 isText 二分）**：在『无 @』分支里按 `isText()` 分流：
  - `isText()=true`（普通文本）：维持原行为——`content.split` 取最后一个 token，末尾非动作词即 return（无 @ 无引用 → 无 target → return）。**不调 `getQuoteMsg()`、不解析 XML。**
  - `!isText()`（appmsg）：`q = msg.getQuoteMsg()`；`q==null`（链接/文件/图片等非引用 appmsg）→ 安全 return；否则用正则 `<title>(.*?)</title>`（非贪婪 + DOTALL）从 `getContent()` 提取回复文本，取其**最后一个 token**（按 `[\s  ]+` split，与 @ 路径一致）作为 `actionTok`，`isActionTok(actionTok)` 判定；非动作词 → 安全 return（引用了但回复词不是动作，不打扰）；提取不到 `<title>` 也安全 return（不崩）。
- **目标**：`target = q.getSendTalker()`（被引用消息发送者 wxid，实测 19 字符有效），防 null/空。
- **权限不绕过**：引用触发的破坏性动作（踢/拉黑/警告等）走与 @ 路径**完全相同**的 `isAuthorized` + `canActOn` 校验（抽象出 `dispatchAction(groupId, sender, target, actionTok)`，目标既可来自 @ 也可来自引用）；非授权者引用发动作**静默拒绝**（与 @ 路径一致，防探测）；对受保护对象/原生群主引用动作被 `canActOn` 拒绝（含 ownerOnly）。
- **热路径红线（C-PERF-01）**：`getQuoteMsg()` **只在 `!isText()` 时调用**；普通文本聊天（`isText()=true`）**绝不调用 `getQuoteMsg()`、绝不解析 XML title**，走原有文本路径（@ 动作 / 文本命令 / 末尾非动作词 return）。
- **警告上限**：见 §4.3。

## 5. 数据存储（fastkv / config.prop）

群级 key（`<前缀><groupId>`）：`bl_`(黑名单) `wt_`(白名单) `protected_`(保护) `admins_`(管理员) `nat_owner_`(原生群主) `wl_`/`wn_`/`wnf_`/`wnt_`(警告：列表/计数/时间) `bp_`(bot权限状态) `lsg_`(最后发言) `fsg_`(首次见) `pending_kick_`/`pending_kick_ts_`(待踢暂存)

全局 key：`bl_global` `wt_global` `enabled_groups` `strict_mode` `super_admins` `super_owners` `default_inactive_days`

存储问题与要求：
- `[历史]` `lsg_<group>` / `fsg_<group>` 曾是 config.prop 整群 CSV（`wxid|ts,...`），单群最大已见 ~500 人 16862B，config.prop 总 66KB。CSV 读写复杂度 O(群人数)，每条消息全量读改写 = VH-01 根因。
- `[现状]`（T2′ 落地，C-PERF-02）`lsg_`/`fsg_` 已从 config.prop CSV **pivot 到自有 SQLite 库**，结构性消除单 key 体量与 O(N) 全量序列化问题：
  - 库文件：`…/WAuxiliary/Plugin/GroupAdmin/groupadmin.db`（与 config.prop 同目录，**不入 git**，已加 `.gitignore *.db`）。
  - 表：`speak(grp TEXT, wxid TEXT, last_speak INTEGER, first_seen INTEGER, PRIMARY KEY(grp,wxid))` + 索引 `idx_speak_grp_last ON speak(grp,last_speak)`（踢潜水 `WHERE grp=? AND last_speak<?` 直查）。
  - 模式：`PRAGMA journal_mode=WAL`（**必须走 `rawQuery`，`execSQL` 会因该语句返回行而抛异常**）+ `PRAGMA synchronous=NORMAL`（可 `execSQL`）。
  - **连接单例**：顶层缓存一个 `SQLiteDatabase L3_DB`，懒打开（onLoad 后台 delay 或首次 flush），`onUnload` close 并置 null（重载后 onLoad 重新懒开，不搞 epoch）。`SQLiteDatabase` 自身线程安全（内部锁），后台 flush 与命令读共享同一实例。
  - 行级 UPSERT：单行写 27.7ms（不可放热路径，故仅后台批量）、批量 500 行/事务 34–54ms（后台可接受）、查询 800µs、open+建表 126ms（一次性）——真机 spike 实测（2026-06-05）。
- **RB-2（分片）被 DB 行级存储替代**：SQLite 行级存储天然无单 key 体量问题，不再需要桶分片。
- **裁剪非必需**：DB 行级存储无 config.prop 单 key 膨胀问题，本任务不做裁剪；且 `first_seen` 与 `last_speak` 同行，`DELETE WHERE last_speak<?` 会连带丢 `first_seen`，裁剪需谨慎设计，留作后续。
- `pending_kick_`/`pending_kick_ts_` 等非潜水时间数据**仍留 config.prop**（YAGNI，只迁 `lsg_`/`fsg_`）。

## 6. 消息热路径（onHandleMsg）

`[现状]` 流程：取 content → 非文本/非群聊 return → 命令匹配（L1/L2，匹配即处理并 return）→ 普通消息走 `recordSpeak`（**同步全量 CSV 读改写，VH-01**）。

`[现状]`（T2 落地，C-PERF-01/03 + C-ARCH-02/03）：
1. 最廉价判定：非文本/非群聊/空 → 立即 return
2. 命令判定走廉价前缀匹配；命中 L1/L2 才进对应处理
3. 普通消息的 L3 采集（`recordSpeak`）只做**内存入队**：在 `synchronized(L3_LOCK)` 内把 `(groupId,wxid,now)` 写入两个顶层脏增量 Map（`L3_dirtyLs` 记 last_speak、`L3_dirtySeen` 记 first_seen 候选），O(1) put 后立即返回。**绝不在 recordSpeak 里调 getString/putString / 碰磁盘**。
4. 后台定时（默认 30s，常量 `L3_FLUSH_MS`）用 `delay()` 自我重调度形成循环；每轮先在 `L3_LOCK` 内**原子换出**脏增量（swap 成新空 Map），再在 `FLUSH_LOCK`（独立于 L3_LOCK 的 flush 串行化锁）内对换出的增量做 **SQLite 批量 UPSERT**：`beginTransaction()` → 用 **compiled `SQLiteStatement`** 对每条增量执行 `INSERT ... ON CONFLICT(grp,wxid) DO UPDATE SET last_speak=max(last_speak,excluded.last_speak)`（`last_speak` 取较新）；`first_seen` 用 `min(first_seen,excluded.first_seen)`（**绝不改晚**，已有更早的 first_seen 不被覆盖）→ `setTransactionSuccessful()` → `endTransaction()`。DB 写异常 per-batch try/catch，记 plugin.log，不中断 flush 循环（finally 仍重调度 delay）；失败累加 `L3_flushFailStreak`（T4 用）。flush 体被 try/catch(Throwable) 包裹，finally 无条件再 `delay()` 下一轮。flush 顺带触发 `perfFlush()`（C-PERF-03 热路径零磁盘 IO）。**FLUSH_LOCK 仅串行化 flush（换出+写DB+提交），绝不被 recordSpeak 获取**——热路径只用 L3_LOCK，永不碰 FLUSH_LOCK/DB。
5. **flush-before-read（关键不变式）**：因最新发言可能仍在内存未落盘，凡需要读最新 `lsg_/fsg_` 的命令路径在查 DB **之前先调一次同步 `l3Flush()`**（把内存增量提交进 DB 再查）。覆盖点：`#潜水 N`（`doShowInactive`）、`群管设置` Dialog（`baselineExists` ~2159）、`#群管设置` 群内 Dialog（`baselineExists` ~2731）。`l3Flush()` 持 `FLUSH_LOCK`，读者会等在途后台 flush 提交完再返回，保证随后的 DB 查询读到已提交数据。`#踢潜水`（`doKickInactive`）只读冻结的 `pending_kick_`（config.prop），**不读 lsg_/fsg_**，无需 flush。
6. **onUnload 补 flush**：卸载/重载前同步 `l3Flush()` 一次内存增量落盘（防丢，RISK-4），并保留 T1 的 `perfFlush()`；随后 close `L3_DB` 并置 null。
7. **onLoad 一次性迁移 CSV→DB（不可逆）**：用 config key `l3_sqlite_migrated` 守卫只迁一次，放在 onLoad 后台 delay 块（不阻塞加载）。遍历有 `lsg_`/`fsg_` 的群（`getEnabledGroups()`），`parseTimeCsv` 读旧 CSV → 批量 UPSERT 进 DB（`last_speak`/`first_seen` 取各自 CSV 值）。迁移后**校验**：DB 行数 / 该群 first_seen 数 与旧 CSV 条目数一致，记 plugin.log；校验通过才清旧 key（`putString("lsg_"/"fsg_"+g, "")`）并置 migrated；校验不过保留旧 key、记错误、不置 migrated（便于回滚，T0 已备份 config.prop）。
8. `[现状]`（T4 落地）：降级已实装——缓冲上界 / flush 连续失败两触发器，`L3_DEGRADED` 仅挡 `recordSpeak` 采集入口，详见 §7。
9. **引用动作的热路径红线（W2，C-PERF-01）**：真机 [QDIAG] 实测确诊——引用回复是 **appmsg（`isText()=false`、`getType()=822083633`）**，`getContent()` 是整段 XML，动作词在 `<title>`。因此引用路径的入口判据**改为 `!isText()`**，而非"末尾 token 是动作词"（XML 末尾是碎片，永远不是动作词，旧逻辑在此 return → 引用动作完全不触发，即 W2 的 bug 根因）。判定顺序（无 @ 分支按 `isText` 二分）：
   - `isText()=true`（普通文本，占绝大多数）：维持原样——`content.split` 取 lastTok → `isActionTok(lastTok)` 纯字符串判定 → 末尾非动作词即 return（recordSpeak 内存入队已在更早完成）。**绝不调用 `getQuoteMsg()`、绝不解析 XML title**，热路径成本与改造前一致。
   - `!isText()`（appmsg，频率远小于普通聊天）：`q = msg.getQuoteMsg()`（**仅在此一次调用**）；`q==null` → return；否则正则提取 `<title>` → 取末尾 token 作 `actionTok` → `isActionTok` 判定。`getQuoteMsg()` + XML 正则只发生在非文本消息上，不影响 `rs_avg_us`/`ohm_avg_us`（真机 perf.log 验证）。
10. **isText 闸门（W2，WRISK-1）**：`onHandleMsgBody` 入口对非文本消息**零额外成本放行**——`isText()` 通过照常继续（普通文本路径不变、不调 getQuoteMsg）；`isText()` 不通过时再看 `getContent()` 是否非空（引用回复 appmsg 带 XML 文本），非空才继续走后续判定（引用路径在无 @ 分支的 `!isText` 子分支里调 `getQuoteMsg()` 取 title）。普通文本消息在第一个分支即通过，**永不为非文本消息额外调用 getQuoteMsg()**。（W2 修复后已移除临时探针 `[QDIAG]`/`[QUOTE-PROBE]`。）
    - **活动采集前置（v1.18.0 修复）**：在「`!isText()` 且 `getContent()` 空 → return」这个早退点**之前**，必须先把这条消息记为一次发言活动——取 `groupId=getTalker()` / `sender=getSendTalker()`，`sender` 非空且 `isGroupEnabled(groupId)` 时调一次 `recordSpeak(groupId, sender)`，再 `return`。这样图片/表情/语音/红包等无文本消息也计入 `last_speak`，不再被误判潜水。**热路径红线不变（C-PERF-01）**：只调 `getTalker/getSendTalker/isGroupEnabled`(缓存)`/recordSpeak`(O(1) 内存写)，**绝不调 `getQuoteMsg()`、绝不解析 XML**；整段 try/catch 包裹，异常只跳过采集、绝不抛入消息处理；`L3_DEGRADED` 期间 `recordSpeak` 自身照旧丢弃（§7）。文本/appmsg-有内容 的消息仍走原 ~2188 行那次 `recordSpeak`，**不重复记**（两条路径互斥：空内容非文本在此早退，其余走主路径）。
5. `[现状]`（T1 落地，C-PERF-04）：onHandleMsg 入口埋点已实现。热路径只做 `nanoTime` 取值 + 顶层内存计数器累加（样本数、onHandleMsg 累计/最大耗时、recordSpeak 累计/最大耗时、命令分支/普通分支计数），不每条消息写文件或 plugin.log（避免埋点自身变成热路径开销，RISK-5）。每累计 `PERF_FLUSH_EVERY`（默认 200）条消息聚合落盘一行到独立 `perf.log`（与 plugin.log 分离），落盘后清零窗口计数进入下一窗口。所有埋点逻辑被 try/catch 包裹，任何异常只跳过埋点、绝不抛入消息处理。
   - `perf.log` 路径：插件目录 `…/WAuxiliary/Plugin/GroupAdmin/perf.log`。
   - 行格式（空格分隔，可 grep/awk 聚合）：`ts=<epochMs> iso=<yyyy-MM-dd HH:mm:ss> n=<样本数> ohm_avg_us=<onHandleMsg平均微秒> ohm_max_us=<最大> rs_n=<recordSpeak调用数> rs_avg_us=<recordSpeak平均> rs_max_us=<最大> cmd=<命令分支数> normal=<普通消息分支数>`。
   - 脱敏：perf.log 只含耗时/计数，绝不写 wxid / 群名 / 群 ID / 消息内容。
   - 用途：拿 T2 改造前 recordSpeak（同步 O(N) CSV 读改写）的真实基线耗时，改造后用同一字段 `rs_avg_us`/`rs_max_us` 对比验证去 O(N) 效果。

## 7. 降级策略（C-ARCH-02）

`[现状]`（T4 落地，C-ARCH-02 / RB-4）：L3（潜水采集/统计）实装两个**稳健触发器**（刻意避开易误触发的 timing 触发器），出问题优先降级、保收发，绝不反向影响 L1/L2。

**触发器（满足任一即进入降级，顶层 `L3_DEGRADED=true`）：**

1. **内存缓冲上界**：维护脏增量总条数的 O(1) running counter（`L3_dirtyCount`），`recordSpeak` 写时 `+1`、`l3Flush` 换出时清零，全在 `L3_LOCK` 内增减——热路径不做 O(N) 求 size。当总条数 ≥ `L3_BUFFER_CAP`（5000）→ 进入降级，`recordSpeak` 丢弃新增量（不再 put），保护内存。
2. **flush 连续失败**：`L3_flushFailStreak` 达 `L3_FAIL_CAP`（5，即 DB 持续打不开/写不进约 2.5 分钟）→ 进入降级，暂停采集，避免 DB 不可用时内存白涨。

**降级期采集行为**：`recordSpeak` 入口廉价判断 `if (L3_DEGRADED) return;`——开销仅一个布尔读 + 判断，O(1)，不引入其它成本。降级期丢弃的只是 L3 潜水发言时间采集（可降级，符合 §3/§7 取舍）。

**恢复（自动解除）**：在 `l3Flush` 一轮成功（`L3_flushFailStreak` 归零）**且**换出后缓冲已排空到低水位（`L3_dirtyCount < L3_BUFFER_CAP/2`，即 < 2500）时自动解除 `L3_DEGRADED=false`，`recordSpeak` 恢复采集。

**留痕（C-ARCH-02 要求）**：进入/解除降级各往 `perf.log` 写一条标记行，含原因（`buffer_cap` / `fail_streak`）、当时缓冲条数 `dirty`、`fail_streak`。标记行**绝不含 wxid / 群名 / 群ID / 消息内容**。事后可从 perf.log 看出降级发生过及原因。格式示例：
```
ts=... iso=... L3_DEGRADE enter reason=fail_streak dirty=123 fail_streak=5
ts=... iso=... L3_DEGRADE exit dirty=0 fail_streak=0
```

**红线（C-ARCH-02）**：降级标志只在 `recordSpeak` 采集入口起作用。L1（踢/警告/拉黑）、L2（查询/潜水名单/踢潜水）的命令处理、flush-before-read、DB 查询**永不因降级受影响**——`L3_DEGRADED` 不出现在任何命令路径，flush-before-read 仍照常 `l3Flush()`。

### 已知限制（Santa 第2轮，已评估接受，不阻断）

- **B#7**：`rebuildBaselineForce` 清基线与重建之间若并发后台 flush，个别 `first_seen` 可能被填成发言 ts（UPSERT 取 `min`），与"统一重置为 now"有亚秒级偏差。方向是宽限更短（旧 ts < now），不致误踢，可接受。
- **B#8**：`onLoad` CSV→SQLite 迁移仅遍历 enabled 群；迁移时 disabled 的群其旧 `lsg_/fsg_` CSV 不迁移，日后启用会走冷启动良性重建基线（不踢人），仅丢历史发言记录。后续可改为按群粒度迁移消除该缺口。

**并发正确性（T2′，SQLite 方案，C-PLUGIN-03）：**

- **行级 UPSERT 结构性消除整群 RMW lost-update**：旧 CSV 方案是「读整群 CSV→改一条→写回整群」，两线程并发会互相覆盖（后台 flush vs 命令线程 → 丢 first_seen → 误踢活跃用户，Santa NAUGHTY 的根因）。SQLite 行级 `INSERT ... ON CONFLICT DO UPDATE` 只触碰目标行、`first_seen=min(...)`/`last_speak=max(...)` 是单调合并，结构性消除了整群读写的 lost-update 窗口。
- **`FLUSH_LOCK`（仅串行化 flush）**：把「换出脏增量 + 写 DB + 提交」整段 `l3Flush` 串行化，使后台 flush 与命令路径的 flush-before-read 不会并发写、且读者的 `l3Flush` 会等在途 flush 提交完、并把当前内存增量提交进 DB 后再返回。**注（Santa A#3 校正）**：flush-before-read 的正确性**不靠** FLUSH_LOCK 串行化"保证随后查询读到已提交"（随后的 `query` 在 FLUSH_LOCK 外，期间可能又有新 flush），**而是靠 UPSERT 的单调语义**（`last_speak=max(...)`/`first_seen=min(...)`）——任何并发顺序都只会让值更新得更不误踢，不丢已提交的较新发言。`SQLiteDatabase` 内部锁额外保证多线程读写安全。
- **热路径零锁外开销**：`recordSpeak` **只用 `L3_LOCK` 写内存增量，绝不获取 `FLUSH_LOCK`、绝不碰 `L3_DB`**（普通消息永不阻塞在 flush 锁或 DB 上，保 C-PERF-03）。
- **锁序固定为 FLUSH_LOCK 外、L3_LOCK 内**（仅 `l3Flush` 在 FLUSH_LOCK 内做一小段 L3_LOCK 换出；`recordSpeak` 只持 L3_LOCK，永不持 FLUSH_LOCK）→ 无环、无死锁。
- 读点全部改为 DB 查询：`doShowInactive`、`recordFirstSeen`、`initFirstSeenBaseline`、`rebuildBaselineForce`、两处设置 Dialog 的 `baselineExists`、onLoad 基线判空。其中命令路径需最新数据的读点（`doShowInactive` + 两处 Dialog）读前先 `l3Flush()`（flush-before-read）。`doKickInactive` 只读 config.prop 的 `pending_kick_`，不改。

## 8. 已知问题 / 重构待办

| 编号 | 问题 | 约束 | 优先级 |
|---|---|---|---|
| RB-1 | `recordSpeak` 每条消息同步全量 CSV 读改写（VH-01）。**T2 已落地**：热路径仅内存入队（`L3_dirtyLs`/`L3_dirtySeen`，O(1)）+ 30s 后台批量 flush（`l3Flush`/`l3FlushLoop`）+ flush-before-read + onUnload 补 flush；perf 落盘也搬到后台。 | C-PERF-01, C-ARCH-02 | ✅ |
| RB-2 | `lsg_/fsg_` 单 key 无上界，config.prop 膨胀。**T2′ 已落地**：pivot 到自有 SQLite 库（行级存储，无单 key 体量问题）；分片方案被 DB 行级存储替代；onLoad 一次性迁移 CSV→DB 后清旧 key。裁剪非必需（留后续）。 | C-PERF-02 | ✅ |
| RB-3 | 无性能埋点，问题靠用户实测才暴露（T1 已落地 onHandleMsg 入口埋点 → perf.log，见 §6.5） | C-PERF-04 | P0 |
| RB-4 | 缺降级机制，L3 出问题会拖垮收发。**T4 已落地**：两稳健触发器（缓冲上界 `L3_BUFFER_CAP`=5000 / flush 连续失败 `L3_FAIL_CAP`=5）→ `L3_DEGRADED` 仅挡 `recordSpeak` 采集入口（O(1) 布尔读），成功+排空到低水位自动恢复，进出降级写 perf.log 留痕；L1/L2 不受影响（见 §7）。 | C-ARCH-02 | ✅ |
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
