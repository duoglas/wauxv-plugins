---
name: wechat-quote-reply-appmsg-structure
description: 微信引用回复消息在 WAuxiliary onHandleMsg 里的真实结构(appmsg/XML)，解析方法
metadata: 
  node_type: memory
  type: reference
  originSessionId: 3cbb9098-fbe3-4e37-8615-e1be36f085db
---

GroupAdmin 做"引用回复触发动作"时，真机 [QDIAG] 探针实测引用回复消息的结构(2026-06-05)：

- `msg.isText()` = **false**(引用回复是 appmsg，不是文本)→ onHandleMsg 开头 `if(!isText()) return` 会直接丢掉，必须放行(看 getContent 非空)。
- `msg.getType()` = 822083633(appmsg refer 类型编码)。
- `msg.getContent()` = **整段 XML**(~1200字符)，形如 `<msg><appmsg><title>回复文本</title>...<refermsg><chatusr>被引用者wxid</chatusr><content>被引用原文</content></refermsg></appmsg></msg>`。**不是**干净的回复文本。
- 管理员打的回复词(如"警告")在 **`<title>`** 里 → 用正则 `<title>(.*?)</title>` 取，再判 isActionTok。
- 被引用者 wxid = `msg.getQuoteMsg().getSendTalker()`(实测 19 字符，有效)；`getQuoteMsg().getContent()` = 被引用原文。

**踩过的坑**：W2 初版假设 `getContent()` 返回纯回复文本，用 `content.split()` 取 lastTok → 在 XML 上取到碎片 → isActionTok=false → 动作分发前就 return，引用警告完全不触发，且下游探针无日志。

**How to apply**：解析引用回复必须从 `<title>` 取回复词、`getQuoteMsg().getSendTalker()` 取目标；`getQuoteMsg()` 只在 `!isText()` 时调(普通文本热路径不付成本，C-PERF-01)。任何"解析消息内容"的功能先用真机探针看真实结构，别假设 getContent() 是纯文本。相关 [[recordspeak-refactor-progress]]。