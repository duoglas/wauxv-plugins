# 验证证据 — GroupAdmin recordSpeak 重构（消除 VH-01）

> 真机 Pixel 8a / akita，2026-06-05。底座 pivot 到 SQLite。证据均真机实测/本地 sqlite3 独立核验。

## 1. 性能（头条，前后对比）

| 指标 | 改造前(T1基线) | 改造后 | 量级 |
|---|---|---|---|
| recordSpeak 单次 | ~2,000,000 µs (~2s, O(群人数) 同步CSV读改写) | ~1,100–1,300 µs (内存O(1)入队) | **降 ~1600×** |
| onHandleMsg 总耗时 | ~2,000,000 µs | ~10,000–27,000 µs | 降 ~100× |
| 热路径磁盘 IO | 每条消息整群 CSV 读改写 | **0**（仅内存，后台批量落盘） | — |

证据：真机 perf.log，改造前 `rs_avg_us=2,038,941~2,115,314`；改造后稳态 `rs_avg_us=825~1749`，跨群（含 501 人大群）不随群人数增长。

## 2. 存储迁移（CSV → SQLite，本地 sqlite3 独立核验）

- 旧：config.prop 整群 CSV，总 66KB，单 key 最大 16862B（fsg_）。
- 新：自有 SQLite `speak(grp,wxid,last_speak,first_seen, PK(grp,wxid))` + 索引 `idx_speak_grp_last`，225KB。
- 迁移等价性：8 群逐群行数精确吻合（281/501、82/483、61/125、62/83、58/71、21/31、6/8、2/2）；总 1304 行 / lsg 573 / fsg 1304。
- 完整性：`last_speak < first_seen` 违例 **0 行**（min/max UPSERT 语义正确，first_seen 永不改晚）；`PRAGMA integrity_check=ok`。
- 旧 CSV key 非空值 = 0（已清空，数据只在 DB，无重复）。migrated 标志守卫只迁一次。
- selftest 测试污染（grp=selftest_grp 2 行）已用停机改库法清除，重启后 0 个非@chatroom 行、8 群、integrity ok。

## 3. 并发正确性（Santa Method Layer 4，第 2 轮）

- **A_VERDICT: PASS**（并发/生命周期/连接/WAL回退）+ **B_VERDICT: PASS**（数据完整性/迁移/读点/误踢）→ **NICE**。
- 行级 `INSERT ... ON CONFLICT DO UPDATE max/min` 结构性消除了上一轮 NAUGHTY 的整群 RMW lost-update（曾可致误踢）。
- 锁：热路径 recordSpeak 仅 L3_LOCK（内存 O(1)），永不碰 DB/FLUSH_LOCK；flush/读点磁盘操作由 FLUSH_LOCK 串行化（不在热路径）。锁序 FLUSH_LOCK 外 / L3_LOCK 内，无死锁。
- 第 1 轮 Santa 找出的 CSV 跨线程 lost-update（误踢级）已随 pivot 消失。

## 4. L3 降级（C-ARCH-02，真机三路径实测）

- 触发器：缓冲条数≥5000（O(1) 计数器）/ flush 连续失败≥5。满任一 → recordSpeak 丢新增量保收发。
- 恢复：flush 成功且缓冲排空到 <2500 自动解除。留痕写 perf.log（`L3_DEGRADE enter/exit reason= dirty= fail_streak=`，无 PII）。
- 真机：fail_streak（DB 指不可写路径逼失败）、buffer_cap（CAP=3 注入）、exit 恢复 三路径均触发并留痕。
- 红线静态可证：L3_DEGRADED 仅被 recordSpeak + 2 个降级 helper 引用，命令/L1/L2 路径零引用。

## 5. 埋点（C-PERF-04）

- onHandleMsg 入口埋点写独立 perf.log（不污染 plugin.log），可 grep/awk 聚合，含 onHandleMsg/recordSpeak 平均·最大耗时、命令/普通分支计数。提供了改造前后对比数据（见 1）。

## 6. Security

- group-admin 改动 0 新增泄漏；perf.log/降级留痕含 wxid/chatroom = 0；*.db 已 gitignore（不入版本）。

## 7. §9 测试矩阵覆盖

| 场景 | 状态 | 证据/说明 |
|---|---|---|
| 普通消息高频/大群(≥500人) | ✅ VERIFIED | perf.log rs_avg ~1.2ms 稳定，不随群人数增长，无积压 |
| 热路径去 O(N) | ✅ VERIFIED | 前后对比 2s→1.2ms |
| 迁移正确 | ✅ VERIFIED | 本地 sqlite3 逐群等价 + integrity ok |
| 并发/降级 | ✅ VERIFIED | Santa NICE + 真机降级三路径 |
| 性能埋点聚合 | ✅ VERIFIED | perf.log |
| L1 管理命令(踢/警告/拉黑/严格模式) | ⏳ 待用户真机 | 命令分发逻辑本次未改(静态)；adb 无法发微信消息，需用户在受控群验收 |
| L2 查询命令(读路径改 DB) | ⏳ 待用户真机 | 读点已改 SQLite 查询 + flush-before-read，需用户发命令抽验输出 |
| #潜水 N / #踢潜水(破坏性 L1) | ⏳ 待用户真机 | 读路径 CSV→DB + flush-before-read 变更，需受控群验证名单正确、刚发言者不误判 |
| 边界/异常消息 | ◑ 部分 | recordSpeak 入口守卫未改；organic 流量未见异常 |

## 8. 已知非阻断项

- A#4：main.java 头注释 v1.14.0 vs loading 日志 v1.15.0（纯注释，不影响行为，未为此再重启生产）。
- B#7：rebuildBaselineForce 并发亚秒级偏差（良性，不误踢，已记 SPEC §7）。
- B#8：迁移仅遍历 enabled 群，disabled 群旧 CSV 孤儿；日后重启用走冷启动良性重建（不踢人）。已记 SPEC §7。
- WAL 在 FUSE/emulated 存储回退 journal_mode=delete：事务仍原子，synchronous=NORMAL 生效，无碍。

## Overall: READY（自动化可验证项全 PASS；命令行为路径待用户真机验收）
