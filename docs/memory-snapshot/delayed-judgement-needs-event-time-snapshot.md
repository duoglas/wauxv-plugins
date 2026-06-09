---
name: delayed-judgement-needs-event-time-snapshot
description: "延迟跑的判定若读\"判定时刻\"的可变状态，会被事件后的状态变化污染；需事件时刻快照"
metadata: 
  node_type: memory
  type: feedback
  originSessionId: d5dfe583-825a-46f5-af66-a4796e94501b
---

判定逻辑若**在事件发生后延迟一段时间才跑**，且读的是某个**只存最新值、会被后续变化覆盖**的状态，则事件之后、判定之前发生的状态变化会**污染判定**。

**实例(RedPacketStats 伸手党 v1.11.5)**：伸手党判定要的是"红包到达前 N 分钟有没有发言"，但判定被反检测开包延迟推后 ~90s 才跑，读的是 GA `speak.last_speak`(只存最近一次发言、无历史)。伸手党抢完红包说句"谢谢"，`last_speak` 就被推过窗口阈值 → 判定误以为他抢前活跃 → 漏警告。**抢完发言把人洗白了。**

**修法通则**：在**事件时刻**(这里=红包检测入队那一刻)把判定要用的可变状态**快照**下来(`FREELOADER_SNAP` ConcurrentHashMap，后台线程查，热路径只多 Thread.start())，判定时用快照值而非当时的当前值。

**⚠️ 2026-06-09 续：v1.11.5 的"检测时快照 + 双判据"仍误判，被推翻**。真机实证三类活跃成员被误警告，三个失败模式同一病根：(1) `packetMs`=红包检测时刻，**重试包冻结在原始发出时刻**，之后发言不算；(2) 双判据的 `当前值<=packetMs` 上界把"红包后还在说话"的人排除；(3) **快照在检测那一刻拍，但 GA speak 表每 30s 才异步 flush → 红包前最后 ~30s 的发言还在 GA 内存缓冲没落表，跨插件读不到 → 快照漏掉**。根因三合一：微信无 per-person 领取时刻 + GA speak 表只存最新值 + 跨插件读异步表慢一个 flush 周期。**教训：在事件时刻快照只对了一半——快照源若是异步缓冲的持久层，快照到的仍是陈旧值；且 latest-only 存储 + "判某过去子窗口有没有发生"在该人之后又有动作时根本不可答。**

**正解(合并后做，设计见 docs/superpowers/specs/2026-06-09-红包伸手党-合并后判定重设计.md)**：判定与 recordSpeak/L3缓冲**同插件**后，①快照前直接 `l3Flush()`(或读 `max(表,内存缓冲)`)→ flush 延迟消失；②**单快照拍在 `packetMs + 事后宽限G` 那一刻**(不是检测瞬间)，判定退化成一条 `snapLs < packetMs - N → 伸手党`；③语义细化(用户拍板)：活跃 = 在 `[packetMs-N, packetMs+G]` 内发过言，**红包后G秒内发言算正常**(G默认建议10s，待确认)，抢后 >G 的洗白发言因快照拍在它之前而抓不到→仍判。把"快照时刻"从检测瞬间移到 `packetMs+G` 是关键：既容纳红包附近的即时反应，又天然挡掉晚到的洗白。

**两条衍生教训**：
1. 判定段**只在"命中"时写日志 = 盲区**：原伸手党只有 `warned>0` 才写日志，"跑了但没人被警告"全静默，导致排查时分不清"判了都活跃"还是"压根没判/DB读失败"。→ 加**恒定 summary**(judged/warned/active/skip/snap=ok/fail)。
2. 系统化调试定位它：拉 rp.log(逐道闸门日志) + config(开关/since/win) + groupadmin.db speak 行 + rp_record qualifiers(按昵称 🐽 反查到具体包与群)，逐道闸门排除，而非猜。

**How to apply**：见到"延迟/异步判定 + 读最新可变状态"的组合，先问"事件后状态会不会变、变了会不会改判定"，要就在事件时刻快照。相关 [[waux-getstring-fuse-io-cache-hotpath]](快照查库放后台不占热路径)、[[config-key-written-on-transition-upgrade-gap]](同属"状态/时机缺口"类 bug)。
