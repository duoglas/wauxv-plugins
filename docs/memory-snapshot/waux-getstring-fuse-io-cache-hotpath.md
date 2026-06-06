---
name: waux-getstring-fuse-io-cache-hotpath
description: WAuxiliary getString 每次同步读 FUSE config.prop ~26ms; 热路径上的任何配置读都要内存缓存
metadata: 
  node_type: memory
  type: feedback
  originSessionId: 3cbb9098-fbe3-4e37-8615-e1be36f085db
---

WAuxiliary 的 `getString(key)` **每次调用都同步读 FUSE 上的 config.prop 文件**（路径 `/data/media/0/Android/media/com.tencent.mm/WAuxiliary/Plugin/<名>/config.prop` = sdcardfs/FUSE），实测**单次 ~26ms**（与文件大小关系不大，config.prop 才 5KB；是 FUSE 文件 IO 本身慢）。

**Why:** 2026-06-06 用分段 perf 埋点定位 GroupAdmin onHandleMsg ~30ms/条，其中 seg2 ~26ms 全在 `isGroupEnabled`→`getString("enabled_groups")`。RedPacketStats 红包消息热路径同样栽在自己的 `isGroupEnabled` getString 上。这是 C-PERF-01 同类隐患（per-message 同步 IO），易被忽视因为"只是读个配置"。

**How to apply:**
1. **热路径（onHandleMsg 及每条消息会走到的判定）上，任何 `getString`/`cfgInt`/`cfgStr`（底层都是 getString）都要内存缓存**，不能每条消息读盘。
2. 缓存模式（已在 GroupAdmin v1.16.2 / RedPacketStats v1.6.4 落地）：顶层 `HashSet/值 CACHE + long CACHE_TS + TTL=60000ms`；读时 `cache==null||now-ts>TTL → reload()`；**mutation（putString）后立即 reload 保证即时生效**；reload 失败保留旧缓存、读路径异常回退直接 getString（正确性优先于性能）。60s TTL 兜底外部 adb 改 config.prop。
3. 缓存归一口径必须与原读逻辑完全一致（注意各插件 normGroupId 不同：GroupAdmin 提取 @chatroom，RedPacketStats 只 trim）。
4. 效果：GroupAdmin onHandleMsg ~30ms→~12ms（seg2 26→3ms）；RedPacketStats 红包消息热路径去掉最后一处 26ms。
5. 配合：把重活（XML 解析等）也移出热路径到后台 worker（RedPacketStats v1.6.3，红包消息 ~267ms→）。

相关 [[recordspeak-refactor-progress]]（VH-01 同根: 热路径 per-message IO）、[[storage-substrate-sqlite-over-csv]]、[[beanshell-parser-stricter-than-javac]]。
