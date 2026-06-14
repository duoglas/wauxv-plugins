# Project Constraints

**SINGLE SOURCE OF TRUTH** — 所有规则的唯一权威来源。

## 约束 ID 格式

- `C-{area}-{number}` — 单条约束
- `JC-{number}` — 联合约束组（组内必须同时成立）
- `VH-{number}` — 违规历史

## 约束区域（area）

- `PLUGIN` — WAuxiliary 自定义插件开发（BeanShell main.java）
- `PERF` — 性能（尤其消息热路径与及时性）
- `ARCH` — 架构（插件动作分层、降级策略）
- `DEVICE` — 真机运维操作（刷模块、改系统设置、不可逆动作）
- `SEC` — 隐藏 / 反检测（root/Xposed 对目标 app 不可见）
- `NET` — 网络 / 代理（clash tun、分应用）
- `HARNESS` — Harness 工程（hook 守门、阶段纪律、工具调用稳定性）
- `CTX` — 上下文 / session 管理（长度退化、compact、单 session 生命周期）

---

## 头号原则（P0，凌驾一切功能）

**及时收发消息是第一优先级。** 任何插件功能与"消息按时收发"冲突时，一律牺牲功能保消息。下面所有 PERF/ARCH 约束都服务于这一条。

## [JC-01: 消息热路径性能]

| ID | 约束 | WHY | 违反后果 |
|---|---|---|---|
| C-PERF-01 | 挂在 `onHandleMsg` 等"每条消息"回调上的逻辑，禁止做 O(N) 全量读-解析-序列化-写回，或同步重 IO/网络（N=群成员数/数据量）。需用增量更新、内存缓存 + 定时/退出批量 flush，或 per-key 存储。 | 微信消息处理线程串行，每条消息的开销会累积；数据随时间膨胀后劣化，最终拖垮收发。 | 消息积压、私聊群聊普遍延迟，关插件总开关才恢复。 |
| C-PERF-02 | 插件持久化数据（fastkv/config.prop）的单 key 体量需有上界；按群/按人分片，避免单 key 无限增长。 | 单 key 越大，每次读写越慢，且放大 C-PERF-01。 | config.prop 膨胀（已见 66KB），读写成本随之上升。 |
| C-PERF-03 | 消息收发及时性是 P0。任何插件逻辑（尤其统计类）不得阻塞或拖慢消息主链路；热路径只允许做"判定 + 入队"，重活一律移出线程。 | 见头号原则。 | 收消息延迟，等于插件把核心功能搞坏了。 |
| C-PERF-04 | 插件必须内置**真实性能埋点**：记录每条消息各 hook 的耗时、调用次数到独立性能日志（不污染消息链路），支持定期（如每日）聚合分析、识别热点，基于数据优化而非拍脑袋。 | 没有真实统计就无法定位瓶颈，VH-01 正是缺埋点拖到用户实测才暴露。 | 性能问题潜伏、反复回归、靠用户体感发现。 |
| C-PERF-05 | 把"直连 SQLiteDatabase 的批量写"（线上用单 `beginTransaction()…endTransaction()` 包住 N 行 `execute`）移植/抽象到存储端口（StoragePort）时，**必须保留单事务语义**——端口要提供批量/事务写能力（如 `execBatch(sql, List<binds>)` 在一个事务内跑完 N 行），**禁止退化成"循环逐行 `STORAGE.exec`"**（每行各自 auto-commit = 一次 fsync）。被测路径（命令/启用/迁移）对 N=群成员数 的批量写尤其要批量化。 | 端口抽象若按"一次 exec 一行"实现，循环里每行就是一次独立事务+fsync；启用大群 / CSV 迁移做 N 次 fsync，在 UI 线程（Dialog 启用按钮）会秒级阻塞。逐字保真换载体时极易丢掉原 `beginTransaction` 包裹——注释还写着"批量"但事务没了。解析门/单测（小数据集 + 不计时）都不暴露，只大群真机 ANR。 | 启用群/迁移在 UI 线程 ANR（VH-06 群B群启用，主线程 13.7s 卡在 `SQLiteStatement.execute` 逐行 fsync）。 |

## [JC-02: 插件动作分层与降级]

按及时性把插件动作分三层，热路径按层取舍：

