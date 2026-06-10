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

## 进度更新
- ✅ **T6 完成 (commit bd7f4b1)**：发送层 `redpacket_send.bsh` + host sendQuote 健壮回退 + domain rpQueryWindow。check.sh GREEN 21/21，独立 Review 0 CRITICAL。详见 docs/memory-snapshot/project-status.md。
- 🔄 **T7 进行中**：
  - ✅ **W1 盘点完成**（下方）。
  - ✅ **W2+W3 完成**（RP 设置 UI 全套并入，Implementer Agent 移植 + 我独立复核）：
    - 新增 **995 行 / 0 删除**（纯加载体），5 文件：dialogs.bsh(+815, 主菜单+7子页+UI helper)、redpacket_send.bsh(+71, isDailyEnabled/enable·disableDailyGroup/rpExportToday端口化)、redpacket_qualify.bsh(+33, setCustomOn/setCustomKw/removeExclude)、redpacket_tiers.bsh(+51, cfgT1-3/cfgTxt1-3/rpParseTierBox/rpAppendErr)、redpacket_detect.bsh(+25, rpEnableGroup/rpDisableGroup)。
    - 符号改名表对齐合并命名空间（cfgInt→rpCfgInt / K_*→RP_K_* / tiersCustomKey→rpTiersCustomKey / putString·getString·sendText→HOST.*）；键全部读写同源（rpCustomOnKey/rpExcludeKey/RP_K_ENABLED/K_DAILY_GROUPS）。
    - **2 处非纯改名（语义等价/收口）**：① rpExportToday 端口化（rpQueryToday+HOST.sendText 镜像 rpDailySend，文案/脱敏日志逐字）；② RP 本群启用写路径 rpEnableGroup/rpDisableGroup 提前落地（rp 前缀避撞 GA enableGroup，与读侧 rpIsGroupEnabled 同源，§20 enableDailyGroup 副作用保留）——**T8 isGroupEnabled 同源收口时复核**。
    - **验证 GREEN（我独立复跑）**：build EXIT=0(6308行) / check.sh 21/21 / uniq -d 仅 3 个已知 arity 重载(dispatchAction/doWarn/hbTierAndSend) 无新撞名 / 基础页正确接 rp 前缀路径不与 GA 交叉 / 0 敏感泄漏。
  - 🔄 **W4 待做**：RP 文字命令路由（routeRpCommand：阈值/文案/档/定制/延迟/排除/转发/伸手党 + 红包排除 handler）→ commands.bsh；onClickSendBtn 撞名先抽 rpOnClickSendBtn（合并收口留 T8）。
  - ⏳ **W6 待做**：独立 Code Review 对照线上 §22-§25 逐子页保真 + 热路径/ANR 审。

## T7 W1 盘点结果（2026-06-10，line refs = 线上 plugins/redpacket-stats/main.java）
**总规模 ≈ 1000+ 行逐字移植（P3-b 最大单任务）。建议 W2-W4 在 fresh context 跑。**

### A. RP Dialog（≈730 行，@3221-3950）→ 并入 merged `dialogs.bsh`（与 GA 共存）
- UI helper：`_rpC`@3221 / `_rpRound`@3222 / `_rpBtn`@3228（**与 GA `_gaC`/`_gaRound`/`_gaBtn` 无撞名** ✓）
- 主菜单 `showConfigDialog`@3326（**与 GA `showGroupConfigDialog` 无撞名** ✓）
- 7 子页（全 `showPage*` 前缀，唯一）：`showPageBasic`@3442 / `showPageNormalTiers`@3481 / `showPageCustom`@3554 / `showPageDelay`@3622 / `showPageFreeloader`@3654 / `showPageExclude`@3704 / `showPageForward`@3731
- Forward 子页 helper：`rpPickTarget`@3833（后台 getFriendList/getGroupList + Handler 主线程弹框防 ANR）/ `rpApplyForwardTarget`@3904 / `sendForwardNotice`@3921
- 配置读写一律改走 HOST 端口（对齐 dialogs.bsh 既有约定）；键名/默认/夹取逐字保真。

### B. RP 命令路由（≈290 行）→ 接 merged `commands.bsh`（新 routeRpCommand 或并入）
- 命令段在线上 `onHandleMsgBody`@1204 内 @1359-1600：`红包统计状态`@1359 / `红包阈值`@1459 / `红包延迟`@1493 / `伸手党 开/关/窗口`@1539 等（命令 helper 如 `rpSetThresholdsCmd`@684 等需核实哪些已抽 domain）
- `红包排除`/`取消红包排除` 命令 @1119-1160（rpHandleExcludeCmd，走 onHandleMsg bot 自发 @ 路径）
- ⚠️ **真撞名**：RP `onClickSendBtn`@1333 与 GA `onClickSendBtn`(merged dialogs.bsh:62) **同名** → 必须合并成一个入口（同时认 GA「群管」+ RP 触发词）。**此 collision 解决可放 T8 onLoad/hook 合一**，T7 先把 RP 入口逻辑抽成 `rpOnClickSendBtn` 待 T8 收口，或 T7 直接合并（决策见 W2）。

### C. 每日定时写侧 → `enableDailyGroup`@1085 / `disableDailyGroup`@1093（写 rp_daily_groups，与 T6 已移的读侧 getDailyGroups 同键 K_DAILY_GROUPS="rp_daily_groups"）

### D. 键同源盯点（config-key 升级缺口教训）
- RP per-group 键的 normGroupId：线上 RP normGroupId=trim → merged 用 `rpNormGroupId`（tiers）。T7 Dialog/命令写键必须与读侧（domain tiers/qualify/export + T6 send）逐字同源。
- 定制群规前缀键 `rp_custom_rule_prefix_<rpNormGroupId>`（T6 cfgCustomRulePrefix 读侧已定）；档表键 rp_tiers_/rp_tiers_custom_；排除 rp_exclude_；转发 rp_export_target_；伸手党 rp_freeloader_on_/win_/since_。

### W2-W4 拆法（待 fresh context 执行）
- **W2**：RP UI helper(_rp*) + showConfigDialog 主菜单 + 子页 Basic/NormalTiers/Custom/Delay/Exclude → dialogs.bsh
- **W3**：showPageForward(含 enable/disableDailyGroup 写侧 + rpPickTarget/rpApplyForwardTarget/sendForwardNotice) + showPageFreeloader → dialogs.bsh
- **W4**：RP 命令路由(routeRpCommand: 红包统计状态/阈值/文案/档/定制/延迟/排除/转发/伸手党) + 红包排除 handler → commands.bsh；onClickSendBtn 撞名先抽 rpOnClickSendBtn（合并收口 T8）
- **W5/W6**：build+解析+check.sh+uniq -d撞名查重+键同源grep / 独立 Review
