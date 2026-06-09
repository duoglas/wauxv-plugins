# 合并 GroupAdmin + RedPacketStats — 架构 + 测试 + 迁移设计

> 状态：设计待用户过目。日期 2026-06-09。
> 目标：把 RedPacketStats 合并进 GroupAdmin，成为**一个**统一插件，**直接共享数据与逻辑**（不再靠读库 + 发命令往返）；同时引入**本地单测 + 集成测试**作为全功能的准出门禁。

## 1. 动机（用户确认）
- 统一管理：一个插件，配置/版本/部署归一。
- 共享数据/逻辑：两边都要用发言时间（`last_speak`）、成员名单、警告计数。分开要绕 DB/命令，合了直接共享内存与函数。最典型：**伸手党判定 → 直接调 `doWarn()`**（替代现在 RP 读 groupadmin.db + 发 `[AtWx] 警告` 命令让 GA 解析）。
- 硬要求：所有功能**必须有本地单测 + 集成测试**。

## 2. 运行时硬约束（决定一切）
- WAuxiliary 加载插件目录里的 **`main.java`，BeanShell 解释执行**。设备与本机用**同一个 BeanShell**（已有 `bsh-2.0b6.jar`）。
- BeanShell 的 class/OO 支持弱且脆；现有两插件刻意用**扁平函数 + 全局变量**风格。→ **真正的 Java 式 class 领域模型在此运行时风险极高**。
- 结论：领域模型在**源码层**做（多文件、清晰边界），运行时仍是**单一 main.java**。

## 3. 方案 B：源码分模块 + 构建拼接
- 仓库按领域拆成多个聚焦源文件；`build.sh` 按 manifest 顺序拼接 + 选定适配器 → 部署用单一 `main.java`。
- 你永远只编辑小而清晰的领域文件；运行时拿到单文件。
- 产物过现有 bsh 离线解析门禁（去 `final` 全文解析 `EXIT=0`）。

### 3.1 领域切分（目标态）
```
core/host-api      WAuxiliary 宿主薄封装(防腐层): getString/putString/sendText/
                   getGroupMemberList/getLoginWxid/toast/getFriendList/反射helper
core/storage       SQLite 底座(speak 表 + redpacket 表)+ L3 flush + UPSERT
core/clock         now() 抽象
domain/membership  管理员/群主/原生群主/保护/黑白名单/权限(canActOn)
domain/speak       发言时间 last_speak/first_seen、recordSpeak、#潜水
domain/warning     doWarn/警告计数 wk_/到顶踢、自动来源豁免
domain/redpacket   检测/队列worker/分档/统计/导出/定制包
domain/freeloader  伸手党判定 → 直接 domain/warning.doWarn   ← 合并的核心收益
app/commands       命令分发(onHandleMsg 文本 → 路由领域)
app/hooks          onLoad / onHandleMsg / onActivityResume(WAuxiliary 入口)
app/dialogs        设置 UI
```
依赖方向：app → domain → core；domain 之间 freeloader→warning/speak、redpacket→speak。

## 4. 可测性架构：端口与适配器
所有碰外部世界的调用收敛到**端口**，领域逻辑只依赖端口；生产/测试各一套适配器，build 时择一拼入。

| 端口 | 生产适配器 | 测试适配器 |
|---|---|---|
| `HostPort` | WAuxiliary 宿主 API | 内存 fake KV + 捕获 sendText/toast + 可编程成员表 |
| `StoragePort` | `android.database.sqlite` | **sqlite-jdbc 本地真库**(同一份 SQL) |
| `Clock` | `System.currentTimeMillis` | 可控假时钟(测 cutoff/宽限/窗口阈值的命脉) |

关键：**bsh 本地执行 = 设备同解释器**（语言语义高保真）；**sqlite-jdbc = 本地真 SQLite**（同一份 UPSERT SQL，能本地复现 `max/min(NULL,x)=NULL` 类 bug）。

