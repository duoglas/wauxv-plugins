# VERIFICATION REPORT — GroupAdmin v1.20.0

日期: 2026-06-09 · 迭代: groupadmin-lurk-exempt-and-baseline-v1.20.0 · 风险: medium(#潜水 是踢人前置参考, 手动列表非自动踢)

## 变更摘要
(1) `#潜水` 新成员豁免改固定 1 天(`LURK_NEWMEMBER_EXEMPT_MS`，与潜水窗口 days 解耦)；(2) onLoad 对所有已启用群补 first_seen 基线(去 `l3CountFirstSeen>0` skip，幂等)，堵伸手党漏检窄缝；(3) `l3UpsertFirstSeen` UPSERT 加 coalesce 防 NULL(对齐 v1.19.2)。详见 GroupAdmin SPEC v1.20.0。

## QA 金字塔结果

| 层 | 项 | 结果 | 证据 |
|---|---|---|---|
| L2 | bsh 解析 | PASS | `bsh.Parser` 去 final 全文 4039 行 EXIT=0 |
| L2 | 部署 | PASS | adb push → su cp → chmod660；info.prop=1.20.0；设备 main.java 196510B；留底 main.java.bak-before-v1200-20260609 |
| L2 | 加载 | PASS | 重启后 plugin.log `v1.20.0 loading` + `v1.20.0 baseline check: 已启用群9 补基线0 新增0`(全员已有基线，幂等)；无 eval/parse/TargetError |
| L2 | **R3/T4 coalesce(受控 DB 实测)** | PASS | 拉 groupadmin.db 副本，真实 schema 跑新旧 UPSERT 对比 |
| L2 | Security | PASS | 仅改 first_seen 写法/潜水豁免，无密钥、无敏感日志新增 |
| L2 | Diff | PASS | 仅 GroupAdmin main.java/info.prop/SPEC.md |
| L3 Spec 符合 | Reviewer B | FIX→PASS | R1-R4 + 多做/少做/回归逐项 ✓；首轮抓到 onLoad catch 块 stale `v1.17.0`(R4 一致性)→ 已修 v1.20.0 |
| L4 Santa | A ∧ B | NICE | A(逻辑)全 PASS；B(规格)FAIL→修复(版本串)→ 复验三处运行时串全 v1.20.0 + 干净重载 |

### R3/T4 受控 DB 实测明细(直接验证 coalesce 修复)
真实 groupadmin.db 副本(schema `speak(grp,wxid,last_speak,first_seen)`)构造三类行跑新 UPSERT：
- `first_seen=NULL` → 被设为 now ✓(老 UPSERT `min(NULL,x)` 留 NULL — 已对比坐实)
- `first_seen=更早值` → 保持不变(min 不覆盖)✓
- 无行成员 → 插入 first_seen=now ✓

## 行为实测(T1/T2 #潜水 名单)状态: PENDING-手动
`#潜水 N` 需在微信群里发中文命令触发，adb 输不了中文 → 需手动 scrcpy 实测。逻辑正确性已由 bsh 解析 + Santa 双审(A 确认三类成员分类正确、admin/owner/protected 豁免不变)覆盖。
- T1: 进群>1天没发言 → 上榜；进群<1天 → 豁免。
- T2: 窗口内发过言/admin/owner/protected → 不上榜。

## ⚠️ 影响提示
`#潜水` 豁免缩短到 1 天后名单会更全(进群 1~N 天内从没发言者也上榜)。仍是**手动列表，不自动踢**，由管理员据名单决定 `#踢潜水`。

## Overall: READY(代码层+DB实测)/ #潜水 名单行为 PENDING-手动
回滚: 设备留底 `main.java.bak-before-v1200-20260609`。
