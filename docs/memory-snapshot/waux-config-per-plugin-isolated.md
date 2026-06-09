---
name: waux-config-per-plugin-isolated
description: WAuxiliary getString/putString 是每插件各一份 config.prop，插件间配置不共享
metadata: 
  node_type: memory
  type: reference
  originSessionId: f9ac55d9-2280-459e-ac7e-bf8b80fdc9f8
---

WAuxiliary 的 `getString/putString` 背后是**每个插件目录各一份 `config.prop`**(纯文本 `key=value`，`Plugin/<名>/config.prop`)，**插件之间不共享配置**。两个 SQLite 库也在各自插件目录(`GroupAdmin/groupadmin.db`、`RedPacketStats/redpacket_stats.db`)。

实测核实 2026-06-09:`Plugin/GroupAdmin/config.prop`(9KB) 与 `Plugin/RedPacketStats/config.prop`(2.2KB) 各自独立。这就是**红包伸手党当前只能"读 groupadmin.db + 发 `[AtWx]警告` 命令让群管解析"绕一圈**的根因——配置不共享，跨插件没法直接调函数/读配置。

键前缀(避免合并时撞)：
- RedPacketStats：**全部** `rp_` 前缀。
- GroupAdmin：`admins_/admin_exp_/bl_/bp_/fsg_/lsg_/wt_/wk_/protected_/enabled_groups` 等，**无 `rp_`**。
- 两份键集**不相交** → 合并时 config 可直接 union(把 RP 的 config.prop 并进合并插件的 config.prop)。

**How to apply:** 设计跨插件协作时别假设配置共享(它不共享)；合并两插件(见 [[project-status]] 的 GA+RP 合并项)迁移 = config union(rp_ 前缀零冲突) + DB 按绝对路径接入 + 停旧插件防双重处理。GroupAdmin 曾把 `lsg_/fsg_` 从 config.prop CSV pivot 到 SQLite(见 SPEC §5)就是因为单 key CSV 在 config.prop 里 O(N) 膨胀。相关 [[config-key-written-on-transition-upgrade-gap]]、[[storage-substrate-sqlite-over-csv]]。
