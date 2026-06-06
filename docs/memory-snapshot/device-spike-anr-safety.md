---
name: device-spike-anr-safety
description: 真机 spike 触碰微信 UI/内部时把生产 bot 卡到 ANR 的教训与防护
metadata: 
  node_type: memory
  type: feedback
  originSessionId: 3cbb9098-fbe3-4e37-8615-e1be36f085db
---

红包统计插件 Phase B spike(2026-06-05):试图 `startActivity` 打开红包详情页 `LuckyMoneyDetailUI`(带 key_native_url 等 extra),结果**没打开详情、被弹回 `com.tencent.mm.ui.LauncherUI`**;探针接着在 **UI 线程**上遍历整个 LauncherUI 的巨型 View 树、且**每个节点同步 FileWriter 写日志**,把微信卡到 **ANR**(用户："提示微信没有响应了")。止血:部署 no-op 空壳 + force-stop 重启微信。

**Why:** 遍历几百上千节点的 View 树 + 每节点一次同步磁盘写,跑在主线程,必然阻塞 UI → ANR。而且开页失败时没有"落错页就别遍历"的保护,直接对主界面下手。

**How to apply:**
- 真机 spike 触碰宿主 UI/内部(开 Activity、遍历 View 树、读页面)时:① 重活放后台/Handler,绝不在 onHandleMsg/UI 线程同步做;② 遍历必须设**深度+节点数上界**;③ 日志**批量缓冲一次性写**,不要每节点一次 IO;④ 先校验 `getTopActivity()` 落在**预期页面**(类名白名单)再遍历,落错页(如 LauncherUI)立即放弃。
- 打开微信红包详情:`startActivity(LuckyMoneyDetailUI, key_native_url)` 这条路**实测不通**(弹回 LauncherUI)。微信从聊天气泡点进详情的真实跳转方式未知,需另查(hook 气泡点击的 intent,或 hook 详情 cgi/receiverlist,而非盲目 startActivity)。
- 生产 bot 上做不确定的逆向 spike 前,先想清楚"最坏情况会不会卡死/崩微信",宁可慢、宁可分更小步。相关 [[recordspeak-refactor-progress]]。