# 最终 Cutover Runbook —— GroupAdmin + RedPacketStats → GroupAdminPlus

> 状态：**P2-b 产物，只设计不执行**。本目录脚本是真机不可逆动作，**禁止在 P3 完成前运行**。
> 真正执行时必须：另开独立 PLAN + 用户逐条确认 + Santa 双独立审 + 真机在场（scrcpy）。
> 日期 2026-06-09。

## 0. 这是什么

把合并插件 `GroupAdminPlus`（`merged/build.sh` 拼出的单一 `main.java`）**正式顶替**线上两个旧插件
`GroupAdmin` + `RedPacketStats`。一次性、脚本化、先备份、可回滚。

合并后只有 **一个** 插件在跑：群管 + 红包统计 + 伸手党 in-process 直连 `doWarn`（不再跨插件读库/发命令）。

## 1. 前置条件（不满足一条都不许 cutover）

- [ ] **P3 完成**：合并插件端到端能跑红包 —— 设备侧反射（领取者抽取）、worker 队列、Job/重试/延迟调度、
      快照 P+G 延迟拍照、`freeloader→doWarn` 直连、`sendText` 真发 全部接好并真机验过。
      （现在 `merged/` 只有领域逻辑 + 装配骨架，反射/UI/调度仍是 seam → **现在 cutover 红包会瘫**。）
- [ ] `merged/check.sh` 全绿（build + bsh 解析 EXIT=0 + 全部 L1/L2）。
- [ ] `merged/out/main.java` 已由 `build.sh` 用 **生产 manifest（prod 适配器）** 拼好，且过离线 bsh 解析。
- [ ] 已 `adb push merged/out/main.java` 到设备 `GroupAdminPlus/main.java`（cutover.sh 只校验、不负责 push）。
- [ ] 用户在场 + scrcpy 投屏 + 单测试时段（非群活跃高峰）。
- [ ] 设备可 root（`su`）。插件路径在 `/storage/emulated/0/Android/media/com.tencent.mm/WAuxiliary/Plugin`。

## 2. 关键设计决策（cutover 时须用户确认）

| # | 决策 | 取值 | 理由 / 代价 |
|---|------|------|------------|
| C1 | 合并插件落哪 | **新建 `GroupAdminPlus/`** | 用户定，旧目录原样留着以备回切 |
| C2 | config 怎么合 | `GroupAdminPlus/config.prop` = GA 全量 + RP 的 `rp_` 行 | per-plugin 隔离、`rp_` 前缀与 GA 键零冲突 → 等价 union（设计 §6 已核实） |
| C3 | DB 怎么接 | **拷贝**（非移动）两旧 DB 进 `GroupAdminPlus/`，合并插件读自己目录 | 旧 DB 原地保留=回滚锚点。代价：cutover 后 GroupAdminPlus 写的是副本，**回滚会丢 cutover 之后写入的数据**（接受：回滚是应急、窗口短） |
| C4 | 旧插件怎么停 | 把 `GroupAdmin/` 和 `RedPacketStats/` 整目录**移出** `Plugin/` 到备份区 | WAuxiliary 扫 `Plugin/*` 子目录加载 main.java；移出=不加载，最干净可逆。**两个都要停**（旧 GA 不停=群管双跑；旧 RP 不停=红包双重处理） |
| C5 | 何时生效 | 移动完成后**重启微信**（或 WAuxiliary 内重载，真机确认哪种可靠） | 加载机制以真机为准，留 verify 步 |

> ⚠️ C3 的「拷贝 vs 移动 DB」与「合并插件按相对(自己目录) vs 绝对路径开库」必须对齐：
> 本方案选 **拷贝进 GroupAdminPlus/ + 合并插件读自己目录**（hooks.bsh 的 STORAGE 接线需如此，P3 落实）。
> 若 P3 改成「按绝对路径读旧目录的库」，则 C3/C4 冲突（旧目录被移走库就没了）——届时改成 DB 也留原地、旧目录只停用不移动，需重新评审。

## 3. 执行顺序（真机，逐步确认）

```
# 0) 主机侧：构建 + 推送（cutover 前，host 上做）
cd merged && ./build.sh && ./check.sh          # 全绿才继续
adb push out/main.java /data/local/tmp/gap-main.java
adb shell "su -c 'mkdir -p <PLUGIN_BASE>/GroupAdminPlus && cp /data/local/tmp/gap-main.java <PLUGIN_BASE>/GroupAdminPlus/main.java'"

# 1) 推送 cutover 脚本到设备并跑（带硬确认闸门）
adb push merged/cutover/cutover.sh /data/local/tmp/cutover.sh
adb shell "su -c 'CUTOVER_CONFIRM=yes sh /data/local/tmp/cutover.sh'"

# 2) 重启微信使加载生效（真机确认）
adb shell "am force-stop com.tencent.mm"   # 然后手动/保活拉起

# 3) 真机验收（scrcpy）：见 §4
```

## 4. Cutover 后验收（真机，全过才算成功）

- [ ] logcat / plugin.log：`GroupAdminPlus` 干净加载，无 NoSuchMethod / parse error；旧 GA/RP **不再加载**。
- [ ] 群管命令（如 `@TA 警告`）生效，警告计数读到的是迁移后的 `groupadmin.db`（数据连续）。
- [ ] 红包：发拼手气包 → 检测/统计/导出正常；当日统计读到迁移后的 `redpacket_stats.db`（历史在）。
- [ ] 伸手党：测试群按 P3 配方实测一次（in-process 快照 + doWarn 直连，不再有跨插件命令往返）。
- [ ] 热路径：perf.log 无回归（消息收发不卡）。
- [ ] 反检测：HMA/DenyList 不受影响（没新增包名）。

## 5. 回滚

任何一步失败或验收不过 → 跑 `rollback.sh`：把旧 GA/RP 目录移回 `Plugin/`、停用/移走 `GroupAdminPlus/`、重启微信。
旧 DB 原地未动 → 旧插件恢复后数据完好（仅丢 GroupAdminPlus 运行期间写入的增量，见 C3）。

```
adb push merged/cutover/rollback.sh /data/local/tmp/rollback.sh
adb shell "su -c 'ROLLBACK_CONFIRM=yes BACKUP_DIR=<cutover.sh 打印的备份路径> sh /data/local/tmp/rollback.sh'"
adb shell "am force-stop com.tencent.mm"
```

## 6. 不可逆动作清单（执行 PLAN 必列）

- 写入/合并 `GroupAdminPlus/config.prop`
- 拷贝两旧 DB 进 `GroupAdminPlus/`
- 移走线上 `GroupAdmin/` + `RedPacketStats/` 目录（停用）
- 重启微信进程

全部有备份 + rollback.sh 兜底；仍须 Santa 双审 + 用户在场。
