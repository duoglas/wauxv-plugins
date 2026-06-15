# android-ops

一台 root 安卓设备（Pixel 8a / Android 16 / Magisk）的运维总仓：通过 `adb` 管理 **WAuxiliary 微信增强插件**（自定义 BeanShell 模块）、**keepalive 保活 Magisk 模块**，以及设备隐藏与网络配置。开发与运维全程连真机进行，没有传统编译链。

> 隐私：仓库**不含**任何真实用户/群/成员数据。运行期产物（`config.prop` / `*.db` / `*.log` / 观测日志）一律 `.gitignore`，源码/文档中的群名、群 ID、wxid 已做脱敏。

## 目录结构

```
merged/        ★当前主力：GroupAdmin + RedPacketStats 合并成的统一插件(GroupAdminPlus)
  src/core/      端口 + 生产适配器(宿主 HOST / 存储 STORAGE / 时钟 CLOCK)
  src/domain/    领域逻辑(membership/warning/redpacket/freeloader/rank/chat_recent…，只依赖端口)
  src/app/       命令分发 / hooks / dialogs / LLM / AI 锐评
  build.sh       按 manifest 把 src 模块拼接成单一 out/main.java(WAuxiliary 运行时单文件 BeanShell)
  tests/         本地测试(.bsh) + fakes(fake 端口 / sqlite-jdbc)
  check.sh       准出门禁 = build → bsh 解析 → L1 单测 → L2 集成
plugins/       独立 WAuxiliary 插件
  group-admin/     群管(旧版，已并入 merged)
  redpacket-stats/ 红包统计(旧版，已并入 merged)
  group-stats/     群聊统计分析(含 AI 分析)
  fishtts/         文字转语音(直连 fish.audio)
keepalive/     keepalive 保活 Magisk 模块(POSIX sh: service.sh + scripts)
device/        设备隐藏与网络配置副本(clash 访问控制 / HMA 白名单 / Magisk denylist)
docs/          constraints.md(约束系统) / ONBOARDING.md / 设计 spec / 跨机器记忆快照
scripts/       Harness hook 脚本 + 监控
CLAUDE.md      AI 助手项目说明(Harness Engineering 工作流)
```

## 主力插件：GroupAdminPlus（merged/）

把群管与红包统计合成一个插件，源码按领域分模块、构建期拼接成单一 `main.java`，但**全功能本地可测**。

主要能力：
- **群管**：警告/累计自动踢、黑名单（含进群自动踢，1 分钟宽限）、白名单（强制期限，默认 60 天）、潜水保护（可选期限）、皇冠榜（荣誉名单，置顶显示、永久免罚）、管理员时效、严格模式。
- **红包统计**：圈人 / 分档 / 伸手党判定（潜水/白名单/红包排除任一即免）/ 按群导出。
- **排行榜**：话痨/水王/图王/红包王/潜水冠军，可选 LLM 润色。
- **AI 群聊锐评**：读群聊内容 + 排行榜，调大模型生成一段"讲故事"风格的群聊锐评（可配置 system prompt、temperature；内容短保留、可关、默认关）。

### 架构（六边形端口）
领域逻辑只依赖端口（`HOST` 宿主 / `STORAGE` 存储 / `CLOCK` 时钟 / `LEAF` 动作叶子）：生产接 Android 适配器，测试接 fake / sqlite-jdbc 真库。热路径（每条消息）严格 O(1)，重活全后台线程。

### 测试金字塔
- **L1 单测**：领域 vs fake 端口（纯 BeanShell，毫秒级）。
- **L2 集成**：领域 + sqlite-jdbc 真库（验真 SQL）+ fake 宿主。
- **L3 装配体解析**：拼好的 `main.java` 去 `final` 全文 `bsh.Parser` 通过。
- **L4 真机**：adb 部署 + logcat + 实际消息收发验收。

## 开发 / 构建 / 部署

```bash
# 本地准出(全绿才进真机)
cd merged
tools/fetch-deps.sh    # 首次/新机器: 拉 bsh-2.0b6 + sqlite-jdbc(不入 git)
./check.sh             # build → 解析门 → 单测 → 集成

# 部署到真机(改完 main.java 后)
adb push out/main.java /data/local/tmp/ga.java
adb shell "su -c 'cp /data/local/tmp/ga.java <真机 Plugin 路径>/main.java && chown … && chmod 660 …'"
adb shell "am force-stop com.tencent.mm"   # 重启微信重载插件

# 诊断(只读)
adb shell "su -c 'cat .../WAuxiliary/Plugin/<名>/plugin.log'"
adb logcat -d | grep LSPosedFramework      # 看 hook 加载/异常
```

新机器接力先跑 `bash scripts/bootstrap.sh` 并读 `docs/ONBOARDING.md`。

## 技术栈

BeanShell（WAuxiliary 插件，解释执行 hook 微信）· POSIX sh（keepalive）· adb / Magisk / Zygisk / LSPosed / Shamiko / HideMyApplist / clash.meta。

## 工作流

本仓采用 **Harness Engineering** 6 阶段 Loop（PLAN → SETUP → EXECUTE → VERIFY → REVIEW → FEEDBACK），强调真机验证与独立 code review。约束系统见 `docs/constraints.md`，详情见 `CLAUDE.md`。

## License

[MIT](LICENSE) © 2026 duoglas
