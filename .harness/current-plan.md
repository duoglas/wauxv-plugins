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
- **T1-T7 已完成**（见下方进度更新 + docs/memory-snapshot/project-status.md）。
- **T8 onLoad 合一 + seam 收口（扩容版，本期活动任务）**：见下方「T8」专节。
- **T9 验收（Layer 3/4）**：build → 装配体去 final 全文解析 EXIT=0；独立 Code Reviewer 逐字保真，重点热路径(C-PERF-01/03)+伸手党降级红线+seam 同源；高风险段走 Santa 双审。done：0 CRITICAL。

## ⚠️ 真机不可逆动作：本期一个都不碰
P3-b 全程纯本地产出 + 解析 + 审查。adb push / 改 per-group 配置 / 真机发红包 / 伸手党触发 doWarn / 真机踢人拉黑 全部留 P3-c。

## 已确认决策（2026-06-09 / 2026-06-10）
1. ✅ 同意 9 任务拆法与顺序（T1→T8 移植、T9 验收）。
2. ✅ 设备层用**多模块拆分**。
3. ✅ session 接力：**每 2-3 任务一个 commit 节点**，到 ~400K tokens 主动开新 session。
4. ✅ **T8 扩容（2026-06-10 用户拍板）**：GA 动作 LEAF 全套（~30 do* + makeAndroidLeaf）并入 T8（而非另立任务）。

## 进度更新
- ✅ **T6 完成 (commit bd7f4b1)**：发送层 redpacket_send.bsh + host sendQuote 健壮回退 + domain rpQueryWindow。check.sh 21/21，独立 Review 0 CRITICAL。
- ✅ **T7 完成 (commit 929813b+a8913d3+6a21a99)**：RP 设置 UI(主菜单+7子页) + rp_ 文字命令 + 每日定时写侧 全部逐字保真并入，与 GA 共存无撞名，装配体解析 EXIT=0(6806行) + check.sh 21/21 + 独立 Review READY 0 CRITICAL/0 MAJOR/2 非actionable MINOR。零真机。剩 onClickSendBtn 双入口合一收口 → T8。

## T8（扩容版，用户 2026-06-10 拍板全范围）— onLoad 合一 + seam 收口 + GA 动作层全套

### ★探查坐实的事实（2026-06-10，只读 grep + Read）
合并体 GA 侧三类 seam **被调用但 merged 里从无定义**（BeanShell 解析门只查语法故 EXIT=0 掩盖了断裂）：
1. **启用子系统** `getEnabledGroups`(hooks onLoad@99/115)/`isGroupEnabled`(hooks@299 热路径、dialogs@147)/`enableGroupWithBaseline`(dialogs@221)/`disableGroup` — 全 seam。
2. **L3 基线助手** `initFirstSeenBaseline`(onLoad@121)/`l3MigrateCsvToDb`(onLoad@113)/`l3CountFirstSeen`(dialogs@156/792)/`l3ClearFirstSeen`(dialogs rebuild) — 全 seam。speak_buffer.bsh 只有 recordSpeak/l3Flush/降级，无基线助手。`speakUpsertFirstSeen` 在 speak_store 已定义 ✅。
3. **GA 动作 LEAF 全套** `makeAndroidLeaf`(只在注释)+ commands `_mkNoopLeaf()` 里 ~30 个 do* 全是空占位 → 合并体里 GA 除 doWarn/doWarnClear/doWarnDec(已抽 domain 真调)外，**踢/拉黑/名单/潜水/严格模式/管理员管理 全是静默空操作**。
- 已定义 ✅：domain membership(getAdmins/setWarnKick 等)/warning/speak/freeloader、command 路由、dialog UI、recordSpeak 缓冲、onLoad GA 端口接线 + l3FlushLoop。
- RP 收口缺口：onLoad **没启动 rpWorkerTick**(worker.bsh:201 自重调度循环，靠 onLoad 起头；WORKER_STARTED@device_base:79 守卫位已备好未用)；onLoad **没建 rp_record 表**(rpRecord 走 STORAGE=groupadmin.db 同库，须 `STORAGE.exec(RP_CREATE_SQL)`)。
- onClickSendBtn 撞名：GA(dialogs.bsh:62 认『群管』系列→showGroupConfigDialog)与 RP(commands.bsh:494 rpOnClickSendBtn 认『红包统计设置』系列)各存一份，未合一。

### W0-W8 拆解（仿 T7 成功结构：W1 盘点→Implementer 分组移植→我独立复跑 gate→独立审查+Santa）
- **W0 SETUP**：清 `/private/tmp` ENOSPC(EXECUTE 前必须，build/check.sh/解析门依赖) + 冒烟 ./build.sh。
- **W1 盘点**：读线上 GA main.java，每个 seam → 线上行号 + 落点 + 键同源表 + 撞名面（fresh context）。
- **W2**：GA 启用子系统 + L3 基线助手（domain 层，让 onLoad 可真跑）。
- **W3**：GA do* 组 A 查询/列表类（read-mostly）。
- **W4**：GA do* 组 B 名单写类（键同源红线）。
- **W5**：GA do* 组 C 破坏性类（doKick/doShowInactive/doKickInactive + enable/disableGroup；canActOn/@所有人守卫逐字；踢人走 HOST.tryKickSilent）— **高风险**。
- **W6**：收口片 hooks.bsh — makeAndroidLeaf 桥 + onLoad 置 LEAF + _cmdGroupEnabled→isGroupEnabled 同源 + onLoad RP 收口(建 rp_record 表 + 启 rpWorkerTick + 冷启动日志 + 旧毫秒键核实) + onClickSendBtn 双入口合一(GA 先 RP 后)。
- **W7 VERIFY**：build+解析 EXIT=0 + check.sh 全绿 + uniq -d + 跨模块依赖 grep + 键同源 + Security。
- **W8 REVIEW**：独立 Code Review 逐 do* 保真；破坏性动作走 **Santa 双审**(Layer 4)；0 CRITICAL 方交付。

### commit 节点（每 2-3 W）：W2 后 / W3-W4 后 / W5 后 / W6 后 / T8 收尾。体量必跨 ~400K → 到点 /compact 或开新 session（接力先 bootstrap + 读快照 + 读本 plan）。
