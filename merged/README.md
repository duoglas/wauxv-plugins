# merged/ — GroupAdmin + RedPacketStats 合并工作区(方案 B)

把两个插件合成**一个**统一插件:源码按领域分模块,`build.sh` 拼接成单一 `main.java`(WAuxiliary 运行时仍单文件 BeanShell)。全功能**本地单测 + 集成测试**。

设计文档:`../docs/superpowers/specs/2026-06-09-merge-groupadmin-redpacket-design.md`

## 目录
```
src/core/      宿主/存储/时钟端口 + 生产适配器(android)
src/domain/    领域逻辑(membership/speak/warning/redpacket/freeloader…，只依赖端口)
src/app/       命令分发 / hooks / dialogs (P1+)
build/manifest.prod.txt   生产构建模块顺序
build.sh       按 manifest 拼接 src 模块 → out/main.java(部署用)
tests/         本地测试(.bsh)；tests/fakes/ 测试适配器(fake / sqlite-jdbc)
run-tests.sh   跑 tests/*.bsh(bsh + sqlite-jdbc)，查 TEST_OK 哨兵判定
check.sh       本地准出门禁 = build → bsh 解析 → 单测 → 集成
tools/         bsh-2.0b6.jar + sqlite-jdbc.jar(不进 git，见 fetch-deps.sh)
```

## 用法
```bash
cd merged
tools/fetch-deps.sh    # 首次/新机器:拉 bsh + sqlite-jdbc
./check.sh             # 全绿才进真机 VERIFY
./run-tests.sh         # 只跑本地测试
./build.sh             # 只拼接出 out/main.java
```

## 测试金字塔
- **L1 单测**:领域 vs fake 端口(纯 bsh,毫秒级,契约/逻辑)。
- **L2 集成**:领域 + **sqlite-jdbc 真库**(验真 SQL,能抓 `min/max(NULL,x)=NULL` 类 bug)+ fake 宿主。
- **L3 装配体解析**:拼好的 main.java 去 final `bsh.Parser` EXIT=0。
- **L4 真机**(在 P1+ 部署阶段):只剩 WeChat 反射 / sendText 真发 / scrcpy + 最终验收。

## 关键约束/坑
- WAuxiliary 运行时 = 单 main.java BeanShell,弱 OO → 领域模型在**源码层**做,构建期拼接成单文件。
- 领域逻辑**只依赖端口**(STORAGE/HOST/CLOCK 全局),生产接 android、测试接 fake/jdbc,build/测试各择一套适配器。
- `sqlite-jdbc` 用 **3.36.0.3**(自包含,3.43+ 硬依赖 slf4j 会 NoClassDefFoundError)。
- `bsh.Interpreter` 脚本异常仍 `exit 0` → runner 靠输出哨兵(`TEST_OK` + 无 `FAIL`/`Target exception`)判定,不看退出码。
- 端口 SQL 用标准 SQL(UPSERT/coalesce),android sqlite 与 sqlite-jdbc 两端语义一致。

## 状态
- **P0 地基(完成)**:build + 端口/适配器 + 本地 runner + 证明缝 `speak_store` UPSERT,突变验证 red→green(抓 coalesce/NULL bug)。**未碰线上插件。**
- P1 GroupAdmin 进骨架 / P2 RedPacketStats 进骨架+迁移 / P3 freeloader→doWarn 直连。见设计文档分期。
