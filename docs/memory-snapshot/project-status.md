---
name: project-status
description: 当前部署版本 + 挂起的真机收尾项 + 迁移状态 (每 session 先看这条接上工作)
metadata: 
  node_type: memory
  type: project
  originSessionId: 3cbb9098-fbe3-4e37-8615-e1be36f085db
---

**截至 2026-06-07。换机器/新 session 先看这条接上工作。**

## 工作目录已迁 Dropbox
工作目录 = `~/Dropbox/ops-work/android-ops`（多 Mac 经 Dropbox 同步）。新 session 先 `bash scripts/bootstrap.sh`（自识别机器/adb/scrcpy/手机/root/版本 + 本机记忆为空时从 `docs/memory-snapshot/` 自动 seed）。详见 `docs/ONBOARDING.md`。**写了新记忆后要 `cp ~/.claude/.../memory/*.md docs/memory-snapshot/` 同步回快照**，否则别的机器接不到。

## 当前部署版本（真机已上）
- **GroupAdmin v1.16.3**：在 v1.16.2(黑名单安全化 + recordSpeak SQLite[VH-01 消除] + enabled 群内存缓存 onHandleMsg ~30ms→~12ms)之上,**onLoad 冷启动健壮化**——getLoginWxid 安全取值 + 关键初始化分段 try/catch,修 VH-02(冷重启时 getLoginWxid 抛 NoResetUinStack 连坐整段 onLoad、flush 循环没启动)。真机重载验过 onLoad 干净跑完。见 C-PLUGIN-05/VH-02。GroupAdmin 重启即加载。
- **RedPacketStats v1.6.4**：红包圈人全链路 + 每群 1-10 档可增减 + 按群分组导出/每日私聊发送(ACCT_REDACTED wxid_REDACTED) + 检测重活移后台 worker + enabled 内存缓存。**懒加载(要红包活动才 eval)；曾因解析失败被 WAuxiliary 自动停用，注意确认开关 ON**。

## 挂起的真机收尾项（adb 输不了中文/红包，需人工在微信里做）
1. RedPacketStats v1.6.4：发个拼手气红包 → 拉 perf.log 确认红包消息热路径从 ~130ms 降到几 ms（这次优化的最终数字还没坐实）。
2. enable/disable 即时生效：`关闭/开启红包统计`、`关闭/开启群管` 切换后下一条消息/红包立即按新状态。
3. 确认 WAuxiliary 里 RedPacketStats 开关为 ON。
4. 专属红包应被 worker 侧跳过(`skip exclusive (worker-side)`)。

## perf 埋点路径（拉数据用）
`/storage/emulated/0/Android/media/com.tencent.mm/WAuxiliary/Plugin/<GroupAdmin|RedPacketStats>/perf.log`（脱敏，只耗时/计数）。GroupAdmin 有 seg1-5 分段；RedPacketStats 有 rp_msg/norm/spike_rp/spike_norm/rp_extract。

## 本机(DJRMBP / macOS 13)环境与设备连接
- macOS 13 是 Homebrew Tier 3(无预编译包),`brew install` 会从源码编译(scrcpy 依赖链编了 ~20min)。adb/scrcpy 已装好(`/usr/local/bin`)。
- **一键连接 + 息屏投屏**:双击桌面 `akita-连接.command`(源 `scripts/akita-connect.command`)——自动起 adb/等授权/`scrcpy -S -w --power-off-on-close`(手机黑屏、电脑窗口实时控制)。adb 输不了中文/红包时用投屏窗口手动操作微信。
- **充电"不支持的充电线"告警 = 插入瞬态,非故障**:30min 连续采样实测 92%→100%、Charging 60/60、零横跳;别再被插线瞬间那条告警带去排查线缆。满电后转 Not charging/Full 是正常涓流。
- **后台 watchdog**:`scripts` 外的临时脚本 `/tmp/akita_watchdog.sh`(每5min 查设备掉线/微信进程/插件log新报错,异常才报)——VH-02 就是它真机抓到的。新 session 要常驻监控需重新拉起。

相关 [[waux-getstring-fuse-io-cache-hotpath]]、[[beanshell-parser-stricter-than-javac]]、[[recordspeak-refactor-progress]]、[[redpacket-build-review-before-deploy]]。
