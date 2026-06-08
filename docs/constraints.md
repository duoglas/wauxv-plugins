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

## [JC-02: 插件动作分层与降级]

按及时性把插件动作分三层，热路径按层取舍：

| ID | 约束 | WHY | 违反后果 |
|---|---|---|---|
| C-ARCH-01 | 插件动作分三优先级层：**L1 管理动作**（踢人/警告/拉黑等破坏性或需即时生效）最高保障，必须可靠执行；**L2 用户发起的交互**（查询/帮助/命令响应）次之；**L3 统计/分析类**（潜水记录、群聊统计、AI 分析）及时性要求低。 | 不同动作的及时性与重要性天差地别，混在一条同步路径上互相拖累。 | 统计拖垮管理动作，或全部挤在消息线程导致 C-PERF 违规。 |
| C-ARCH-02 | L3 必须**异步化 + 可降级**：绝不在消息处理线程同步执行；用内存缓冲 + 后台线程/定时 flush；热路径压力大、资源紧张或出错时，L3 优先降级或跳过，不得反向影响 L1/L2 与消息收发。 | L3 是"锦上添花"，不能成为 P0 的风险点。 | 统计类拖慢收发（VH-01）。 |
| C-ARCH-03 | 热路径（onHandleMsg）内的判定顺序按层：先以最廉价方式判断是否 L1/L2 命令；非命令的普通消息，L3 处理只做"轻量入队"后立即返回。 | 大多数群消息是普通聊天，应以最低成本快速放过。 | 每条普通消息都付重成本。 |

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

## [NET]

| ID | 约束 | WHY | 违反后果 |
|---|---|---|---|
| C-NET-01 | clash 为 tun/VPN 模式时全局流量都过 tun；对实时长连接敏感的 app（微信 mmtls）应在"访问控制/分应用"里排除，规则层 DIRECT 不够（流量仍过 tun 用户态栈）。 | fake-ip + tun 用户态转发会给长连接加延迟/抖动。 | 微信等收消息延迟（即便规则是直连）。 |

## [HARNESS]

| ID | 约束 | WHY | 违反后果 |
|---|---|---|---|
| C-HARNESS-01 | Harness hook 的纪律拦截（first-call 阶段声明守门等）只施加在**有副作用的工具**（Edit/Write/Agent/写或执行类 Bash）上；**无副作用的只读探索**（Read/Grep/Glob/WebFetch/WebSearch、只读 Bash）不得被拦，且不消耗"首次调用"计数——把强制声明留到第一个真正动手的工具。 | 守门目的是在动手前对齐阶段，不是阻止看代码。每个新 session 开头拦只读探索会被感知成"工具调用出错/任务中断"，纯噪音，还削弱对守门本身的信任。 | 新 session 开头只读探索被拦一次，体感"总是格式出错中断"；用户绕过或关 Harness。 |
| C-HARNESS-02 | 结构化输入工具（尤其 `AskUserQuestion`）的数组字段（`questions`/`options`）必须是**真数组**，绝不整体序列化成 JSON 字符串；option `description` 写短，**不在 `preview` 里塞带转义引号的多行 shell/git 命令**，大问题拆成多个小问题。 | payload 过大、过深嵌套（长描述 + preview 多行转义命令）会让模型把整个数组折叠成（且常已损坏的）字符串，触发 `type expected array provided string` 校验错误、浪费一轮、看着像卡住。 | AskUserQuestion 调用被拒、自动重试，交互卡顿；payload 越大越频繁。 |

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
