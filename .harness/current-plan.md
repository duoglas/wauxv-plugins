# PLAN — 合并工程 P2（RedPacketStats 进骨架 + 迁移设计）

## 关键现实约束（决定 P2 怎么拆）
设计文档把 P2 写成"redpacket 领域+测试 + config union 迁移 + 停旧插件 + 真机"。但：
- 停旧 RP 插件 + config 迁移 = 最终 cutover，只有合并插件真能跑红包（依赖设备反射检测/领取者抽取/UI自动化 + P3 伸手党直连）后才能做。
- 现在停旧插件会直接让红包瘫掉（merged/ 还没部署、反射层还没接）。
- 沿用纪律：merged/ 不碰线上插件，直到最终 cutover。

→ P2 拆两段：能本地测的红包领域逻辑现在抽（纯本地、零真机）；不可逆 cutover 现在只设计、不执行，留到 P3 之后合并插件功能完整时再带独立 PLAN 执行。

## P2-a（现在做，纯本地，零真机，check.sh 全绿）：红包领域逻辑抽进 merged/
线上 plugins/redpacket-stats/main.java（4111 行）里可领域化（纯逻辑 + StoragePort 驱动）的部分抽进 merged/src/domain/redpacket.bsh（+ 必要时拆 redpacket_store）。反射/UI/worker/调度留设备 seam，不在本期。

### 切片（每片 L1/L2 + 红→绿 mutation，check.sh 绿）
- P2-a1 红包存储：redpacket_stats 表 schema + 录入/查询（rpRecordStats 核心 + 当日聚合）走 StoragePort；L2 sqlite-jdbc 真库测。
- P2-a2 分档逻辑：getTiers/getTiersByType（per-group 1-10 档、普通vs定制回退）、按金额选档、阈值/动作渲染（含 v1.6.0a "未设档不泄漏"）；config 驱动，L1。
- P2-a3 达标/排除/定制判定：拼手气vs均分（只判拼手气）、达标(>阈值)、getExcludeList 排除、rpIsCustom(title 包含关键字)；纯逻辑 L1。
- P2-a4 导出组装：rpBuildGroupedMsgs（按群分条组装多行）、当日统计聚合、转发对象按群路由(cfgExportTargetFor 回退全局)；L2(存储)+L1(组装)。
- 配置访问器（cfgRetry/cfgDailyHour/cfgExportTarget/cfgCustom* 等）按需抽，走 HOST 端口。

### 明确留设备 seam（本期不抽，占位/TODO 指向线上行号）
红包检测(onHandleMsg type 判定)、worker 队列(hbProcess)、领取者反射抽取(hbDetailExtract/NewDetailUI/NewReceiveUI)、无障碍 UI 自动化、Job/重试/delay 调度、sendText 真发。最终设备集成期接。

### P2-a 验收
merged/check.sh GREEN（build + bsh 解析 EXIT=0 + 所有 L1/L2）；红包领域逐字保真线上（分档/达标/排除/定制/导出 SQL）；Security grep 干净。无任何真机动作。

## P2-b（现在只设计，不执行）：最终 cutover 方案
写成可执行脚本 + 回滚预案，留到 P3 之后合并插件功能完整时执行：
1. config union 迁移：RedPacketStats/config.prop 并入合并插件目录 config.prop（rp_ 前缀零冲突=等价 union）。一次性、脚本化、先备份。
2. DB 接入：合并插件按绝对路径同时开 groupadmin.db + redpacket_stats.db（或迁 redpacket_stats.db 进合并目录）。数据不动。
3. 停旧 RedPacketStats 插件（防红包双重处理）。
4. 回滚：保留两旧插件目录 + config 备份；出问题切回。

## ⚠️ 真机不可逆动作清单（本 P2 PLAN 内一个都不执行，全部留最终 cutover 另开 PLAN 确认）
- 改/并入合并插件 config.prop（config union）
- 停用线上 RedPacketStats 插件
- 部署合并 main.java 到真机、迁移 redpacket_stats.db
→ 只在 P3 完成、合并插件端到端可跑红包后，带独立 PLAN + 真机 Santa 双审执行。

## 本期产出边界（YAGNI）
- 只抽 P2-a 红包领域逻辑 + 测试（纯本地）。
- P2-b 只产出迁移脚本/cutover 文档，不执行。
- freeloader→doWarn 直连是 P3，不在本期（判定逻辑已就位）。

## 待确认
1. 同意 P2 这样拆（P2-a 现在抽红包领域+测试纯本地；cutover 留 P3 后执行）？
2. P2-a 四片一口气做，还是先做某片？
3. 合并插件最终落哪个目录：复用 GroupAdmin/ 还是新建 GroupAdminPlus/？（影响 P2-b cutover，现在定方向即可，不执行）