| ID | 约束 | WHY | 违反后果 |
|---|---|---|---|
| C-ARCH-01 | 插件动作分三优先级层：**L1 管理动作**（踢人/警告/拉黑等破坏性或需即时生效）最高保障，必须可靠执行；**L2 用户发起的交互**（查询/帮助/命令响应）次之；**L3 统计/分析类**（潜水记录、群聊统计、AI 分析）及时性要求低。 | 不同动作的及时性与重要性天差地别，混在一条同步路径上互相拖累。 | 统计拖垮管理动作，或全部挤在消息线程导致 C-PERF 违规。 |
| C-ARCH-02 | L3 必须**异步化 + 可降级**：绝不在消息处理线程同步执行；用内存缓冲 + 后台线程/定时 flush；热路径压力大、资源紧张或出错时，L3 优先降级或跳过，不得反向影响 L1/L2 与消息收发。 | L3 是"锦上添花"，不能成为 P0 的风险点。 | 统计类拖慢收发（VH-01）。 |
| C-ARCH-03 | 热路径（onHandleMsg）内的判定顺序按层：先以最廉价方式判断是否 L1/L2 命令；非命令的普通消息，L3 处理只做"轻量入队"后立即返回。 | 大多数群消息是普通聊天，应以最低成本快速放过。 | 每条普通消息都付重成本。 |
| C-ARCH-04 | **发言基线（first_seen / last_speak）是共通基础设施，与功能开关解耦**：插件 onLoad/进群即建并持续维护（recordSpeak 追踪、first_seen 基线建立），覆盖"任一功能启用"的群，**不因某个功能开关而存在/消失**。功能开关（群管启用 / 伸手党 / 红包统计）只控制"哪段逻辑跑 + 算多长时间窗 + 功能开关"，**绝不充当基线的建立锚点或替身**（禁止"开关一开就建一个临时基线/全员宽限期"这类 since-anchor 模式——它把基线绑死在开关上，造成升级缺口与静默失效，VH 已咬过）。**各功能基于同一共通基线计算时，保护时间（新人豁免期等）按功能各自独立**：潜水用 `LURK_NEWMEMBER_EXEMPT_MS`（长期沉默口径）、伸手党用**发言窗口 winMs 本身**作豁免期（VH-08/C-ARCH-07：领包前短窗沉默口径——进群不足一个窗口、没机会发言才豁免；**不用 24h 固定常量** `FREELOADER_NEWMEMBER_EXEMPT_MS`），**绝不合并成共享常量、更不可把潜水的 24h 套到伸手党**。 | 基线绑在功能开关上 → 开关状态缺口（OFF→ON 才写锚点）导致升级后功能静默失效（RP v1.11.4 病根）；且同一份基线本应服务多个功能，各功能的"保护时间"语义不同（潜水=长期沉默、伸手党=领包前短窗沉默），合并常量会互相污染判定。 | 升级/换机后基于开关锚点的功能静默失效；或潜水/伸手党共享豁免期导致一方误判（误踢红线方向）。 |
| C-ARCH-05 | **跨方法共享的端口/单例全局（HOST/STORAGE/CLOCK/LEAF 等）必须保证写穿全局命名空间**：要么在**顶层**（非任何方法体内）先声明（`Object X = null;` 或带默认工厂如 `LEAF = _mkNoopLeaf();`），要么在赋值处用 `global.X = ...` 限定。**绝不能只在某个方法体（如 onLoad）内裸赋值 `X = factory()`** 而无顶层声明——BeanShell 里方法内对"无顶层声明"的变量裸赋值只建**方法局部**，其它方法（onHandleMsg/isGroupEnabled 等）读到 `void`/undefined。实证（bsh-2.0b6）：顶层已声明→方法内裸赋值**写穿全局**(✓)；无顶层声明→方法内裸赋值**跨方法读 undefined**(✗)；`global.X=`→跨方法可读(✓)。 | 装配体把端口工厂的接线放在 onLoad 方法体内，若该全局无顶层声明，BeanShell 当方法局部 → 真机每条消息 onHandleMsg 调到该端口即抛 "undefined variable or class name"。**离线 bsh 解析门只查语法不查符号解析、单测在顶层注入 fake 端口（已是全局）→ 两道门都抓不到，只真机首次装配执行才暴露**（与"自递归""callee 闭合"同属"门禁盲区逃逸"类）。 | 合并/装配类插件首次真机加载即每条消息报错、功能整体瘫痪；或更隐蔽——端口读到顶层 noop 默认（如 LEAF）导致破坏性动作（踢人）静默空转不报错。 |
| C-ARCH-06 | 把一个插件 **fork/合并到新的隔离目录**（如 RedPacketStats→GroupAdminPlus）时，**必须重定向该设备层的全部绝对路径常量——db 与日志一视同仁**：不只是显眼的 `*_DB_PATH`，还包括逐字保真搬来的日志/导出/缓存路径常量（`HB_LOG`/`PERF_LOG`/`*.log`/任何硬编码 `Plugin/<旧名>/...`）。**收口手法：改完隔离常量后，对源码全量 grep `Plugin/<旧名>/` 路径串，结果须为空**（注释里的"线上 @行号"引用除外）。 | 逐字保真移植会把线上硬编码绝对路径整串搬过来；隔离改造时人眼只盯"库"忘了"日志"。漏改的日志常量指向旧插件目录 → 运行时静默写进旧目录（FileWriter 异常被 try 吞），新插件目录里看不到日志，**可观测性假性缺失**（误判"埋点没生成/没在跑"）；一旦旧插件目录被清理，写盘静默失败、日志彻底丢。**单测注入 fake/不碰绝对路径、解析门只查语法 → 两道门全绿，只真机+"日志去哪了"反查才暴露**（与 C-ARCH-05 同属装配隔离盲区）。 | 隔离插件的 rp.log/perf.log 写错目录（VH-07 群B群红包统计：链路全对、rp_record 正确、文案已发，但 [RP]/perf 日志全落在旧 RedPacketStats/ 目录，GroupAdminPlus/ 里 plugin.log 冻结、perf.log 不存在，一度误判"统计没生效/埋点没跑"）。 |
| C-ARCH-07 | 合成/默认基线（缺历史时用 `now` 批量填充的 `first_seen`，如 `enableGroupWithBaseline`/`initFirstSeenBaseline`）只代表"bot 首次观测时刻"，**不是真实入群/事件时刻**。跨功能复用它做判定豁免时，豁免口径**必须用该功能自己的时间窗**，不得直接套另一功能的豁免期（如把潜水踢人的 24h 新人期套到红包伸手党）。**自检判据**：任何"`first_seen` + 固定豁免期"的判定，必须问"启用群当天全员 `first_seen=now`，会不会让该功能在豁免期内整体静默失效 / 把老成员误当新人豁免"。 | 合成基线在启用当天给全员同一个 `now`；若豁免期 EXEMPT ≫ 该功能真实关心的窗（如 24h ≫ 10min 发言窗），启用后 EXEMPT 期内全员命中豁免 → 功能静默失效，且潜水数周的老成员只因"bot 今天才看"被误当新人豁免。纯函数单测显式传任意 EXEMPT 测的是通用契约、解析门只查语法 → 双门都不暴露生产用错了豁免口径，只真机"功能不触发"反查才暴露（与 C-ARCH-04 同源，是其细化）。 | VH-08 伸手党 `exemptMs=24h`(潜水口径) 撞合成基线：群B群启用当天 482 人 `first_seen` 全落今早 06:08–07:55 → exemptNew 全员 → 零警告；修为 `exemptMs=发言窗口 winMs`。 |

