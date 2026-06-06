---
name: wechat-redpacket-receiver-list-reflection
description: 微信红包领取者列表(昵称+金额+wxid)在内存里的位置与字段，纯反射读取法
metadata: 
  node_type: memory
  type: reference
  originSessionId: 3cbb9098-fbe3-4e37-8615-e1be36f085db
---

红包统计插件 RedPacketStats 的核心突破(2026-06-05,微信 1.2.7.r1398)：领完后"看大家手气"金额列表的**数据**可用**纯反射**从详情页 Activity 对象图里读到,**零 UI 遍历 → 不卡不 ANR**(前三轮遍历 View 树都卡/ANR,因为页面动画重+RecyclerView 虚拟化)。

**位置**:金额列表页 Activity = `com.tencent.mm.plugin.luckymoney.ui.LuckyMoneyNewDetailUI`(领完→点红包→封面 NewReceiveUI→点封面下方小字进)。领取者列表挂在该 Activity 的一个字段(spike 时是 `L1`,LinkedList),元素类 `com.tencent.mm.plugin.luckymoney.model.v4`。

**v4 字段(spike 时的混淆名)**:`d`=昵称(String)、**`f`=金额(long,单位「分」,÷100=元;手气最佳 f 最大)**、`n`=领取者 wxid(String)、`g`=领取时间(String,epoch 秒)、`m`='手气最佳'标记(非最佳空串)、`i`=红包/领取 id。

**致命注意——别写死混淆名**:`L1`/`v4`/`d`/`f`/`n` 都是 R8 混淆符号,**每个微信版本都会变**。正式实现要用**结构化反射**抗版本:对象图 BFS 找"非空 List,元素类在 `com.tencent.mm.plugin.luckymoney.model` 包下"→ 在元素里按**类型+特征**认字段(long 且在合理分值域=金额;String 匹配 `wxid_` 前缀=wxid;另一非空 String=昵称),而不是认 `f`/`n`/`d` 这些名字。

**读取法(ANR 安全铁律)**:hook `Activity.onResume` 过滤类名含 `LuckyMoneyNewDetailUI` → identityHashCode 去重 → `delay(3500)` 等 cgi 数据 → **后台线程**纯反射(`getDeclaredFields`+`setAccessible`+`get`,带 MAX_VISIT/MAX_DEPTH 上界)。**绝不调用任何 View 方法**(getText/getChildAt/getWindow…)。见 [[device-spike-anr-safety]]。

**待解(下一步)**:bot 要自己拿到数据就得让 NewDetailUI 打开(数据是 cgi 拉的、只活在 Activity 实例里)。能否用抓到的 intent(34 个 extra:key_native_url/key_sendid/key_msgid/key_username/key_from_username/key_packet_id…)直接 startActivity 拉起 NewDetailUI 待验证(直接拉 DetailUI 曾被弹回 LauncherUI)。相关 [[recordspeak-refactor-progress]]。