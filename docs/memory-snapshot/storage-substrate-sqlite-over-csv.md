---
name: storage-substrate-sqlite-over-csv
description: GroupAdmin 潜水数据落盘从 config.prop 整群 CSV pivot 到自有 SQLite；遇存储瓶颈先质疑底座再打补丁
metadata: 
  node_type: memory
  type: feedback
  originSessionId: 3cbb9098-fbe3-4e37-8615-e1be36f085db
---

修 GroupAdmin recordSpeak(VH-01) 时，我一开始顺着现有 config.prop/CSV 存储往下打补丁（内存缓冲+后台flush+分片+手搓 DISK_LOCK 解并发）。用户打断问"为啥非得用文件？引入轻量级数据库怎样，对比分析看看"——这是对的方向。

真机 spike 验证(2026-06-05)：BeanShell 能驱动 `android.database.sqlite.SQLiteDatabase`，在微信进程内开自有库可行；单行同步 upsert 27.7ms(不可放热路径)、批量500行/事务 34-54ms(后台可接受)、查询 800µs、DELETE 513µs。pivot 到 SQLite 不仅可行，还**结构性消灭了 Santa 揪出的整群 read-modify-write lost-update bug**(行级 UPSERT 没有整群 RMW)，并让分片(RB-2)整个消失、裁剪不再必需。坑：`PRAGMA journal_mode=WAL` 必须走 `rawQuery` 不能 `execSQL`。

**Why:** 把"整群 CSV 塞进单 KV key"当成既定底座去优化，是在错误的抽象上叠复杂度——分片、手搓锁都是为了补 CSV 的先天缺陷。换对底座(行级 DB)后这些复杂度直接不存在。

**How to apply:** 遇到存储/性能瓶颈，先问"底座选对了吗"再优化现有结构，不要默认沿用文件/CSV。对架构岔路：先做低风险真机 spike 拿数据，再决策，别拍脑袋。相关：[[recordspeak-refactor-progress]]。