# VERIFICATION REPORT — 合并 P0：模块化构建 + 本地测试地基

日期: 2026-06-09 · 迭代: merge-P0-modular-build-and-test-foundation · 风险: low(新工作区，不碰线上/设备)

## 交付
`merged/` 工作区:源码分模块 + `build.sh`(manifest 拼接 → 单 main.java)+ 端口与适配器(StoragePort：android 生产 / sqlite-jdbc·fake 测试)+ 本地 bsh 测试 runner + `check.sh` 门禁 + 证明缝 `speak_store` UPSERT。

## QA 结果(本地为主，P0 不部署设备)

| 层/项 | 结果 | 证据 |
|---|---|---|
| 地基冒烟 | PASS | bsh 本地驱动 sqlite-jdbc 3.36.0.3 真库跑 coalesce UPSERT，三断言全过(SMOKE_OK) |
| L1 单测(T1) | PASS | `speak_store_unit`：领域用正确 SQL(含 coalesce)+binds 调端口；queryLong 透传 |
| L2 集成(T2) | PASS | `speak_store_integration`(sqlite-jdbc 真库)：NULL→now / 更早保留 / 无行插入；内含旧 SQL 留 NULL 的 RED 演示 |
| **突变验证 red→green** | PASS | 注入 bug(去 coalesce)→ 两测全红 `RED_RUN_EXIT=1`(集成 NULL 未补触发断言失败 + 单测抓 SQL 变更)；还原 → 全绿 `GREEN_RUN_EXIT=0`。**证明本地测试真能抓 min(NULL,x) 类回归(咬过两次的 bug)** |
| L3 装配体解析(T3) | PASS | build.sh 拼接 out/main.java(66 行，含 android 适配器)→ 去 final `bsh.Parser` EXIT=0 |
| check.sh 门禁 | PASS | build → 解析 → 单测 → 集成 全绿 `CHECK_EXIT=0` |
| **T4 不碰线上** | PASS | `git status plugins/*/main.java` 空——P0 零改动线上 GroupAdmin/RedPacketStats；新增仅 `merged/` |
| 依赖可复现 | PASS | `tools/fetch-deps.sh` 幂等(bsh 复用 gradle / maven 回退；sqlite-jdbc 3.36.0.3 固定)；jar gitignore，跨机器 Dropbox/脚本 |

## L4 真机 / Santa：N/A(记录降级)
P0 是新工作区的构建+测试基建,**不部署设备、不碰线上插件、无破坏性动作**。按 QA 标准(纯基建/不可逆动作=无),L4 真机与 Santa 双审**降级跳过**;业务迁移期(P1+)恢复真机 + 破坏性改动 Santa。

## Overall: READY
P0 地基跑通,后续每个功能"必须有单测+集成测"靠此落地。下一步 P1：GroupAdmin 逐领域进骨架 + L1/L2 测试 + 真机零回归。
