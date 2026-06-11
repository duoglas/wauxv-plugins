---
name: waux-msg-detect-gettype-unreliable
description: "WAuxiliary msg.getType() 返回大数不可靠, 微信消息类型检测一律走 content 标签判据; 转账每笔2条消息"
metadata: 
  node_type: memory
  type: reference
  originSessionId: 4ad569b5-c701-4bf4-b1dd-d57cfae7b92d
---

2026-06-11 真机嗅探（临时只读探针）实测的 WAuxiliary 消息检测事实，做新消息类型功能（排行榜等）前必看：

1. **`msg.getType()` 不可靠**：真机返回大数（拍一拍=922746929、转账=419430449、appmsg/引用=822083633、纯文本里夹关键字=1…），**不是标准微信消息枚举**（text=1/image=3/appmsg=49/sys=10000）。→ **消息类型检测一律用 content 子串/XML 标签判据，不要比 getType 数值。**

2. **关键字会误命中聊天**：用"转账"二字判会把"我要转账"这种打字内容误判（type=1）。→ 结构化消息必须用 XML 标签判据。

3. **判据表（已坐实）**：
   - 拍一拍：content 含 `拍了拍`，形如 `"${A}" 拍了拍 "${B}"` → 正则 `"\$\{(.+?)\}"\s*拍了拍\s*"\$\{(.+?)\}"` 提拍方/被拍方 wxid。
   - 转账：content 含 `<wcpayinfo>` → `<feedesc>￥金额`/`<payer_username>`/`<receiver_username>`/`<transferid>`(去重)/`<paysubtype>`(1发起,3收款)。
   - 红包：content 含 `<nativeurl>`（现成 RP 模块）。

4. **转账每笔=2条群消息**：发起(paysubtype=1)+收款(paysubtype=3)，**同一 transferid**，按 transferid 去重、单一 subtype 计一次。群聊里转账成立（talker=...@chatroom）。

嗅探范式：onHandleMsgBody 顶部插临时只读探针（PROBE_ON 闸门+self-disarm+整段 try/catch），命中标签写 rp.log `[PROBE]`，build→check.sh→部署→真机触发→读样本→还原探针+清日志。设计落地见 `docs/superpowers/specs/2026-06-11-排行榜-ranking-design.md`。关联 [[waux-getstring-fuse-io-cache-hotpath]] [[waux-plugin-root-deploy-pitfalls]]。