## [PLUGIN]

| ID | 约束 | WHY | 违反后果 |
|---|---|---|---|
| C-PLUGIN-01 | 自定义 BeanShell 插件改动后，必须 `adb` 部署到真机并实测（消息收发 / 群命令 / logcat）再判定完成；无单测框架，禁止仅静态判断"应该好了"。 | BeanShell 解释执行、hook 真实微信，静态看不出运行期问题。 | 改动带病上机，用户实测才暴露。 |
| C-PLUGIN-02 | 改动 `main.java` 前先在 `versions/` 或设备 `*.before-*` 留可追溯旧版（现已纳入 git）。 | 出问题要能快速对比/回滚。 | 无法定位哪次改动引入回归。 |
| C-PLUGIN-03 | 每个插件必须有 **spec 文档**（`plugins/<name>/SPEC.md`）：描述功能、hook 点、动作的 L1/L2/L3 分层、数据存储结构与上界、降级策略。**改行为先改 spec 再改代码。** | spec 是 review/测试/重构的对照基准，避免凭记忆改。 | 行为漂移、性能/正确性无对照。 |
| C-PLUGIN-04 | **充分测试**：无单测框架下建立真机测试矩阵（正常消息 / 各管理命令 / 高频积压 / 大群 / 边界与异常消息类型），关键场景每次改动回归并留证据。 | 微信版本/群规模变化会让 hook 失效（已见红包个数 ArrayIndexOutOfBounds）。 | 回归无人发现，线上群出事。 |
| C-PLUGIN-05 | `onLoad` 必须对微信登录态/通讯录未就绪健壮：任何依赖登录态的调用（`getLoginWxid` 等，冷启动早期会抛 `NoResetUinStack`）必须 try/catch 兜底；**cosmetic 调用（日志拼串等）绝不能阻断关键初始化**（后台 flush 循环启动、内存缓存填充、延迟基线调度）。关键初始化分段 try/catch，互不连坐；登录态相关的一次性初始化应延迟/重试到就绪。 | 微信冷启动时 onLoad 可能先于登录态恢复执行，一处早抛会中断整段初始化，导致 flush 循环不启动（脏增量不落盘、强杀丢数据）等隐性降级。 | onLoad 早期异常静默吞掉关键初始化，插件半瘫且不易察觉。 |

