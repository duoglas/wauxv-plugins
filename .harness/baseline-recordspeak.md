# recordSpeak 重构 — T0 基线（改造前）

> 采集时间：2026-06-05（session）。真机 Pixel 8a / akita。
> config.prop 副本（gitignored）：`plugins/group-admin/versions/config.prop.baseline-20260605-073212`
> main.java 备份：`plugins/group-admin/versions/main.java.before-recordspeak-refactor-20260605-070117`

## config.prop 体量基线

- 总体量：**66908 B**，68 行（64 行为群级 key）
- 启用群数：8（`enabled_groups`）

### 按前缀归类

| 前缀 | 总字节 | 群数 | 单 key 最大 | 占比 | 备注 |
|---|---|---|---|---|---|
| `fsg_`（first_seen） | 44116 | 8 | 16862 | 66% | **最大头；不裁，靠分片控上界** |
| `lsg_`（last_speak） | 19495 | 8 | 9436 | 29% | 裁 90 天超期 + 分片 |
| `admins_` | 952 | 4 | 287 | — | 不动 |
| `wnf_`/`wnt_`/`wn_`（警告） | ~1830 | 12+12+13 | ≤64 | — | 不动 |
| `wl_` / `bp_` | ~230 | — | ≤63 | — | 不动 |

`fsg_ + lsg_` = 63611 B（占 95%）。这两个 CSV key 就是 VH-01 的体量根因。

## 热路径耗时基线（T1 埋点实测，2026-06-05 07:41 真机 perf.log）

| 分支 | onHandleMsg 耗时 | recordSpeak 耗时 |
|---|---|---|
| 普通消息（走 L3 采集） | **~2.0–2.1 s**（2010–2145 ms） | **~2.0–2.1 s**（2010–2115 ms） |
| early-return / 非采集 | **652–727 µs** | 0 |

- 改造前 `recordSpeak` 平均耗时：**约 2,000,000 µs（~2 秒）/ 次**，单群约 500 人 CSV。
- onHandleMsg 总耗时几乎 100% 由 recordSpeak 贡献（去掉采集后仅亚毫秒）。
- 结论：单条普通群消息阻塞微信消息线程 ~2 秒（O(群人数) 同步 CSV 读改写），消息串行 → VH-01 积压。
- **T2 验收口径**：改造后 recordSpeak 入队应降到 µs 级（O(1) 内存 put），perf.log 的 `rs_avg_us` 应从 ~2,000,000 降到 <100。

## 验收对比口径（T5 用）

- 体量：改造后单 key（lsg_/fsg_ 分片）应显著低于 16862 B 上界
- 热路径：改造后 recordSpeak 入队耗时应 ≈ O(1)，不随群人数变化
- 踢潜水名单：改造后 `#潜水 N` 输出与改造前一致（迁移正确性）
