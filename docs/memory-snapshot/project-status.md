---
name: project-status
description: 当前部署版本 + 挂起的真机收尾项 + 迁移状态 (每 session 先看这条接上工作)
metadata: 
  node_type: memory
  type: project
  originSessionId: 3cbb9098-fbe3-4e37-8615-e1be36f085db
---

**截至 2026-06-06。换机器/新 session 先看这条接上工作。**

## 工作目录已迁 Dropbox
工作目录 = `~/Dropbox/ops-work/android-ops`（多 Mac 经 Dropbox 同步）。新 session 先 `bash scripts/bootstrap.sh`（自识别机器/adb/scrcpy/手机/root/版本 + 本机记忆为空时从 `docs/memory-snapshot/` 自动 seed）。详见 `docs/ONBOARDING.md`。**写了新记忆后要 `cp ~/.claude/.../memory/*.md docs/memory-snapshot/` 同步回快照**，否则别的机器接不到。

## 当前部署版本（真机已上）
- **GroupAdmin v1.16.2**：黑名单命令安全化(添加黑名单@/去除黑名单@/【序号】) + recordSpeak SQLite(VH-01 消除) + enabled 群内存缓存(onHandleMsg ~30ms→~12ms)。GroupAdmin 重启即加载。
- **RedPacketStats v1.6.4**：红包圈人全链路 + 每群 1-10 档可增减 + 按群分组导出/每日私聊发送(ACCT_REDACTED wxid_REDACTED) + 检测重活移后台 worker + enabled 内存缓存。**懒加载(要红包活动才 eval)；曾因解析失败被 WAuxiliary 自动停用，注意确认开关 ON**。

## 挂起的真机收尾项（adb 输不了中文/红包，需人工在微信里做）
1. RedPacketStats v1.6.4：发个拼手气红包 → 拉 perf.log 确认红包消息热路径从 ~130ms 降到几 ms（这次优化的最终数字还没坐实）。
2. enable/disable 即时生效：`关闭/开启红包统计`、`关闭/开启群管` 切换后下一条消息/红包立即按新状态。
3. 确认 WAuxiliary 里 RedPacketStats 开关为 ON。
4. 专属红包应被 worker 侧跳过(`skip exclusive (worker-side)`)。

## perf 埋点路径（拉数据用）
`/storage/emulated/0/Android/media/com.tencent.mm/WAuxiliary/Plugin/<GroupAdmin|RedPacketStats>/perf.log`（脱敏，只耗时/计数）。GroupAdmin 有 seg1-5 分段；RedPacketStats 有 rp_msg/norm/spike_rp/spike_norm/rp_extract。

相关 [[waux-getstring-fuse-io-cache-hotpath]]、[[beanshell-parser-stricter-than-javac]]、[[recordspeak-refactor-progress]]、[[redpacket-build-review-before-deploy]]。
