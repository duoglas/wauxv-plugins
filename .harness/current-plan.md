# PLAN — 合并工程 P1-d（app 层装配）

## 背景
P0 + P1-a/b/c 已落（commit 1566aba）：core 端口骨架 + membership/warning/speak/speak_store 四领域 + L1/L2，merged/check.sh GREEN。
P1-d 把线上 GroupAdmin 的 **app 层**（hooks + 命令路由 + recordSpeak 异步 infra + 设置 UI）抽进 merged/ 骨架。
这是合并工程**最重、热路径风险最高**的一期 → 切成 4 个独立可验证 slice，逐个做、逐个 check.sh 绿。

## 线上锚点（plugins/group-admin/main.java）
- recordSpeak + L3 缓冲/flush/降级 infra：@146-166（状态/锁）、@403-514（l3Flush/降级/恢复）、@710（recordSpeak 热路径入口）
- onHandleMsg 包装层 @2086 → onHandleMsgBody @2107（热路径主体：采集 recordSpeak + 文本命令路由）
- 设置对话框 UI：@3100-4010（一堆 onClick handler）

## Slice 拆分（每片独立可验证，check.sh 必绿）

### P1-d1 — recordSpeak 异步缓冲/flush/降级 infra → src/app/speak_buffer.bsh
- 抽：L3 状态机（L3_dirtyLs/L3_dirtySeen/L3_DEGRADED/双锁 L3_LOCK+FLUSH_LOCK）、recordSpeak 热路径入队（O(1) 内存写）、l3Flush 换出+落库、降级/恢复触发器。
- 落库走 **STORAGE 端口**（speakRecord/speakUpsertFirstSeen 已在 speak_store），不直接碰 jdbc。
- **保真红线**：锁序恒为 FLUSH_LOCK 外/L3_LOCK 内；recordSpeak 只持 L3_LOCK、绝不碰 DB/FLUSH_LOCK（C-PERF-03）；降级只影响采集入口，不影响命令/查询。
- 测试：L1 纯逻辑能测的部分 —— 降级触发器（缓冲上界 N→DEGRADED）、恢复条件（flush 成功且低水位<CAP/2→解除）、换出后 dirtyCount 归零。**诚实标注**：infra 本体的并发正确性本地测不了，只测纯状态转移，真并发留 L4 真机。

### P1-d2 — 命令路由骨架 → src/app/commands.bsh
- 抽 onHandleMsgBody 里"文本 → 领域调用"的路由（#警告/#清零/#潜水/… → doWarn/doWarnClear/lurkClassify 等已抽领域函数）。
- 纯分发：解析命令 token → 调 domain 函数；不含热路径采集（那在 d1）。
- 测试：L1 —— 喂命令字符串，断言路由到正确 domain 函数（用 host_fake 记录 FAKE_SENT/调用）。

### P1-d3 — 生命周期 hooks + 端口接线 → src/app/hooks.bsh
- onLoad（建表 + STORAGE=makeAndroidStorage(db) / HOST=makeAndroidHost() / CLOCK=makeAndroidClock() 接线）、onHandleMsg 包装层（性能埋点 + 调 d1 采集 + d2 路由）、onActivityResume。
- 测试：L3 装配解析门（check.sh 已有：build → 去 final 全文 bsh 解析 EXIT=0）。本地不跑真 hook。

### P1-d4 — 设置对话框 UI → src/app/dialogs.bsh
- 抽 @3100-4010 的 onClick handler（设置开关读写走 HOST.getString/putString）。
- 风险最低（UI、非热路径）。测试：仅 L3 解析门。

## 本期范围/边界（YAGNI）
- 只抽 **GA** 的 app 层；RP 并入（config union 迁移、freeloader→doWarn 直连、停旧 RP 插件）是 P2/P3，不在本期。
- 不动真机：本期纯本地（merged/ 文件 + check.sh）。**无真机不可逆动作**。
- manifest.prod.txt 增加 app/* 模块（core→domain→app 顺序）。

## 验收（每 slice）
- merged/check.sh GREEN（build + 装配体去 final bsh 解析 EXIT=0 + L1/L2 全绿）
- 新增逻辑有对应 L1 测，红→绿 mutation 验证过
- 逐字保真：抽出的逻辑与线上锚点一致（关键红线：热路径 O(1)、锁序、降级隔离）
- Security grep 干净（交付批次时）

## 建议执行顺序
P1-d1 先做（热路径心脏、喂 speak_store，价值最高也最该先锁死保真）→ 暂停看结果 → 再 d2 → d3 → d4。
每片做完 check.sh 绿即可 commit 或攒批。