## [SEC]

| ID | 约束 | WHY | 违反后果 |
|---|---|---|---|
| C-SEC-01 | 新装任何 root/Xposed 相关 app 后，必须确认被 HMA 白名单遮蔽 + 视需要加入 Magisk DenyList，目标风控类 app（微信/银联等）才能正常用。 | 风控类 app 查包/查挂载即判风险。 | 目标 app 报风险受限 / 功能受限。 |
| C-SEC-02 | 重装目标 app 后必须重新确认 DenyList 覆盖（重装会从 DenyList 掉）。 | Magisk 检测到包变化会清除其 denylist 条目。 | 重装后 root 重新暴露，风控复现。 |
| C-SEC-03 | **发到群里的可见文案（`HOST.sendText(群, …)`）不得暴露后台数据采集 / AI / 大模型 / LLM 等敏感能力**。开关确认、错误提示、命令拒绝等群内消息只用中性词（"统计/记录/榜/润色/生成出错"），暴露细节（采集聊天内容、配 key、大模型）只放**操作者本地 toast / 配置 Dialog**（仅管理员可见）。命令关闭时优先**静默消费**（`return true` 不发群）。例外：用户主动开启的公开 AI 命令（如已开「AI 点评命令」后的锐评正文）本就是面向群的输出，不在此限。 | 群成员看到"正在采集你们的聊天记录喂 AI"会感到被监视、引发反感与信任问题；运营方要的是不动声色。toast/Dialog 只有操作的管理员看得到，是安全的告知面。 | 群成员察觉被后台采集 / AI 处理，质疑、退群、运营暴露（VH: 2026-06-14 榜单润色/AI点评错误文案把 LLM/采集写进群）。 |

## [NET]

| ID | 约束 | WHY | 违反后果 |
|---|---|---|---|
| C-NET-01 | clash 为 tun/VPN 模式时全局流量都过 tun；对实时长连接敏感的 app（微信 mmtls）应在"访问控制/分应用"里排除，规则层 DIRECT 不够（流量仍过 tun 用户态栈）。 | fake-ip + tun 用户态转发会给长连接加延迟/抖动。 | 微信等收消息延迟（即便规则是直连）。 |

## [HARNESS]

