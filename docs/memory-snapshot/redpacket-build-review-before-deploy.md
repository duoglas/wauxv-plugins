---
name: redpacket-build-review-before-deploy
description: "红包统计这类复杂/敏感改动:先写好代码等用户人工 review,再部署到真机"
metadata: 
  node_type: memory
  type: feedback
  originSessionId: 3cbb9098-fbe3-4e37-8615-e1be36f085db
---

做红包统计 DB+导出这个需求时,用户明确:"这个需求做好以后,不要着急发到手机,要我人工 review 以后再看。"

**Why:** 红包插件已经很复杂(队列/反射/开页/发送),又要加 DB 持久化+导出私聊,改动面大、且涉及"每天往某号私聊发数据"这种外发动作;用户要在上机前先过一遍代码,降低线上风险。

**How to apply:** 这类大改/敏感改动(DB、外发消息、热路径附近),Implementer 只写代码到工作树、**不 push/不 cp 到设备/不 force-stop 重启**;先把 diff/设计摘要呈给用户人工 review,用户批准后再部署。区别于之前红包迭代的"改完即上机实测"快循环。相关 [[recordspeak-refactor-progress]]、[[wechat-redpacket-receiver-list-reflection]]。