---
name: recordspeak-refactor-progress
description: GroupAdmin recordSpeak 重构(消除 VH-01)的进行中状态与决策
metadata: 
  node_type: memory
  type: project
  originSessionId: 3cbb9098-fbe3-4e37-8615-e1be36f085db
---

按 SPEC 重构 GroupAdmin recordSpeak，消除 VH-01(L3 潜水采集同步压在 onHandleMsg 热路径)。6 阶段 Harness Loop，iteration spec 在 `.harness/iteration-spec.json`，计划 `.harness/current-plan.md`，基线 `.harness/baseline-recordspeak.md`。

进度(2026-06-05)：
- T0 ✅ 备份 + config.prop 基线(总66KB，fsg_44KB+lsg_19KB=95%，单key最大16862B)
- T1 ✅ onHandleMsg 入口埋点(perf.log)。实测改造前 recordSpeak ~2秒/次(VH-01铁证)
- T2 内存缓冲+后台flush 部署后 Santa FAIL(整群CSV跨线程lost-update→可致误踢) → **pivot SQLite**(见 [[storage-substrate-sqlite-over-csv]])
- T2′ ✅ SQLite 落盘+迁移(8群src==db全等)+读点改DB查询。热路径 ~1200µs。**Santa 第2轮 A∧B PASS=NICE**。吸收 T2-fix+T3
- Santa 遗留非阻断 CONCERN：A#5 l3Db()懒开未加锁可双开泄漏连接(→T4 顺修)；A#3 flush-before-read 注释夸大(靠UPSERT单调性而非锁)；B#7 rebuildBaseline 亚秒偏差(良性)；B#8 迁移只遍历enabled群,disabled群旧CSV孤儿(重启用则良性重建,不踢人)
- T4 ✅ L3降级(buffer_cap+fail_streak 两触发器+恢复+留痕,真机三路径实测) + 顺修 A#5(l3Db双检锁)/A#3(注释)
- T5 自动化部分 ✅：本地 sqlite3 独立核验迁移等价(8群1304行,last<first违例0,integrity ok)、selftest污染已清、前后对比、安全扫描。**命令行为路径(L1/L2/#潜水/#踢潜水)需用户真机发消息验收**(adb 发不了微信消息)
- 成果：recordSpeak ~2s→~1.2ms(降~1600×)、O(N)→O(1)、热路径零磁盘IO、SQLite行级存储、Santa NICE
- DB: …/Plugin/GroupAdmin/groupadmin.db (WAL 在 FUSE 回退 journal=delete, 事务仍原子, 无碍)
- 已提交 `98a6dda`(recordSpeak 异步化+SQLite)。已知非阻断:A#4版本注释v1.14/v1.15不一致、B#7/B#8(SPEC已记)

## 后续:警告系统增强(已提交 8765b2e)
- 引用回复(非@)触发全部管理动作,目标=getQuoteMsg().getSendTalker();引用回复是 appmsg(isText=false),动作词取自 <title>(见 [[wechat-quote-reply-appmsg-structure]])
- 警告上限默认 3→10,每群 wk_<group> 可调(Dialog/`警告上限 N`),夹取1~99
- 满上限自动踢:踢成功才清零计数+移名单(重新进群从0累计),无权限/踢失败保留
- Santa 双 Reviewer NICE(权限不绕过+逻辑正确)。用户真机验过引用警告生效
- 非阻断 CONCERN:isText 闸门放行非文本 appmsg → 图片/链接等也进 recordSpeak+命令匹配(良性,XML不误命中命令),建议真机发图确认无异常
- 两提交均未 push

关键约束：热路径 recordSpeak 只内存 O(1)；flush 在后台/命令路径，FLUSH_LOCK 串行化但不碰热路径；潜水读路径 flush-before-read；onLoad 一次性迁移旧 CSV→DB(migrated 标志守卫)。高风险任务必过 Layer 4 Santa。