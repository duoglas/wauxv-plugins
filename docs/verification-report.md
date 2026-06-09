# VERIFICATION REPORT — RedPacketStats v1.11.3 伸手党治理收尾

日期: 2026-06-09 · 迭代: redpacket-freeloader-finalize-v1.11.3 · 风险: high(破坏性上游=累计触发群管踢人)

## 变更摘要
撤 DRY-RUN/GFDBG 调试态、恢复真发送警告。豁免模型改「全局开关时刻宽限」(`rp_freeloader_since_<gid>`，OFF→ON 写当前 ms；判定段 `since<=0` 或 `packetMs-since<winMs` 整包跳过)；宽限后所有领取者按 `last_speak` 判，不用 `first_seen`(与 #潜水 区分)。复用红包排除名单 `getExcludeList` 豁免。默认关。详见 SPEC §25.9。

## QA 金字塔结果

| 层 | 项 | 结果 | 证据 |
|---|---|---|---|
| L1 Agent 自验 | 改后即查 | PASS | 无 GFDBG/wouldWarn 残留代码(仅注释/日志串)；安全 grep 无 api_key/Bearer/silicon |
| L2 验证回路 | bsh 解析 | PASS | `bsh.Parser` 去 final 全文解析 4023 行 EXIT=0(无 ParseException) |
| L2 | 部署 | PASS | adb push → su cp → chmod660；info.prop=1.11.3；设备 main.java 273650B |
| L2 | 加载 | PASS | 重启微信后 rp.log 两次打出 `v1.11.3 loaded`；LSPosed verbose 无 RedPacketStats eval/parse/TargetError |
| L2 | 副作用(热路径) | PASS(评审) | since 写入仅命令/Dialog(非热路径)；判定全程后台 fire-and-forget 线程；onHandleMsg 零改 — 双 Reviewer 确认 |
| L2 | Security | PASS | grep 无密钥；新日志仅 wxid 尾4 + NULL/old + 计数，无昵称/完整 wxid |
| L2 | Diff | PASS | 仅 RedPacketStats main.java/info.prop/SPEC.md |
| L3 Spec 符合 | 独立 Reviewer B | PASS | R1-R5 全 ✓；无多做/少做；版本三处一致；`关`/`窗口`命令未误改 |
| L4 Santa 对抗 | Reviewer A ∧ B | NICE | A(逻辑/安全)+B(规格) 两独立 Reviewer 均 PASS；含 T1 边界 `==winMs` 不豁免、OFF→ON transition、闭包 final、BeanShell 兼容 |

## 行为实测(T1-T5)状态: 部分 PENDING-手动

真红包触发 `hbDetailExtract` 详情页路径 **adb 无法模拟**(不能输中文/不能发抢红包)。功能**默认关**，未在任何群启用。

- 可自动验证项(已做): bsh 解析、干净加载、逻辑/规格双审 — **PASS**。
- 需手动 scrcpy 实测项(用户在场、单测试群、临时 `伸手党 开`):
  - T1 宽限内抢包 → rp.log `within activation grace`，零警告。
  - T2 宽限后: `last_speak` NULL 者 → 发警告；窗口内发过言者 → 不发。
  - T3 排除名单内者 → 不发。
  - T4 命令/Dialog OFF→ON 写 since；已 ON 重存不重置。
  - T5 日志脱敏；安全网(since<=0/packetMs<=0/ls<0)跳过。

## ⚠️ 启用前提醒(数据自愈期)
`last_speak` 刚修复(GA v1.19.2)+刚重置，仍大量 NULL(自愈中)。30min 全局宽限盖不住多小时自愈期 → 此刻启用会批量误警告。**建议数据自愈后或仅在小测试群临时开**。本轮交付保持默认关，不启用、不踢任何人。

## Overall: READY(代码层)/ 行为实测 PENDING-手动(默认关，无风险)

回滚: 设备留底 `main.java.bak-before-v1113-20260609`。
