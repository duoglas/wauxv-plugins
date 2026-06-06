# 当前迭代计划 — GroupAdmin recordSpeak 重构

> 消除 VH-01：L3 潜水采集（recordSpeak）同步压在 onHandleMsg 热路径上，每条消息对整群 CSV 做 O(N) 读改写。
> 范围：全套 RB-1~4 含分片 + lsg_ 超期裁剪（fsg_ 不裁）。用户已确认。

## 根因（main.java:1433 → recordSpeak 196-211）

每条普通群消息同步 `parseTimeCsv(getString(lsg_)) → put → putString(buildTimeCsv)`，O(群人数) 读-解析-序列化-写回 + 单 key 无上界（66KB）。违反 C-PERF-01/02、C-ARCH-01/02。

## 任务清单（每步 ≤15min，逐步真机验证）

| # | 任务 | 对应 | done 条件（真机） | 风险 |
|---|---|---|---|---|
| T0 | versions/ 备份 + adb pull config.prop 基线 + 记录 lsg_/fsg_ 体量 | C-PLUGIN-02 | 可回滚 + 基线已记录 | low |
| T1 | onHandleMsg 入口埋点（先行拿改造前基线） | RB-3 / C-PERF-04 | 性能日志有数据；拿到改造前 recordSpeak 平均耗时 | medium |
| T2 | recordSpeak 内存缓冲 + delay() 后台 flush + onUnload 补 flush | RB-1 / C-PERF-01/03, C-ARCH-02/03 | 高频发消息无延迟；key 被定时更新；不丢数据 | **high (Santa)** |
| T3 | lsg_/fsg_ 按桶分片 + onLoad 迁移 + flush 裁 lsg_ 超期 | RB-2 / C-PERF-02 | 踢潜水名单一致；单 key 体量降；大群 flush 不阻塞 | **high (Santa)** |
| T4 | L3 降级开关（超阈值/flush 失败 → 暂停采集留痕） | RB-4 / C-ARCH-02 | 模拟失败触发降级，收发不受影响 | medium |
| T5 | 全矩阵回归 + 埋点前后对比 + Layer 4 Santa 双 Reviewer | C-PLUGIN-04 | SPEC §9 七场景全 PASS | review |

## 不可逆动作（已在 PLAN 确认）

- 每任务后 adb push 覆盖真机插件 + 重载/重启微信
- T3 删除旧 lsg_/fsg_ 单 key 并迁移分片（T0 先备份 config.prop）
- lsg_ 裁剪永久丢弃超期发言记录（用户确认接受；fsg_ 保留）

## 执行约束

- 实现代码全部由 Implementer Agent 写（引用 Constraint ID），Director 不直接写插件代码
- T2/T3 高风险 → VERIFY 后走 Layer 4 Santa Method 双独立 Reviewer
- 改行为先更新 SPEC [目标]→[现状] 再改 main.java（C-PLUGIN-03）
- flush 周期默认 30s；lsg_ 裁剪阈值默认 90 天
