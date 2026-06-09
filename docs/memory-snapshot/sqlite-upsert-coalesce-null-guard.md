---
name: sqlite-upsert-coalesce-null-guard
description: SQLite UPSERT 里 max/min 碰可能为 NULL 的旧列必须 coalesce 兜底(已咬两次)
metadata: 
  node_type: memory
  type: feedback
  originSessionId: f9ac55d9-2280-459e-ac7e-bf8b80fdc9f8
---

SQLite 标量 `max(NULL, x)` / `min(NULL, x)` **返回 NULL**(不是 x)。所以 `INSERT ... ON CONFLICT DO UPDATE SET col=max(col, excluded.col)` 当已有行 `col` 为 NULL 时,会**永远卡 NULL**,新值进不去。

**规则:UPSERT 的 `DO UPDATE SET col=max/min(col, excluded.col)`,只要 `col` 在某些行可能为 NULL,就必须写成 `max/min(coalesce(col, excluded.col), excluded.col)`。**

**Why:** 这个坑在本项目咬了两次——
- GA v1.19.2:`last_speak=max(last_speak,excluded)` → 先建基线(last_speak=NULL)再发言者永远卡 NULL → 发言统计/#潜水/伸手党全失真。
- GA v1.20.0:`l3UpsertFirstSeen` 的 `first_seen=min(first_seen,excluded)` → force-rebuild 清空(first_seen=NULL)后补不进。

何时会出现 NULL 旧列:建行时只填了另一列(last_speak-only 或 first_seen-only 路径)、或 `UPDATE SET col=NULL` 清空(如 rebuildBaselineForce)、或基线重置。

**How to apply:** 写/审任何 BeanShell 插件(plugins/group-admin、redpacket-stats)的 SQLite UPSERT,见到 `max(`/`min(` 套旧列,先问"这列会不会是 NULL"——会就 coalesce。受控验证法:拉一份 .db 副本,构造 NULL 行跑新旧 UPSERT 对比(老的留 NULL=坐实)。相关 [[storage-substrate-sqlite-over-csv]]、[[project-status]]。
