---
name: project-status
description: 当前部署版本 + 挂起的真机收尾项 + 迁移状态 (每 session 先看这条接上工作)
metadata: 
  node_type: memory
  type: project
  originSessionId: 3cbb9098-fbe3-4e37-8615-e1be36f085db
---

**截至 2026-06-09。换机器/新 session 先看这条接上工作。**

## 后续项(伸手党收尾时浮现)——均已完成 ✅
1. ✅ **#潜水 新成员豁免改固定 1 天**:GA v1.20.0 已做(`LURK_NEWMEMBER_EXEMPT_MS`)。
2. ✅ **GroupAdmin 主动补全员 first_seen 基线**:GA v1.20.0 已做(onLoad 对所有已启用群补 + coalesce 防 NULL)。

## 工作目录已迁 Dropbox
工作目录 = `~/Dropbox/ops-work/android-ops`（多 Mac 经 Dropbox 同步）。新 session 先 `bash scripts/bootstrap.sh`（自识别机器/adb/scrcpy/手机/root/版本 + 本机记忆为空时从 `docs/memory-snapshot/` 自动 seed）。详见 `docs/ONBOARDING.md`。**写了新记忆后要 `cp ~/.claude/.../memory/*.md docs/memory-snapshot/` 同步回快照**，否则别的机器接不到。

## 当前部署版本（真机已上）
- **GroupAdmin v1.20.0**：在 v1.16.3(onLoad 冷启动健壮化 C-PLUGIN-05/VH-02)之上:
  - v1.17.0 **管理员时效**:加管理可选永久/N天,并行键 `admin_exp_<gid>`(未设=永久,向后兼容);到期惰性清除 `purgeExpiredAdmins`(管理函数入口,不进热路径);@命令 `@TA 管理员 [N]` + Dialog 天数双入口;Dialog 加删静默、群@命令保留群消息。
  - v1.17.1 列表/群消息文案「**有效期至** yyyy-MM-dd」(adminExpiryLabel 同源)。
  - v1.17.2 **@所有人(notify@all)不能踢** — @路径踢/请前置守卫 `_isAtAll`(标记真机待最终确认)。
  - **v1.18.0 潜水误判修复(2026-06-08)**:非文本消息(图片/表情/语音/红包/文件)`getContent()` 空, 原在 onHandleMsgBody 入口 isText 闸门早退、走不到 ~2188 行 recordSpeak → 只发非文本的人 last_speak 永空被 `#潜水` 误判潜水。修复=早退点前先记一次发言活动(只调 talker/sendTalker/isGroupEnabled缓存/recordSpeak O(1), 不调 getQuoteMsg, try/catch, 与主路径互斥)。真机实测纯图片使 owner last_speak 推进。**冒泡=任意消息类型**。详见 SPEC §3/§6.10。
  - **全群基线重置(2026-06-08 17:19)**:应用户要求"从今天起算", 停微信→`UPDATE speak SET first_seen=now`(全 8 群所有行, **last_speak 保留**)→推回→重启验证。真机留底 `groupadmin.db.before-baseline-reset-20260608`。**预期**:之后 N 天 `#潜水 N` 基本空(全员新成员豁免期), N 天后从今天起没冒泡的人才上榜。
  - **v1.19.0 警告带理由(2026-06-09)**:`@TA 警告 <理由>` 解析(末token非动作词但前一token="警告"→动作=警告+理由), `doWarn` 双签名(旧3参转调4参), 通知拼 ` · 理由`; **自动来源(理由非空)对豁免对象静默跳过**(不发报错)。手动 `@TA 警告` 不变。为伸手党治理铺路。
  - **v1.19.1 自动来源豁免管理员(Santa 修复)**:`canActOn(owner→admin)=true` 会让自动警告误踢潜水管理员; doWarn auto 分支前置 `if(auto&&(isAdminInGroup||isOwner||isNativeOwner))return;`。
  - **v1.19.2 [严重] recordSpeak last_speak 永卡 NULL(2026-06-09)**:l3Flush 的 UPSERT `last_speak=max(last_speak,excluded)`,**SQLite 标量 `max(NULL,x)=NULL`** → 先被建基线(last_speak=NULL)再发言的成员永远卡 NULL。**影响发言统计/#潜水(活跃者误判从未发言)/伸手党 全失真**(该群 491 人里 404 NULL,很多其实在发言)。修:`max(coalesce(last_speak,excluded),excluded)`(对齐 first_seen 那行本就有的 coalesce)。真机验证:长期 NULL 的活跃成员部署后再发言 last_speak 立即更新。**教训:SQLite UPSERT 里凡 max/min 旧列可能为 NULL,必须 coalesce 兜底**。注:此前 06-08 全群基线重置(first_seen=now)放大了此 bug(全员有 NULL 行)。修复后大家发言逐步自愈。
  - **v1.20.0 #潜水豁免+基线(2026-06-09, commit 95e012b)**:(1) **#潜水 新成员豁免改固定 1 天**(`LURK_NEWMEMBER_EXEMPT_MS`, 与潜水窗口 days 解耦):豁免 `fst>now-1天`、活跃仍 `lst>now-days天`;冷启动文案改"豁免1天"。语义=`#潜水 7` 列出"进群>1天 且 7天没发言"的人(原"进群>7天"), **名单更全**, 仍手动列表不自动踢。(2) **onLoad 对所有已启用群补 first_seen 基线**(去 `l3CountFirstSeen>0` skip, 幂等只补缺失), 堵"入群从不发言者无 speak 行→红包伸手党 rpQueryLastSpeak 返-1 漏检"窄缝(用户洞见)。(3) **`l3UpsertFirstSeen` coalesce 防 NULL**:`min(first_seen,excluded)`→`min(coalesce(...),excluded)`(对齐 v1.19.2 last_speak 教训)。验证:bsh EXIT=0 + 真机干净加载 baseline check「补基线0 新增0」(全员已有基线) + **受控 DB 实测 coalesce**(NULL→now/更早值保留/无行插入, 老 UPSERT 对比留 NULL 坐实) + Santa 双审(A逻辑PASS; B规格抓到 onLoad catch 块 stale v1.17.0 版本串→修→PASS)。#潜水 名单行为(T1/T2)需手动 scrcpy。设备留底 main.java.bak-before-v1200-20260609。**两个后续(#潜水改1天 + GA补基线)至此都做完**。
  - GroupAdmin 重启即加载。
