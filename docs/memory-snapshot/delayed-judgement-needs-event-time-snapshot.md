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

**修法通则**：在**事件时刻**(这里=红包检测入队那一刻)把判定要用的可变状态**快照**下来(`FREELOADER_SNAP` ConcurrentHashMap，后台线程查，热路径只多 Thread.start())，判定时用快照值而非当时的当前值。再加当前值做**双判据**兜 flush 延迟(抢前刚发言未落盘)：`活跃 = 当前值∈[thr,packetMs] 或 快照值>=thr`。快照拿不到则**降级回当前值判据**(保守，宁可漏判不误警告——本判定是踢人上游)。

**两条衍生教训**：
1. 判定段**只在"命中"时写日志 = 盲区**：原伸手党只有 `warned>0` 才写日志，"跑了但没人被警告"全静默，导致排查时分不清"判了都活跃"还是"压根没判/DB读失败"。→ 加**恒定 summary**(judged/warned/active/skip/snap=ok/fail)。
2. 系统化调试定位它：拉 rp.log(逐道闸门日志) + config(开关/since/win) + groupadmin.db speak 行 + rp_record qualifiers(按昵称 🐽 反查到具体包与群)，逐道闸门排除，而非猜。

**How to apply**：见到"延迟/异步判定 + 读最新可变状态"的组合，先问"事件后状态会不会变、变了会不会改判定"，要就在事件时刻快照。相关 [[waux-getstring-fuse-io-cache-hotpath]](快照查库放后台不占热路径)、[[config-key-written-on-transition-upgrade-gap]](同属"状态/时机缺口"类 bug)。