| ID | 约束 | WHY | 违反后果 |
|---|---|---|---|
| C-HARNESS-01 | Harness hook 的纪律拦截（first-call 阶段声明守门等）只施加在**有副作用的工具**（Edit/Write/Agent/写或执行类 Bash）上；**无副作用的只读探索**（Read/Grep/Glob/WebFetch/WebSearch、只读 Bash）不得被拦，且不消耗"首次调用"计数——把强制声明留到第一个真正动手的工具。 | 守门目的是在动手前对齐阶段，不是阻止看代码。每个新 session 开头拦只读探索会被感知成"工具调用出错/任务中断"，纯噪音，还削弱对守门本身的信任。 | 新 session 开头只读探索被拦一次，体感"总是格式出错中断"；用户绕过或关 Harness。 |
| C-HARNESS-02 | 结构化输入工具（尤其 `AskUserQuestion`）的数组字段（`questions`/`options`）必须是**真数组**，绝不整体序列化成 JSON 字符串；option `description` 写短，**不在 `preview` 里塞带转义引号的多行 shell/git 命令**，大问题拆成多个小问题。 | payload 过大、过深嵌套（长描述 + preview 多行转义命令）会让模型把整个数组折叠成（且常已损坏的）字符串，触发 `type expected array provided string` 校验错误、浪费一轮、看着像卡住。 | AskUserQuestion 调用被拒、自动重试，交互卡顿；payload 越大越频繁。 |
| C-HARNESS-03 | **每个涉及代码改动的迭代，REVIEW 阶段必须包含至少一个独立 agent 的 code review（≠ 实现该改动的 agent/主循环），不可跳过、不可由作者自审替代。** PLAN 拆解里必须显式列出独立 code review 作为一个阶段/任务（不是埋在"真机准出"里的附属）。热路径/破坏性/反检测等高风险改动在此基础上再加 Santa 双独立审（qa-standards Layer 4）。**唯一可降级**：纯文档/注释/只读诊断脚本（降级需在 REVIEW 留一行记录）。独立 reviewer 输出结构化 PASS/FAIL，有 FAIL 进 Fix Cycle 复审通过才算 done。 | 作者自审有 author-bias，看不见自己的盲区（端口写穿、callee 闭合、SQL NULL、热路径 O(N) 这些"门禁盲区逃逸"类问题恰恰最需要第二双眼）；把 review 埋进真机阶段会被赶进度时悄悄跳过。固化成 PLAN 里的独立阶段 + 不可跳过，才能"始终"成立。 | 带 author-bias 的改动直接上真机/交付，盲区问题逃逸到生产（VH-05/VH-07 类装配盲区本可被独立 review 拦下）。 |

## [CTX]

| ID | 约束 | WHY | 违反后果 |
|---|---|---|---|
| C-CTX-01 | 单 session 不得无限期跨多天长跑。察觉模型开始吐裸 `<invoke>`/`<parameter>` 文本而工具不执行（空转回合），或上下文逼近 ~400K tokens 时，必须主动 `/compact` 或开新 session，**不要等 auto-compact**（它只在逼近 1M 窗口 ~80%+ 才触发，长跑 session 常卡在已退化却没触发的中间地带）。长跑守护任务（watchdog/连续观察）用后台脚本+日志承载，不靠让对话 session 一直开着。harness 须把重复的阶段 directive 灬注降到每 (session,stage) 一次。 | 上下文越长，模型把 tool-call 信封当纯文本吐出的概率越高（死回合随长度暴增）；回合空转 end_turn，看着像"不断停下来"，被迫反复"继续"。 | 任务频繁空转中断、用户反复打"继续"、误判成卡死；浪费大量轮次。 |

---

## Violation History