- **RedPacketStats v1.11.4**：v1.6.4 基础上累积 包类型(§22)/设置页拆分(§23)/转发对象按群(§24)/重试3(§4)/伸手党治理(§25, v1.11.3 收尾 + v1.11.4 升级缺口自愈)。
  - 包类型 普通/定制(v1.7.x):定制开关(仅 Dialog)+关键字(**包含匹配**)+定制档表;worker 判定零热路径;定制第一条用定制前缀、第二条「【查包】定制包」。
  - 设置页(v1.8.x):主菜单 + 二级子页(showPageBasic/NormalTiers/Custom/Delay/Exclude/**Forward**);menuHolder 修关闭堆叠;排除名单显示「昵称（…尾4）」。
  - **转发对象按群**(v1.9.x §24):`rp_export_target_<gid>`/name(未设回退全局默认);后台 `getFriendList()`/`getGroupList()` + Handler 主线程单选弹框(防 ANR);设置后向目标发通知(只讲当前群);发送按群路由(rpBuildGroupedMsgs 带 grp);**每日定时开关+推送时间(全局)迁入「📮 转发对象」子页**,基础页只留本群启用。真机验收过(含选好友 getFriendList)。
  - **重试3(v1.10.0 §4)**:红包重试阶梯加末档 `rp_retry3_sec`(默认3600s=1小时), afterCoverNoDetail attempt≥2→≥3, 三段阶梯; cfgRetry3 仅 worker 读。延迟子页/命令(4参)/状态增列重试3。
  - **伸手党治理(v1.11.x §25, 2026-06-09, 默认关 `rp_freeloader_on_<gid>`)**:抢红包者在**红包检测时刻**前 N 分(默认30 `rp_freeloader_win_<gid>`)未在群发言 → 后台 fire-and-forget 线程发 `[AtWx=wxid] 警告 {N}分钟内未发言` 命令, 交群管 v1.19.1 原警告流程执行(同一计数到 wk_ 上限踢)。只读查 groupadmin.db speak.last_speak(rpQueryLastSpeak: -1无行/DB不可用跳过、0从未发言判、>0毫秒)。Santa 修复: 后台线程不占单飞锁/不撞30s看门狗 + 上限 FREELOADER_MAX_WARN=10(超出留痕) + flush 容差60s。安全网宁可漏判; 豁免靠群管 doWarn。只拼手气包、只领完的包。命令 `伸手党 开/关`、`伸手党窗口 N`; Dialog 子页🖐伸手党治理。**⚠️ 端到端启用实测(开开关+已知活跃/潜水各抢一次+管理员豁免)还没做, 用户暂未开启。**
  - **教训:红包领取者反射模型(LuckyMoneyNewReceiveUI 详情)无 per-person 领取时刻字段**(`g` 取不到、无任何时间戳数值字段; 真机 GPROBE 探测确认)。本想用每人领取时刻判定, 退用红包检测时刻(Job 加 JOB_DETECTMS 第10槽承载)。要按人领取时刻得另挖(UI 文本/字符串字段, 更脆弱)。
  - **伸手党 v1.11.3 收尾完成(2026-06-09, commit b747a58, 默认关)**:撤 DRY-RUN/GFDBG 恢复真发送。**豁免模型(用户最终拍板)= 全局开关时刻宽限, 不是每人 first_seen**:新增 per-group 键 `rp_freeloader_since_<gid>`(命令『伸手党 开』/Dialog OFF→ON 写当前 ms, 已 ON 不重置); 判定段闸门 `since<=0`(安全网跳过) 或 红包检测时刻 `packetMs` 在 `since+winMs` 内 → 整包跳过(全员豁免=刚开开关数据预热期); 宽限后**所有领取者(含新进群)按 last_speak 判, 不用 first_seen**(与 #潜水 刻意区分:进群就抢、抢前没发言即判)。复用红包排除名单 `getExcludeList` 豁免领取者。排查确认红包 wxid 与聊天 wxid **一致(无错配)**, 误判根因就是 GA v1.19.2 的 last_speak NULL bug(已修)。验证:bsh 解析 EXIT=0 + 真机干净加载无 eval/parse 异常 + Santa 双独立 Reviewer(逻辑+规格)均 PASS。**⚠️ 真红包行为实测(T1-T5)adb 模拟不了, 需手动 scrcpy(用户在场+单测试群+临时『伸手党 开』); 且 last_speak 仍自愈中(大量 NULL), 30min 宽限盖不住多小时自愈期 → 建议自愈后或仅测试群再开。默认关, 未启用。见 docs/verification-report.md**。设备留底 main.java.bak-before-v1113-20260609。
  - **v1.11.4 伸手党升级缺口自愈(2026-06-09, commit c6de767)**:用户报"刚才几个红包伸手党没发挥作用"。根因(rp.log 实证, 系统化调试):伸手党 v1.11.2 测试时已 `伸手党 开`(10:00 真发 warned=4), v1.11.3 引入「开关打开时刻 since」闸门(只在 OFF→ON 写), 升级时开关带"已开"状态从未经历 OFF→ON → `since=0` → 判定段 `since<=0` 安全网整包跳过 → 静默失效("freeloader skip: no since anchor")。**不是逻辑 bug, 是升级状态缺口**。修:判定段 `since<=0` 由直接跳过改 **lazy-init 补写 since=now 再跳过**(自愈、免手动重切, 下一个包按宽限/判定生效, 仍保守不当轮判)。Santa A 抓到三处 since 写点用裸 groupId、读用 normGroupId(群 ID 无空格不触发但结构性不一致)→ 统一套 normGroupId。**测试配方(避 30min 宽限):测试群 `伸手党窗口 1` → 发抢拼手气包(第1个触发 lazy-init 跳过)→ 等1分钟 → 再发抢(抢前1分钟没发言者被警告)→ 测完 `伸手党窗口 30` 调回**。lazy-init 真触发需真红包, 已部署待用户实测。设备留底 main.java.bak-before-v1114-20260609。**教训见 [[config-key-written-on-transition-upgrade-gap]]**。
  - **懒加载;曾因解析失败被自动停用,注意确认开关 ON**。

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