## 5. 测试金字塔
- **L1 本地单测**（领域 vs fakes，纯 bsh，毫秒级）：membership 权限；speak/#潜水 三分类(新成员豁免1天/活跃/潜水)；warning 计数/豁免/到顶踢；redpacket 分档/拼手气vs均分/达标/排除；**freeloader 谁被警告 + 宽限/lazy-init/NULL/容差/cap + 负向**。
- **L2 本地集成**（多真实模块 + sqlite-jdbc 真库 + fake 宿主）：recordSpeak→flush→#潜水 查询（**抓 coalesce/NULL bug**）；**伸手党全链路**：喂领取者→freeloader 判→doWarn→计数入库→到阈值→踢人动作被捕获；onLoad 全群补基线；config CSV→DB 迁移。
- **L3 装配体 bsh 解析**（现有）：拼好的整 main.java `EXIT=0`。
- **L4 真机**（现有 Layer 2）：只剩本机测不了的 **WeChat 反射**（红包领取者抽取/无障碍点击）、sendText 真发群、scrcpy 行为 + 最终验收。

工具：`bsh-2.0b6.jar`（已有）+ `sqlite-jdbc`（maven 取一次，记 README）。`tests/*.bsh` 同语言不引 JUnit；`run-tests.sh` 失败非零退出；`check.sh = build → bsh解析 → 单测 → 集成`，全绿才进 VERIFY 真机。

## 6. config store 事实（已上设备核实 2026-06-09）
- **per-plugin**：`getString/putString` 背后是**每个插件目录各一份 `config.prop`**（`Plugin/GroupAdmin/config.prop` 9KB、`Plugin/RedPacketStats/config.prop` 2.2KB）。插件之间**不共享配置**——这正是伸手党当前只能发命令往返的根因。
- **零冲突**：RP 的键**全部** `rp_` 前缀；GA 键用 `admins_/bl_/bp_/fsg_/lsg_/wt_/wk_/protected_/admin_exp_/enabled_groups` 等，**无一 `rp_` 开头**。两份 config 键集**不相交**。
- 两个 SQLite 库在各自插件目录：`GroupAdmin/groupadmin.db`、`RedPacketStats/redpacket_stats.db`。

## 7. 迁移方案（P2，因 config per-plugin）
合并插件落在**一个目录**（建议复用 `GroupAdmin/`，或新目录 `GroupAdminPlus/`）：
1. **配置迁移**：把 `RedPacketStats/config.prop` 内容**并入**合并插件的 config.prop（`rp_` 前缀零冲突，等价 union）。一次性、脚本化、先备份可回滚。
2. **DB**：合并插件按**绝对路径**同时开 `groupadmin.db` 与 `redpacket_stats.db`（或把 `redpacket_stats.db` 移进合并目录）。数据不动。
3. **停用旧 RedPacketStats 插件**（否则红包被**双重处理**）。
4. **回滚**：保留两个旧插件目录与 config 备份；出问题切回。

## 8. 风险
- **单点故障**：合一后任何 BeanShell 解析错 → 群管+红包**同时挂**（现在各挂各的）。→ 本地 L1-L3 + 真机 L4 双保险；拼接产物强制 bsh 解析门禁。
- **8000 行重切**：两套 onHandleMsg/onLoad/热路径合一，每块真机重验。→ 分期、每期独立可部署。
- **热路径回归**：合并 onHandleMsg 不得引入 O(N)/全量读写（C-PERF-01/03）。→ 集成测试 + 真机 perf 埋点。
- **迁移**：config union / DB 路径 / 停旧插件——先备份、可回滚。

## 9. 分期（每期带它自己的测试，不欠账）
- **P0 地基**：build.sh + 端口/适配器 + 本地 bsh 测试 runner（sqlite-jdbc）+ **证明缝 = speak-store UPSERT** red→green（顺带把 v1.19.2/v1.20.0 coalesce 修复变本地回归测试）。**不动线上插件。**
- **P1 GroupAdmin 进骨架**：membership/speak/warning + 命令/hooks/dialogs 逐域迁入 + L1/L2 测试 + 真机零回归。
- **P2 RedPacketStats 进骨架 + 迁移**：redpacket 领域 + 测试；config union 迁移 + DB 接入 + 停旧插件 + 真机。
- **P3 freeloader→doWarn 直连**：删跨插件命令往返；集成测试覆盖"领取者→判→doWarn→计数→踢"全链路 + 真机。

## 10. 验收（每期）
本地 `check.sh` 全绿（L1+L2+L3）→ 真机 L4（部署+logcat+行为+perf 无回归）→ 高风险破坏性改动加 Santa 双审。
