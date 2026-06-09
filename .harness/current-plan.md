# PLAN — 合并工程 P3-b（设备 seam 移植，纯本地产出 + L3 解析 + 代码审查，零真机）

## 上游状态
- ✅ P3-a 完成（commit 7e06970）：redpacket_app.bsh 领域装配 + 伸手党 in-process 直连 doWarn，check.sh 17/17 绿。
- 本期 = P3-b：把线上 `plugins/redpacket-stats/main.java` 的**设备层**逐字保真移植进 merged app/hooks，
  接掉 P2-a/P3-a 留的 seam（commands LEAF、`_msgGet` 反射缝、isGroupEnabled、RECV 端口、sendQuote）。
- **零真机**：设备层本地测不了（反射/无障碍 UI/worker/Job/真发送）。本地验收上限 = L3 装配体 bsh 解析 EXIT=0 + 独立 Code Review 逐字保真 + 热路径复杂度审（C-PERF-01/03）。真机留 P3-c。

## 铁律（贯穿每个任务）
1. **逐字保真**：以线上 main.java 为唯一事实源，移植不重写；行为零改动，只换载体（拼接式单文件→模块）。
2. **热路径零 O(N)**（C-PERF-01/03）：合并后红包检测与 GA 群管在**同一 onHandleMsg**，互斥分支；热路径只做廉价 type/含 `<nativeurl>` 判定 + 入队，重活全在 worker。getString 必走内存缓存（FUSE IO 教训）。
3. **降级红线不绕过**：伸手党 无快照/失败/无此人 = SKIP，绝不误踢（P3-a 已立，P3-b 接调度不得破坏）。
4. 每任务完成即 `./build.sh` + 装配体去 final 全文 bsh 解析 EXIT=0（beanshell-parser-stricter 教训），check.sh 保持全绿。

## 任务拆解（每个 ≤ 独立可 L3 验证；按依赖序）

- **T1 设备基座 + 反射工具**：移植 UI 常量(UI_RECEIVE 等)/hbBgLog/hbNow/反射辅助/去重 key/perf 埋点缝到新 `app/device_*.bsh`（或并入 hooks 基座段）。done：模块解析 EXIT=0。
- **T2 检测热路径合一**：onHandleMsgBody 红包分支（廉价判定→入队 raw content，dedup k:talker:sender:msgid）并入合并 hooks 的 onHandleMsg，与 GA 群管互斥分支；接掉 `_msgGet` 反射缝。done：装配体解析 EXIT=0 + 热路径复杂度审无 O(N)。
- **T3 worker + Job 调度/重试**：单飞 tick(看门狗 30s/dueAt 出队)、hbProcess(专属早退/nativeurl/rpExtractTitle 回填 job)、三段重试阶梯/jitter/JOB_DETECTMS 承载 packetMs。done：解析 EXIT=0 + 审查对齐线上 §15/§4。
- **T4 UI 自动化**：NewReceiveUI/NewDetailUI onResume 路由（有界查找详情入口/performClick/finish/重试/封面连带 finish）。done：解析 EXIT=0 + 审查对齐 §1。
- **T5 反射领取者 → RECV 端口 + 伸手党调度接线**：hbDetailExtract/hbExtractTriple(d/f/n 首选+抗版本兜底) 实现 P3-a 的 `RECV` 生产适配器；接 freeloaderRunForPacket 的 T_snap=packetMs+G 延迟拍照调度 + G 夹取 + 拍照终结竞态#24 + l3Flush in-process。done：解析 EXIT=0 + 降级红线 mutation 不破坏 + 审查。
- **T6 发送**：hbTierAndSend 接 domain tiers/qualify(已抽) + sendQuoteMsg 引用条 + @条 sendText + 随机语气/限频/间隔延迟；接掉 host_android sendQuote 占位。done：解析 EXIT=0 + 审查对齐 §22.6。
- **T7 设置 UI 并入**：RP showConfigDialog + 子页(Basic/NormalTiers/Custom/Delay/Exclude/Forward/伸手党) 并入合并 dialogs（与 GA dialogs 共存），rp_ 文字命令接 commands LEAF 路由。done：解析 EXIT=0 + 审查对齐 §23/§24/§25。
- **T8 onLoad 合一 + seam 收口**：GA 端口接线 + l3FlushLoop + RP worker tick 启动 + 旧毫秒键清理 + 冷启动日志；接掉 commands 占位 `_cmdGroupEnabled` ↔ hooks isGroupEnabled 同源（P1-d 标的必盯缝）。done：装配体解析 EXIT=0 + check.sh 全绿。
- **T9 验收（Layer 3/4）**：build → 装配体去 final 全文解析 EXIT=0；独立 Code Reviewer Agent 对照线上逐字保真，重点热路径(C-PERF-01/03)+伸手党降级红线+seam 同源，输出 PASS/FAIL；高风险段(热路径合一)走 Santa 双审。done：0 CRITICAL。

## ⚠️ 真机不可逆动作：本期一个都不碰
P3-b 全程纯本地产出 + 解析 + 审查。adb push / 改 per-group 配置 / 真机发红包 / 伸手党触发 doWarn 全部留 P3-c（届时另开 PLAN + 用户在场 scrcpy + Santa 双审 + 灰度单测试群破"测试须先 cutover"死结）。

## 产出边界（YAGNI）
- 本期只到设备 seam 移植 + L3 解析 + 代码审查。
- 不在本期：P3-c 灰度真机 E2E、P2-b 最终 cutover。

## 已确认决策（2026-06-09）
1. ✅ 同意 9 任务拆法与顺序（T1→T8 移植、T9 验收）。
2. ✅ 设备层用**多模块拆分**：新建 `app/device_detect.bsh`/`device_worker.bsh`/`device_ui.bsh`/`device_recv.bsh`（与方案 B"不要单一巨型"一致，便于分任务审查）。
3. ✅ session 接力：**每 2-3 任务一个 commit 节点**，到 ~400K tokens 主动开新 session（规避长 session tool-call 退化，CLAUDE.md 教训）。