| ID | 日期 | 发生了什么 | 根因 | 对应约束 |
|---|---|---|---|---|
| VH-01 | 2026-06-05 | GroupAdmin `recordSpeak` 每条群消息对全群发言时间 CSV 做读-解析-改一条-序列化-写回(O(群人数))，`lsg_/fsg_` 随时间膨胀使 config.prop 涨到 66KB，微信收消息严重延迟、私聊群聊都卡；关 WAuxiliary 总开关即恢复。本质是把 L3 统计类工作同步压在消息热路径上，且无性能埋点拖到用户实测才暴露。 | onHandleMsg 热路径上的 O(N) 全量读写 + 单 key 无上界 + L3 未异步降级 + 无埋点。 | C-PERF-01, C-PERF-02, C-PERF-04, C-ARCH-01, C-ARCH-02 |
| VH-02 | 2026-06-07 | GroupAdmin 在微信冷重启(00:29)时 `onLoad` 第一行日志拼串调 `getLoginWxid()`，因登录态未就绪抛 `zt0.c: NoResetUinStack`，导致整段 onLoad 中断——后台 L3 flush 循环未启动、延迟基线复查未排程。插件消息路径仍懒恢复(owner 探测/recordSpeak 正常)，但处于隐性降级(脏增量缺定时 flush)。watchdog 真机监控捕获。 | onLoad 早期对登录态未就绪无防御，一句 cosmetic 日志的 getLoginWxid 连坐关键初始化。 | C-PLUGIN-05 |
| VH-03 | 2026-06-08 | 用户反馈"另一个 session 总是工具调用格式出错导致任务中断"。复盘历史 jsonl：真正的格式错误仅 2 次（`AskUserQuestion` 把 `questions` 数组序列化成损坏字符串，模型 ~8s 后自动重试恢复）；"中断"大头是 17 次 Harness Stage Guard 拦截，其中 first-call guard 对每个新 session 第一次只读工具（Read/Grep/Glob）也拦一次，最像误报。已改 `harness-stage-guard.js` 让只读工具豁免首次拦截（不递增计数器，block 留到第一个写/执行工具），手测 4 项通过。 | first-call 守门未区分有无副作用，连坐只读探索；AskUserQuestion payload 过大触发模型字符串折叠。 | C-HARNESS-01, C-HARNESS-02 |
| VH-04 | 2026-06-08 | 用户再次反馈 android-ops session "今天上午不断停下来、不断格式报错"。深挖同一 jsonl（2282 条 / 3.7MB / 跨 06-06→06-08 从未清空）发现 VH-03 的结论不完整：**真正的高频根因是长 session 上下文退化**——模型把工具调用标记 `<invoke …>` 当纯文本吐出、工具不执行、回合空转，共 **39 次死回合**（VH-03 只数了 schema 校验错的 2 次，漏了这 39 次）；随长度暴增 06-06=1→06-07=10→06-08=28，聚集在上下文最深处；峰值 ~570K tokens，**auto-compact 从未触发**（1M 窗口阈值 ~80%+，570K 没到）。用户实打了 19 次"继续"（含"继续啊 怎么又卡住了"）。已：① 改 `harness-stage-guard.js`+`harness-session-start.js`，整段阶段 directive 每 (session,stage) 只注入一次（灬注 1262B→124B），减缓上下文增速；② CLAUDE.md + 本约束 C-CTX-01 记录"主动 compact/开新 session、单 session 不跨多天"。手测 3 项（首次注入/同 session 抑制/新 session 重注）通过。 | 长 session 上下文退化使 tool-call 信封格式崩坏；auto-compact 阈值以下进入退化中间地带；harness 每次调用重复灬注加速上下文膨胀。 | C-CTX-01, C-HARNESS-01 |
| VH-06 | 2026-06-11 | P3-c Phase 2 真机灰度：在群B群点"启用本群群管"后微信**反复 ANR（没有响应）**。ANR trace 主线程(tid=1) utm=1369(~13.7s CPU)卡在 `android.database.sqlite.SQLiteStatement.execute`(nativeExecute, locked SQLite 连接)，深层 BeanShell 解释帧循环逐行写。根因：启用群走 Dialog 按钮(UI 线程)→ `enableGroupWithBaseline`→`initFirstSeenBaseline`→`l3UpsertFirstSeen` 逐成员 `STORAGE.exec`(compileStatement+execute) 每行 auto-commit。**线上 GA @249-282 本是 `db.beginTransaction()`…`endTransaction()` 单事务包 N 行(1 次 fsync)**，合并移植到 StoragePort 抽象时丢了事务 → 大群 N 次 fsync 卡 UI 13s。onLoad 的 baseline 在 `delay(5000)` 后台线程跑所以没 ANR，只 Dialog 启用按钮(UI 线程)炸。已：① 写 C-PERF-05；② 止血(清 enabled_groups 停用群B群 + force-stop)；③ 派 Implementer 给 StoragePort 加 `execBatch`(单事务批量写)修 `l3UpsertFirstSeen`(及同型 `l3MigrateCsvToDb`)。 | StoragePort 抽象按"一次 exec 一行"实现，移植丢了线上 beginTransaction 包裹；小数据集单测/解析门不暴露。 | C-PERF-05, C-PERF-03 |
| VH-05 | 2026-06-11 | P3-c Phase 2 合并插件 GroupAdminPlus **首次真机灰度**：部署后每条群消息 onHandleMsg 即抛 `Attempt to resolve method: now() on undefined variable or class name: CLOCK`（调用链 onHandleMsg→onHandleMsgBody→isGroupEnabled→CLOCK.now()），功能整体瘫痪。根因：端口接线 `STORAGE/HOST/CLOCK = makeAndroid*()` 只写在 onLoad **方法体内且无顶层声明**，BeanShell 当方法局部 → 其它方法读到 undefined（groupadmin.db 能建是因建表也在 onLoad 内同作用域可见局部，制造"装上了"假象）。连带：onLoad 里 `LEAF = makeAndroidLeaf()` 因 LEAF 有顶层 noop 声明而写穿成功（未爆但若无声明则破坏性踢人会静默空转）。**离线 bsh 解析门（只查语法）+ L1/L2 单测（顶层注入 fake 端口已是全局）双门全绿却逃逸**，真机首次装配执行才暴露。已：① 写 C-ARCH-05；② onLoad 四端口接线统一改 `global.` 限定（实证 bsh-2.0b6 写穿全局，tests 不调 onLoad 零影响）；③ 整库/配置已 Phase 2 前置备份，回滚=删 GroupAdminPlus 目录+重开旧插件。 | 装配体端口接线放方法体内 + 无顶层声明 → BeanShell 方法局部作用域；解析门不查符号、单测注入的是全局 fake，两道门是装配盲区。 | C-ARCH-05 |
| VH-07 | 2026-06-11 | P3-c Phase 2 真机 E2E：用户发红包后反馈"红包统计没生效"。反查发现两件事——(a)"没生效"实为**误判**：`rp_first_sec=120` 抖动后 ~90s 内置延迟，worker 到点才开包发文案，用户发完立即看故"没生效"，90s 后文案才出（"刚才已经有红包提醒了"）；链路一直正确，rp_record 写入正确（qualified=2、分档准确、excluded=1 发包人豁免生效、3/3 领取者反射）。(b) 但反查时撞见真 bug：合并"隔离新库"只改了 3 个 `*_DB_PATH` 常量，**漏改 `HB_LOG`/`PERF_LOG` 两个日志路径常量**，仍指向旧 `Plugin/RedPacketStats/` 目录 → 全部 [RP]/perf 日志写进旧插件目录，GroupAdminPlus/ 的 plugin.log 自部署起冻结、perf.log 不存在，制造"统计没在跑/埋点没生成"假象，一度把排障引偏。已：① 写 C-ARCH-06（隔离须重定向 db+日志全部路径常量 + grep 旧目录串收口）；② 改 device_base.bsh 两常量→GroupAdminPlus/，全量 grep `Plugin/RedPacketStats/` 已空，check.sh 23/23 绿；③ 修复攒着与后续改动一次 force-stop 重载，不打断真机测试。 | 逐字保真把线上硬编码日志路径整串搬来，隔离改造盯"库"忘"日志"；漏改的日志常量指向旧目录，写盘异常被 try 吞→可观测性假性缺失，把"延迟"误判叠加成"功能没生效"。 | C-ARCH-06 |
| VH-08 | 2026-06-11 | P3-c E2E：用户反馈"群B群发红包伸手党没生效"。真机 rp.log 取证：伸手党其实**正常跑**（开关 on / win=10min / 快照 ok rows=481），summary `judged=4 active=1 exemptNew=3 excluded=1 warned=0`——**3 个领取者全被"新人豁免"**。根因：伸手党判定 `freeloaderDecide` 的 `exemptMs` 取 `FREELOADER_NEWMEMBER_EXEMPT_MS`(24h，潜水踢人口径)判新人；而 `enableGroupWithBaseline` 用**合成基线**（启用那刻给全员 `first_seen=now`），拉 db 实证群B群 482 人 `first_seen` 100% 落今早建基线 06:08–07:55 → 24h 内全员"新人" → 伸手党启用后头 24h 对全员静默失效，更糟潜水数周老油条只因 bot 今天才看也被豁免。修：**生产 `exemptMs` 改 = 发言窗口 `winMs`**（device_recv.bsh，进群<X 分没机会发言→豁免，>X 分窗口内没发言→判），根治"潜水口径串进伸手党 + 合成基线撞 24h"（10min 自动老化）；加 VH-08 边界单测；check.sh 23/23 绿。 | 合成基线（全员 `first_seen=now`）不是真实入群时刻却被当真值喂伸手党新人豁免；24h 是潜水概念误用到红包；纯函数单测显式传 EX 测通用契约不暴露生产用了 24h，只真机"伸手党不触发"反查暴露。 | C-ARCH-07, C-ARCH-04 |
