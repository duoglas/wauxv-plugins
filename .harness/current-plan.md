# 计划: 排行榜 v1 — 发言榜端到端 (gated 单群灰度 + 真机准出) — 2026-06-12

设计源: `docs/superpowers/specs/2026-06-11-排行榜-ranking-design.md`

## 本迭代范围 (有意收窄, 先打通管道)
- **只做发言相关**: 总发言榜(话痨王 COUNT) + 水王(字数 SUM val) + 潜水冠军(复用 speak 表)。
- 红包/拍一拍/转账 metric + 趣味之最全套 + rollup → **留后续迭代**(管道通了再逐个加 metric, 增量低风险)。
- **A 逐条事件 msg_event 全量保留** 的底座本迭代落地; B 先直接查 A(rollup 下迭代)。

## 关键安全决策
- **采集挂热路径 = medium+ 风险**: onHandleMsgBody 每条消息 append, 必须 O(1) 内存(C-PERF-01), Santa 双审 + 真机实测。
- **gated 灰度**: 加 `rank` 单群开关(仿 enable_state, 默认 OFF, 60s TTL 缓存)。**先只在一个安静测试群启用**, 不blanket全群, 防生产 482 人群写爆/拖慢。
- **type 用自归一码, 不用 msg.getType()**(返大数不可靠, 见 [[waux-msg-detect-gettype-unreliable]]): v1 只分 text(=1,val=字数)/nontext(=0,val=NULL), 细类后续。
- 真机有 24h 监测在跑: 灰度后看 norm_avg 不回归。

## 任务拆解 (每个 ≤15min 可独立验证)

- **T1 domain/rank.bsh (纯离线)**: msg_event 建表 SQL `(grp,wxid,type,ts,val)`+索引(grp,ts); rankInsertEvent; rankTopNCount(grp,sinceTs,n)→COUNT 榜; rankTopNSum(grp,sinceTs,type,n)→SUM(val) 榜(coalesce 兜 NULL, [[sqlite-upsert-coalesce-null-guard]]); rankFirst(LIMIT1)。L1 单测(fake)+L2 集成(sqlite-jdbc 真库验 SUM/COUNT/NULL)。done: check.sh 新测 GREEN。
- **T2 app/rank_collect.bsh (离线+装配)**: RANK_DIRTY 内存缓冲(List)+rankRecordEvent O(1) append; rankFlush 单事务批量 INSERT(复用 STORAGE 批量); rank 单群门 rankIsGroupEnabled(rank_enabled_groups, 默认OFF, TTL缓存)+enable/disable。done: 解析过 + 缓冲累积/flush 单测。
- **T3 热路径接线 (hooks.bsh)**: onHandleMsgBody 共通点(recordSpeak 旁)加 `if(rankIsGroupEnabled(grp)) rankRecordEvent(grp,sender,isText?1:0,ts,isText?len:NULL)` O(1); onLoad 建 msg_event 表; rankFlush 接入 l3FlushLoop 节拍; manifest 登记 rank.bsh/rank_collect.bsh。done: 解析 + check.sh GREEN + callee 闭合扫描。
- **T4 展示+命令 (rank_render.bsh + commands.bsh)**: 命令「发言榜」/「群龙榜」→后台线程→rankTopNCount/Sum→反射解析昵称(getGroupMemberList, 后台+缓存)→渲染趣味文案(👑话痨王/💧水王/🤿潜水冠军 🥇🥈🥉)→sendText 发群。done: 解析 + check.sh GREEN。
- **T5 构建准出**: check.sh GREEN(build+解析+L1/L2+新测) + callee 闭合扫描 + Security grep。
- **T6 独立 agent code review (C-HARNESS-03, 不可跳过, 真机前)**: 派**独立 reviewer agent(≠实现 agent/主循环)** 对 T1-T4 改动做 code review——规格符合 spec + 正确性 + callee 闭合 + SQL NULL/coalesce + gate 默认OFF + 热路径 O(1)。热路径采集属 medium+ → **叠加 Santa 双独立审**(A: 热路径 O(1)+采集正确+不阻塞消息 / B: BeanShell 陷阱(global.限定/自递归)+rank gate+SQL/NULL)。输出结构化 PASS/FAIL; 有 FAIL → Fix Cycle 复审 NICE 才 done。done: 独立 review PASS + Santa NICE。
- **T7 真机准出**: 部署→force-stop 重载→**仅一个测试群** rankEnable→发若干消息→验 msg_event 入行(拉库查)+「发言榜」命令输出排名正确(话痨/水王/潜水)+热路径 norm_avg 不回归(看 perf.log/监测)+无异常+Security。done: 真机实测 PASS。

## 风险 / 不可逆动作
- 热路径 medium+: 必 Santa + 真机。
- 真机部署 force-stop com.tencent.mm(标准动作)。
- 启用即开始写 msg_event(磁盘增长): 仅测试群灰度, 体积看门狗下迭代; 本迭代人工看 db 增速。

## 验收标准
- check.sh GREEN + callee 闭合 + Security 0。
- 真机: 测试群启用后 msg_event 正确入行 + 「发言榜」输出话痨/水王/潜水正确 + 热路径不回归。
- **独立 agent code review PASS（C-HARNESS-03, 不可跳过）+ Santa 双审 NICE**。
