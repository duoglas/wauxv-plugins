// RedPacketStats — v1.11.2 — 在 v1.11.1 之上: 伸手党判定基准从「每人领取时刻」改为「红包检测时刻」(全员统一, SPEC §25)。原因: 真机确认当前微信版本领取者反射模型无 per-person 领取时刻字段(g 取不到, 无任何时间戳数值字段)。改用红包检测/到达时刻(onHandleMsg 检测入队那一刻, ≈红包发出时刻)做基准, 语义=「红包来之前 N 分钟你在不在场」。Job 增 JOB_DETECTMS 第 10 槽承载检测时刻(入队 O(1) 只塞一个已算好的 long, 重试透传不改基准); 撤掉 hbExtractTriple 的 g/grabMs 提取(返回回 4 元素)+receivers 回 3 元素{nick,amount,wxid}; 删临时 GPROBE 诊断。后台线程/上限 FREELOADER_MAX_WARN/容差 FREELOADER_FLUSH_GRACE_MS/安全网 全不变。
// RedPacketStats — v1.11.1 — 在 v1.11.0 之上 (Santa 修复): 伸手党判定整段移入独立 fire-and-forget 后台线程 (不占 IN_FLIGHT 单飞锁/不撞看门狗 30s; 闭包变量 fTalker+fSnap 快照均 final) + 警告人数上限 FREELOADER_MAX_WARN=10 (超出 capped 留痕不静默截断) + last_speak flush 容差 FREELOADER_FLUSH_GRACE_MS=60s (防抢前刚发言未落盘误判: 判定式 ls<grabMs-winMs-容差)。
// RedPacketStats — v1.11.0 — 在 v1.10.0 之上: 红包伸手党治理 (SPEC §25) — 抢红包者在抢到时刻前 N 分(默认30, rp_freeloader_win_<gid>)未在群发言 → 后台反射线程自动发群管警告命令 `[AtWx=wxid] 警告 {N}分钟内未发言`, 交 GroupAdmin v1.19.0 按原流程执行(累计可触发踢人)。
//                            数据流: hbExtractTriple 增提领取时刻 g(单位自动归一为毫秒, 返回第5元素)→ receivers 加第4元素 grabMs(下游 rpRecordStats/hbTierAndSend/均分过滤 只读 [0..2] 不越界)→ hbDetailExtract 在"均分跳过"过滤之后(仅拼手气)+rpRecordStats 之后, 对每领取者: rpQueryLastSpeak(只读查 groupadmin.db 的 speak.last_speak, 独立连接 OPEN_READONLY 用完即关) → ls==0(从未发言)或 ls<grabMs-winMs(抢前N分无发言)→ sendText 警告命令。
//                            安全网(宁可漏判): 开关关(rp_freeloader_on_<gid>!=1)/wxid或grabMs取不到/rpQueryLastSpeak<0(无speak行或DB不可用, 含群管该群没开)→ 跳过。多伸手党间 Thread.sleep(800ms)防刷屏(已在后台线程)。配置: 命令 伸手党 开/关 · 伸手党窗口 N · 伸手党(显示); Dialog 子页「🖐 伸手党治理」(开关 toggle + 窗口 EditText); 状态增显。全程后台反射线程 + 整段 try/catch, 绝不碰 onHandleMsg 热路径, 不直接踢人。
// RedPacketStats — v1.10.0 — 在 v1.9.2 之上: 红包重试阶梯加末档「重试3」(SPEC §3/§4/§15.5) — 新增 rp_retry3_sec(默认 3600 秒=1 小时) + cfgRetry3()。afterCoverNoDetail 放弃点 attempt>=2→>=3, retrySec 三段阶梯(attempt0→retry1/1→retry2/2→retry3), 抖动公式 hbJitter 不动。延迟子页加重试3 EditText(d3) + onSave 写 K_RETRY3; 文字命令『红包延迟』加 4 参分支(首次/重试1/重试2/重试3), 保留空参/1参/3参; 状态显示/当前延迟 toast 增列重试3。worker 主干/检测/入队/统计/定时/onHandleMsg 热路径零改, cfgRetry3 仅 worker 的 afterCoverNoDetail 读。
// RedPacketStats — v1.9.1 — 转发对象 全局→按群 §24: rp_export_target_<gid>/name + cfgExportTargetFor 回退全局默认 + rpBuildGroupedMsgs 带 grp、发送按群路由到各自目标 + 通知只讲当前群。
// RedPacketStats — v1.9.2 — 在 v1.9.1 之上: 每日定时开关 + 推送时间(全局)迁入「📮 转发对象」子页 (SPEC §24/§20) — 转发对象页加「📅 本群每日定时」开关(即时生效 enable/disableDailyGroup) + 「⏰ 每日推送时间」EditText(子页💾保存校验 0-23 写 K_DAILY_HOUR); 基础开关页(showPageBasic)移走每日定时 toggle, 只留「本群启用」。定时开关函数/cfgDailyHour 夹取/存储键 不动, 只动 Dialog 展示层, worker/onHandleMsg 热路径零改。
// RedPacketStats — v1.9.0 — 在 v1.8.1 之上: 转发对象可选好友/群 (SPEC §24) — getFriendList/getGroupList 选择器 + rp_export_target_name(显示名) + 设置后通知(sendForwardNotice)。后台线程加载 list, Handler 回主线程弹单选 AlertDialog 防 ANR。只动 Dialog(showPageForward 入口)+发送层; 每日定时/手动导出沿用 cfgExportTarget() 逻辑不动, onHandleMsg 热路径零改。
// RedPacketStats — v1.8.1 — 在 v1.8.0 之上: 修主菜单「关闭」失效(对话框堆叠—入口按钮开子页前先 dismiss 主菜单, menuHolder 捕获) + 排除名单显示名字(rpSenderName 群名片/昵称, 「昵称（…尾4）」)。
// RedPacketStats — v1.8.0 — 在 v1.7.2 之上: 设置页拆分 (SPEC §23) — 原单一长 ScrollView 配置 Dialog 拆为「主菜单 + 二级子页」。只动 Dialog 展示层 (worker/onHandleMsg 热路径/文字命令/存储键 全不动)。
//                            主菜单(showConfigDialog): 状态摘要 + 入口按钮; 子页 showPageBasic/showPageNormalTiers/showPageCustom/showPageDelay/showPageExclude, 各页只写本页 key, 保存/返回回主菜单。导出为主菜单直接动作(不进子页)。每页/骨架整段 try/catch, 只构建 Dialog 无 UI 遍历(§7)。
// RedPacketStats — v1.7.2 — 在 v1.7.1 之上: 定制包第二条 @消息【查包】前缀加「定制包」→【查包】定制包 (SPEC §22.6)。
// RedPacketStats — v1.7.1 — 在 v1.7.0 之上: 定制包判定包含匹配修正 + 定制包第一条群规文本 (SPEC §22.2/§22.6)。
//
// v1.7.1: [修复/SPEC §22.2,§22.6] 两处 (其余 v1.7.0 逻辑一行未动: 限频/排除/分档/两条消息骨架/抖动/ANR/onHandleMsg 热路径)。
//         1. 定制判定 前缀→包含 (§22.2): rpIsCustom 把 title.trim().startsWith(kw) 改为 title.contains(kw) (kw 先 trim, 空串仍按未启用→false→普通)。
//            真实定制包祝福语形如「【定制】…」, 关键字「定制」被【】包裹不在串首, 前缀匹配漏判 → 包含匹配命中。整段 try/catch 不变, 异常回退 false。
//            worker pkg-type: 诊断日志保留(脱敏 kwLen/titleLen, 不打原文)。
//         2. 定制包第一条群规文本 (§22.6): 新增 per-group 键 rp_custom_rule_prefix_<gid>(默认「定制包请按要求执行」)+helper cfgCustomRulePrefix(groupId)。
//            hbTierAndSend 第一条群规前缀 = isCustom ? cfgCustomRulePrefix(talker) : cfgRulePrefix(); 各档仍「，过{阈值}元{动作}」(档表已是 getTiersByType 定制档)。
//            第二条(@条/【查包】/逐档@)机制一行不动, 仅档表/文案来自定制档表。
//            录入: 命令 定制群规前缀 <文字>(当前群, 仿 红包群规前缀) + Dialog 定制区块「定制群规前缀」EditText + 红包统计状态增显。
//
// v1.7.0: [功能/SPEC §22] 给红包加「包类型」维度 — 普通包(默认) / 定制包(祝福语命中定制关键字, v1.7.1 改包含匹配)。默认全关 → 没开定制的群与今天逐字节一致。
//         · 配置(per-group): rp_custom_on_<gid>(开关,仅 Dialog 可改)/rp_custom_kw_<gid>(关键字,前缀匹配)/rp_tiers_custom_<gid>(定制档表,格式同 rp_tiers_)。
//         · 类型判定: 全在后台 worker(hbProcess, title 本就在此提取) — rpIsCustom(talker,title): custom_on 关→普通; 否则 kw 非空且 title 包含 kw(v1.7.1 由前缀改包含)→定制; 否则普通。
//           判定结果回填 Job 第 9 槽 JOB_ISCUSTOM, hbDetailExtract 取出透传给 hbTierAndSend。【onHandleMsg/onHandleMsgBody 热路径一行不加 — 只在入队 Job 塞常量 Boolean.FALSE(C-PERF-01/03, VH-01 教训)】。
//         · 选档: hbTierAndSend 加 isCustom 参, getTiers(talker)→getTiersByType(talker,isCustom)(定制读 rp_tiers_custom, 未配回退普通)。限频/排除/分档/两条消息(引用群规+@)/ANR/抖动 一行不动, 仅档表来源切换。
//         · 命令(onClickSendBtn, 当前群, 复用普通解析器 isCustom=true): 定制阈值/定制文案K/定制增加档/定制减少档(操作 rp_tiers_custom) + 定制关键字 <kw>(写 rp_custom_kw)。错误/格式文案仿普通。
//         · Dialog(红包统计设置): 加「定制」区块 — ☑是否开启定制(仅此处改 rp_custom_on)+定制关键字 EditText+定制档表多行 EditText(复用 rpParseTierBox 逐行校验, 全无效不保存)。
//         · 状态(红包统计状态): 增显 定制开关/定制关键字/定制档表(rpTiersSummary)。
//         · 兼容: 默认全关→完全向后兼容; 改动落在 worker+发送+命令+Dialog, 全程 try/catch; 旧档表 helper 抽 parseTiersFromKey/setTiersToKey 共用普通/定制, 普通 call site 行为不变。
//
// v1.6.4: [性能/C-PERF-01,03] 启用群集合内存缓存 (仿 GroupAdmin v1.16.2)。消除红包消息热路径最后一处 per-红包 FUSE 读:
//         onHandleMsgBody 的 isGroupEnabled → getEnabledGroups → getString(K_ENABLED) 原每个红包候选读一次 config.prop (~26ms)。
//         新增顶层 RP_ENABLED_CACHE(HashSet,normGroupId 后)/RP_ENABLED_CACHE_TS/RP_ENABLED_TTL_MS(60s)。rpLoadEnabledCache() 全量 norm 后原子替换。
//         isGroupEnabled: 缓存 null 或超 TTL → rpLoadEnabledCache(), 然后 contains; 整段 try/catch, 异常回退原"直接 getString 遍历"(宁可慢不可错)。
//         enableGroup/disableGroup putString 后立即 rpLoadEnabledCache() — 开启/关闭红包统计下一个红包即时生效, 不等 TTL。
//         归一口径与原 isGroupEnabled 完全一致(normGroupId=trim)。加载失败保留旧缓存只 log。只动 isGroupEnabled 读路径+缓存维护,
//         不碰检测/入队/worker/分档/发送/统计/定时/导出; spike 归因埋点保留(验证红包热路径耗时进一步降)。
//         其它只读 getEnabledGroups() 调用(每日迁移/daily groups, 非热路径)保持原 getString(不影响正确性)。
//
// v1.6.2: spike 归因埋点 (诊断用, 仿 v1.4.0 PERF_* 风格, 绝不反噬热路径)。
//         目的: 区分 "每 ~200 条出现 1 次 200-370ms onHandleMsg max spike" 是 (A)真红包那条在热路径做重活,
//         还是 (B)GC/框架抖动。新增一组顶层 long/int 累加 (只 nanoTime+整型, 无 IO/log/反射):
//         1. A/B 分类计时: 每条 onHandleMsg 耗时按 红包消息(A, PERF_RP_*)/普通消息(B, PERF_NORM_*) 分别累加 sum/max/count。
//            A/B 判定: onHandleMsgBody 过完"命中检测(isHb)+本群启用"闸门即置顶层标记 PERF_LAST_RP=true (走 extract+入队"重活"路径);
//            未命中/未启用快速 return 的为 B。wrapper finally 读标记分类。每条进入前 wrapper 先清标记。
//         2. spike 标记: 单条耗时 > PERF_SPIKE_NS(50ms) → PERF_SPIKE_N++, 并分 PERF_SPIKE_RP_N(spike且红包)/PERF_SPIKE_NORM_N(spike且普通)。
//            关键判据: spike 几乎全落普通消息(B) → 大概率 GC/框架抖动(与本插件无关); spike 集中在红包消息(A) → extract/入队在热路径做了重活, 需移后台。
//         3. perf flush 落盘行追加: rp_msg_n/rp_msg_avg_us/rp_msg_max_us/norm_avg_us/norm_max_us/spike_n/spike_rp/spike_norm + (可选)rp_extract_*。窗口重置时同步清零。
//         4. (可选细计时) 红包消息内部单量 rpExtractTitle(XML parse) 耗时 → PERF_EXTRACT_* (rp_extract_avg_us/max_us), 帮进一步定位重活点。仅 nanoTime, 无新 IO/反射。
//         严格不碰任何业务逻辑(检测/extract/入队/队列/分档/发送一行不动), 只插计时与计数; 整段 try/catch, 埋点出错不影响消息。
//
// v1.6.1: A. 档位上限 RP_MAX_TIERS 4→10。所有"写死 4"的档位相关命中点改用 RP_MAX_TIERS (getTiers 截断/红包阈值个数上限/
//            红包增加档满档判定/Dialog 行解析截断/各错误文案 1-4→1-10/K=1/2/3/4→档号 1-10)。与档位无关的 4 (如尾号 substring(-4)、
//            标题长度校验) 一律不动。
//         B. 修红包文案K 两位数 bug: v1.6.0 用 afterPrefix.charAt(0) 只认 '1'-'4', "红包文案10 发红包" 误解析为档1+动作"0 发红包"。
//            改为解析前导整数(1-2位)作 K, 其余 trim 作动作; 校验 1<=K<=RP_MAX_TIERS (否则"❌ 档号需 1-10")、动作非空 (否则格式提示)。
//         C. 全量保存错误检测: rpSetThresholdsCmd (空/非整数→"阈值需整数"/<=0→"阈值需正整数"/重复/>10档→"最多10档"/乱序自动升序);
//            rpAddTierCmd (缺阈值或动作→格式/非整数/<=0/重复→"该阈值已存在"/满→"最多10档"); rpDelTopTierCmd (仅1档→"至少保留1档");
//            Dialog 多行解析逐行校验收集行号错误(缺动作/非整数/<=0/重复/超10档截断), 全部无效→不保存不破坏原表, 部分有效→存有效行+提示。
//            所有入口 try/catch 兜底, 绝不抛异常打断 onClickSendBtn/Dialog。
//         不碰: getTiers 回退/setTiers 升序/tierOf 分档/hbTierAndSend 发送/rpRecordStats 写库/onHandleMsg 热路径/队列/反射/定时/导出/ANR 铁律。
//            onHandleMsg 热路径仍零 getTiers/setTiers 调用 (经审计确认)。
//
// v1.6.0: 档位 per-group 化 (键 rp_tiers_<groupId> = "阈值|动作;..."(升序), 未配置群回退全局三档 cfgT1/2/3+cfgTxt1/2/3, 旧行为不变)。
//         新增 helper: getTiers/setTiers/sortTiers/tierOf/tierCent/sanitizeTierAction/rpTiersSummary 及命令 helper
//         rpSetThresholdsCmd/rpSetTierActionCmd/rpAddTierCmd/rpDelTopTierCmd。
//         命令(onClickSendBtn, 全作用当前群): 红包阈值 a [b c d](1-4个递增正整数)/红包文案K 文字(K=1-4)/
//         红包增加档 <阈值> <动作>(档数<4)/红包减少档(删最高档,档数>1)。回执动态列档表。
//         hbTierAndSend: 写死3档分档→getTiers(talker)的 N 档循环 (归最高满足档, 群规列全档, @条逐非空档, notice 用最低档阈值)。
//         rpRecordStats: 写死3档判定→tierOf 循环 (达标=金额>最低档, qualifiers 档次 1..N)。
//         状态 红包统计状态 按 getTiers(当前群) 动态列档; Dialog 群上下文用单多行 EditText(每行"阈值 动作")编辑本群档表(降级方案,无UI遍历/ANR), 无群上下文仍编辑全局默认三档。
//         严格不碰 onHandleMsg 热路径 (getTiers 只在 bg worker/统计线程/UI 回调调); 队列/单飞/看门狗/重试/检测/专属/反射/排除/定时/导出格式/抖动/ANR/perf 全不动。
//
// v1.5.0: 只动 rpExportToday() 与 rpDailySend() 的【组装文本 + 分条 + 发送循环】, 外加 SQL ORDER BY 改 grp,ts (便于按群顺序遍历)。
//         热路径 onHandleMsg / 写库 rpRecordStats / 录入范围(只记超档) / SQL WHERE 过滤(窗口/分组条件) —— 全部一行未动。
//         1. 输出格式重写 (旧: 跨群混排、按1800字切条、每红包压一行 → 新: 按群分组、每群一条 sendText、多行格式)。
//            新增私有 helper rpBuildGroupedMsgs(rows, headerPrefix): 解析 rp_record 行 → 按群 (grp) 用 LinkedHashMap 分组 →
//            每群拼一条文本 (头行 "{headerPrefix}  【群名】"; 红包行 "HH:mm 发包人「标题」 达标N人"; 人行 全角缩进"　昵称 金额元"; 红包间空一行) →
//            单群文本超 ~1800 字按【行】兜底分条 (不切断单行, MAX_CHARS scope 改成群内) → 返回 List<String> 每元素 = 一条待发消息。
//            qualifiers 解析 helper rpParseQualifiers(qf): "昵称|金额元|档次;..." → 防空/防缺段, 单人解析失败跳过不抛。
//         2. SQL: 两处查询 ORDER BY ts ASC → ORDER BY grp ASC, ts ASC (窗口/分组 WHERE 不变)。
//         3. rpExportToday header 用当天 MM-dd; rpDailySend header 用 winStr。每群一条 sendText。
//            rpDailySend: 全 daily group 窗口内都无记录 → 仍发一条总心跳 (header + 无记录提示), 保留 [手动测试] 标记。
//         4. 嗅探日志 (export: sent N msg / daily-send: sent N msg) 条数改实际发送条数, 加 groups/rows, 全程脱敏 (只记条数/群数/行数/目标尾4位, 绝不打群名/昵称/内容)。
//         整段 try/catch 可降级, 发送仍放后台线程。BeanShell 无泛型/lambda, 用 ArrayList/LinkedHashMap, 强转 (String)/(List)。
//
// v1.4.0: 三件 (其余 v1.3.x/v1.2.0 逻辑一行未动: 第一条/队列/单工作者/单飞/反射/分档/排除/统计DB写入点/导出基本结构/抖动/ANR/两条消息机制)。
//         1. 发包人昵称修复(bug): rpRecordStats 之前用 lookupName(senderWxid, talker) 在本设备显示成 wxid。
//            改用 rpSenderName(): 优先 getFriendDisplayName(wxid, talker)(群名片/群昵称) → 退 getFriendNickName(wxid) → 退 wxid。
//            领取者昵称(反射 d 字段)不动(正常)。仅后台反射线程取名/写库, 不碰热路径。
//         2. 每日定时统计 → 私聊 rp_export_target (§20): 按群开关 rp_daily_groups(CSV); 一次性迁移 rp_daily_migrated 把
//            daily_groups 初始化=当前 enabled_groups; 开启红包统计时也把该群加进 daily_groups(默认开, 可单独关)。
//            命令(onClickSendBtn): 开启红包定时/关闭红包定时(对当前群)/红包每日测试(立即按过去24h跑); 红包统计状态显示本群定时开关。
//            触发: rpWorkerTick 每 tick 调 rpDailyCheck — 本地时间 >= 今天 rp_daily_hour(默认7)点 且 rp_daily_last_sent != 今天 →
//            执行每日发送 → last_sent 置今天 (每天只发一次, 重启不重发不漏发, 7点后启动当天补发)。
//            发送窗口=[今天7点往前24h, 今天7点), 用 ts 范围查(跨两个日历日, 别用 date 列), grp IN daily_groups, ts 升序, 每红包一行带[群名];
//            无记录也发 header + (过去24h无达标红包) 作每日心跳。整段后台线程 + try/catch 可降级。
//         3. 轻量 perf 埋点(仿 GroupAdmin): onHandleMsg 包装层只 nanoTime + 顶层整型累加(PERF_N/PERF_SUM_NS/PERF_MAX_NS),
//            不每条写文件; 由 rpWorkerTick 定时(>=PERF_FLUSH_EVERY)或热路径兜底(>=N*50)聚合落盘独立 perf.log(与 rp.log 分开),
//            行含 ts/iso/样本数/平均us/最大us。全程 try/catch, 埋点异常不影响消息; 落盘放后台线程, 绝不变成热路径负担。
//
// v1.3.1: 三处增量 (其余 v1.3.0/v1.2.0 逻辑一行未动: 第一条/队列/单工作者/单飞/反射/分档/排除/统计DB写入点/导出基本结构/抖动/ANR/perf顺序/第二条"隔gap秒发"机制)。
//
// v1.3.1: 三处增量 (其余 v1.3.0/v1.2.0 逻辑一行未动: 第一条/队列/单工作者/单飞/反射/分档/排除/统计DB写入点/导出基本结构/抖动/ANR/perf顺序/第二条"隔gap秒发"机制)。
//         1. 统计 rp_record 加 group_name 列 (§17.1a): CREATE TABLE 含该列 + 建表后 try ALTER TABLE ADD COLUMN(老库兜底,已存在则吞)。
//            rpRecordStats 用 rpGroupName(talker) 取当前群名(仿 GroupAdmin: getGroupList()→getRoomId()匹配→getName()), 取不到留空串。INSERT 多绑 group_name。
//            导出 rpExportToday 每行行首带 [群名](取不到回退群id, 再空则 [])。仅后台反射线程取群名/写库, 不碰热路径。
//         2. 第二条(@提醒)整体开头加 【查包】 前缀 (§16): msg2 = "【查包】" + 原逐档随机@文本 (在所有 [AtWx=] 之前)。
//         3. >at上限 也发第二条 (§6.1/§16): 现状">at上限只发第一条"改为"第一条照发 + 隔 rp_two_msg_gap_sec 秒发第二条(notice)"。
//            notice = "【查包】本次过{t1}元共{Y}人，人数较多不逐一提醒，请大家自觉。" ({t1}=cfgT1元, {Y}=totalQualified达标总人数), 无@纯文字。
//            走与 ≤上限 同一"第一条后隔gap秒发第二条"机制(final捕获, fire-and-forget, 不进在飞锁)。
//            即: 达标0不发; 达标≥1 都发第一条; 第二条 ≤上限=【查包】逐档@、>上限=【查包】汇总notice, 两种都带【查包】、都隔gap秒。
//
// v1.3.0: 在 v1.2.0(已真机验证的"队列+两条消息"定版)之上重新加回统计 DB + 导出 + 标题提取, 并加 3 处嗅探调试日志。
//         v1.2.0 的队列/单工作者/单飞/看门狗/CUR_JOB/重试/检测/专属/开页/封面三分支/反射/分档/排除/普通跳过/
//         两条消息(第一条固定简洁 + 隔 gap 秒第二条随机@)/finish/抖动/ANR/onClickSendBtn/perf顺序 —— 全部一行未动。
//         本版只新增 (全程独立 try/catch, 可降级):
//         A. 统计 DB(§17): rpDb()(SQLite 懒打开单例, 库 …/Plugin/RedPacketStats/redpacket_stats.db; GroupAdmin 同级目录建库已验证)
//            + rpRecordStats(...) + rpCentToYuan + rpToday + DB 常量(RP_DB/RP_DB_PATH/RP_DB_LOCK) + onUnload 关库。
//            表 rp_record(key,date,ts,grp,sender_wxid,sender_name,title,qualified_count,qualifiers, PK(key)), 一红包一行。
//            写入点铁律(perf review 结论): 只在 hbDetailExtract 的【后台反射线程】里、拿到 receivers 后、关页前调 rpRecordStats;
//            整段 try/catch 可降级, 失败只 log, 绝不抛/不卡关页/不发提醒; 绝不在 onHandleMsg/入队/tick/UI线程写 DB。
//            达标(>档1阈值、剔除排除名单、归最高档)全量记录(>10 也全记); 达标 0 不记; INSERT OR REPLACE 去重。日志脱敏(只记 qualified=N)。
//         B. 红包标题 title(§17.3): rpExtractTitle(content) 从 wcpayinfo 按 sendertitle→receivertitle→scenetext→des 取首个非空,
//            onHandleMsg 检测红包时提取存进 Job(JOB_TITLE 第 7 槽), 后台写 DB 时用。只在红包消息上 parse, 不碰普通消息热路径。
//         C. 导出按钮(§18): rpExportToday() + Dialog「📤 导出今日红包统计 → 私聊」。查当天 rp_record 每红包一行
//            "HH:mm 发红包:X 标题:Y 达标N人:昵称|金额|档次;...", 超 1800 字分多条, 后台线程 sendText(target,...);
//            目标可配 rp_export_target (默认 wxid_REDACTED)。当天无记录 toast。
//         D. 嗅探调试日志(真机一验即知 3 个未知): ① rpRecordStats 写库时记 [RP] stats title: usedField=<字段名> len=<长度>
//            (只记字段名+长度, 不打标题原文); ② rpDb 打开成功记 stats-db opened / 失败记 open fail;
//            ③ rpExportToday 发送后记 export: sent N msg to target(尾4位)。
//
// v1.2.0: 两条消息定版, 剥离统计。从 v1.1.4 剥掉统计 DB(rpRecordStats/rpDb/rpExportToday/rpCentToYuan/rpToday)
//         + 导出按钮(§18) + 红包标题提取(rpExtractTitle/JOB_TITLE 槽), Job 恢复无 title 的 6 元组。
//         保留: 队列/单工作者/单飞/看门狗/CUR_JOB/重试/检测/专属/开页/封面三分支/反射/分档/排除/普通跳过/finish/抖动/ANR/onClickSendBtn/perf顺序
//         + 两条消息修正(v1.1.4 §16, 完整保留)。
//         (v1.3.0 已在此之上重新加回统计功能, 见上。)
//
// v1.1.4: 发两条修正(SPEC §16)。只改 hbTierAndSend 的两条发送, 其余(队列/单工作者/单飞/看门狗/CUR_JOB/重试/检测/专属/开页/封面三分支/反射/分档口径/排除/普通跳过/finish/抖动/ANR/onClickSendBtn/perf顺序)全不动。
//         1) 严格顺序+间隔: 第一条(引用群规)发出后, 隔 rp_two_msg_gap_sec 秒(可配, 默认 3) 再发第二条(@), 保证到达顺序。
//            实现: delay(rp_two_msg_gap_sec*1000, Runnable) 发第二条; @文本/talker final 捕获进闭包(BeanShell 闭包坑); 回调 try/catch。
//            延迟 fire-and-forget, 不进在飞锁; 主流程(rpClearInFlight/finishDetailAndCover)照常继续, 不等第二条。>at上限无第二条故无延迟。
//         2) 第一条固定简洁、不随机、无语气词: 第一条(引用群规条)改最简固定措辞, 不调 pickConnector/pickActionPhrase。
//            格式 = cfgRulePrefix() + "，过{t1}元{动作1}，过{t2}元{动作2}，过{t3}元{动作3}"(连接词固定"过X元", 动作用 cfgTxt1/2/3 原样, 无语气无随机, 三档都列)。
//            ≤at上限 与 >at上限 都用同一条固定简洁文本(>上限时它是唯一发的那条)。
//         3) 只第二条(@条)保持随机: pickConnector(金额连接词随机)+pickActionPhrase(语气包裹随机)+[AtWx=], 只@达标且未排除的人, 逐档。不变。
//
// v1.1.3(本版已剥离): 统计本地 DB(SPEC §17) + 导出按钮(§18) + 热路径命令闸门顺序优化(§19)。
//         统计 DB / 导出按钮 / 标题提取已在 v1.2.0 整体剥离, 后续单独实现; 完整 WIP 实现见
//         versions/main.java.v1.1.4-with-stats-wip-* 备份。§19 perf 顺序优化保留。
//         C. perf 顺序(§19): onHandleMsg 排除命令闸门把 content.indexOf("红包排除")>=0 提到 sender.equals(getLoginWxid()) 之前, 普通消息不再每条调 getLoginWxid()。纯顺序调整, 零行为变化。
//
// v1.1.2: 发提醒拆"两条"(SPEC §16 终版)。实测结论(2026-06-05, 用户真机):
//         · sendQuoteMsg(talker, 本地msgId(msg.getMsgId(), 小数字), content) 能成功引用原红包消息;
//           但 content 里的 [AtWx=wxid] 在引用消息里**不会被解析成真@**, 被@者收不到提醒。
//         · sendText(talker, "[AtWx=wxid] ...") 的 @ 真生效, 被@者收到提醒。
//         → 引用 与 @ 不能一条兼得, 故拆两条 (同一次提醒, 共享 30s 同群限频, 限频判定在发两条之前):
//           1) 引用条 (达标≥1 必发, ≤上限 与 >上限 都发): rpSendQuote(talker, CUR_JOB.msgid, 固定简洁群规文本)。
//              v1.1.4: 群规文本 = 前缀 + "过{t}元{动作核心}" 三档 (无随机无语气, 动作 cfgTxt 原样), 两种情况内容一致。
//           2) @条 (仅 1~at上限 发, v1.1.4 延迟 rp_two_msg_gap_sec 秒后发以保序): rpSendAt(talker, @文本) — 每档 [AtWx=wxid]...{连接词随机}{语气随机} 真@到人。
//         · 达标 == 0 → 两条都不发。 > at上限 → 只发引用群规条 (不发@条, 无延迟)。
//         健壮: 引用条 sendQuoteMsg 抛异常/msgid≤0 → 回退 sendText(不带引用的同一群规文本); @条始终 sendText。绝不因引用失败漏@。
//         本基线 (queue-wip) 已无 v1.1.x 临时测试命令 (红包引用测试/rpRunQuoteTest/rpTryReflectMsgId/SvrId/rpTail/LAST_HB_*)。
//         队列/单飞/看门狗/CUR_JOB归属/重试重新入队/检测/专属过滤/开页/封面三分支/反射/结构化提取/分档/排除/普通跳过/文案随机/finish精确/抖动/ANR上界/onClickSendBtn 全不动。
//
// v1.1.1(已被 v1.1.2 取代): @提醒尝试"引用红包+@"同条。实测证明引用条内 [AtWx=] 不解析, @未到人 → v1.1.2 改发两条。
//
// v1.1.0: 调度层重构(SPEC §15) + 发提醒引用原红包(SPEC §16)。主干(开页/封面三分支/反射/分档/排除/上限/文案随机/关页/抖动/限频/ANR上界)逻辑全不动, 只换调度驱动 + 末端发送方式。
//         A. 队列+单工作者(单飞, §15): 旧"每红包各自 delay(hbProcess)"红包雨并发开页对撞、全局态被踩 → 改单工作者串行驱动。
//            · Job={nativeurl(=去重key), talker, sender, msgid, dueAt(ms), attempt}。
//            · 生产者(onHandleMsg): 检测→dueAt=now+首次延迟(秒×1000,抖动), attempt=0 → 入队(synchronized RP_QUEUE, 按 key 去重: 在队/在飞/已完成都不重入)。上限 RP_QUEUE_MAX=50 满则丢最旧+log。不再 per-红包 delay。
//            · 单工作者 rpWorkerTick(): onLoad 启动, 每 tick 末 delay(RP_TICK_MS=3000,自己) 自重调度(仿 GroupAdmin l3FlushLoop; 整体 try/catch+finally 必重调度不停摆)。
//              每 tick: 看门狗(在飞且 now-inflightStartMs>RP_WATCHDOG_MS=30000 → 强清在飞) → 有在飞 return(单飞) → 否则挑 dueAt≤now 最早 job 出队 → IN_FLIGHT=true+inflightStartMs=now+CUR_JOB=job → hbProcess(job)。
//            · CUR_JOB 归属: 当前处理红包上下文统一成 CUR_JOB(Job 对象), onResume hook(路由/反射/发送/关页)全用 CUR_JOB 取。单飞下不会被踩; SEEN.clear() 只影响当前 job。
//            · 退出点必清在飞: 发完成功/普通跳过/金额全等跳过/attempt≥2放弃/任何异常 → IN_FLIGHT=false;CUR_JOB=null。没领完重试 → job 重新入队(dueAt=now+重试间隔(秒×1000,抖动),attempt+1)+清在飞(重试不再独立 delay)。
//         B. 发提醒(§16, v1.1.2 终版): 拆两条 — 引用条 rpSendQuote(talker,msgid,群规文本) + @条 rpSendAt(talker,@文本)。
//            · 引用条: 达标≥1 必发, sendQuoteMsg 引用原红包(纯群规, 无@); msgid≤0/抛异常 → 回退 sendText(同一群规文本)。
//            · @条: 仅 1~at上限 发, sendText 解析 [AtWx=] 真@到人 (引用条内 @ 实测不解析, 故拆开)。
//            · msgid=本地 msg.getMsgId()(非 srvid), Job 里已存。达标0不发; >at上限只发引用条。
//
// v1.0.7: 措辞随机改造(SPEC §14)。撤销 v1.0.6 的动作多变体(| 分隔 + pickVariant); 动作核心词/群规前缀恢复单值原样录入。
//         随机仅两处: pickConnector(阈值)=金额连接词随机(超过{X}元/过{X}/...), pickActionPhrase(动作原文)=语气包裹随机({A}/快点{A}啊/记得{A}哦/...)。
//         动作核心词、金额档位、群规前缀始终原样, 不参与随机。主干(检测/专属/开页/封面三分支/反射/分档/排除/上限/重试/onClickSendBtn/抖动/限频/finish/ANR上界)全不动。
// v1.0.6: 新增红包提醒排除名单(按群) + 反检测硬化。主干(检测/专属过滤/开页/封面三分支/反射/结构化提取/重试放弃/onClickSendBtn配置命令/秒级延迟/ANR上界)不动。
//         A. 排除名单(SPEC §12): 按群 key rp_exclude_<groupId>(CSV wxid)。排除名单里的人永不被红包提醒@, 也不计入@上限人数。
//            · @增删走 onHandleMsg 路径(onClickSendBtn 只收纯文本, 拿不到 @ 目标 wxid): sender==getLoginWxid() 且 atList 非空时,
//              文本含"红包排除"(非取消)→加入名单+toast; 含"取消红包排除"/"红包取消排除"→移除+toast。命中即 return, 不当红包处理, 不发群回执(只 toast)。
//            · Dialog(红包统计设置)加"排除名单"块, 列本群成员(lookupName 昵称)+每人"移除"按钮。
//            · hbTierAndSend 分档前剔除本群排除名单 wxid(不@/不计入达标总人数→也影响 >上限 判定)。
//         B. 反检测硬化(SPEC §13): ① hbJitter(base,span)=base+Math.random()*span 抖动: 首次/重试±25%, 点击 1500~2800ms, 反射 3000~4500ms。
//            ② DEF_AT_LIMIT 20→10。③ 同群@限频 rp_msg_min_gap_sec(默认30s): 内存 Map 记每群上次发提醒时间, <间隔则跳过本次。
//            ④ 删 detect-fields 调试解析(5字段重复 parse), 专属判定保留(只解析 exclusive_recv_username 一个)。
//            ⑤ finish 精确: finish 前先确认 getTopActivity() 类名含 luckymoney 才 finish, 否则不 finish(避免误关用户正在看的页面)。
//
// v1.0.5: 普通红包封面阶段即跳过, 不再多开一次详情页。封面 receive-decide 找到【可点】detail 入口后, 按入口文字区分:
//         · 含"手气"(看看大家的手气) → 拼手气领完 → 照旧 performClick 进详情(反射→分档→@)。
//         · 不含"手气"(查看领取详情) → 普通红包领完 → 不点击、finish 封面、清状态、跳过, log `skip normal (cover=...)`, 不开详情/不发消息。
//         · 没找到任何可点 detail 入口 → 维持原"没领完"逻辑(关页 + 重试两次后放弃), 不误判为普通。
//         反射后"金额全等→跳过"(v1.0.4②) 保留作兜底安全网。其余已验证逻辑(检测/专属过滤/开页/反射/分档/@/>20群规/重试放弃/onClickSendBtn/延迟)不动。ANR 铁律不变。
//
// v1.0.4: 仅处理拼手气红包。新增三处过滤(其余已验证逻辑不动):
//         ① 专属红包(wcpayinfo.exclusive_recv_username 非空)→ onHandleMsg 检测即跳过(不开页/不排程), log `skip exclusive`。
//         ② 普通红包(均分)→ 反射读到领取者列表后、分档发消息前: 领取者≥2人 且 所有金额(分)全等 → 判普通, 清状态/关页/不发任何消息, log `skip normal(equal amounts)`; 金额有差异(拼手气)正常走分档+发消息; 1人无法判定按正常处理。
//         ③ 检测时一行脱敏调试 log(detect-fields): 记 exclusive_recv 是否非空 + totalnum/scenetext/type/hbtype/sceneid 是否存在及短值(不记 wxid/sign 原文), 便于以后找"开页前识别普通红包"的字段。
//         全程 try/catch; 普通过滤仅在已成功反射到列表后判断, 反射不到列表维持原逻辑不误判。ANR 铁律不动。
//
// v1.0.3: 延迟配置单位由毫秒改为秒。新键 rp_first_sec(默认120=2分)/rp_retry1_sec(300=5分)/rp_retry2_sec(600=10分),
//         旧 rp_*_ms 毫秒键废弃不再读 (onLoad 清空避免残留)。delay() 内部 sec×1000L 换算 ms (long 防溢出)。
//         命令『红包延迟』支持秒: 单参设首次 / 三参设首次+重试1+重试2 / 四参设首次+重试1+重试2+重试3 (v1.10.0) / 空参显示当前; Dialog+状态显示均改秒。
//
// v1.0.2: 配置命令改用 onClickSendBtn 拦截 (bot 一点发送即拦下, return true 阻止消息发到群里),
//         就地执行 (弹 Dialog / 改配置) + toast 本地提示, 不再 sendText 回执到群 (不污染群)。
//         当前会话群用 getTargetTalker() 获取 (仿 GroupAdmin)。
//         onHandleMsg 移除全部配置命令与发群回执, 只保留红包检测/排程。
//         修 `红包统计设置` Dialog: 经 onClickSendBtn (UI 线程上下文) 调 getTopActivity()+AlertDialog → 能正常弹出。
//
// v1.0.1: 达标总人数 (金额>rp_t1 的三档之和) > rp_at_limit(默认20) 时, 不再单独 @,
//         改发一条无 @ 通用群规消息; 新增配置 rp_at_limit (Dialog + 命令『红包at上限 N』+ 状态显示)。
//
// 群红包领完后, 自动开页读明细 → 按金额阈值分档 → 群内发一条 @ 消息圈出"手气好"的人。
//
// 完整链路 (SPEC §4):
//   onHandleMsg(廉价, v1.6.3): 群聊 + (type==436207665 或含 <nativeurl>) → 本群启用? 否则 return
//     → 廉价 key=k:talker:sender:msgid + 携原始 content 入队 (attempt=0, 不在热路径 parse nativeurl/exclusive/title)
//   hbProcess(后台 worker, v1.6.3): 出队后解析原始 content — 专属早退(skip exclusive)/nativeurl/rpExtractTitle(回填 job) → 启动器拉封面页 NewReceiveUI。
//   NewReceiveUI.onResume: delay 后 UI 有界查找 detail 入口("看看大家的手气"/"查看领取详情" 且可点)
//     ├─ 可点且含"手气"(拼手气领完) → performClick → 微信流转 NewDetailUI
//     ├─ 可点但不含"手气"="查看领取详情"(普通领完) → 不点击 → finish 封面 → 清状态跳过(不开详情/不发消息)
//     └─ 无可点入口(=没领完) → 绝不点领取/不抢 → finish() 关封面 → 按 attempt 重试(0→retry1, 1→retry2, 2→放弃)
//   NewDetailUI.onResume: delay 后【后台反射】读领取者 List → 结构化提取(d/f/n 首选 + 抗版本兜底)
//     → finish() 关详情页 → 分档 → 发一条 [AtWx=...] 消息到 talker → 清状态(去重, 每红包只成功处理一次)
//
// !!! ANR 铁律 (已 ANR 两次, 死守):
//   1. 开页/启动器 = startActivity/callStaticMethod, 不遍历。
//   2. 查找点击 = UI 线程一次性有界 BFS(节点 ≤800 / 深度 ≤50) + 单次 performClick + 零文件IO。
//   3. 反射全在后台线程 + 强上界(MAX_VISIT=4000 / MAX_DEPTH=6) + identityHashCode 去重防环。
//   4. 关页 finish() 在 UI 线程调, try/catch。
//   5. 每个 Activity 实例 identityHashCode 去重; 每红包 nativeurl 去重。
//   6. onHandleMsg 只廉价判定 + 判启用 + 存状态 + 排一个 delay。
//   7. 全程 try/catch(Throwable), 任何异常不抛回微信。
//
// 隐私: 昵称/金额/wxid 是真实数据(用于发消息), 绝不写进明文日志文件; 调试 log 仍脱敏。

import android.app.Activity;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodHook.MethodHookParam;
import de.robv.android.xposed.XposedHelpers;

// ==================== 配置 key 与默认值 (SPEC §3) ====================
String K_ENABLED = "rp_enabled_groups";
String K_T1 = "rp_t1"; String K_T2 = "rp_t2"; String K_T3 = "rp_t3";
String K_TXT1 = "rp_txt1"; String K_TXT2 = "rp_txt2"; String K_TXT3 = "rp_txt3";
// v1.6.0: 每群独立 1-4 档。键 rp_tiers_<groupId>, 序列化 "阈值|动作;阈值|动作;..."(升序)。
//         未配置该键的群回退全局 cfgT1/2/3 + cfgTxt1/2/3 (三档), 保证旧行为不变。
String K_TIERS_PREFIX = "rp_tiers_";
int RP_MAX_TIERS = 10;
// v1.7.0 §22: 包类型 普通包/定制包。per-group 三个键 (默认全关 → 与今天逐字节一致)。
//   rp_custom_on_<gid>   定制开关 (bool, 仅 Dialog 可改); 关 → 所有红包按普通包。
//   rp_custom_kw_<gid>   定制关键字 (String, 包含匹配 title, v1.7.1); 空串视为未启用 → 回退普通。
//   rp_tiers_custom_<gid> 定制档表 (格式同 rp_tiers_<gid>); 未配置 → 回退普通档表 getTiers。
String K_CUSTOM_ON_PREFIX = "rp_custom_on_";
String K_CUSTOM_KW_PREFIX = "rp_custom_kw_";
String K_TIERS_CUSTOM_PREFIX = "rp_tiers_custom_";
String K_DELAY = "rp_first_sec"; String K_RETRY1 = "rp_retry1_sec"; String K_RETRY2 = "rp_retry2_sec"; String K_RETRY3 = "rp_retry3_sec";
String K_AT_LIMIT = "rp_at_limit";
// v1.11.0 §25: 红包伸手党治理 (per-group)。抢到红包者在抢到时刻前 N 分未在群发言 → 发群管警告命令。
String K_FREELOADER_ON_PREFIX = "rp_freeloader_on_";    // 开关: rp_freeloader_on_<gid> = "1"/"" (默认关)
String K_FREELOADER_WIN_PREFIX = "rp_freeloader_win_";  // 窗口(分钟): rp_freeloader_win_<gid> (1-1440, 默认 30)
int DEF_FREELOADER_WIN = 30;
int FREELOADER_MAX_WARN = 10;                          // v1.11.1 §25: 单次红包警告人数上限(超出留痕不发, 防刷屏)
long FREELOADER_FLUSH_GRACE_MS = 60000L;               // v1.11.1 §25: last_speak flush 容差(60s, 防抢前刚发言未落盘误判)
String K_MSG_GAP = "rp_msg_min_gap_sec";          // 同群两条红包提醒最小间隔(秒, v1.0.6 §13.3)
String K_TWO_MSG_GAP = "rp_two_msg_gap_sec";      // v1.1.4 §16: 同一次提醒内第一条(引用群规)与第二条(@)之间的延迟(秒)
String K_EXCLUDE_PREFIX = "rp_exclude_";          // 按群排除名单前缀: rp_exclude_<groupId> (CSV wxid, v1.0.6 §12)
String K_EXPORT_TARGET = "rp_export_target";      // v1.3.0 §18: 导出今日红包统计私聊目标 wxid (好友 wxid 或群 xxx@chatroom, v1.9.0 §24)
String K_EXPORT_TARGET_NAME = "rp_export_target_name";  // v1.9.0 §24.1: 转发目标显示名 (供 Dialog/状态展示; 类型由 id 是否含 @chatroom 区分, 不另存)
// v1.4.0 §20: 每日定时统计 (7点-7点窗口, worker-tick 触发, 私聊导出目标)。
String K_DAILY_GROUPS = "rp_daily_groups";        // 开了每日定时的群 (CSV groupId), 与红包统计开关独立
String K_DAILY_MIGRATED = "rp_daily_migrated";    // 一次性迁移 flag: 首次加载把 daily_groups 初始化=当前 enabled_groups
String K_DAILY_HOUR = "rp_daily_hour";            // 每日发送的小时 (本地时间, 默认 7)
String K_DAILY_LAST_SENT = "rp_daily_last_sent";  // 上次每日发送的日期串 (yyyy-MM-dd), 抗重启不重发不漏发
int DEF_DAILY_HOUR = 7;
// v1.3.0 §18: 导出私聊默认目标。注意: 此 wxid 公开仓库可见, 属可配置默认值(可经命令/Dialog 覆盖); 真实采集数据落在本地 DB, 不入仓。
String DEF_EXPORT_TARGET = "wxid_REDACTED";

int DEF_T1 = 5; int DEF_T2 = 10; int DEF_T3 = 20;
// v1.0.7 §14: 动作文案 = 用户自定义录入的内容, 原样使用, 绝不随机/改动。每档单值。
String DEF_TXT1 = "爆照";
String DEF_TXT2 = "宣群";
String DEF_TXT3 = "发视频";
// 通用群规消息前缀(>at上限时发, 无@), 单值原样使用。
String K_RULE_PREFIX = "rp_rule_prefix";
String DEF_RULE_PREFIX = "领红包请遵守群规执行";
// v1.7.1 §22.6: 定制包第一条群规文本前缀 (per-group, rp_custom_rule_prefix_<gid>)。可经命令/Dialog 改, 空回退默认。
String K_CUSTOM_RULE_PREFIX_PREFIX = "rp_custom_rule_prefix_";
String DEF_CUSTOM_RULE_PREFIX = "定制包请按要求执行";
// v1.0.7 §14: 只在"金额连接词"和"动作语气包裹"上随机, 去机器指纹。动作核心词/前缀/档位均不动(用户原样录入)。
// 内置默认连接词变体 (带 {X} 占位, X=该档阈值)。
String[] DEF_CONNECTORS = new String[] { "超过{X}元，", "过{X}元，", "过{X}，", "{X}元以上，", "领超{X}元，" };
// 内置默认动作语气包裹变体 (带 {A} 占位, A=该档动作核心原文)。包裹只改语气词, {A} 始终原样。
String[] DEF_ACTION_PHRASES = new String[] { "{A}", "快点{A}啊", "记得{A}哦", "需要{A}", "该{A}了", "麻烦{A}一下" };
long DEF_DELAY = 120L;    // 首次延迟 120 秒 = 2 分钟 (正式默认; spike 测试可经命令/Dialog 临时设短如 60)
long DEF_RETRY1 = 300L;   // 重试1 300 秒 = 5 分钟
long DEF_RETRY2 = 600L;   // 重试2 600 秒 = 10 分钟
long DEF_RETRY3 = 3600L;  // 重试3 3600 秒 = 1 小时 (v1.10.0 §3/§4 新增末档; 红包晚至 1 小时内被领完仍能回看记录)
int DEF_AT_LIMIT = 10;       // 达标总人数 > 此值则改发无 @ 通用群规消息 (SPEC §6.1; v1.0.6 默认 20→10)
long DEF_MSG_GAP = 30L;      // 同群两条红包提醒最小间隔(秒, v1.0.6 §13.3)
long DEF_TWO_MSG_GAP = 3L;   // v1.1.4 §16: 第一条(引用群规)发出后, 延迟此秒数再发第二条(@), 保证到达顺序

// ==================== 对象图遍历强上界 (ANR 防护) ====================
int MAX_VISIT = 4000;
int MAX_DEPTH = 6;

// 详情页落地后, 后台反射等 cgi 数据到位 (v1.0.6: 抖动 3000~4500ms, 去零方差; 每次调用 hbJitter 现算)
int REFLECT_DELAY_BASE_MS = 3000; int REFLECT_DELAY_SPAN_MS = 1500;
// 封面页落地后, UI 线程有界查找 detail 入口 (v1.0.6: 抖动 1500~2800ms; 每次调用 hbJitter 现算)
int RECEIVE_CLICK_DELAY_BASE_MS = 1500; int RECEIVE_CLICK_DELAY_SPAN_MS = 1300;

// 随机抖动 helper (v1.0.6 §13.1): base + Math.random()*span。去零方差机器指纹。
// BeanShell 设备上 Math.random() 可用; 任何异常退回 base (绝不返回负/0 异常值)。
long hbJitter(long baseMs, long spanMs) {
    try {
        if (spanMs <= 0L) return baseMs;
        return baseMs + (long) (Math.random() * (double) spanMs);
    } catch (Throwable t) { return baseMs; }
}

// View 树查找强上界
int CLICK_MAX_NODES = 800;
int CLICK_MAX_DEPTH = 50;

// 防重复注册守卫
boolean HOOKED = false;

// 每 Activity 实例只处理一次 (key = identityHashCode)
java.util.Set SEEN = java.util.Collections.synchronizedSet(new java.util.HashSet());

// 每红包只成功处理一次 (key = nativeurl)
java.util.Set DONE = java.util.Collections.synchronizedSet(new java.util.HashSet());

// v1.1.0: 旧"红包运行态 RP_STATE"已被 Job 队列(§15)取代; talker/sender/msgid/attempt 全部随 Job 在队列/CUR_JOB 中携带, 不再单独存 Map。

// onHandleMsg 去重 (每红包只排一次首跑)
java.util.Set OPENED = java.util.Collections.synchronizedSet(new java.util.HashSet());

// bot 自己开的封面页 Activity (key = nativeurl -> identityHashCode of NewReceiveUI), 用于点详情成功后连封面一起 finish
java.util.Map COVER_ACT = java.util.Collections.synchronizedMap(new java.util.HashMap());

// ==================== v1.1.0 §15: 队列 + 单工作者(单飞) ====================
// Job = Object[]{ key(=廉价去重key k:talker:sender:msgid, String), talker(String), sender(String), msgid(Long), dueAt(Long, ms), attempt(Integer), title(String), content(String 原始消息 XML) }
// 索引常量 (BeanShell 无 enum, 用 int 常量映射)。v1.3.0: 加 JOB_TITLE 第 7 槽 (供后台写统计 DB 用)。
// v1.6.3: 去重 key 改用廉价 k:talker:sender:msgid (不再依赖 nativeurl XML parse); 加 JOB_CONTENT 第 8 槽携原始 content,
//         worker(hbProcess) 出队后才做 nativeurl/exclusive/title 的 XML 解析 (重活移出热路径, C-PERF-03)。
//         入队时 JOB_TITLE 留空, 由 worker rpExtractTitle 后写回本 job 数组 (CUR_JOB), 供 rpRecordStats 读。
// v1.7.0 §22: 加 JOB_ISCUSTOM 第 9 槽 (Boolean), worker(hbProcess) 判定包类型后回填本 job, hbDetailExtract 取用透传给 hbTierAndSend。
//   入队时留 Boolean.FALSE; worker 拿到 title 后 rpIsCustom 算出真值并回填。绝不在热路径算 (C-PERF-01/03)。
int JOB_KEY = 0; int JOB_TALKER = 1; int JOB_SENDER = 2; int JOB_MSGID = 3; int JOB_DUEAT = 4; int JOB_ATTEMPT = 5; int JOB_TITLE = 6; int JOB_CONTENT = 7; int JOB_ISCUSTOM = 8; int JOB_DETECTMS = 9;
// 待处理 Job 队列 (生产者 onHandleMsg 入队; 单工作者出队)。所有读写持 RP_QUEUE 自身锁 (synchronized(RP_QUEUE))。
java.util.List RP_QUEUE = new java.util.ArrayList();
int RP_QUEUE_MAX = 50;           // 队列上限: 满则丢最旧一条 + log
int RP_TICK_MS = 3000;           // 单工作者自重调度间隔
long RP_WATCHDOG_MS = 30000L;    // 看门狗: 在飞超过此值强清 (防卡死)
boolean WORKER_STARTED = false;  // 防重复启动工作者
// 在飞态 (单飞: 同一时刻只一个红包流水线)。CUR_JOB = 当前处理的 Job 对象 (旧 CUR_NATIVEURL 升级)。
boolean IN_FLIGHT = false;
Object[] CUR_JOB = null;
long inflightStartMs = 0L;

// 同群红包提醒上次发送时间 (groupId -> lastSentMs), 用于 @ 限频 (v1.0.6 §13.3, 内存, 不落盘)
java.util.Map RP_LAST_MSG = java.util.Collections.synchronizedMap(new java.util.HashMap());

// v1.9.0 §24.2: 好友/群 list 缓存 (getFriendList()/getGroupList() 可能慢; 首次用时后台线程加载, 简单不主动失效)。
// 只在「转发对象」子页后台线程读写, 不碰热路径 (C-PERF-01/03)。null = 尚未加载。
java.util.List sCachedFriendList = null;
java.util.List sCachedGroupList = null;

String HB_LOG = "/storage/emulated/0/Android/media/com.tencent.mm/WAuxiliary/Plugin/RedPacketStats/rp.log";

// ==================== v1.4.0: 轻量 perf 埋点 (仿 GroupAdmin; 为明早性能分析) ====================
// 热路径(onHandleMsg)只做 nanoTime + 整型累加, 绝不每条写文件/写 log (埋点不得反噬热路径)。
// 聚合行由 rpWorkerTick 定时驱动落盘 (worker 必跑), 或窗口异常膨胀时兜底 flush。perf.log 与 rp.log 分开。
// perf.log 只写耗时/计数, 绝不写 wxid/群名/群ID/消息内容。
String PERF_LOG = "/storage/emulated/0/Android/media/com.tencent.mm/WAuxiliary/Plugin/RedPacketStats/perf.log";
int  PERF_FLUSH_EVERY = 200;   // 每累计 N 条(或 worker tick)聚合落盘一行
long PERF_N           = 0L;    // 当前窗口样本数 (onHandleMsg 调用数)
long PERF_SUM_NS      = 0L;    // 窗口 onHandleMsg 累计耗时 (ns)
long PERF_MAX_NS      = 0L;    // 窗口 onHandleMsg 最大耗时 (ns)

// ==================== v1.6.2: spike 归因埋点 (诊断用, 仿上方 PERF_* 风格) ====================
// 目的: 区分 "每 ~200 条出现 1 次 200-370ms onHandleMsg max spike" 是 (A)真红包那条在热路径做重活,
//       还是 (B)GC/框架抖动落在普通消息上。判据: spike 几乎全落普通消息→GC/框架抖动(与本插件无关);
//       spike 集中在红包消息→extract/入队在热路径做了重活, 需移后台。
// 实现: 仍只 nanoTime + 顶层整型累加, 绝不在热路径加 IO/log/反射。分类由 onHandleMsgBody 设的 PERF_LAST_RP 标记驱动。
// 分类: A 红包消息 = 命中检测(isHb)且本群启用, 走 extract title + 入队(或 skip exclusive)那条路径;
//       B 普通消息 = 未命中或未启用, 快速 return。
long PERF_RP_SUM_NS   = 0L;    // 窗口内 红包消息 累计 onHandleMsg 耗时 (ns)
long PERF_RP_MAX_NS   = 0L;    // 窗口内 红包消息 最大单条耗时 (ns)
long PERF_RP_N        = 0L;    // 窗口内 红包消息 条数
long PERF_NORM_SUM_NS = 0L;    // 窗口内 普通消息 累计 onHandleMsg 耗时 (ns)
long PERF_NORM_MAX_NS = 0L;    // 窗口内 普通消息 最大单条耗时 (ns)
long PERF_NORM_N      = 0L;    // 窗口内 普通消息 条数
long PERF_SPIKE_NS    = 50000000L; // spike 阈值 = 50ms = 50_000_000ns (>此值算一次 spike)
long PERF_SPIKE_N     = 0L;    // 窗口内 spike 总数 (>阈值的条数)
long PERF_SPIKE_RP_N  = 0L;    // 窗口内 spike 且是红包消息 的条数 (关键判据)
long PERF_SPIKE_NORM_N= 0L;    // 窗口内 spike 且是普通消息 的条数 (关键判据)
// onHandleMsgBody 把 "本条是否被当红包处理" 写进这个标记, 供 wrapper 在 finally 里按 A/B 分类计时。
// 默认 false; body 入口处由 wrapper 清, body 内确认红包后置 true。
boolean PERF_LAST_RP  = false;
// (可选 §4) rpExtractTitle 耗时单独累加。v1.6.3: extract 已移到后台 worker(hbProcess), 此计时现在 worker 线程累加,
//   不再计入 onHandleMsg 热路径 — 故热路径 rp_msg 耗时应大幅下降, rp_extract_* 仍可观测提取耗时但不再反噬热路径。
long PERF_EXTRACT_SUM_NS = 0L; // 窗口内 rpExtractTitle 累计耗时 (ns, v1.6.3 后台 worker 侧)
long PERF_EXTRACT_MAX_NS = 0L; // 窗口内 rpExtractTitle 最大单次耗时 (ns)
long PERF_EXTRACT_N      = 0L; // 窗口内 rpExtractTitle 调用次数

// v1.3.0 §17: 统计本地 SQLite (rp_record, 一红包一行)。库与 rp.log 同目录 (GroupAdmin 同级目录建库已验证可行)。
String RP_DB_PATH = "/storage/emulated/0/Android/media/com.tencent.mm/WAuxiliary/Plugin/RedPacketStats/redpacket_stats.db";
// v1.11.0 §25: GroupAdmin 库 (只读查 speak.last_speak, 与 RP 库同级目录)。
String GA_DB_PATH = "/storage/emulated/0/Android/media/com.tencent.mm/WAuxiliary/Plugin/GroupAdmin/groupadmin.db";
android.database.sqlite.SQLiteDatabase RP_DB = null;   // 连接单例 (懒打开)
Object RP_DB_LOCK = new Object();                       // 打开连接串行化 (防并发各 open 一次泄漏)

String UI_RECEIVE = "LuckyMoneyNewReceiveUI";
String UI_DETAIL  = "LuckyMoneyNewDetailUI";
String LM_PKG = "com.tencent.mm.plugin.luckymoney";
int HB_MSG_TYPE = 436207665;

// detail 入口文案 (已领完才有这种"可点"入口)
String[] DETAIL_TEXTS = {"看看大家的手气", "查看领取详情"};

void onLoad() {
    try { log("RedPacketStats v1.11.2 loaded."); } catch (Throwable t) {}
    // 旧毫秒键废弃, 清空避免残留混淆 (新键 rp_first_sec/rp_retry1_sec/rp_retry2_sec 为秒)
    try { putString("rp_delay_ms", ""); putString("rp_retry1_ms", ""); putString("rp_retry2_ms", ""); } catch (Throwable t) {}
    if (HOOKED) { try { log("RedPacketStats hook already registered, skip."); } catch (Throwable t) {} return; }
    try {
        XposedHelpers.findAndHookMethod(Activity.class, "onResume", new XC_MethodHook() {
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                try { hbOnActivityResume(param.thisObject); } catch (Throwable t) {}
            }
        });
        HOOKED = true;
        // v1.1.0 §15: 启动单工作者 tick (串行驱动红包流水线)。仿 GroupAdmin l3FlushLoop 自重调度。
        if (!WORKER_STARTED) {
            WORKER_STARTED = true;
            try {
                delay(RP_TICK_MS, new Runnable() { public void run() { rpWorkerTick(); } });
                log("RedPacketStats worker started (queue+single-flight, tick=" + RP_TICK_MS + "ms).");
                hbBgLog("[RP] " + hbNow() + " v1.6.0 worker started: queue(max=" + RP_QUEUE_MAX + ")+single-flight worker tick=" + RP_TICK_MS + "ms, watchdog=" + RP_WATCHDOG_MS + "ms, daily-check+perf-flush per tick.");
            } catch (Throwable t) { try { log("RedPacketStats worker start FAIL: " + t); } catch (Throwable t2) {} }
        }
        // v1.4.0 §20: 每日定时迁移 (一次性) — 首次把 rp_daily_groups 初始化 = 当前 rp_enabled_groups。
        try { rpDailyMigrateOnce(); } catch (Throwable t) {}
        try { log("RedPacketStats Activity.onResume hooked + onHandleMsg redpacket-stats enabled."); } catch (Throwable t) {}
        hbBgLog("[RP] " + hbNow() + " v1.11.2 loaded (伸手党判定基准 每人领取时刻→红包检测时刻 全员统一 §25; 原因: 当前微信版本领取者反射模型无 per-person 领取时刻字段; Job 增 JOB_DETECTMS 第10槽承载检测时刻(入队 O(1) 只塞已算好 long, 重试透传); 撤 hbExtractTriple g/grabMs 提取(回 4 元素)+receivers 回 3 元素; 删临时 GPROBE; 后台线程/上限/容差/安全网不变).\\nv1.11.1 loaded (Santa 修复: 伸手党判定整段移入独立 fire-and-forget 后台线程, 不占 IN_FLIGHT 单飞锁/不撞看门狗30s, 闭包 fTalker+fSnap 快照均 final; 警告人数上限 FREELOADER_MAX_WARN=10 超出 capped 留痕不静默截断; last_speak flush 容差 FREELOADER_FLUSH_GRACE_MS=60s 判定式 ls<grabMs-winMs-容差 防刚发言未落盘误判).\\nv1.11.0 loaded (红包伸手党治理 §25: 抢红包者在抢到时刻前 N 分(默认30 rp_freeloader_win_<gid>)未在群发言 → 后台反射线程发群管警告命令 [AtWx=wxid] 警告 {N}分钟内未发言 交 GroupAdmin v1.19.0 执行; hbExtractTriple 增提领取时刻 g(归一为毫秒, 第5元素)+receivers 加 grabMs 第4元素(下游只读[0..2]不越界); 判定在均分跳过之后(仅拼手气)+rpRecordStats 之后; rpQueryLastSpeak 只读查 groupadmin.db speak.last_speak(OPEN_READONLY 用完即关); 安全网 开关关/wxid或grabMs取不到/查询<0(无行或DB不可用) → 跳过; 命令 伸手党 开/关/伸手党窗口 N/伸手党; Dialog 子页🖐伸手党治理+状态增显; 全程后台线程 try/catch, 零 onHandleMsg 热路径, 不直接踢人).\nv1.10.0 loaded (红包重试阶梯加末档「重试3」§3/§4/§15.5: rp_retry3_sec 默认 3600 秒=1 小时 + cfgRetry3(); afterCoverNoDetail 放弃点 attempt>=2→>=3, retrySec 三段阶梯(0→retry1/1→retry2/2→retry3), hbJitter 抖动公式不动; 延迟子页加 d3 重试3 EditText+onSave 写 K_RETRY3; 命令『红包延迟』加 4 参分支(保留空/1/3 参); 状态+当前延迟 toast 增列重试3; worker 主干/检测/入队/统计/定时/onHandleMsg 热路径零改, cfgRetry3 仅 afterCoverNoDetail 读).\nv1.9.2 loaded (每日定时开关+推送时间(全局)迁入「📮 转发对象」子页 §24/§20: showPageForward 加 📅本群每日定时开关(即时 enable/disableDailyGroup→刷新本页)+⏰每日推送时间 EditText(子页💾保存校验 0-23 写 K_DAILY_HOUR 全局); showPageBasic 移走每日定时 toggle 只留本群启用; 定时开关函数/cfgDailyHour 夹取/存储键不动, 只动 Dialog 展示层, worker/onHandleMsg 热路径零改).\nv1.9.1 loaded (转发对象 全局→按群 §24: rp_export_target_<gid>/name_<gid> + cfgExportTargetFor/NameFor 回退全局默认(向后兼容: 未设群=旧行为); rpApplyForwardTarget(groupId,...) 写按群键 + rpPickTarget/showPageForward 作用当前群; rpBuildGroupedMsgs 返回 List<Object[]{grp,msg}>, rpExportToday/rpDailySend 发送循环按 cfgExportTargetFor(grp) 路由到各自目标, 无达标心跳仍发全局默认; sendForwardNotice(groupId,target) 通知只讲当前群; 状态/子页按群显示; worker/检测/写库/onHandleMsg 热路径零改).\nv1.9.0 loaded (转发对象可选好友/群 §24: getFriendList/getGroupList 选择器 + rp_export_target_name + 设置后通知 sendForwardNotice; 后台线程加载 list+Handler 回主线程弹单选 AlertDialog 防 ANR(§7), sCachedFriendList/sCachedGroupList 缓存; 只动 Dialog(showPageForward 入口)+发送层, 每日定时/手动导出仍用 cfgExportTarget() 逻辑不动, onHandleMsg 热路径零改).\nv1.8.1 loaded (修主菜单关闭失效=对话框堆叠(menuHolder 捕获, 入口按钮开子页前 dismiss 主菜单)+排除名单显示名字(rpSenderName, 昵称（…尾4）); 仍只动 Dialog 层).\nv1.8.0 loaded (设置页拆分 §23: 主菜单 showConfigDialog(状态摘要+入口按钮) + 二级子页 showPageBasic/NormalTiers/Custom/Delay/Exclude, 每子页只写本页 key, 保存/返回回主菜单, 导出为主菜单直接动作; 只动 Dialog 展示层, worker/onHandleMsg 热路径/文字命令/存储键/校验/即时生效逻辑全不动, 只构建 Dialog 无 UI 遍历 §7, 每页整段 try/catch).\nv1.7.2 loaded (定制包第二条@消息【查包】前缀加「定制包」→【查包】定制包, §22.6, isCustom?checkPrefix; 其余不动).\nv1.7.1 loaded (定制判定 前缀→包含 §22.2: rpIsCustom title.contains(kw)(原 startsWith), 修「【定制】…」被【】包裹漏判; 定制包第一条群规文本 §22.6: rp_custom_rule_prefix_<gid>(默认「定制包请按要求执行」)+cfgCustomRulePrefix, hbTierAndSend 第一条前缀 isCustom?定制:普通, 各档仍「，过{阈值}元{动作}」(定制档表); 第二条@/【查包】机制一行不动; 命令 定制群规前缀 <文字>+Dialog 定制区块「定制群规前缀」+状态增显; 限频/排除/分档/两条消息骨架/抖动/ANR/onHandleMsg 热路径全不动).\nv1.7.0 loaded (包类型 普通/定制 SPEC §22: rp_custom_on/kw/tiers_custom per-group, 默认全关=逐字节兼容; 类型判定全在 worker(hbProcess)rpIsCustom(title包含kw), 回填 Job 第9槽 JOB_ISCUSTOM→hbDetailExtract→hbTierAndSend; onHandleMsg 热路径零改动(只塞常量 Boolean.FALSE); 选档 getTiersByType(定制读 rp_tiers_custom 未配回退普通), 限频/排除/分档/两条消息/ANR 全不动; 命令 定制阈值/定制文案K/定制增加档/定制减少档/定制关键字; Dialog 定制区块(开关仅此处改)+状态增显).\nv1.6.4 loaded (enabled 群内存缓存: isGroupEnabled 走 RP_ENABLED_CACHE(HashSet,TTL=60s,enable/disable 后立即刷新), 消除红包消息热路径最后一处 per-红包 FUSE 读 ~26ms; 缓存出错回退直接读 getString 保正确; 只动读路径+缓存维护, 检测/入队/worker/分档/发送/统计/定时/导出全不动, spike 埋点保留).\nv1.6.3 loaded (C-PERF-03: 红包检测重活(extract/专属判定 XML解析)移出热路径到后台 worker — 热路径只做廉价判定(type/含<nativeurl>)+入队原始content; 去重 key 改廉价 k:talker:sender:msgid(不依赖 nativeurl parse); worker(hbProcess)出队后做 专属早退/nativeurl/rpExtractTitle, title 回填 job 供写库. 消除红包消息 ~267ms 热路径 spike. 行为不变只换线程/时机).\nv1.6.2 loaded (spike 归因埋点(诊断用): onHandleMsg 按红包(A)/普通(B)分类计时 PERF_RP_*/PERF_NORM_*, spike(>50ms)分 spike_rp/spike_norm; perf.log 追加 rp_msg_*/norm_*/spike_*/rp_extract_*; 仅 nanoTime+整型, 不碰业务逻辑, try/catch 不影响消息).\nv1.6.1 loaded (档位上限 4→10 RP_MAX_TIERS; 修红包文案K 两位数解析 bug(前导整数 1-RP_MAX_TIERS); 全量保存错误检测加强(阈值/增加档/Dialog 逐行校验+行号错误, 全无效不保存不破坏原表); onHandleMsg 热路径仍零 getTiers/setTiers).\nv1.6.0a loaded (修订: 未设档动作不泄漏进群消息 — 群规第一条/@条渲染时把占位\"未设\"/空动作当作暂无动作, 只发阈值不发动作词; 存储/解析/getTiers/setTiers/分档/热路径全不动; 管理员可见处(红包统计状态/配置Dialog)仍保留\"未设\"提示).\nv1.6.0 loaded (per-group 1-4 tiers: rp_tiers_<gid>, 未配置群回退全局三档; 命令 红包阈值/红包文案K/红包增加档/红包减少档 全作用当前群; hbTierAndSend+rpRecordStats 改 N 档循环; getTiers 只在 bg/UI 调不碰热路径).\nv1.5.0 (grouped export: per-group one msg, multi-line format; export/daily-send 组装/分条/发送循环重写, SQL ORDER BY grp,ts; 热路径/写库/录入范围/WHERE 不变).\nv1.4.0 (sender group-nickname fix + daily scheduled stats §20 + perf instrumentation). per-group enable (default OFF), config via onClickSendBtn (NOT to group), dialog, "
            + "[v1.6.3] detect(廉价 type/含<nativeurl>)→enqueue raw content(dedup k:talker:sender:msgid,dueAt=now+delay jittered)→[worker tick single-flight]→hbProcess(CUR_JOB,后台):parse[skip exclusive→nativeurl→extract title 回填 job]→open cover→[手气→detail|查看领取详情→skip normal cover|no-entry→re-enqueue retry(2x,jittered)]→reflect(jittered,bg thread)→[skip normal/equal]→[stats-db INSERT OR REPLACE (qualified>=1, bg thread only, try/catch degrade)]→finish→exclude-filter→rate-limit→tier(random variant)→send(quote-rule + at). "
            + "v1.3.0: 在 v1.2.0(队列+两条消息定版)之上加回统计 DB(§17, redpacket_stats.db, rp_record 一红包一行, 仅后台反射线程写, 可降级) + 导出今日红包统计→私聊(§18, Dialog 按钮, sendText 给 rp_export_target) + 标题提取(§17.3) + 嗅探调试日志(title usedField/len, stats-db opened, export sent N). DB 存真实昵称/金额/wxid(本地存储), hbBgLog 仍只记计数(脱敏). 两条消息逻辑一行未动. "
            + "v1.1.4: 发两条修正(§16) — 第一条(引用群规)=固定简洁(cfgRulePrefix+\"过{t}元{动作}\"三档, 无随机无语气); 第二条(@)=随机(pickConnector+pickActionPhrase+[AtWx=]), 隔 rp_two_msg_gap_sec 秒(默认3,可配)延迟发以保序(delay+final捕获,fire-and-forget,不进在飞锁). >at上限只发第一条无第二条无延迟. 只改 hbTierAndSend. "
            + "v1.1.2: 发提醒拆两条 — qualified>=1: rpSendQuote(talker,CUR_JOB.msgid,群规文本)引用原红包(无@,失败回退sendText); qualified 1~at_limit additionally rpSendAt(talker,@文本)真@(sendText). qualified==0不发; >at_limit只发引用群规条. 限频对整次提醒判一次. "
            + "v1.1.0: queue+single-flight worker(§15, CUR_JOB归属/看门狗/重试重新入队/退出点清在飞). v1.0.7: per-group exclude, jitter, at_limit=10, rate-limit, finish精确. 措辞随机=pickConnector+pickActionPhrase. ANR rules enforced.");
    } catch (Throwable t) {
        try { log("RedPacketStats hook register FAIL: " + t); } catch (Throwable t2) {}
    }
}

void onUnload() {
    // v1.3.0 §17: 关闭统计 DB 连接 (单例)。try/catch, 失败无碍。
    try { if (RP_DB != null && RP_DB.isOpen()) RP_DB.close(); } catch (Throwable t) {}
    RP_DB = null;
}

// ==================== 配置 getter ====================
int cfgT1() { return cfgInt(K_T1, DEF_T1); }
int cfgT2() { return cfgInt(K_T2, DEF_T2); }
int cfgT3() { return cfgInt(K_T3, DEF_T3); }
String cfgTxt1() { return cfgStr(K_TXT1, DEF_TXT1); }
String cfgTxt2() { return cfgStr(K_TXT2, DEF_TXT2); }
String cfgTxt3() { return cfgStr(K_TXT3, DEF_TXT3); }

// ==================== v1.6.0: 每群独立档位 (1-4 档) ====================
// 模型: 每群一个键 rp_tiers_<groupId>, 值 = "阈值|动作;阈值|动作;..." (升序)。
// 元素类型 Object[]{Integer 阈值(元), String 动作}。BeanShell 无泛型, 用 ArrayList + 强转。
// 整段 try/catch 可降级: 解析失败/键不存在 → 回退全局三档 (cfgT1/2/3 + cfgTxt1/2/3)。
String tiersKey(String groupId) { return K_TIERS_PREFIX + normGroupId(groupId); }
// v1.7.0 §22: 定制档表键 rp_tiers_custom_<gid>。
String tiersCustomKey(String groupId) { return K_TIERS_CUSTOM_PREFIX + normGroupId(groupId); }

// 动作里的 | ; 会污染序列化分隔符 → 与 qualifiers 同款替换 (| → /, ; → ,)。
String sanitizeTierAction(String a) {
    if (a == null) return "";
    return a.replace('|', '/').replace(';', ',').trim();
}

// 取某群档表 (升序, 长度 1-4)。键存在且解析出 >=1 档 → 用它; 否则回退全局三档。
// 返回 List<Object[]{Integer,String}>。绝不返回空/null (兜底全局三档)。
List getTiers(String groupId) {
    List out = parseTiersFromKey(tiersKey(groupId));
    if (out != null && out.size() >= 1) { sortTiers(out); return out; }
    // 回退: 全局三档 (旧行为)。
    out = new java.util.ArrayList();
    out.add(new Object[]{ Integer.valueOf(cfgT1()), cfgTxt1() });
    out.add(new Object[]{ Integer.valueOf(cfgT2()), cfgTxt2() });
    out.add(new Object[]{ Integer.valueOf(cfgT3()), cfgTxt3() });
    sortTiers(out);
    return out;
}

// v1.7.0 §22: 解析某存储键的档表 (升序去重, 长度 1-RP_MAX_TIERS)。
//   键不存在/为空/全无效 → 返回空 List (由调用方决定回退源: 普通回退全局三档, 定制回退普通档表)。
//   解析逻辑与 v1.6.0 原 getTiers 内联段完全一致, 仅抽成 helper 供普通/定制共用。整段 try/catch 可降级。
List parseTiersFromKey(String key) {
    List out = new java.util.ArrayList();
    try {
        String raw = getString(key, "");
        if (raw != null && !raw.trim().isEmpty()) {
            String[] segs = raw.split(";");
            for (int i = 0; i < segs.length; i++) {
                String seg = segs[i].trim();
                if (seg.isEmpty()) continue;
                int bar = seg.indexOf('|');
                if (bar < 0) continue;
                String tStr = seg.substring(0, bar).trim();
                String act = seg.substring(bar + 1);   // 动作可能含空格, 不 trim 内部, 仅整体
                act = (act == null) ? "" : act.trim();
                int thr;
                try { thr = Integer.parseInt(tStr); } catch (Throwable t) { continue; }
                if (thr <= 0) continue;
                if (act.isEmpty()) continue;
                // 阈值去重
                boolean dup = false;
                for (int j = 0; j < out.size(); j++) { if (((Integer) ((Object[]) out.get(j))[0]).intValue() == thr) { dup = true; break; } }
                if (dup) continue;
                out.add(new Object[]{ Integer.valueOf(thr), act });
                if (out.size() >= RP_MAX_TIERS) break;
            }
        }
    } catch (Throwable t) { out = new java.util.ArrayList(); }
    return out;
}

// v1.7.0 §22.3: 按包类型取档表。isCustom=true → 读 rp_tiers_custom_<gid> (未配/全无效 → 回退普通档表 getTiers);
//   isCustom=false → getTiers(普通, 未配回退全局三档)。绝不返回空/null。仅 worker/统计/UI 线程调, 不碰热路径。
List getTiersByType(String groupId, boolean isCustom) {
    try {
        if (isCustom) {
            List out = parseTiersFromKey(tiersCustomKey(groupId));
            if (out != null && out.size() >= 1) { sortTiers(out); return out; }
        }
    } catch (Throwable t) {}
    return getTiers(groupId);   // 普通 / 定制未配 → 回退普通档表
}

// v1.7.0 §22.1: 定制开关读写 (per-group, 仅 Dialog 改)。
String customOnKey(String groupId) { return K_CUSTOM_ON_PREFIX + normGroupId(groupId); }
boolean isCustomOn(String groupId) {
    try {
        String g = normGroupId(groupId);
        if (g.isEmpty()) return false;
        String s = getString(customOnKey(g), "");
        return s != null && s.trim().equals("1");
    } catch (Throwable t) { return false; }
}
void setCustomOn(String groupId, boolean on) {
    try {
        String g = normGroupId(groupId);
        if (g.isEmpty()) return;
        putString(customOnKey(g), on ? "1" : "");
    } catch (Throwable t) {}
}

// v1.7.0 §22.1: 定制关键字读写 (per-group; 包含匹配 title 用, v1.7.1; 空串=未启用)。
String customKwKey(String groupId) { return K_CUSTOM_KW_PREFIX + normGroupId(groupId); }
String getCustomKw(String groupId) {
    try {
        String g = normGroupId(groupId);
        if (g.isEmpty()) return "";
        String s = getString(customKwKey(g), "");
        return (s == null) ? "" : s;
    } catch (Throwable t) { return ""; }
}
void setCustomKw(String groupId, String kw) {
    try {
        String g = normGroupId(groupId);
        if (g.isEmpty()) return;
        putString(customKwKey(g), (kw == null) ? "" : kw.trim());
    } catch (Throwable t) {}
}

// v1.7.0 §22.2 (v1.7.1 修正): 包类型判定 (worker 内, 零热路径)。
//   该群定制开关关 → 普通(false); 否则取 kw, kw 非空且 title 包含 kw → 定制(true); 否则普通(false)。
//   v1.7.1: 由前缀匹配(title.startsWith)改为包含匹配(title.contains) — 真实定制包祝福语形如「【定制】…」,
//           关键字「定制」被【】包裹不在串首, 前缀匹配会漏判, 用包含匹配命中 (SPEC §22.2)。
//   整段 try/catch, 任何异常退回普通(false), 与定制全关行为一致 (安全降级)。
boolean rpIsCustom(String groupId, String title) {
    try {
        if (!isCustomOn(groupId)) return false;
        String kw = getCustomKw(groupId);
        if (kw == null) return false;
        kw = kw.trim();
        if (kw.isEmpty()) return false;
        String t = (title == null) ? "" : title;
        return t.contains(kw);
    } catch (Throwable t) { return false; }
}

// 按阈值升序排序 (简单插入排序; 最多 RP_MAX_TIERS 元素)。
void sortTiers(List tiers) {
    try {
        for (int i = 1; i < tiers.size(); i++) {
            Object[] cur = (Object[]) tiers.get(i);
            int cv = ((Integer) cur[0]).intValue();
            int j = i - 1;
            while (j >= 0 && ((Integer) ((Object[]) tiers.get(j))[0]).intValue() > cv) {
                tiers.set(j + 1, tiers.get(j));
                j--;
            }
            tiers.set(j + 1, cur);
        }
    } catch (Throwable t) {}
}

// 落盘某群档表 (升序去重序列化)。空列表 → 删键 (回退全局)。
void setTiers(String groupId, List tiers) { setTiersToKey(tiersKey(groupId), tiers); }

// v1.7.0 §22: 落盘到指定键 (普通 rp_tiers_<gid> / 定制 rp_tiers_custom_<gid> 共用)。逻辑同 v1.6.0 原 setTiers。
void setTiersToKey(String key, List tiers) {
    try {
        if (tiers == null || tiers.isEmpty()) { putString(key, ""); return; }
        sortTiers(tiers);
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (int i = 0; i < tiers.size(); i++) {
            Object[] e = (Object[]) tiers.get(i);
            int thr = ((Integer) e[0]).intValue();
            String act = sanitizeTierAction((String) e[1]);
            if (thr <= 0 || act.isEmpty()) continue;
            if (n > 0) sb.append(";");
            sb.append(thr).append("|").append(act);
            n++;
            if (n >= RP_MAX_TIERS) break;
        }
        putString(key, sb.toString());
    } catch (Throwable t) {}
}

// 档表里某档阈值 (cent 单位金额比较用)。
long tierCent(Object[] tier) { return (long) ((Integer) tier[0]).intValue() * 100L; }

// 把金额(分)归入最高满足档, 返回 1-based 档次 (0 = 未达最低档)。
// tiers 已升序; 从最高档往低判: cent > 该档阈值×100 即归该档。
int tierOf(List tiers, long cent) {
    for (int i = tiers.size() - 1; i >= 0; i--) {
        if (cent > tierCent((Object[]) tiers.get(i))) return i + 1;
    }
    return 0;
}

// 档表 → 人读字符串 "档1>5元 爆照 | 档2>10元 宣群" (回执/状态用)。
String rpTiersSummary(List tiers) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < tiers.size(); i++) {
        Object[] e = (Object[]) tiers.get(i);
        if (i > 0) sb.append(" | ");
        sb.append("档").append(i + 1).append(">").append(((Integer) e[0]).intValue()).append("元 ").append((String) e[1]);
    }
    return sb.toString();
}

// 红包阈值 a [b ...]: 设当前群 1-RP_MAX_TIERS 个递增阈值。保留原各档动作 (按档次对位), 新增档动作留空提示。
String rpSetThresholdsCmd(String groupId, String rest) { return rpSetThresholdsCmd(groupId, rest, false); }
// v1.7.0 §22.4: isCustom=true → 操作 rp_tiers_custom_<gid> (读回退普通档表, 写定制键); 文案前缀按类型切换。逻辑与普通逐行一致。
String rpSetThresholdsCmd(String groupId, String rest, boolean isCustom) {
    String cmdName = isCustom ? "定制阈值" : "红包阈值";
    String actCmd = isCustom ? "定制文案" : "红包文案";
    if (rest == null) rest = "";
    String trimmed = rest.trim();
    if (trimmed.isEmpty()) return "❌ 格式: " + cmdName + " 5 10 20 (1-" + RP_MAX_TIERS + " 个递增正整数)";
    String[] parts = trimmed.split("\\s+");
    List nums = new java.util.ArrayList();
    for (int i = 0; i < parts.length; i++) {
        String p = parts[i].trim();
        if (p.isEmpty()) continue;
        int v;
        try { v = Integer.parseInt(p); } catch (Throwable t) { return "❌ 阈值需整数: " + cmdName + " 5 10 20"; }
        if (v <= 0) return "❌ 阈值需正整数";
        nums.add(Integer.valueOf(v));
    }
    if (nums.isEmpty()) return "❌ 格式: " + cmdName + " 5 10 20 (1-" + RP_MAX_TIERS + " 个递增正整数)";
    if (nums.size() > RP_MAX_TIERS) return "❌ 最多 " + RP_MAX_TIERS + " 档";
    // 升序排序 + 拒绝重复/相等
    for (int i = 0; i < nums.size(); i++) {
        for (int j = i + 1; j < nums.size(); j++) {
            if (((Integer) nums.get(i)).intValue() == ((Integer) nums.get(j)).intValue()) return "❌ 阈值不能重复";
        }
    }
    // 简单升序
    for (int i = 1; i < nums.size(); i++) {
        Integer cur = (Integer) nums.get(i); int cv = cur.intValue(); int k = i - 1;
        while (k >= 0 && ((Integer) nums.get(k)).intValue() > cv) { nums.set(k + 1, nums.get(k)); k--; }
        nums.set(k + 1, cur);
    }
    // 旧档表 (按档次对位保留动作)。定制: 读 getTiersByType(true) (未配回退普通档表), 写定制键。
    List old = isCustom ? getTiersByType(groupId, true) : getTiers(groupId);
    List out = new java.util.ArrayList();
    int missingFrom = -1;
    for (int i = 0; i < nums.size(); i++) {
        int thr = ((Integer) nums.get(i)).intValue();
        String act = "";
        if (i < old.size()) act = (String) ((Object[]) old.get(i))[1];
        if (act == null) act = "";
        act = sanitizeTierAction(act);
        if (act.isEmpty() && missingFrom < 0) missingFrom = i + 1;   // 1-based
        out.add(new Object[]{ Integer.valueOf(thr), act.isEmpty() ? "未设" : act });
    }
    if (isCustom) setTiersToKey(tiersCustomKey(groupId), out); else setTiers(groupId, out);
    List saved = isCustom ? getTiersByType(groupId, true) : getTiers(groupId);
    String msg = "✅ 本群" + (isCustom ? "定制" : "") + "已设 " + saved.size() + " 档: " + rpTiersSummary(saved);
    if (missingFrom > 0) msg += "\n⚠️ 档" + missingFrom + "起动作未设, 请发 " + actCmd + missingFrom + " <动作> 设置";
    return msg;
}

// 红包文案K 文字: 设当前群第 K 档动作。
String rpSetTierActionCmd(String groupId, int k, String v) { return rpSetTierActionCmd(groupId, k, v, false); }
// v1.7.0 §22.4: isCustom=true → 操作定制档表 (读 getTiersByType(true), 写定制键)。
String rpSetTierActionCmd(String groupId, int k, String v, boolean isCustom) {
    String txtCmd = isCustom ? "定制文案" : "红包文案";
    String addCmd = isCustom ? "定制增加档" : "红包增加档";
    if (v == null || v.trim().isEmpty()) return "❌ 格式: " + txtCmd + k + " 爆照";
    List tiers = isCustom ? getTiersByType(groupId, true) : getTiers(groupId);
    if (k < 1 || k > tiers.size()) return "❌ 当前仅 " + tiers.size() + " 档, 请先发 " + addCmd + " <阈值> <动作> 增加档";
    String act = sanitizeTierAction(v);
    if (act.isEmpty()) return "❌ 动作不能为空";
    Object[] e = (Object[]) tiers.get(k - 1);
    e[1] = act;
    if (isCustom) setTiersToKey(tiersCustomKey(groupId), tiers); else setTiers(groupId, tiers);
    List saved = isCustom ? getTiersByType(groupId, true) : getTiers(groupId);
    return "✅ " + (isCustom ? "定制" : "") + "档" + k + "动作(原样,措辞随机): " + act + "\n当前: " + rpTiersSummary(saved);
}

// 红包增加档 <阈值> <动作>: 当前群追加一档。
String rpAddTierCmd(String groupId, String rest) { return rpAddTierCmd(groupId, rest, false); }
// v1.7.0 §22.4: isCustom=true → 追加到定制档表 (读 getTiersByType(true), 写定制键)。
String rpAddTierCmd(String groupId, String rest, boolean isCustom) {
    String addCmd = isCustom ? "定制增加档" : "红包增加档";
    if (rest == null) rest = "";
    rest = rest.trim();
    int sp = rest.indexOf(' ');
    if (sp < 0) return "❌ 格式: " + addCmd + " 30 发红包";
    String tStr = rest.substring(0, sp).trim();
    String act = sanitizeTierAction(rest.substring(sp + 1));
    int thr;
    try { thr = Integer.parseInt(tStr); } catch (Throwable t) { return "❌ 阈值需整数: " + addCmd + " 30 发红包"; }
    if (thr <= 0) return "❌ 阈值需正整数";
    if (act.isEmpty()) return "❌ 格式: " + addCmd + " 30 发红包";
    List tiers = isCustom ? getTiersByType(groupId, true) : getTiers(groupId);
    if (tiers.size() >= RP_MAX_TIERS) return "❌ 最多 " + RP_MAX_TIERS + " 档";
    for (int i = 0; i < tiers.size(); i++) {
        if (((Integer) ((Object[]) tiers.get(i))[0]).intValue() == thr) return "❌ 该阈值已存在";
    }
    tiers.add(new Object[]{ Integer.valueOf(thr), act });
    if (isCustom) setTiersToKey(tiersCustomKey(groupId), tiers); else setTiers(groupId, tiers);
    List saved = isCustom ? getTiersByType(groupId, true) : getTiers(groupId);
    return "✅ 已增加" + (isCustom ? "定制" : "") + "档 (>" + thr + "元 " + act + "), 当前 " + saved.size() + " 档:\n" + rpTiersSummary(saved);
}

// v1.7.0 §22.4: Dialog 多行档表解析 helper (普通/定制档表编辑器共用; 逐行校验 + 行号错误 + 满档截断)。
//   返回 Object[]{ List parsed, StringBuilder errs, Integer errCount, Boolean overflow }。逻辑与 v1.6.1 内联段逐字一致。
Object[] rpParseTierBox(String raw) {
    List parsed = new java.util.ArrayList();
    StringBuilder errs = new StringBuilder();
    int errCount = 0;
    int nonEmptyLineNo = 0;
    boolean overflow = false;
    if (raw == null) raw = "";
    String[] lines = raw.split("\n");
    for (int i = 0; i < lines.length; i++) {
        String ln = lines[i].trim();
        if (ln.isEmpty()) continue;
        nonEmptyLineNo++;
        if (parsed.size() >= RP_MAX_TIERS) { overflow = true; continue; }
        int sp = ln.indexOf(' ');
        if (sp < 0) { rpAppendErr(errs, errCount, "第" + nonEmptyLineNo + "行格式错(缺动作)"); errCount++; continue; }
        String ts = ln.substring(0, sp).trim();
        String act = sanitizeTierAction(ln.substring(sp + 1));
        int thr;
        try { thr = Integer.parseInt(ts); } catch (Throwable t) { rpAppendErr(errs, errCount, "第" + nonEmptyLineNo + "行阈值非整数"); errCount++; continue; }
        if (thr <= 0) { rpAppendErr(errs, errCount, "第" + nonEmptyLineNo + "行阈值需正整数"); errCount++; continue; }
        if (act.isEmpty()) { rpAppendErr(errs, errCount, "第" + nonEmptyLineNo + "行缺动作"); errCount++; continue; }
        boolean dup = false;
        for (int j = 0; j < parsed.size(); j++) { if (((Integer) ((Object[]) parsed.get(j))[0]).intValue() == thr) { dup = true; break; } }
        if (dup) { rpAppendErr(errs, errCount, "第" + nonEmptyLineNo + "行阈值重复"); errCount++; continue; }
        parsed.add(new Object[]{ Integer.valueOf(thr), act });
    }
    if (overflow) rpAppendErr(errs, errCount, "最多 " + RP_MAX_TIERS + " 档, 超出已截断");
    return new Object[]{ parsed, errs, Integer.valueOf(errCount), Boolean.valueOf(overflow) };
}

// v1.6.1: Dialog 行解析错误收集 helper (前 5 条带分隔符追加, 简化调用点避免 BeanShell 深嵌套解析问题)。
void rpAppendErr(StringBuilder errs, int curCount, String msg) {
    try {
        if (curCount >= 5) return;
        if (curCount > 0) errs.append("; ");
        errs.append(msg);
    } catch (Throwable t) {}
}

// 红包减少档: 删当前群最高档 (档数需 >1)。
String rpDelTopTierCmd(String groupId) { return rpDelTopTierCmd(groupId, false); }
// v1.7.0 §22.4: isCustom=true → 删定制档表最高档 (读 getTiersByType(true), 写定制键)。
String rpDelTopTierCmd(String groupId, boolean isCustom) {
    List tiers = isCustom ? getTiersByType(groupId, true) : getTiers(groupId);
    if (tiers.size() <= 1) return "❌ 至少保留 1 档, 无法再减少";
    Object[] removed = (Object[]) tiers.remove(tiers.size() - 1);
    if (isCustom) setTiersToKey(tiersCustomKey(groupId), tiers); else setTiers(groupId, tiers);
    List saved = isCustom ? getTiersByType(groupId, true) : getTiers(groupId);
    return "✅ 已删除" + (isCustom ? "定制" : "") + "最高档 (>" + ((Integer) removed[0]).intValue() + "元 " + (String) removed[1] + "), 剩 " + saved.size() + " 档:\n" + rpTiersSummary(saved);
}

long cfgDelay() { return cfgLong(K_DELAY, DEF_DELAY); }
long cfgRetry1() { return cfgLong(K_RETRY1, DEF_RETRY1); }
long cfgRetry2() { return cfgLong(K_RETRY2, DEF_RETRY2); }
long cfgRetry3() { return cfgLong(K_RETRY3, DEF_RETRY3); }   // v1.10.0 §4: 重试3 末档 (默认 1 小时); 仅 worker 的 afterCoverNoDetail 读, 不进热路径
int cfgAtLimit() { return cfgInt(K_AT_LIMIT, DEF_AT_LIMIT); }
// v1.11.0 §25: 伸手党治理 per-group 配置 (沿用现有 getString 读法)。
boolean cfgFreeloaderOn(String gid) { try { return "1".equals(getString(K_FREELOADER_ON_PREFIX + normGroupId(gid), "")); } catch (Throwable t) { return false; } }
int cfgFreeloaderWin(String gid) {
    try { int v = Integer.parseInt(getString(K_FREELOADER_WIN_PREFIX + normGroupId(gid), "").trim()); return (v >= 1 && v <= 1440) ? v : DEF_FREELOADER_WIN; }
    catch (Throwable t) { return DEF_FREELOADER_WIN; }
}
long cfgMsgGap() { return cfgLong(K_MSG_GAP, DEF_MSG_GAP); }
long cfgTwoMsgGap() { return cfgLong(K_TWO_MSG_GAP, DEF_TWO_MSG_GAP); }   // v1.1.4 §16

int cfgInt(String key, int def) {
    try { String s = getString(key, ""); if (s == null || s.isEmpty()) return def; return Integer.parseInt(s.trim()); }
    catch (Throwable t) { return def; }
}
long cfgLong(String key, long def) {
    try { String s = getString(key, ""); if (s == null || s.isEmpty()) return def; return Long.parseLong(s.trim()); }
    catch (Throwable t) { return def; }
}
String cfgStr(String key, String def) {
    try { String s = getString(key, ""); if (s == null || s.isEmpty()) return def; return s; }
    catch (Throwable t) { return def; }
}
String cfgRulePrefix() { return cfgStr(K_RULE_PREFIX, DEF_RULE_PREFIX); }
// v1.7.1 §22.6: 定制包第一条群规前缀 (per-group, 空回退默认「定制包请按要求执行」)。
String customRulePrefixKey(String groupId) { return K_CUSTOM_RULE_PREFIX_PREFIX + normGroupId(groupId); }
String cfgCustomRulePrefix(String groupId) {
    try {
        String s = getString(customRulePrefixKey(groupId), "");
        if (s == null || s.trim().isEmpty()) return DEF_CUSTOM_RULE_PREFIX;
        return s;
    } catch (Throwable t) { return DEF_CUSTOM_RULE_PREFIX; }
}
String cfgExportTarget() { return cfgStr(K_EXPORT_TARGET, DEF_EXPORT_TARGET); }   // v1.3.0 §18 (每日定时/手动导出沿用, v1.9.0 不变)
// v1.9.0 §24.1: 转发目标显示名 (空回退空串)。
String cfgExportTargetName() { try { String s = getString(K_EXPORT_TARGET_NAME, ""); return (s == null) ? "" : s; } catch (Throwable t) { return ""; } }
// v1.9.1 §24.1: 按群转发目标。rp_export_target_<gid> 非空则用, 否则回退全局默认 cfgExportTarget()。
String cfgExportTargetFor(String gid) {
    try {
        String g = normGroupId(gid);
        if (!g.isEmpty()) {
            String s = getString(K_EXPORT_TARGET + "_" + g, "");
            if (s != null && !s.trim().isEmpty()) return s.trim();
        }
    } catch (Throwable t) {}
    return cfgExportTarget();
}
// v1.9.1 §24.1: 按群转发目标显示名, 同理回退全局 cfgExportTargetName()。
String cfgExportTargetNameFor(String gid) {
    try {
        String g = normGroupId(gid);
        if (!g.isEmpty()) {
            // 仅当该群设过按群目标时才用按群显示名 (避免目标空但名残留)。
            String tgt = getString(K_EXPORT_TARGET + "_" + g, "");
            if (tgt != null && !tgt.trim().isEmpty()) {
                String s = getString(K_EXPORT_TARGET_NAME + "_" + g, "");
                return (s == null) ? "" : s;
            }
        }
    } catch (Throwable t) {}
    return cfgExportTargetName();
}
// v1.9.0 §24: 目标是否为群 (id 含 @chatroom)。
boolean rpTargetIsGroup(String target) { try { return target != null && target.indexOf("@chatroom") >= 0; } catch (Throwable t) { return false; } }
int cfgDailyHour() { int h = cfgInt(K_DAILY_HOUR, DEF_DAILY_HOUR); if (h < 0 || h > 23) h = DEF_DAILY_HOUR; return h; }   // v1.4.0 §20

// 统计 "A|B|C" 里的非空变体数 (toast 提示用)。
int rpVariantCount(String raw) {
    try {
        if (raw == null || raw.isEmpty()) return 0;
        if (raw.indexOf('|') < 0) return 1;
        String[] arr = raw.split("\\|");
        int n = 0;
        for (int i = 0; i < arr.length; i++) if (!arr[i].trim().isEmpty()) n++;
        return n == 0 ? 1 : n;
    } catch (Throwable t) { return 1; }
}

// v1.0.7 §14: 只随机"连接词"(金额与动作之间的措辞), 去机器指纹。
// 从内置 DEF_CONNECTORS 随机挑一条, 把 {X} 占位替换为该档阈值, 返回已填阈值的连接词。
// 动作文案 / 群规前缀 / 档位均不参与随机, 用户录入什么就原样发什么。
String pickConnector(int threshold) {
    try {
        String[] arr = DEF_CONNECTORS;
        if (arr == null || arr.length == 0) return "超过" + threshold + "元，";
        int idx = (int) (Math.random() * (double) arr.length);
        if (idx < 0) idx = 0; if (idx >= arr.length) idx = arr.length - 1;
        String c = arr[idx];
        if (c == null || c.isEmpty()) return "超过" + threshold + "元，";
        return c.replace("{X}", String.valueOf(threshold));
    } catch (Throwable t) { return "超过" + threshold + "元，"; }
}

// v1.0.7 §14: 只随机"动作语气包裹"(动作核心词周围的语气措辞), 去机器指纹。
// 从内置 DEF_ACTION_PHRASES 随机挑一条, 把 {A} 占位替换为该档动作核心原文, 返回已填动作原文的语气包裹。
// 动作核心词本身用户录入什么就原样发什么, 不参与随机; 档位/前缀同样不动。
String pickActionPhrase(String actionCore) {
    String core = (actionCore == null) ? "" : actionCore;
    try {
        String[] arr = DEF_ACTION_PHRASES;
        if (arr == null || arr.length == 0) return core;
        int idx = (int) (Math.random() * (double) arr.length);
        if (idx < 0) idx = 0; if (idx >= arr.length) idx = arr.length - 1;
        String p = arr[idx];
        if (p == null || p.isEmpty()) return core;
        return p.replace("{A}", core);
    } catch (Throwable t) { return core; }
}

// ==================== 按群启用 (仿 GroupAdmin) ====================
List parseCsv(String csv) {
    List r = new java.util.ArrayList();
    if (csv == null || csv.isEmpty()) return r;
    String[] arr = csv.split(",");
    for (int i = 0; i < arr.length; i++) { String s = arr[i].trim(); if (!s.isEmpty()) r.add(s); }
    return r;
}
String joinCsv(List list) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < list.size(); i++) { if (i > 0) sb.append(","); sb.append(list.get(i)); }
    return sb.toString();
}
String normGroupId(String g) { if (g == null) return ""; return g.trim(); }

// v1.6.4: [性能/C-PERF-01,03] 启用群集合内存缓存 — 消除红包消息热路径最后一处 per-红包 FUSE 读
//   (isGroupEnabled → getEnabledGroups → getString(K_ENABLED) ~26ms/红包候选)。仿 GroupAdmin v1.16.2 同款。
//   RP_ENABLED_CACHE 存 normGroupId 后的群 id; TTL 60s 兜底外部 adb 改 config.prop; enable/disable 变更后立即刷新保即时生效。
//   加载失败保留旧缓存 (降级安全); isGroupEnabled 异常时回退直接读 getString (宁可慢不可错: 这是红包在本群是否处理的总闸门)。
//   归一口径: 与原 isGroupEnabled 完全一致 = normGroupId(=trim)。
java.util.HashSet RP_ENABLED_CACHE = null;   // 已启用群 id 集合 (normGroupId 后); null=尚未加载
long RP_ENABLED_CACHE_TS = 0L;               // 上次加载毫秒
long RP_ENABLED_TTL_MS   = 60000L;           // 缓存 TTL: 外部改 config.prop 最多此时长后生效

// 从 config.prop 读 K_ENABLED, 全量 norm 后原子替换 RP_ENABLED_CACHE。失败保留旧缓存只 log。
void rpLoadEnabledCache() {
    try {
        List l = parseCsv(getString(K_ENABLED, ""));
        java.util.HashSet ns = new java.util.HashSet();
        for (int i = 0; i < l.size(); i++) {
            String g = normGroupId((String) l.get(i));
            if (!g.isEmpty()) ns.add(g);
        }
        RP_ENABLED_CACHE = ns;            // 原子替换
        RP_ENABLED_CACHE_TS = System.currentTimeMillis();
    } catch (Throwable t) {
        try { log("rpLoadEnabledCache failed (keep old cache): " + t); } catch (Throwable t2) {}
    }
}

List getEnabledGroups() { return parseCsv(getString(K_ENABLED, "")); }
boolean isGroupEnabled(String groupId) {
    String g = normGroupId(groupId);
    if (g.isEmpty()) return false;
    try {
        long now = System.currentTimeMillis();
        if (RP_ENABLED_CACHE == null || (now - RP_ENABLED_CACHE_TS) > RP_ENABLED_TTL_MS) {
            rpLoadEnabledCache();
        }
        if (RP_ENABLED_CACHE != null) return RP_ENABLED_CACHE.contains(g);
    } catch (Throwable t) {
        try { log("isGroupEnabled cache error, fallback to direct read: " + t); } catch (Throwable t2) {}
    }
    // 回退: 缓存出错时直接读 (保证正确性, 与原逻辑完全一致, 不因缓存错误误判)
    List l = getEnabledGroups();
    for (int i = 0; i < l.size(); i++) if (g.equals(normGroupId((String) l.get(i)))) return true;
    return false;
}
void enableGroup(String groupId) {
    String g = normGroupId(groupId);
    if (g.isEmpty()) return;
    List l = getEnabledGroups();
    boolean already = false;
    for (int i = 0; i < l.size(); i++) if (g.equals(normGroupId((String) l.get(i)))) { already = true; break; }
    if (!already) { l.add(g); putString(K_ENABLED, joinCsv(l)); }
    rpLoadEnabledCache();   // v1.6.4 变更即时失效: 开启后下一个红包立即按新状态判定
    // v1.4.0 §20: 开启红包统计时默认把该群加进每日定时 (可单独关)。
    try { enableDailyGroup(g); } catch (Throwable t) {}
}
void disableGroup(String groupId) {
    String g = normGroupId(groupId);
    List l = getEnabledGroups();
    boolean changed = false;
    for (int i = l.size() - 1; i >= 0; i--) if (g.equals(normGroupId((String) l.get(i)))) { l.remove(i); changed = true; }
    if (changed) putString(K_ENABLED, joinCsv(l));
    rpLoadEnabledCache();   // v1.6.4 变更即时失效: 关闭后下一个红包立即不再处理该群
}

// ==================== 红包提醒排除名单 (按群, v1.0.6 §12) ====================
// key = rp_exclude_<groupId> (CSV wxid)。名单里的人永不被红包提醒@, 也不计入@上限人数。
String excludeKey(String groupId) { return K_EXCLUDE_PREFIX + normGroupId(groupId); }
List getExcludeList(String groupId) {
    String g = normGroupId(groupId);
    if (g.isEmpty()) return new java.util.ArrayList();
    return parseCsv(getString(excludeKey(g), ""));
}
boolean isExcluded(String groupId, String wxid) {
    if (wxid == null || wxid.isEmpty()) return false;
    String w = wxid.trim();
    List l = getExcludeList(groupId);
    for (int i = 0; i < l.size(); i++) if (w.equals(((String) l.get(i)).trim())) return true;
    return false;
}
// 加入若干 wxid, 返回新增数量。
int addExclude(String groupId, List wxids) {
    String g = normGroupId(groupId);
    if (g.isEmpty() || wxids == null || wxids.isEmpty()) return 0;
    List l = getExcludeList(g);
    int added = 0;
    for (int i = 0; i < wxids.size(); i++) {
        String w = ((String) wxids.get(i)).trim();
        if (w.isEmpty()) continue;
        boolean exist = false;
        for (int j = 0; j < l.size(); j++) if (w.equals(((String) l.get(j)).trim())) { exist = true; break; }
        if (!exist) { l.add(w); added++; }
    }
    if (added > 0) putString(excludeKey(g), joinCsv(l));
    return added;
}
// 移除若干 wxid, 返回移除数量。
int removeExclude(String groupId, List wxids) {
    String g = normGroupId(groupId);
    if (g.isEmpty() || wxids == null || wxids.isEmpty()) return 0;
    List l = getExcludeList(g);
    int removed = 0;
    for (int k = 0; k < wxids.size(); k++) {
        String w = ((String) wxids.get(k)).trim();
        if (w.isEmpty()) continue;
        for (int i = l.size() - 1; i >= 0; i--) if (w.equals(((String) l.get(i)).trim())) { l.remove(i); removed++; }
    }
    if (removed > 0) putString(excludeKey(g), joinCsv(l));
    return removed;
}

// ==================== v1.4.0 §20: 每日定时统计 (按群开关) ====================
// rp_daily_groups (CSV groupId): 开了每日定时的群, 与红包统计开关 (rp_enabled_groups) 独立。
List getDailyGroups() { return parseCsv(getString(K_DAILY_GROUPS, "")); }
boolean isDailyEnabled(String groupId) {
    String g = normGroupId(groupId);
    if (g.isEmpty()) return false;
    List l = getDailyGroups();
    for (int i = 0; i < l.size(); i++) if (g.equals(normGroupId((String) l.get(i)))) return true;
    return false;
}
void enableDailyGroup(String groupId) {
    String g = normGroupId(groupId);
    if (g.isEmpty()) return;
    List l = getDailyGroups();
    for (int i = 0; i < l.size(); i++) if (g.equals(normGroupId((String) l.get(i)))) return;
    l.add(g);
    putString(K_DAILY_GROUPS, joinCsv(l));
}
void disableDailyGroup(String groupId) {
    String g = normGroupId(groupId);
    List l = getDailyGroups();
    boolean changed = false;
    for (int i = l.size() - 1; i >= 0; i--) if (g.equals(normGroupId((String) l.get(i)))) { l.remove(i); changed = true; }
    if (changed) putString(K_DAILY_GROUPS, joinCsv(l));
}
// 一次性迁移 (onLoad 调): 首次把 rp_daily_groups 初始化 = 当前 rp_enabled_groups (现在开了红包统计的群默认打开定时)。
// 用 rp_daily_migrated flag 保证只跑一次; 跑过后用户对 daily_groups 的增删不被覆盖。
void rpDailyMigrateOnce() {
    try {
        String flag = getString(K_DAILY_MIGRATED, "");
        if (flag != null && flag.equals("1")) return;   // 已迁移
        List enabled = getEnabledGroups();
        // 把当前已启用红包统计的群全部加进 daily_groups (去重)。
        for (int i = 0; i < enabled.size(); i++) {
            String g = normGroupId((String) enabled.get(i));
            if (!g.isEmpty()) enableDailyGroup(g);
        }
        putString(K_DAILY_MIGRATED, "1");
        hbBgLog("[RP] " + hbNow() + " daily-migrate: initialized rp_daily_groups from rp_enabled_groups (" + enabled.size() + " groups). one-time, flag set.");
    } catch (Throwable t) {
        hbBgLog("[RP] " + hbNow() + " daily-migrate err: " + t);
    }
}

// 处理 "@某人 红包排除 / 取消红包排除" 命令 (v1.0.6 §12, 走 onHandleMsg 路径)。
// 返回 true=已处理(调用方 return, 不当红包/不发群回执, 只 toast); false=不是排除命令(继续走红包检测)。
// atList = bot @ 的 wxid 列表(去掉 bot 自己)。命令文本本身会显示在群里(一次性设置, 可接受); 处理后只 toast 不发群。
boolean rpHandleExcludeCmd(String groupId, String content, java.util.List atList) {
    try {
        String g = normGroupId(groupId);
        if (g.isEmpty() || g.indexOf("@chatroom") < 0) return false;   // 仅群聊
        if (content == null) return false;

        // 收集 @ 目标 wxid, 去掉 bot 自己。
        String me = null;
        try { me = getLoginWxid(); } catch (Throwable t) { me = null; }
        java.util.List wxids = new java.util.ArrayList();
        for (int i = 0; i < atList.size(); i++) {
            Object o = atList.get(i);
            if (o == null) continue;
            String w = o.toString().trim();
            if (w.isEmpty()) continue;
            if (me != null && w.equals(me)) continue;   // 去掉 bot 自己
            wxids.add(w);
        }
        if (wxids.isEmpty()) return false;   // 只 @ 了自己 / 无有效目标 → 不当排除命令

        boolean isCancel = (content.indexOf("取消红包排除") >= 0) || (content.indexOf("红包取消排除") >= 0);
        if (isCancel) {
            int n = removeExclude(g, wxids);
            try { toast("✅ 已移出红包排除名单: " + n + " 人 (恢复正常 @ 提醒)"); } catch (Throwable t) {}
            hbBgLog("[RP] " + hbNow() + " exclude-cmd: removed " + n + " from group exclude list (count target=" + wxids.size() + ").");
            return true;
        }
        // 含 "红包排除" 且非取消 → 加入
        int n = addExclude(g, wxids);
        try { toast("✅ 已加入红包排除名单: " + n + " 人 (此后红包提醒不再 @ TA)"); } catch (Throwable t) {}
        hbBgLog("[RP] " + hbNow() + " exclude-cmd: added " + n + " to group exclude list (count target=" + wxids.size() + ").");
        return true;
    } catch (Throwable t) {
        hbBgLog("[RP] " + hbNow() + " exclude-cmd err: " + t);
        return false;
    }
}

// ================================================================
// ============ onHandleMsg: 命令 + 红包检测 + 排程 ============
// ================================================================
// v1.4.0: perf 埋点包装层 (仿 GroupAdmin)。测本次 onHandleMsg 总耗时, 累加进窗口计数, 到阈值兜底落盘。
// 埋点全程 try/catch, 任何异常都不影响消息处理 (热路径优先)。只 nanoTime + 整型累加, 不做文件 IO (落盘由 worker tick 驱动)。
void onHandleMsg(Object msg) {
    long _perfStart = 0L;
    boolean _perfOn = false;
    // v1.6.2: 每条进入前清红包标记; body 内确认本条是红包(命中检测+启用)才置 true。整段 try/catch, 埋点出错不影响消息。
    try { PERF_LAST_RP = false; } catch (Throwable t) {}
    try { _perfStart = System.nanoTime(); _perfOn = true; } catch (Throwable t) { _perfOn = false; }
    try {
        onHandleMsgBody(msg);
    } finally {
        if (_perfOn) {
            try {
                long dur = System.nanoTime() - _perfStart;
                PERF_N++;
                PERF_SUM_NS += dur;
                if (dur > PERF_MAX_NS) PERF_MAX_NS = dur;
                // v1.6.2: 按 A(红包)/B(普通) 分类累加 sum/max/count, 并标记 spike (>阈值)。
                boolean wasRp = false;
                try { wasRp = PERF_LAST_RP; } catch (Throwable t) { wasRp = false; }
                if (wasRp) {
                    PERF_RP_N++;
                    PERF_RP_SUM_NS += dur;
                    if (dur > PERF_RP_MAX_NS) PERF_RP_MAX_NS = dur;
                } else {
                    PERF_NORM_N++;
                    PERF_NORM_SUM_NS += dur;
                    if (dur > PERF_NORM_MAX_NS) PERF_NORM_MAX_NS = dur;
                }
                if (dur > PERF_SPIKE_NS) {
                    PERF_SPIKE_N++;
                    if (wasRp) PERF_SPIKE_RP_N++;
                    else PERF_SPIKE_NORM_N++;
                }
                // 兜底: 窗口异常膨胀 (worker 长时间没跑) 时才在热路径 flush 一次, 防内存计数溢出/数据失真。
                if (PERF_N >= (long) PERF_FLUSH_EVERY * 50L) perfFlush();
            } catch (Throwable t) { /* 埋点出错绝不影响消息 */ }
        }
    }
}

void onHandleMsgBody(Object msg) {
    try {
        if (msg == null) return;
        try { if (!msg.isChatroom()) return; } catch (Throwable t) { return; }

        String groupId = null, sender = null, content = null;
        try { groupId = msg.getTalker(); } catch (Throwable t) {}
        try { sender = msg.getSendTalker(); } catch (Throwable t) {}
        try { content = msg.getContent(); } catch (Throwable t) {}

        // ---- (1) 配置命令不在这里处理 ----
        // v1.0.2: 全部配置命令 (红包统计设置/开启/关闭/阈值/文案/延迟/at上限/状态) 改由 onClickSendBtn 拦截,
        //         bot 一点发送即 return true 阻止消息进群 + 就地执行 + toast 本地提示。onHandleMsg 不再碰命令/不再发群回执。

        // ---- (1b) 排除名单 @ 增删 (v1.0.6 §12): bot 自己发 + atList 非空 + 文本含关键字 ----
        //   onClickSendBtn 只收纯文本拿不到 @ 目标 wxid, 故走这里。命中即处理 + toast + return (不当红包/不发群回执)。
        //   "@某人 红包排除" 这条命令本身会显示在群里(一次性设置动作, 可接受)。廉价: 仅在 bot 自己发且有 @ 时才解析 atList。
        // v1.1.3 §19: 顺序优化 — 先判廉价的 content.indexOf("红包排除")(普通消息绝大多数不含, 立即短路),
        //   再判 sender.equals(getLoginWxid())。普通消息不再每条调 getLoginWxid()。纯顺序调整, 零行为变化。
        try {
            if (content != null && content.indexOf("红包排除") >= 0 && sender != null && sender.equals(getLoginWxid())) {
                java.util.List atList = null;
                try { atList = msg.getAtUserList(); } catch (Throwable t) { atList = null; }
                if (atList != null && !atList.isEmpty()) {
                    boolean handled = rpHandleExcludeCmd(groupId, content, atList);
                    if (handled) return;
                }
            }
        } catch (Throwable t) {}

        // ---- (2) 红包检测 (廉价: type 或 字符串包含) ----
        boolean isHb = false;
        int type = 0;
        try { type = ((Number) msg.getType()).intValue(); } catch (Throwable t) { type = 0; }
        if (type == HB_MSG_TYPE) isHb = true;
        if (!isHb) {
            if (content == null) return;
            if (content.indexOf("<nativeurl>") < 0) return;
            isHb = true;
        }
        if (content == null) return;

        // ---- (3) 本群启用闸门: 没启用直接 return (不开页/不排 delay) ----
        if (!isGroupEnabled(groupId)) return;

        // v1.6.2: 至此本条已确认 = 候选红包消息且本群启用, 后续只做"廉价判定+入队"。
        //         给 wrapper 的 finally 分类用 (A 红包消息)。仅置标记, 不做任何 IO/反射。
        try { PERF_LAST_RP = true; } catch (Throwable t) {}

        // ---- (4) v1.6.3 (C-PERF-03): 热路径只做最廉价的判定 + 入队原始数据。----
        //   不再在热路径解析 nativeurl / exclusive_recv_username / rpExtractTitle(XML parse)。
        //   这些重活 (原占 ~267ms 热路径 spike, 其中 extract ~50ms) 全部移到 worker(hbProcess, 已在后台线程)。
        //   去重 key 改用廉价的 k:talker:sender:msgid (不依赖任何 XML parse, 热路径就拿得到)。
        //   原始 content 随 Job 携带 (JOB_CONTENT), worker 出队后再 parse nativeurl/exclusive/title。
        String sender0 = sender; if (sender0 == null) sender0 = groupId;
        final String fSender = sender0;
        final String fTalker = groupId;
        long msgid0 = 0L;
        try { msgid0 = ((Number) msg.getMsgId()).longValue(); } catch (Throwable t) { msgid0 = 0L; }
        final long fMsgid = msgid0;
        final String fContent = content;

        // 廉价去重 key (无 XML parse): k:talker:sender:msgid。同一红包 msgid 唯一, 不重复入队。
        final String fKey = "k:" + fTalker + ":" + fSender + ":" + fMsgid;
        if (!OPENED.add(fKey)) return;

        // v1.1.0 §15.2: 生产者只入队, 不再 per-红包 delay。
        long delaySec = cfgDelay();
        // v1.0.6 §13.1: 首次延迟 ±25% 抖动 (base*0.75 ~ base*1.25), 去零方差。long 乘防溢出。
        long delayMs = hbJitter((long) (delaySec * 1000L * 0.75), (long) (delaySec * 1000L * 0.5));
        // v1.11.2 §25: 记红包检测时刻(≈红包发出时刻)作伸手党判定的全员统一基准。已算好的 long, O(1), 不加热路径开销 (C-PERF-01)。
        long detectMs = System.currentTimeMillis();
        long dueAt = detectMs + delayMs;
        // Job = { key, talker, sender, msgid, dueAt, attempt=0, title(留空, worker填), content(原始), isCustom(留 FALSE, worker填), detectMs(检测时刻) }
        //   v1.6.3: +JOB_CONTENT 第 8 槽; v1.7.0 §22: +JOB_ISCUSTOM 第 9 槽 (热路径只塞常量 Boolean.FALSE, 不算类型 — 类型判定全在 worker); v1.11.2 §25: +JOB_DETECTMS 第 10 槽 (检测时刻, 伸手党判定基准)。
        Object[] job = new Object[]{ fKey, fTalker, fSender, Long.valueOf(fMsgid), Long.valueOf(dueAt), Integer.valueOf(0), "", fContent, Boolean.FALSE, Long.valueOf(detectMs) };
        rpEnqueue(job);
        hbBgLog("[RP] " + hbNow() + " redpacket candidate in enabled group (type=" + type
            + "). enqueued raw (dueAt=now+" + (delayMs / 1000L) + "s, cfg=" + delaySec + "s, jittered, attempt=0). queue size=" + rpQueueSize() + ". (extract/exclusive deferred to worker)");
    } catch (Throwable t) {
        try { log("RedPacketStats onHandleMsg err: " + t); } catch (Throwable t2) {}
    }
}

// ================================================================
// ============ onClickSendBtn: 拦截配置命令 (不发群) ============
// ================================================================
// v1.0.2: bot 在输入框打配置命令点发送 → 本方法拦截。命中即就地执行 + toast 本地提示 + return true
//         (return true 阻止该消息发到群里, 不污染群)。不命中 return false (正常发送, 不影响普通聊天)。
// 当前会话群: getTargetTalker() (仿 GroupAdmin onClickSendBtn)。本方法运行在 UI 线程上下文,
// getTopActivity() 拿得到前台 Activity, 故 `红包统计设置` 能正常弹 Dialog (修复在消息线程弹失败)。
boolean onClickSendBtn(String text) {
    try {
        if (text == null) return false;
        String content = text.trim();
        if (content.isEmpty()) return false;

        // 设置入口: 弹本群配置 Dialog (UI 线程, getTopActivity)
        if (content.equals("红包统计设置")) {
            String groupId = rpCurrentTalker();
            showConfigDialog(groupId);   // groupId 可能为 null(非群聊) → Dialog 走全局配置分支
            return true;
        }
        if (content.equals("开启红包统计")) {
            String groupId = rpCurrentGroupOrToast();
            if (groupId == null) return true;
            enableGroup(groupId);
            try { toast("✅ 已开启本群红包统计 (领完后按金额分档 @ 圈人)"); } catch (Throwable t) {}
            return true;
        }
        if (content.equals("关闭红包统计")) {
            String groupId = rpCurrentGroupOrToast();
            if (groupId == null) return true;
            disableGroup(groupId);
            try { toast("🔕 已关闭本群红包统计"); } catch (Throwable t) {}
            return true;
        }
        if (content.equals("红包统计状态")) {
            String groupId = rpCurrentTalker();
            try { toast(rpStatusText(groupId)); } catch (Throwable t) {}
            return true;
        }
        // v1.4.0 §20: 每日定时按群开关 (对当前群; 拦截不发群, toast 反馈)。
        if (content.equals("开启红包定时")) {
            String groupId = rpCurrentGroupOrToast();
            if (groupId == null) return true;
            enableDailyGroup(groupId);
            try { toast("✅ 已开启本群每日定时统计 (每天" + cfgDailyHour() + "点私聊汇总过去24h)"); } catch (Throwable t) {}
            return true;
        }
        if (content.equals("关闭红包定时")) {
            String groupId = rpCurrentGroupOrToast();
            if (groupId == null) return true;
            disableDailyGroup(groupId);
            try { toast("🔕 已关闭本群每日定时统计"); } catch (Throwable t) {}
            return true;
        }
        // v1.4.0 §20: 手动测试每日发送 (bot 自己; 立即按过去24h对 daily_groups 跑一次 → 私聊 ACCT_REDACTED)。
        if (content.equals("红包每日测试")) {
            try { rpDailyTest(); toast("📤 已触发每日测试 (过去24h → 私聊, 看 rp.log)"); } catch (Throwable t) { try { toast("❌ 测试失败: " + t); } catch (Throwable t2) {} }
            return true;
        }
        // ========== v1.7.0 §22.4: 定制包档表命令 (作用当前群, 复用普通解析器 isCustom=true) ==========
        // 顺序: 定制* 必须在 红包* 之前不必, 因前缀不同; 但 定制关键字 要先于 定制(若有其它). 这里各自独立 startsWith/equals。
        if (content.startsWith("定制关键字")) {
            String groupId = rpCurrentGroupOrToast();
            if (groupId == null) return true;
            String kw = content.substring("定制关键字".length()).trim();
            try {
                setCustomKw(groupId, kw);
                if (kw.isEmpty()) toast("✅ 已清空本群定制关键字 (定制将回退普通包)");
                else toast("✅ 本群定制关键字(包含匹配祝福语): " + kw + (isCustomOn(groupId) ? "" : "\n⚠️ 定制开关未开, 请在『红包统计设置』Dialog 开启"));
            } catch (Throwable t) { try { toast("❌ 格式: 定制关键字 恭喜发财"); } catch (Throwable t2) {} }
            return true;
        }
        // v1.7.1 §22.6: 定制包第一条群规前缀 (当前群, 单值原样存储/发送), 仿 红包群规前缀。
        if (content.startsWith("定制群规前缀")) {
            String groupId = rpCurrentGroupOrToast();
            if (groupId == null) return true;
            String v = content.substring("定制群规前缀".length()).trim();
            try { if (!v.isEmpty()) { putString(customRulePrefixKey(groupId), v); toast("✅ 本群定制群规前缀(原样发送): " + v); } else toast("❌ 格式: 定制群规前缀 定制包请按要求执行"); } catch (Throwable t) {}
            return true;
        }
        if (content.startsWith("定制增加档")) {
            String groupId = rpCurrentGroupOrToast();
            if (groupId == null) return true;
            String rest = content.substring("定制增加档".length()).trim();
            try { toast(rpAddTierCmd(groupId, rest, true)); } catch (Throwable t) { try { toast("❌ 格式: 定制增加档 20 发视频"); } catch (Throwable t2) {} }
            return true;
        }
        if (content.equals("定制减少档")) {
            String groupId = rpCurrentGroupOrToast();
            if (groupId == null) return true;
            try { toast(rpDelTopTierCmd(groupId, true)); } catch (Throwable t) { try { toast("❌ 操作失败"); } catch (Throwable t2) {} }
            return true;
        }
        if (content.startsWith("定制阈值")) {
            String groupId = rpCurrentGroupOrToast();
            if (groupId == null) return true;
            String rest = content.substring("定制阈值".length()).trim();
            try { toast(rpSetThresholdsCmd(groupId, rest, true)); } catch (Throwable t) { try { toast("❌ 格式: 定制阈值 5 10 (1-" + RP_MAX_TIERS + "个递增正整数)"); } catch (Throwable t2) {} }
            return true;
        }
        if (content.startsWith("定制文案")) {
            String groupId = rpCurrentGroupOrToast();
            if (groupId == null) return true;
            try {
                String afterPrefix = content.substring("定制文案".length());
                int di = 0;
                while (di < afterPrefix.length() && di < 2 && afterPrefix.charAt(di) >= '0' && afterPrefix.charAt(di) <= '9') di++;
                if (di == 0) { try { toast("❌ 格式: 定制文案1 爆照 (档号 1-" + RP_MAX_TIERS + ")"); } catch (Throwable t2) {} return true; }
                String kStr = afterPrefix.substring(0, di);
                int k = Integer.parseInt(kStr);
                if (k < 1 || k > RP_MAX_TIERS) { try { toast("❌ 档号需 1-" + RP_MAX_TIERS); } catch (Throwable t2) {} return true; }
                String v = afterPrefix.substring(di).trim();
                if (v.isEmpty()) { try { toast("❌ 格式: 定制文案" + k + " 爆照 (动作不能为空)"); } catch (Throwable t2) {} return true; }
                try { toast(rpSetTierActionCmd(groupId, k, v, true)); } catch (Throwable t) { try { toast("❌ 格式: 定制文案" + k + " 爆照"); } catch (Throwable t2) {} }
            } catch (Throwable t) { try { toast("❌ 格式: 定制文案1 爆照 (档号 1-" + RP_MAX_TIERS + ")"); } catch (Throwable t2) {} }
            return true;
        }
        // v1.6.0: 红包增加档 <阈值> <动作> — 当前群追加一档 (需档数 <4)。
        if (content.startsWith("红包增加档")) {
            String groupId = rpCurrentGroupOrToast();
            if (groupId == null) return true;
            String rest = content.substring("红包增加档".length()).trim();
            try { toast(rpAddTierCmd(groupId, rest)); } catch (Throwable t) { try { toast("❌ 格式: 红包增加档 20 发视频"); } catch (Throwable t2) {} }
            return true;
        }
        // v1.6.0: 红包减少档 — 删当前群最高档 (档数需 >1)。
        if (content.equals("红包减少档")) {
            String groupId = rpCurrentGroupOrToast();
            if (groupId == null) return true;
            try { toast(rpDelTopTierCmd(groupId)); } catch (Throwable t) { try { toast("❌ 操作失败"); } catch (Throwable t2) {} }
            return true;
        }
        // v1.6.1: 红包阈值 a [b ...] — 接受 1-RP_MAX_TIERS 个正整数, 作用当前群; 自动升序、拒重复。
        //   新档数 > 原档数: 新增档动作留空(沿用旧动作或提示设置); 新档数 < 原档数: 截断多余档动作。
        if (content.startsWith("红包阈值")) {
            String groupId = rpCurrentGroupOrToast();
            if (groupId == null) return true;
            String rest = content.substring("红包阈值".length()).trim();
            try { toast(rpSetThresholdsCmd(groupId, rest)); } catch (Throwable t) { try { toast("❌ 格式: 红包阈值 5 10 (1-" + RP_MAX_TIERS + "个递增正整数)"); } catch (Throwable t2) {} }
            return true;
        }
        // v1.6.1: 红包文案K 文字 (K=1..RP_MAX_TIERS) — 设当前群第 K 档动作。
        //   解析前导整数(1-2位)作 K, 其余 trim 作动作 v。修 v1.6.0 charAt(0) 只认 '1'-'4' 的两位数 bug
        //   (旧: "红包文案10 发红包" → K=1 动作="0 发红包"; 新: K=10 动作="发红包")。
        if (content.startsWith("红包文案")) {
            String groupId = rpCurrentGroupOrToast();
            if (groupId == null) return true;
            try {
                String afterPrefix = content.substring("红包文案".length());
                // 取开头连续数字 (1-2 位) 作 K
                int di = 0;
                while (di < afterPrefix.length() && di < 2 && afterPrefix.charAt(di) >= '0' && afterPrefix.charAt(di) <= '9') di++;
                if (di == 0) { try { toast("❌ 格式: 红包文案1 爆照 (档号 1-" + RP_MAX_TIERS + ")"); } catch (Throwable t2) {} return true; }
                String kStr = afterPrefix.substring(0, di);
                int k = Integer.parseInt(kStr);
                if (k < 1 || k > RP_MAX_TIERS) { try { toast("❌ 档号需 1-" + RP_MAX_TIERS); } catch (Throwable t2) {} return true; }
                String v = afterPrefix.substring(di).trim();
                if (v.isEmpty()) { try { toast("❌ 格式: 红包文案" + k + " 爆照 (动作不能为空)"); } catch (Throwable t2) {} return true; }
                try { toast(rpSetTierActionCmd(groupId, k, v)); } catch (Throwable t) { try { toast("❌ 格式: 红包文案" + k + " 爆照"); } catch (Throwable t2) {} }
            } catch (Throwable t) { try { toast("❌ 格式: 红包文案1 爆照 (档号 1-" + RP_MAX_TIERS + ")"); } catch (Throwable t2) {} }
            return true;
        }
        // v1.0.7 §14: >上限通用群规消息前缀, 单值原样存储/发送 (不随机)。
        if (content.startsWith("红包群规前缀")) {
            String v = content.substring("红包群规前缀".length()).trim();
            try { if (!v.isEmpty()) { putString(K_RULE_PREFIX, v); toast("✅ 群规前缀(原样发送): " + v); } else toast("❌ 格式: 红包群规前缀 领红包请遵守群规执行"); } catch (Throwable t) {}
            return true;
        }
        if (content.startsWith("红包延迟")) {
            String v = content.substring("红包延迟".length()).trim();
            try {
                if (v.isEmpty()) {
                    toast("当前延迟(秒): 首次=" + cfgDelay() + " 重试1=" + cfgRetry1() + " 重试2=" + cfgRetry2() + " 重试3=" + cfgRetry3());
                } else {
                    String[] parts = v.split("\\s+");
                    if (parts.length == 1) {
                        long s = Long.parseLong(parts[0]); putString(K_DELAY, String.valueOf(s));
                        toast("✅ 首次开页延迟: " + s + "秒");
                    } else if (parts.length >= 4) {
                        // v1.10.0 §4: 四参 = 首次/重试1/重试2/重试3 (秒)
                        long s0 = Long.parseLong(parts[0]); long s1 = Long.parseLong(parts[1]); long s2 = Long.parseLong(parts[2]); long s3 = Long.parseLong(parts[3]);
                        putString(K_DELAY, String.valueOf(s0)); putString(K_RETRY1, String.valueOf(s1)); putString(K_RETRY2, String.valueOf(s2)); putString(K_RETRY3, String.valueOf(s3));
                        toast("✅ 延迟(秒): 首次=" + s0 + " 重试1=" + s1 + " 重试2=" + s2 + " 重试3=" + s3);
                    } else if (parts.length == 3) {
                        long s0 = Long.parseLong(parts[0]); long s1 = Long.parseLong(parts[1]); long s2 = Long.parseLong(parts[2]);
                        putString(K_DELAY, String.valueOf(s0)); putString(K_RETRY1, String.valueOf(s1)); putString(K_RETRY2, String.valueOf(s2));
                        toast("✅ 延迟(秒): 首次=" + s0 + " 重试1=" + s1 + " 重试2=" + s2);
                    } else {
                        toast("❌ 格式: 红包延迟 120 (秒) 或 120 300 600 (首次/重试1/重试2) 或 120 300 600 3600 (含重试3 秒)");
                    }
                }
            }
            catch (Exception e) { try { toast("❌ 格式: 红包延迟 120 (秒) 或 120 300 600 (首次/重试1/重试2) 或 120 300 600 3600 (含重试3 秒)"); } catch (Throwable t) {} }
            return true;
        }
        if (content.startsWith("红包at上限")) {
            String v = content.substring("红包at上限".length()).trim();
            try { int n = Integer.parseInt(v); putString(K_AT_LIMIT, String.valueOf(n)); toast("✅ 达标总人数上限: " + n + " (超过则改发无@群规消息)"); }
            catch (Exception e) { try { toast("❌ 格式: 红包at上限 20"); } catch (Throwable t) {} }
            return true;
        }
        // v1.11.0 §25: 伸手党治理 (作用当前群)。注意 "伸手党窗口" 必须先于 "伸手党" 判定 (前缀包含关系)。
        if (content.startsWith("伸手党窗口")) {
            String groupId = rpCurrentGroupOrToast();
            if (groupId == null) return true;
            String v = content.substring("伸手党窗口".length()).trim();
            try {
                int n = Integer.parseInt(v);
                if (n < 1 || n > 1440) { toast("❌ 窗口需 1-1440 分钟"); return true; }
                putString(K_FREELOADER_WIN_PREFIX + groupId, String.valueOf(n));
                toast("✅ 本群伸手党窗口: " + n + " 分钟 (抢到前此窗口内未发言 → 警告)");
            } catch (Exception e) { try { toast("❌ 格式: 伸手党窗口 30 (分钟, 1-1440)"); } catch (Throwable t) {} }
            return true;
        }
        if (content.equals("伸手党 开") || content.equals("伸手党开")) {
            String groupId = rpCurrentGroupOrToast();
            if (groupId == null) return true;
            try { putString(K_FREELOADER_ON_PREFIX + groupId, "1"); toast("✅ 已开启本群伸手党治理 (抢到前" + cfgFreeloaderWin(groupId) + "分钟未发言 → 发群管警告)"); } catch (Throwable t) {}
            return true;
        }
        if (content.equals("伸手党 关") || content.equals("伸手党关")) {
            String groupId = rpCurrentGroupOrToast();
            if (groupId == null) return true;
            try { putString(K_FREELOADER_ON_PREFIX + groupId, ""); toast("🔕 已关闭本群伸手党治理"); } catch (Throwable t) {}
            return true;
        }
        if (content.equals("伸手党")) {
            String groupId = rpCurrentGroupOrToast();
            if (groupId == null) return true;
            try { toast("🖐 本群伸手党治理: " + (cfgFreeloaderOn(groupId) ? "✅ 开" : "🔴 关") + "\n窗口: " + cfgFreeloaderWin(groupId) + " 分钟\n命令: 伸手党 开/关 | 伸手党窗口 N"); } catch (Throwable t) {}
            return true;
        }
    } catch (Throwable t) {
        try { log("RedPacketStats onClickSendBtn err: " + t); } catch (Throwable t2) {}
    }
    return false;   // 非配置命令: 正常发送, 不影响普通聊天/红包
}

// 当前会话 talker (群=@chatroom; 非群聊返回原值)。仿 GroupAdmin getTargetTalker()。
String rpCurrentTalker() {
    try {
        String t = getTargetTalker();
        if (t != null && !t.isEmpty()) return normGroupId(t);
    } catch (Throwable t) {}
    return null;
}

// 取当前群 id; 不在群聊里则 toast 提示并返回 null (调用方据此放弃, 但仍 return true 不发群)。
String rpCurrentGroupOrToast() {
    String t = rpCurrentTalker();
    if (t == null || t.indexOf("@chatroom") < 0) {
        try { toast("请在目标群里发该命令"); } catch (Throwable t2) {}
        return null;
    }
    return t;
}

String rpStatusText(String groupId) {
    StringBuilder sb = new StringBuilder();
    sb.append("📊 红包统计 v1.11.2\n");
    sb.append("本群: ").append(isGroupEnabled(groupId) ? "✅ 已启用" : "🔴 未启用").append("\n");
    sb.append("本群每日定时: ").append(isDailyEnabled(groupId) ? ("✅ 开 (每天" + cfgDailyHour() + "点)") : "🔴 关").append("\n");
    // v1.6.0: 每群独立 1-4 档 (未配置则回退全局默认三档)。
    List _tiers = getTiers(groupId);
    boolean _custom = false;
    try { String _raw = getString(tiersKey(groupId), ""); _custom = (_raw != null && !_raw.trim().isEmpty()); } catch (Throwable t) {}
    sb.append("本群档位 (").append(_tiers.size()).append("档").append(_custom ? "" : ",全局默认").append("): ").append(rpTiersSummary(_tiers)).append("\n");
    // v1.7.0 §22.4: 定制包维度状态。
    boolean _customOn = isCustomOn(groupId);
    sb.append("定制开关: ").append(_customOn ? "✅ 开" : "🔴 关 (仅 Dialog 可改)").append("\n");
    String _kw = getCustomKw(groupId);
    sb.append("定制关键字: ").append((_kw == null || _kw.isEmpty()) ? "(未设)" : _kw).append("\n");
    boolean _customTiersSet = false;
    try { String _rawc = getString(tiersCustomKey(groupId), ""); _customTiersSet = (_rawc != null && !_rawc.trim().isEmpty()); } catch (Throwable t) {}
    List _ct = getTiersByType(groupId, true);
    sb.append("定制档位 (").append(_ct.size()).append("档").append(_customTiersSet ? "" : ",回退普通").append("): ").append(rpTiersSummary(_ct)).append("\n");
    // v1.7.1 §22.6: 定制包第一条群规前缀。
    sb.append("定制群规前缀: ").append(cfgCustomRulePrefix(groupId)).append("\n");
    sb.append("命令: 红包阈值 a b.. / 红包文案K 文字 / 红包增加档 阈值 动作 / 红包减少档 / 定制阈值.. / 定制文案K.. / 定制增加档.. / 定制减少档 / 定制关键字 <kw> / 定制群规前缀 <文字>\n");
    sb.append("延迟: 首次:").append(cfgDelay()).append("秒 重试1:").append(cfgRetry1()).append("秒 重试2:").append(cfgRetry2()).append("秒 重试3:").append(cfgRetry3()).append("秒 (±抖动)\n");
    sb.append("at上限: ").append(cfgAtLimit()).append(" (超过则发无@群规消息)\n");
    // v1.11.0 §25: 伸手党治理 (按群)。
    sb.append("伸手党治理: ").append(cfgFreeloaderOn(groupId) ? ("✅ 开 (抢到前" + cfgFreeloaderWin(groupId) + "分钟未发言→警告)") : "🔴 关").append("\n");
    sb.append("同群限频: ").append(cfgMsgGap()).append("秒\n");
    sb.append("本群排除名单: ").append(getExcludeList(groupId).size()).append(" 人\n");
    // v1.9.1 §24.5: 转发对象 (按群): 有群上下文显示该群目标 (未设回退全局默认), 无群上下文显示全局默认。
    sb.append("转发对象: ");
    boolean _hasGrp = (normGroupId(groupId).length() > 0);
    String et = _hasGrp ? cfgExportTargetFor(groupId) : cfgExportTarget();
    String etn = _hasGrp ? cfgExportTargetNameFor(groupId) : cfgExportTargetName();
    sb.append((etn != null && !etn.isEmpty()) ? etn : "(未命名)");
    sb.append(rpTargetIsGroup(et) ? "（群）" : "（好友）");
    sb.append("尾4: ");
    sb.append((et != null && et.length() >= 4) ? et.substring(et.length() - 4) : et);
    if (!_hasGrp) sb.append(" (全局默认)");
    return sb.toString();
}

// ================================================================
// ============ v1.1.0 §15: 队列辅助 + 单工作者 tick ============
// ================================================================
// 当前 Job 的去重 key (nativeurl)。CUR_JOB 为空返回 null。
String curKey() {
    try { Object[] j = CUR_JOB; if (j == null) return null; return (String) j[JOB_KEY]; } catch (Throwable t) { return null; }
}
int rpQueueSize() { synchronized (RP_QUEUE) { return RP_QUEUE.size(); } }

// 入队 (§15.2): 按 key 去重 (在队/在飞/已完成都不重入); 满 RP_QUEUE_MAX 丢最旧。
void rpEnqueue(Object[] job) {
    try {
        if (job == null) return;
        String key = (String) job[JOB_KEY];
        synchronized (RP_QUEUE) {
            // 去重: 已在队
            for (int i = 0; i < RP_QUEUE.size(); i++) {
                Object[] q = (Object[]) RP_QUEUE.get(i);
                if (q != null && key != null && key.equals((String) q[JOB_KEY])) {
                    hbBgLog("[RP] " + hbNow() + " enqueue dedup: key already queued, skip.");
                    return;
                }
            }
            // 去重: 在飞
            try { if (IN_FLIGHT && key != null && key.equals(curKey())) { hbBgLog("[RP] " + hbNow() + " enqueue dedup: key in-flight, skip."); return; } } catch (Throwable t) {}
            // 去重: 已完成
            try { if (key != null && DONE.contains(key)) { hbBgLog("[RP] " + hbNow() + " enqueue dedup: key already done, skip."); return; } } catch (Throwable t) {}
            // 上限: 丢最旧
            if (RP_QUEUE.size() >= RP_QUEUE_MAX) {
                try { RP_QUEUE.remove(0); } catch (Throwable t) {}
                hbBgLog("[RP] " + hbNow() + " enqueue: queue full (max=" + RP_QUEUE_MAX + "), dropped oldest job.");
            }
            RP_QUEUE.add(job);
        }
    } catch (Throwable t) { hbBgLog("[RP] " + hbNow() + " enqueue err: " + t); }
}

// 单工作者 tick (§15.3): 看门狗 → 单飞 return → 否则挑最早到期 job 开跑。末尾必自重调度 (finally)。
void rpWorkerTick() {
    try {
        long now = System.currentTimeMillis();
        // v1.4.0 §20: 每日定时检查 (抗重启, 不用精确定时器)。整段独立 try/catch, 异常不影响红包流水线。
        try { rpDailyCheck(); } catch (Throwable t) { hbBgLog("[RP] " + hbNow() + " daily-check err: " + t); }
        // v1.4.0: perf 聚合落盘 (worker 必跑, 借此定时 flush; 有样本才写)。独立 try/catch。
        try { if (PERF_N >= (long) PERF_FLUSH_EVERY) perfFlush(); } catch (Throwable t) {}
        // 1) 看门狗: 在飞超时强清
        if (IN_FLIGHT && (now - inflightStartMs) > RP_WATCHDOG_MS) {
            hbBgLog("[RP] " + hbNow() + " WATCHDOG: in-flight " + ((now - inflightStartMs) / 1000L) + "s > " + (RP_WATCHDOG_MS / 1000L) + "s. force-clear in-flight (key=" + curKey() + ").");
            try { SEEN.clear(); } catch (Throwable t) {}
            IN_FLIGHT = false; CUR_JOB = null;
        }
        // 2) 单飞: 有在飞则等
        if (IN_FLIGHT) return;
        // 3) 挑 dueAt<=now 最早的 job 出队
        Object[] picked = null;
        synchronized (RP_QUEUE) {
            int bestIdx = -1; long bestDue = Long.MAX_VALUE;
            for (int i = 0; i < RP_QUEUE.size(); i++) {
                Object[] q = (Object[]) RP_QUEUE.get(i);
                if (q == null) continue;
                long due = ((Long) q[JOB_DUEAT]).longValue();
                if (due <= now && due < bestDue) { bestDue = due; bestIdx = i; }
            }
            if (bestIdx >= 0) { picked = (Object[]) RP_QUEUE.get(bestIdx); RP_QUEUE.remove(bestIdx); }
        }
        if (picked == null) return;   // 无到期 job, 等下个 tick
        // 出队成功 → 进入在飞
        IN_FLIGHT = true; inflightStartMs = System.currentTimeMillis(); CUR_JOB = picked;
        try { SEEN.clear(); } catch (Throwable t) {}   // 新 job 开始, 清上一 job 残留页面去重 (单飞下安全)
        int attempt = ((Integer) picked[JOB_ATTEMPT]).intValue();
        hbBgLog("[RP] " + hbNow() + " worker: pick job (attempt=" + attempt + ", queue left=" + rpQueueSize() + ") -> hbProcess.");
        try { hbProcess(picked); }
        catch (Throwable t) {
            hbBgLog("[RP] " + hbNow() + " worker hbProcess err: " + t + ". clear in-flight.");
            IN_FLIGHT = false; CUR_JOB = null;
        }
    } catch (Throwable t) {
        hbBgLog("[RP] " + hbNow() + " worker tick err: " + t);
    } finally {
        // §15.3: 任何情况下必重调度, 工作者绝不停摆。
        try { delay(RP_TICK_MS, new Runnable() { public void run() { rpWorkerTick(); } }); } catch (Throwable t) {}
    }
}

// v1.1.0 §15.5: 统一清在飞 (流水线退出点调用)。可选重新入队 (没领完重试)。
void rpClearInFlight() { IN_FLIGHT = false; CUR_JOB = null; }

// v1.1.2 §16: 发提醒拆两条 (引用条 + @条)。实测结论(2026-06-05, 用户真机):
//   · sendQuoteMsg(talker, 本地msgId(msg.getMsgId(), 小数字), content) 能成功引用原红包消息;
//     但 content 里的 [AtWx=wxid] 在引用消息里**不会被解析成真@**, 被@者收不到提醒。
//   · sendText(talker, "[AtWx=wxid] ...") 的 @ 真生效, 被@者收到提醒。
//   → 引用 与 @ 不能一条兼得, 故拆两条: 引用条放无@群规文本; @条 sendText 真@到人。
// 引用条: sendQuoteMsg(talker, msgid, 群规文本) 引用原红包; msgid<=0 或抛异常 → 回退 sendText(不带引用的同一群规文本), 绝不漏发。
void rpSendQuote(String talker, long msgid, String text) {
    if (talker == null || text == null || text.length() == 0) return;
    // 优先引用原红包 (纯引用, 文本内本就无 @)。msgid 有效才能引用。
    if (msgid > 0L) {
        try {
            sendQuoteMsg(talker, msgid, text);
            hbBgLog("[RP] " + hbNow() + " rpSendQuote: sendQuoteMsg ok (quoted original redpacket, no @).");
            return;
        } catch (Throwable t) {
            hbBgLog("[RP] " + hbNow() + " rpSendQuote: sendQuoteMsg failed (" + t + "), fallback sendText (no quote).");
        }
    } else {
        hbBgLog("[RP] " + hbNow() + " rpSendQuote: msgid<=0, use sendText (no valid msgid to quote).");
    }
    // 回退: 引用不可用/失败 → 普通发送同一群规文本 (绝不因引用失败漏发)。
    try { sendText(talker, text); } catch (Throwable t) { hbBgLog("[RP] " + hbNow() + " rpSendQuote fallback sendText err: " + t); }
}

// v1.1.2 §16: @条 — 真@到人 (sendText 解析 [AtWx=], 实测 @ 真生效)。不引用 (引用条内 @ 无效, 故拆两条)。
void rpSendAt(String talker, String text) {
    if (talker == null || text == null || text.length() == 0) return;
    try {
        sendText(talker, text);
        hbBgLog("[RP] " + hbNow() + " rpSendAt: sendText ok ([AtWx=] @ should land).");
    } catch (Throwable t) {
        hbBgLog("[RP] " + hbNow() + " rpSendAt: sendText err: " + t);
    }
}

// ================================================================
// ============ hbProcess: 开封面页 (一次尝试) ============
// ================================================================
void hbProcess(Object[] job) {
    try {
        if (job == null) { hbBgLog("[RP] " + hbNow() + " hbProcess: job null, skip. clear in-flight."); rpClearInFlight(); return; }
        int attempt = ((Integer) job[JOB_ATTEMPT]).intValue();
        String talker = (String) job[JOB_TALKER];
        String key = (String) job[JOB_KEY];
        String content = (String) job[JOB_CONTENT];
        hbBgLog("[RP] " + hbNow() + " hbProcess attempt=" + attempt + " -> (worker-side parse) opening cover " + UI_RECEIVE + ".");

        // ---- v1.6.3 (C-PERF-03): 原热路径"重活"在此后台 worker 线程执行 (逻辑不变, 只换线程/时机)。----
        //   出队后才解析: 专属判定(early-exit) → nativeurl(开页用) → title(写库用, 回填本 job)。
        //   只在首跑(attempt==0)解析专属/title (重试时 job 已带回填的 title; 专属在首跑已被早退, 不会有重试)。
        String nativeurl = null;
        if (content != null) {
            // (a) 专属红包早退: wcpayinfo.exclusive_recv_username 非空 = 指定接收人 → 跳过(不开页/不排程), 与改前行为一致。
            //     只在首跑判 (attempt==0); 专属一旦命中即 return, 不会进入重试路径, 故重试时无需重判。
            if (attempt == 0) {
                String exclusiveRecv = null;
                try { exclusiveRecv = hbGetElement(content, "wcpayinfo", "exclusive_recv_username"); } catch (Throwable t) { exclusiveRecv = null; }
                if (exclusiveRecv != null && !exclusiveRecv.trim().isEmpty()) {
                    hbBgLog("[RP] " + hbNow() + " skip exclusive (exclusive_recv_username present, worker-side). not opening. clear in-flight.");
                    if (key != null) DONE.add(key);   // 专属: 标记完成, 避免再次入队
                    SEEN.clear();
                    rpClearInFlight();
                    return;
                }
            }
            // (b) nativeurl: 开封面页需要 (原热路径解析, 现移此)。
            try { nativeurl = hbGetElement(content, "wcpayinfo", "nativeurl"); } catch (Throwable t) { nativeurl = null; }
            if (nativeurl != null && nativeurl.length() == 0) nativeurl = null;
            // (c) title: rpExtractTitle (原占 ~50ms 热路径 spike, 现在后台)。回填本 job[JOB_TITLE] 供 rpRecordStats 读。
            //     首跑解析并回填; 重试时 job[JOB_TITLE] 已是首跑回填值, 不重复 parse。
            if (attempt == 0) {
                long _exStart = 0L; boolean _exOn = false;
                try { _exStart = System.nanoTime(); _exOn = true; } catch (Throwable t) { _exOn = false; }
                String hbTitle = null;
                try { hbTitle = rpExtractTitle(content); } catch (Throwable t) { hbTitle = null; }
                if (_exOn) {
                    try {
                        long exDur = System.nanoTime() - _exStart;
                        PERF_EXTRACT_N++;
                        PERF_EXTRACT_SUM_NS += exDur;
                        if (exDur > PERF_EXTRACT_MAX_NS) PERF_EXTRACT_MAX_NS = exDur;
                    } catch (Throwable t) {}
                }
                try { job[JOB_TITLE] = (hbTitle == null) ? "" : hbTitle; } catch (Throwable t) {}
                // v1.7.0 §22.2 (v1.7.1): 包类型判定 (worker 内, 零热路径)。该群定制关 → 普通; 否则 title 包含 kw → 定制。
                //   回填本 job[JOB_ISCUSTOM] 供 hbDetailExtract 透传给 hbTierAndSend。整段 try/catch, 异常退回普通(FALSE)。
                try {
                    boolean isCustom = rpIsCustom(talker, (String) job[JOB_TITLE]);
                    job[JOB_ISCUSTOM] = isCustom ? Boolean.TRUE : Boolean.FALSE;
                    hbBgLog("[RP] " + hbNow() + " pkg-type: " + (isCustom ? "custom" : "normal") + " (custom_on=" + isCustomOn(talker) + ", kwLen=" + getCustomKw(talker).length() + ", titleLen=" + ((String) job[JOB_TITLE]).length() + ").");
                } catch (Throwable t) { try { job[JOB_ISCUSTOM] = Boolean.FALSE; } catch (Throwable t2) {} }
            }
        }

        Object hostCtx = null;
        try { hostCtx = hostContext; } catch (Throwable t) { hostCtx = null; }
        if (hostCtx == null) {
            try { Activity top = getTopActivity(); if (top != null) hostCtx = top.getApplicationContext(); } catch (Throwable t) {}
        }
        if (hostCtx == null) { hbBgLog("[RP] " + hbNow() + " ABORT: no hostContext. clear in-flight."); rpClearInFlight(); return; }

        Intent intent = new Intent();
        if (nativeurl != null) intent.putExtra("key_native_url", nativeurl);
        if (talker != null) intent.putExtra("key_username", talker);
        intent.putExtra("key_is_self_sent", false);

        startLuckyMoneyReceive(hostCtx, intent);
        // 成功开页后, 在飞由 onResume 链路(封面/详情→各退出点)负责清; 若版本漂移开页失败, 看门狗 30s 兜底强清。
    } catch (Throwable t) {
        try { log("RedPacketStats hbProcess err: " + t); } catch (Throwable t2) {}
        hbBgLog("[RP] " + hbNow() + " hbProcess err: " + t + ". clear in-flight.");
        rpClearInFlight();
    }
}

// ============ 启动器 (源: 自动抢红包) — 目标 NewReceiveUI ============
void startLuckyMoneyReceive(Object hostCtx, Intent intent) {
    boolean success = false;
    String landedOn = "?";
    try {
        String[] possibleClasses = {"nk4.l", "oq4.l", "pn4.l", "qm4.l", "rm4.l", "sm4.l", "tm4.l", "um4.l", "vm4.l", "wl4.l"};
        String[] possibleMethods = {"A", "B", "C", "D"};
        String activity = ".ui.LuckyMoneyNewReceiveUI";

        for (int ci = 0; ci < possibleClasses.length && !success; ci++) {
            String className = possibleClasses[ci];
            for (int mi = 0; mi < possibleMethods.length && !success; mi++) {
                String methodName = possibleMethods[mi];
                try {
                    Class clazz = XposedHelpers.findClass(className, hostCtx.getClassLoader());
                    XposedHelpers.callStaticMethod(clazz, methodName, hostCtx, "luckymoney", activity, intent);
                    success = true;
                    landedOn = className + "." + methodName;
                } catch (Throwable ignored) {}
            }
        }
        if (!success) {
            try {
                String pkg = hostCtx.getPackageName();
                intent.setClassName(pkg, "com.tencent.mm.plugin.luckymoney" + activity);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                hostCtx.startActivity(intent);
                success = true;
                landedOn = "fallback startActivity";
            } catch (Throwable ignored2) {}
        }
        if (!success) hbBgLog("[RP] " + hbNow() + " OPEN FAIL: launcher + fallback both failed (version drift?).");
        else hbBgLog("[RP] " + hbNow() + " OPEN OK via " + landedOn + " -> " + UI_RECEIVE + ".onResume will route.");
    } catch (Throwable t) {
        hbBgLog("[RP] " + hbNow() + " startLuckyMoneyReceive err: " + t);
    }
}

// v1.3.0 §17.3: 从红包消息 wcpayinfo 提取标题/祝福语 (只在红包命中时调用, 低频; 不碰普通消息热路径)。
// 候选字段取首个非空: sendertitle → receivertitle → scenetext → des。全部取不到返回空串。
// 存疑: 不同微信版本字段可能不同, 到底哪个是用户可见祝福语需真机确认 (rpRecordStats 写库时会 hbBgLog usedField/len 嗅探, 见 SPEC §17.3)。
String rpExtractTitle(String content) {
    try {
        if (content == null) return "";
        String[] cands = new String[]{ "sendertitle", "receivertitle", "scenetext", "des" };
        for (int i = 0; i < cands.length; i++) {
            String v = null;
            try { v = hbGetElement(content, "wcpayinfo", cands[i]); } catch (Throwable t) { v = null; }
            if (v != null) {
                String s = v.trim();
                if (!s.isEmpty()) {
                    // 嗅探日志① (字段来源): 只记字段名 + 长度, 不打标题原文 (脱敏)。真机发个红包即知标题取自哪个字段。
                    hbBgLog("[RP] " + hbNow() + " stats title: usedField=" + cands[i] + " len=" + s.length());
                    return s;
                }
            }
        }
        // 全部字段空: 嗅探日志① 记 none (真机若总是 none, 说明该版本字段名都不对, 需另找)。
        hbBgLog("[RP] " + hbNow() + " stats title: usedField=none len=0");
    } catch (Throwable t) {}
    return "";
}

// 轻量 XML 取值 (源: 自动抢红包), 仅红包命中时调用一次
String hbGetElement(String xmlString, String elementName, String tagName) {
    try {
        if (xmlString == null) return null;
        javax.xml.parsers.DocumentBuilderFactory factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
        javax.xml.parsers.DocumentBuilder builder = factory.newDocumentBuilder();
        org.xml.sax.InputSource is = new org.xml.sax.InputSource(
            new java.io.StringReader(xmlString.replaceAll("^[^:]+:\n", "")));
        org.w3c.dom.Document document = builder.parse(is);
        org.w3c.dom.NodeList list = document.getElementsByTagName(elementName);
        if (list.getLength() > 0) {
            org.w3c.dom.Node parent = list.item(0);
            org.w3c.dom.NodeList children = parent.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                org.w3c.dom.Node child = children.item(i);
                if (child.getNodeName().equalsIgnoreCase(tagName)) return child.getTextContent();
            }
        }
    } catch (Throwable t) { return null; }
    return null;
}

// ================================================================
// ============ onResume 路由: NewReceiveUI / NewDetailUI ============
// ================================================================
void hbOnActivityResume(Object thisObject) {
    try {
        if (thisObject == null) return;
        final Activity act = (Activity) thisObject;
        String cn = act.getClass().getName();
        if (cn == null) return;
        boolean isReceive = cn.indexOf(UI_RECEIVE) >= 0;
        boolean isDetail  = cn.indexOf(UI_DETAIL) >= 0;
        if (!isReceive && !isDetail) return;

        // 只处理 bot 自己正在跟的红包 (CUR_JOB 非空说明工作者刚开了页; 单飞下只一个)
        String curKey = curKey();
        if (CUR_JOB == null || curKey == null) return;

        Integer idHash = Integer.valueOf(System.identityHashCode(act));
        if (!SEEN.add(idHash)) return;
        final String fcn = cn;

        if (isReceive) {
            COVER_ACT.put(curKey, idHash);
            final int clickDelay = (int) hbJitter((long) RECEIVE_CLICK_DELAY_BASE_MS, (long) RECEIVE_CLICK_DELAY_SPAN_MS);
            hbBgLog("[RP] " + hbNow() + " HIT " + fcn + " (cover). schedule UI find detail-entry in " + clickDelay + "ms (jittered).");
            delay(clickDelay, new Runnable() {
                public void run() {
                    try { hbReceiveDecide(act); }
                    catch (Throwable t) { hbBgLog("[RP] " + hbNow() + " receive-decide err: " + t); }
                }
            });
            return;
        }

        // NewDetailUI: 后台反射读明细
        final String fKey = curKey;
        final int reflectDelay = (int) hbJitter((long) REFLECT_DELAY_BASE_MS, (long) REFLECT_DELAY_SPAN_MS);
        hbBgLog("[RP] " + hbNow() + " HIT " + fcn + " (detail). schedule bg-reflect in " + reflectDelay + "ms (jittered).");
        delay(reflectDelay, new Runnable() {
            public void run() {
                try {
                    new Thread() {
                        public void run() {
                            try { hbDetailExtract(act, fKey); }
                            catch (Throwable t) { hbBgLog("[RP] " + hbNow() + " detail extract bg err: " + t); }
                        }
                    }.start();
                } catch (Throwable t) { hbBgLog("[RP] " + hbNow() + " detail reflect schedule err: " + t); }
            }
        });
    } catch (Throwable t) {
        try { log("RedPacketStats onResume route err: " + t); } catch (Throwable t2) {}
    }
}

// ================================================================
// ============ 封面页判定: 领完→点详情 / 没领完→关页+重试 ============
// ================================================================
// UI 线程, 一次性有界 BFS。找"看看大家的手气"/"查看领取详情" 且【可点击】=已领完。
// 找不到可点入口 = 没领完: 绝不点领取按钮(不抢), finish 封面, 按 attempt 重试或放弃。
void hbReceiveDecide(Activity act) {
    int[] nodes = new int[]{0};
    View hitView = null;
    String hitText = null;
    try {
        View root = null;
        try { root = act.getWindow().getDecorView(); } catch (Throwable t) { root = null; }
        if (root == null) { hbBgLog("[RP] " + hbNow() + " receive-decide: no DecorView."); afterCoverNoDetail(); return; }

        java.util.ArrayDeque queue = new java.util.ArrayDeque();
        queue.add(new Object[]{ root, Integer.valueOf(0) });

        while (!queue.isEmpty()) {
            if (nodes[0] >= CLICK_MAX_NODES) break;
            Object[] entry = (Object[]) queue.poll();
            View v = (View) entry[0];
            int depth = ((Integer) entry[1]).intValue();
            if (v == null) continue;
            nodes[0]++;

            String text = null;
            if (v instanceof TextView) {
                try { CharSequence cs = ((TextView) v).getText(); if (cs != null) text = cs.toString(); } catch (Throwable t) { text = null; }
            }
            if (text != null && text.length() > 0) {
                boolean matchDetail = false;
                for (int i = 0; i < DETAIL_TEXTS.length; i++) { if (text.indexOf(DETAIL_TEXTS[i]) >= 0) { matchDetail = true; break; } }
                if (matchDetail) {
                    // 该文本本身或最近可点祖先可点 → 视为"已领完, 有详情入口"
                    View target = findClickableSelfOrAncestor(v);
                    if (target != null) { hitView = target; hitText = text; break; }
                    // 文案在但不可点 → 继续找(也可能别处有可点入口)
                }
            }
            if (depth < CLICK_MAX_DEPTH && v instanceof ViewGroup) {
                ViewGroup vg = (ViewGroup) v;
                int cc = 0;
                try { cc = vg.getChildCount(); } catch (Throwable t) { cc = 0; }
                for (int i = 0; i < cc; i++) {
                    if (nodes[0] >= CLICK_MAX_NODES) break;
                    View child = null;
                    try { child = vg.getChildAt(i); } catch (Throwable t) { child = null; }
                    if (child != null) queue.add(new Object[]{ child, Integer.valueOf(depth + 1) });
                }
            }
        }

        if (hitView == null) {
            // 没找到可点的详情入口 = 没领完。绝不点领取/不抢。
            hbBgLog("[RP] " + hbNow() + " receive-decide: NO clickable detail-entry (not finished, nodes=" + nodes[0] + "). will NOT grab; finish + retry/give-up.");
            afterCoverNoDetail();
            return;
        }

        // 已找到可点 detail 入口 = 已领完。按入口文字区分拼手气/普通:
        //   含"手气"(看看大家的手气) → 拼手气 → 照旧点进详情(反射→分档→@)。
        //   不含"手气"(查看领取详情) → 普通红包 → 不点击、关封面、清状态、跳过, 不开详情/不发消息。
        // (反射后"金额全等→跳过"仍作兜底, 见 hbDetailExtract。)
        boolean isLucky = (hitText != null && hitText.indexOf("手气") >= 0);
        if (!isLucky) {
            hbBgLog("[RP] " + hbNow() + " receive-decide: skip normal (cover='" + hbTextSkeleton(hitText)
                + "') -> NOT clicking; finish cover + drop (nodes=" + nodes[0] + ").");
            afterCoverNormalSkip();
            return;
        }

        boolean clicked = false;
        try { clicked = hitView.performClick(); } catch (Throwable t) { clicked = false; }
        hbBgLog("[RP] " + hbNow() + " receive-decide: finished -> clicked detail-entry '" + hbTextSkeleton(hitText)
            + "' clicked=" + clicked + " (nodes=" + nodes[0] + "). NewDetailUI.onResume will follow.");
        // 不在此 finish 封面: 等详情页提取成功后连封面一起 finish (COVER_ACT)。
    } catch (Throwable t) {
        hbBgLog("[RP] " + hbNow() + " receive-decide err: " + t + " (nodes=" + nodes[0] + ").");
        afterCoverNoDetail();
    }
}

// 普通红包(封面入口"查看领取详情", 不含手气): 已领完, 但不需开详情/不发消息。
// finish 封面 + 清状态/去重, 直接结束本红包流转。不重试(已领完, 不是没领完)。
// v1.1.0 §15.5: 这是流水线退出点 → 必清在飞。
void afterCoverNormalSkip() {
    Object[] job = CUR_JOB;
    final String key = (job != null) ? (String) job[JOB_KEY] : null;
    finishActivityByHash(COVER_ACT.get(key));
    if (key != null) COVER_ACT.remove(key);
    if (key != null) DONE.add(key);   // 普通红包: 标记完成, 避免再次入队
    SEEN.clear(); // 本红包流转结束, 清 Activity 去重防内存增长
    rpClearInFlight();
    hbBgLog("[RP] " + hbNow() + " skip normal: cover finished, in-flight cleared, no detail/no msg.");
}

// 没领完: finish 封面 + 按 attempt 重试(重新入队)或放弃。
// v1.1.0 §15.5: 重试不再独立 delay → job 重新入队(dueAt=now+重试间隔抖动, attempt+1) + 清在飞; attempt≥2 放弃也清在飞。
void afterCoverNoDetail() {
    Object[] job = CUR_JOB;
    final String key = (job != null) ? (String) job[JOB_KEY] : null;
    // 先 finish 封面页 (UI 线程)
    finishActivityByHash(COVER_ACT.get(key));
    if (key != null) COVER_ACT.remove(key);

    if (job == null) { SEEN.clear(); rpClearInFlight(); return; }
    int attempt = ((Integer) job[JOB_ATTEMPT]).intValue();

    if (attempt >= 3) {
        // v1.10.0 §4/§15.5: attempt3 (1 小时后) 还没领完 → 放弃 (原 attempt2 10 分钟后放弃, 末档后移一档)
        if (key != null) DONE.add(key);   // 放弃: 标记完成, 避免再次入队
        SEEN.clear(); // 该红包流转结束, 清 Activity 去重防内存增长 (下一个红包重新建)
        rpClearInFlight();
        hbBgLog("[RP] " + hbNow() + " GIVE UP: attempt=" + attempt + " still not finished. drop this redpacket. in-flight cleared.");
        return;
    }

    final int nextAttempt = attempt + 1;
    // v1.10.0 §4: 三段重试阶梯 attempt0→retry1, attempt1→retry2, attempt2→retry3(末档默认1小时)。
    long retrySec = (attempt == 0) ? cfgRetry1() : ((attempt == 1) ? cfgRetry2() : cfgRetry3());
    // v1.0.6 §13.1: 重试间隔 ±25% 抖动 (base*0.75 ~ base*1.25)。long 乘防溢出。
    long retryMs = hbJitter((long) (retrySec * 1000L * 0.75), (long) (retrySec * 1000L * 0.5));
    long dueAt = System.currentTimeMillis() + retryMs;
    // §15.5: job 重新入队 (新 dueAt + attempt+1); 复用 talker/sender/msgid/title。v1.3.0: 7 元组(含 title)。
    Object[] reJob = new Object[]{ job[JOB_KEY], job[JOB_TALKER], job[JOB_SENDER], job[JOB_MSGID], Long.valueOf(dueAt), Integer.valueOf(nextAttempt), job[JOB_TITLE], job[JOB_CONTENT], job[JOB_ISCUSTOM], job[JOB_DETECTMS] };
    SEEN.clear(); // 清 Activity 去重, 下次重开封面是新实例
    rpClearInFlight();   // 先清在飞, 否则 rpEnqueue 会因"在飞"去重而拒绝重入
    rpEnqueue(reJob);
    hbBgLog("[RP] " + hbNow() + " not finished at attempt=" + attempt + ". RE-ENQUEUE retry in " + (retryMs / 1000L) + "s (cfg=" + retrySec + "s, jittered, attempt=" + nextAttempt + "). in-flight cleared, queue size=" + rpQueueSize() + ".");
}

View findClickableSelfOrAncestor(View v) {
    try {
        if (v == null) return null;
        if (v.isClickable()) return v;
        Object p = v.getParent();
        int up = 0;
        while (p != null && up < CLICK_MAX_DEPTH) {
            if (p instanceof View) {
                View pv = (View) p;
                if (pv.isClickable()) return pv;
                p = pv.getParent();
            } else break;
            up++;
        }
    } catch (Throwable t) {}
    return null;
}

// finish 一个 Activity (按 identityHashCode 在当前 task 找) — 简化: 直接 finish topActivity 若 hash 匹配,
// 否则 finish getTopActivity (bot 自己开的页一般就是前台)。UI 线程, try/catch。
void finishActivityByHash(Object hashObj) {
    try {
        final Activity top = getTopActivity();
        if (top == null) return;
        // v1.0.6 §13.5: finish 精确 — 仅当前台 Activity 类名含 luckymoney(bot 开的红包页)才 finish,
        //              否则不 finish(避免误关用户正在看的非红包页面)。
        if (!hbIsLuckyMoneyTop(top)) {
            hbBgLog("[RP] " + hbNow() + " finish skipped: top activity not luckymoney (avoid closing user's page).");
            return;
        }
        top.runOnUiThread(new Runnable() {
            public void run() {
                try { top.finish(); } catch (Throwable t) { hbBgLog("[RP] " + hbNow() + " finish err: " + t); }
            }
        });
    } catch (Throwable t) { hbBgLog("[RP] " + hbNow() + " finishActivityByHash err: " + t); }
}

// v1.0.6 §13.5: 判定前台 Activity 是否红包页 (类名含 luckymoney, 大小写不敏感)。
boolean hbIsLuckyMoneyTop(Activity top) {
    try {
        if (top == null) return false;
        String cn = top.getClass().getName();
        if (cn == null) return false;
        return cn.toLowerCase().indexOf("luckymoney") >= 0;
    } catch (Throwable t) { return false; }
}

// ================================================================
// ============ v1.3.0 §17: 统计本地 SQLite (rp_record) ============
// ================================================================
// 懒打开 DB 单例 + 建表。仅后台反射线程(写)/导出按钮(读, UI 事件非热路径)调用。失败返回 null。
// 绝不在 onHandleMsg / rpEnqueue / rpWorkerTick / 普通消息热路径调用。
android.database.sqlite.SQLiteDatabase rpDb() {
    if (RP_DB != null && RP_DB.isOpen()) return RP_DB;
    synchronized (RP_DB_LOCK) {
        if (RP_DB != null && RP_DB.isOpen()) return RP_DB;   // 双重检查
        try {
            android.database.sqlite.SQLiteDatabase db =
                android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(RP_DB_PATH, (android.database.sqlite.SQLiteDatabase.CursorFactory) null);
            db.execSQL("CREATE TABLE IF NOT EXISTS rp_record(key TEXT, date TEXT, ts INTEGER, grp TEXT, group_name TEXT, sender_wxid TEXT, sender_name TEXT, title TEXT, qualified_count INTEGER, qualifiers TEXT, PRIMARY KEY(key))");
            // v1.3.1 §17.1a: 老库无 group_name 列时安全升级 (本库尚未真机建过, CREATE 已含该列; ALTER 仅稳妥兜底, 已存在则抛异常被吞)。
            try { db.execSQL("ALTER TABLE rp_record ADD COLUMN group_name TEXT"); } catch (Throwable t) {}
            // WAL 在 FUSE 可能回退 delete (GroupAdmin 经验), 无碍。PRAGMA 返回一行需 rawQuery, execSQL 会抛。
            try { android.database.Cursor c = db.rawQuery("PRAGMA journal_mode=WAL", (String[]) null); if (c != null) c.close(); } catch (Throwable t) {}
            try { db.execSQL("PRAGMA synchronous=NORMAL"); } catch (Throwable t) {}
            RP_DB = db;
            // 嗅探日志②: 建库/打开成功 (真机一验即知 GroupAdmin 同级目录建库可行)。
            hbBgLog("[RP] " + hbNow() + " stats-db opened (" + RP_DB_PATH.substring(RP_DB_PATH.lastIndexOf('/') + 1) + ").");
            return RP_DB;
        } catch (Throwable t) {
            // 嗅探日志②: 打开失败 → 降级 (本次不记录, 不影响发提醒/关页)。
            hbBgLog("[RP] " + hbNow() + " stats-db: open fail (degrade, no record this time): " + t);
            return null;
        }
    }
}

// v1.3.1 §17.1a: 取当前群显示名 (仿 GroupAdmin: 遍历 getGroupList() 找 getRoomId()==talker, 取 getName())。
// 仅后台反射线程调 (rpRecordStats 内), 不碰热路径。取不到返回空串。整段 try/catch, 绝不抛。
String rpGroupName(String talker) {
    try {
        if (talker == null || talker.isEmpty()) return "";
        List groups = getGroupList();
        if (groups == null) return "";
        for (int i = 0; i < groups.size(); i++) {
            Object info = groups.get(i);
            if (info == null) continue;
            String rid = null;
            try { Object r = info.getClass().getMethod("getRoomId").invoke(info); if (r != null) rid = r.toString(); } catch (Throwable t) {}
            if (rid == null || !rid.equals(talker)) continue;
            try { Object n = info.getClass().getMethod("getName").invoke(info); if (n != null) { String nm = n.toString().trim(); if (!nm.isEmpty()) return nm; } } catch (Throwable t) {}
            break;
        }
    } catch (Throwable t) {}
    return "";
}

// v1.4.0 §17.1b: 取发包人【群昵称】(仅后台反射线程调; 不碰热路径)。
// 优先 getFriendDisplayName(wxid, groupId)=群名片/群昵称 → 退 getFriendNickName(wxid)=微信昵称 → 退 wxid 原值。
// 整段 try/catch, 绝不抛; wxid 空时返回空串。
String rpSenderName(String wxid, String groupId) {
    if (wxid == null || wxid.isEmpty()) return "";
    if (groupId != null && !groupId.isEmpty()) {
        try { String n = getFriendDisplayName(wxid, groupId); if (n != null && !n.trim().isEmpty()) return n.trim(); } catch (Throwable t) {}
    }
    try { String n = getFriendNickName(wxid); if (n != null && !n.trim().isEmpty()) return n.trim(); } catch (Throwable t) {}
    return wxid;
}

// v1.3.0 §17.4: 写一行统计 (仅后台反射线程调; 整段 try/catch 可降级, 失败只 log 计数)。
// 达标 = 金额(分) > 档1阈值×100, 已剔除本群排除名单 (与 §6 分档口径一致)。达标 >=1 才写; 0 不写。
// qualified_count / qualifiers 全量不截断 (at 上限只影响发不发@, 不影响记录, §17.2)。
void rpRecordStats(String key, String talker, String senderWxid, String title, List receivers) {
    try {
        if (key == null || key.isEmpty()) { hbBgLog("[RP] " + hbNow() + " stats-db: skip (key empty)."); return; }
        if (receivers == null || receivers.isEmpty()) return;

        // v1.6.0: 每群独立 1-4 档 (未配置该群则 getTiers 回退全局三档, 旧行为不变)。
        //         本函数在后台反射线程调 (写库点), 非 onHandleMsg 热路径, getTiers 读 store 满足约束。
        List tiers = getTiers(talker);
        int lowThr = ((Integer) ((Object[]) tiers.get(0))[0]).intValue();   // 最低档阈值(元)
        List excl = getExcludeList(talker);   // 本群排除名单 (与 §6 同口径)

        // qualifiers 序列化: 昵称|金额元|档次;...  (全部列出, >10 也全列)。档次 1..N, 严格大于、归最高满足档。
        StringBuilder qb = new StringBuilder();
        int qualified = 0;
        for (int i = 0; i < receivers.size(); i++) {
            Object[] r = (Object[]) receivers.get(i);
            String nick = (String) r[0];
            long cent = ((Long) r[1]).longValue();
            String wxid = (String) r[2];
            if (wxid == null || wxid.isEmpty()) continue;          // 与 §6 一致: @ 需要 wxid 才计入
            // 剔除本群排除名单
            boolean skip = false;
            for (int j = 0; j < excl.size(); j++) { if (wxid.equals(((String) excl.get(j)).trim())) { skip = true; break; } }
            if (skip) continue;
            int tier = tierOf(tiers, cent);   // 1-based 最高满足档; 0 = 未达最低档
            if (tier < 1) continue;   // 未超最低档, 不计入达标
            qualified++;
            String yuan = rpCentToYuan(cent);
            String nm = (nick == null) ? "" : nick.replace('|', '/').replace(';', ',');   // 防分隔符污染
            if (qb.length() > 0) qb.append(";");
            qb.append(nm).append("|").append(yuan).append("|").append(tier);
        }

        if (qualified == 0) {
            hbBgLog("[RP] " + hbNow() + " stats-db: 0 qualified (>" + lowThr + "元), not recorded.");
            return;
        }

        // v1.4.0: 发包人昵称用【群昵称】(群名片/群昵称) 优先, 取不到退好友昵称, 再退 wxid。
        // 之前 v1.3.x 用 lookupName(senderWxid, talker) 在本设备上显示成了 wxid (bug)。
        // getFriendDisplayName(wxid, groupId) = 群内显示名(群名片/群昵称, 仿 GroupAdmin lookupName 首选);
        // getFriendNickName(wxid) = 微信昵称兜底。整段 try/catch, 绝不抛。
        String senderName = rpSenderName(senderWxid, talker);

        // v1.3.1 §17.1a: 取当前群名 (取不到留空串)。
        String groupName = rpGroupName(talker);
        if (groupName == null) groupName = "";
        // 嗅探日志 (§17.5/§17.1a): 只记群名长度, 不打群名原文 (脱敏)。真机一验即知 rpGroupName 是否取到。
        hbBgLog("[RP] " + hbNow() + " stats group_name len=" + groupName.length());

        String date = rpToday();
        long ts = System.currentTimeMillis();
        String titleVal = (title == null) ? "" : title;

        // 嗅探日志① (写库侧, 配合 rpExtractTitle 的字段名日志): 只记 title 长度, 不打原文 (脱敏)。
        // 字段名(usedField=...)在 rpExtractTitle parse 时已记; 这里确认该 title 长度确实带进了本次写库。
        hbBgLog("[RP] " + hbNow() + " stats title: writing record, title len=" + titleVal.length() + " (field name logged at parse time; 原文不记).");

        android.database.sqlite.SQLiteDatabase db = rpDb();
        if (db == null) return;   // 打不开 → 降级 (rpDb 已 log)
        try {
            android.database.sqlite.SQLiteStatement st = db.compileStatement(
                "INSERT OR REPLACE INTO rp_record(key,date,ts,grp,group_name,sender_wxid,sender_name,title,qualified_count,qualifiers) VALUES(?,?,?,?,?,?,?,?,?,?)");
            try {
                st.bindString(1, key);
                st.bindString(2, date);
                st.bindLong(3, ts);
                st.bindString(4, talker == null ? "" : talker);
                st.bindString(5, groupName);
                st.bindString(6, senderWxid == null ? "" : senderWxid);
                st.bindString(7, senderName);
                st.bindString(8, titleVal);
                st.bindLong(9, (long) qualified);
                st.bindString(10, qb.toString());
                st.execute();
            } finally { st.close(); }
        } catch (Throwable t) { hbBgLog("[RP] " + hbNow() + " stats-db: insert fail (degrade): " + t); return; }

        // 脱敏 log: 只记计数, 不打明文 wxid/昵称/金额/title。
        hbBgLog("[RP] " + hbNow() + " stats-db: recorded 1 row (date=" + date + ", qualified=" + qualified + "). details NOT logged (privacy).");
    } catch (Throwable t) {
        hbBgLog("[RP] " + hbNow() + " stats-db: rpRecordStats err (degrade): " + t);
    }
}

// 分 → 元 字符串 (保留 2 位, 去尾零)。如 1234 → "12.34", 500 → "5", 1050 → "10.5"。
String rpCentToYuan(long cent) {
    try {
        long yuan = cent / 100L, frac = cent % 100L;
        if (frac == 0L) return String.valueOf(yuan);
        String f = (frac < 10L) ? ("0" + frac) : String.valueOf(frac);
        // 去尾零
        while (f.length() > 1 && f.charAt(f.length() - 1) == '0') f = f.substring(0, f.length() - 1);
        return yuan + "." + f;
    } catch (Throwable t) { return String.valueOf(cent) + "分"; }
}

String rpToday() {
    try { return new java.text.SimpleDateFormat("yyyy-MM-dd").format(new java.util.Date()); }
    catch (Throwable t) { return "?"; }
}

// ================================================================
// ============ v1.5.0: 分组导出 helper (复用于 rpExportToday + rpDailySend) ============
// ================================================================
// 单条红包记录的轻量载体: 用 Object[] 承载一行查询结果 (BeanShell 无泛型/无 record, 沿用文件现有数组写法)。
// 槽位: [0]=ts(Long) [1]=sn(String,发包人) [2]=ti(String,标题) [3]=qn(Long,达标数) [4]=qf(String,qualifiers) [5]=gn(String,群名) [6]=gid(String,grp)
Object[] rpReadRow(android.database.Cursor c) {
    long ts = c.isNull(0) ? 0L : c.getLong(0);
    String sn = c.getString(1); if (sn == null) sn = "";
    String ti = c.getString(2); if (ti == null) ti = "";
    long qn = c.isNull(3) ? 0L : c.getLong(3);
    String qf = c.getString(4); if (qf == null) qf = "";
    String gn = c.getString(5); if (gn == null) gn = "";
    String gid = c.getString(6); if (gid == null) gid = "";
    return new Object[]{ new Long(ts), sn, ti, new Long(qn), qf, gn, gid };
}

// v1.5.0: 解析 qualifiers 列 "昵称|金额元|档次;昵称|金额元|档次;..." → List<String[]>(每元素 = {昵称, 金额元})。
// ';' 分多人, '|' 分三段。防空/防字段缺失, 单人解析失败 (段数不足/空昵称) 跳过不抛。
List rpParseQualifiers(String qf) {
    List out = new java.util.ArrayList();   // 每元素 String[]{name, amount}
    try {
        if (qf == null || qf.trim().isEmpty()) return out;
        String[] persons = qf.split(";");
        for (int i = 0; i < persons.length; i++) {
            try {
                String p = persons[i];
                if (p == null) continue;
                p = p.trim();
                if (p.isEmpty()) continue;
                String[] seg = p.split("\\|");
                if (seg.length < 2) continue;             // 缺段 → 跳过
                String name = (seg[0] == null) ? "" : seg[0].trim();
                String amt = (seg[1] == null) ? "" : seg[1].trim();
                if (name.isEmpty()) continue;             // 无昵称 → 跳过
                out.add(new String[]{ name, amt });
            } catch (Throwable t) { /* 单人解析失败跳过, 不抛 */ }
        }
    } catch (Throwable t) { /* 整体解析失败 → 返回已解析部分 */ }
    return out;
}

// v1.5.0: 把窗口内 rows (List<Object[]>) 按群 (grp) 分组 → 每群拼一条多行文本 → 单群超长按行兜底分条。
// v1.9.1 §24.4: 返回带 grp 的分组消息 List<Object[]{String grp, String msg}> (每元素 = {该群 grp, 一条待发消息})。
//   发送循环据此把每条发到该群自己的目标 cfgExportTargetFor(grp) (未设回退全局默认 → 旧行为不变)。
// headerPrefix: rpExportToday 传当天 "📊 红包统计 MM-dd"; rpDailySend 传 "📊 每日红包统计[...] (winStr)"。
// 每条消息头行 = headerPrefix + "  【群名】"。群名取该群首条记录的 group_name, 空回退 grp, 都空则 【】。
// 红包行: "HH:mm 发包人「标题」 达标N人" (标题空则省「」); 人行: 全角空格缩进 "　昵称 金额元"。红包之间空一行, 群内最后一个不留空行。
List rpBuildGroupedMsgs(List rows, String headerPrefix) {
    List msgs = new java.util.ArrayList();
    try {
        int MAX_CHARS = 1800;
        java.text.SimpleDateFormat hm = new java.text.SimpleDateFormat("HH:mm");
        // 按 grp 分组, 保持遍历顺序 (SQL 已 ORDER BY grp,ts)。
        java.util.LinkedHashMap groups = new java.util.LinkedHashMap();   // grp(String) -> List<Object[]>
        for (int i = 0; i < rows.size(); i++) {
            Object[] r = (Object[]) rows.get(i);
            String gkey = (String) r[6];   // 用 grp 列作分组键 (稳定)
            if (gkey == null) gkey = "";
            List bucket = (List) groups.get(gkey);
            if (bucket == null) { bucket = new java.util.ArrayList(); groups.put(gkey, bucket); }
            bucket.add(r);
        }
        // 逐群拼文本。
        java.util.Iterator it = groups.values().iterator();
        while (it.hasNext()) {
            List bucket = (List) it.next();
            if (bucket.isEmpty()) continue;
            Object[] first = (Object[]) bucket.get(0);
            String fgn = (String) first[5];
            String fgid = (String) first[6];
            final String grpKey = (fgid != null) ? fgid : "";   // v1.9.1: 该群 grp, 随每条消息返回供按群路由
            String groupName = (fgn != null && !fgn.isEmpty()) ? fgn : fgid;
            if (groupName == null) groupName = "";
            String header = headerPrefix + "  【" + groupName + "】";
            // 先把该群所有"行"(每红包的多行块)摊平成 List<String>, 块间已含空行, 再按 MAX_CHARS 兜底分条 (每条带 header)。
            List blockLines = new java.util.ArrayList();
            for (int bi = 0; bi < bucket.size(); bi++) {
                Object[] r = (Object[]) bucket.get(bi);
                long ts = ((Long) r[0]).longValue();
                String sn = (String) r[1];
                String ti = (String) r[2];
                long qn = ((Long) r[3]).longValue();
                String qf = (String) r[4];
                if (bi > 0) blockLines.add("");   // 红包之间空一行
                String hhmm = "?";
                try { if (ts > 0L) hhmm = hm.format(new java.util.Date(ts)); } catch (Throwable t) {}
                StringBuilder rpLine = new StringBuilder();
                rpLine.append(hhmm).append(" ").append(sn);
                // 样例: 有标题 "张三「恭喜发财」达标2人" (标题后紧接达标, 无空格); 无标题 "赵六 达标1人" (名后空格)。
                if (ti != null && !ti.isEmpty()) rpLine.append("「").append(ti).append("」");
                else rpLine.append(" ");
                rpLine.append("达标").append(qn).append("人");
                blockLines.add(rpLine.toString());
                List ppl = rpParseQualifiers(qf);
                for (int pi = 0; pi < ppl.size(); pi++) {
                    String[] pa = (String[]) ppl.get(pi);
                    blockLines.add("　" + pa[0] + " " + pa[1] + "元");
                }
            }
            // 分条: 同群多行超 MAX_CHARS 时切到下一条, 不切断单行; 每条都带 header。
            StringBuilder cur = new StringBuilder();
            cur.append(header);
            for (int li = 0; li < blockLines.size(); li++) {
                String ln = (String) blockLines.get(li);
                if (cur.length() + 1 + ln.length() > MAX_CHARS && cur.length() > 0) {
                    msgs.add(new Object[]{ grpKey, cur.toString() });   // v1.9.1: 带 grp
                    cur = new StringBuilder();
                    cur.append(header);   // 续条重复 header 保持每条可读
                }
                cur.append("\n").append(ln);
            }
            if (cur.length() > 0) msgs.add(new Object[]{ grpKey, cur.toString() });   // v1.9.1: 带 grp
        }
    } catch (Throwable t) { hbBgLog("[RP] " + hbNow() + " build-grouped err: " + t); }
    return msgs;
}

// v1.5.0 §导出: 导出今日红包统计 → 私聊, 按群分组、每群一条独立消息。UI 事件回调(非热路径), 只读查询 + 发消息, 不影响采集。
// 头行用当天 MM-dd; 当天无记录 → toast 提示, 不发送。全程 try/catch。
void rpExportToday() {
    try {
        String date = rpToday();

        android.database.sqlite.SQLiteDatabase db = rpDb();
        if (db == null) { try { toast("❌ 统计库打不开"); } catch (Throwable t) {} return; }

        List rows = new java.util.ArrayList();   // List<Object[]>
        android.database.Cursor c = null;
        try {
            // v1.5.0: ORDER BY grp,ts 便于按群顺序遍历分组 (WHERE 不变)。
            c = db.rawQuery("SELECT ts,sender_name,title,qualified_count,qualifiers,group_name,grp FROM rp_record WHERE date=? ORDER BY grp ASC, ts ASC", new String[]{ date });
            while (c.moveToNext()) rows.add(rpReadRow(c));
        } finally { if (c != null) try { c.close(); } catch (Throwable t) {} }

        if (rows.isEmpty()) { try { toast("今日无红包统计"); } catch (Throwable t) {} return; }

        // 头行前缀: 当天 MM-dd。
        String mmdd = date;
        try { mmdd = new java.text.SimpleDateFormat("MM-dd").format(new java.util.Date()); } catch (Throwable t) {}
        String headerPrefix = "📊 红包统计 " + mmdd;
        List msgs = rpBuildGroupedMsgs(rows, headerPrefix);   // v1.9.1: List<Object[]{grp, msg}>

        final List fMsgs = msgs;
        final int fRows = rows.size();
        // 发消息放后台线程 (sendText 可能涉及 IO; UI 回调里别阻塞)。
        // v1.9.1 §24.4: 每条按 cfgExportTargetFor(该群 grp) 发到该群自己的目标 (未设回退全局默认 → 旧行为不变)。
        new Thread() {
            public void run() {
                try {
                    for (int i = 0; i < fMsgs.size(); i++) {
                        try {
                            Object[] gm = (Object[]) fMsgs.get(i);
                            String grp = (String) gm[0];
                            String msg = (String) gm[1];
                            sendText(cfgExportTargetFor(grp), msg);
                        } catch (Throwable t) { hbBgLog("[RP] " + hbNow() + " export sendText err: " + t); }
                    }
                    // 嗅探日志 (脱敏): 只记实际发送条数 + 行数, 绝不打群名/昵称/内容/目标。
                    hbBgLog("[RP] " + hbNow() + " export: sent " + fMsgs.size() + " msg (per-group routed) (rows=" + fRows + "). content NOT logged.");
                } catch (Throwable t) { hbBgLog("[RP] " + hbNow() + " export thread err: " + t); }
            }
        }.start();
        try { toast("📤 已导出 " + rows.size() + " 个红包 → 私聊 (按群发 " + msgs.size() + " 条)"); } catch (Throwable t) {}
    } catch (Throwable t) {
        hbBgLog("[RP] " + hbNow() + " export err: " + t);
        try { toast("❌ 导出失败: " + t); } catch (Throwable t2) {}
    }
}

// ================================================================
// ============ v1.4.0 §20: 每日定时统计 → 私聊 ============
// ================================================================
// 触发机制 (抗重启, 不用精确定时器): rpWorkerTick 每 tick 调本方法。
// 若当前本地时间 ≥ 今天 cfgDailyHour:00 且 rp_daily_last_sent != 今天日期 → 执行每日发送 → 把 last_sent 置今天。
// 这样每天只发一次、重启不重发不漏发 (7点后启动当天补发)。整段 try/catch, 失败可降级。
void rpDailyCheck() {
    try {
        int hour = cfgDailyHour();
        java.util.Calendar cal = java.util.Calendar.getInstance();   // 本地时区
        int curHour = cal.get(java.util.Calendar.HOUR_OF_DAY);
        if (curHour < hour) return;   // 还没到今天的发送时刻
        String today = rpToday();
        String last = getString(K_DAILY_LAST_SENT, "");
        if (last != null && last.equals(today)) return;   // 今天已发过
        // 到点且今天未发 → 执行每日发送。先置 last_sent (即使发送过程出错也不重发, 避免循环重试刷屏; 心跳本身能反映)。
        putString(K_DAILY_LAST_SENT, today);
        hbBgLog("[RP] " + hbNow() + " daily-check: trigger daily send (hour>=" + hour + ", today=" + today + ", last marked). ");
        // 窗口 = [今天 hour:00 往前 24 小时 (即昨天 hour:00), 今天 hour:00)。
        long windowEnd = rpTodayHourMs(hour);
        long windowStart = windowEnd - 24L * 3600L * 1000L;
        rpDailySend(windowStart, windowEnd, false);
    } catch (Throwable t) {
        hbBgLog("[RP] " + hbNow() + " daily-check err: " + t);
    }
}

// 今天本地 hour:00:00.000 的毫秒。
long rpTodayHourMs(int hour) {
    try {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.set(java.util.Calendar.HOUR_OF_DAY, hour);
        cal.set(java.util.Calendar.MINUTE, 0);
        cal.set(java.util.Calendar.SECOND, 0);
        cal.set(java.util.Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis();
    } catch (Throwable t) { return System.currentTimeMillis(); }
}

// 每日发送: 查 rp_record 中 ts ∈ [windowStart, windowEnd) 且 grp ∈ rp_daily_groups, 按 ts 升序, 每红包一行 (行首带 [群名])。
// 发给 rp_export_target, 超长分多条。无记录也发一条 header + (过去24h无达标红包) 作每日心跳。
// 整段后台线程 + try/catch 可降级。manual=true 时 header 标注为手动测试。
void rpDailySend(final long windowStart, final long windowEnd, final boolean manual) {
    new Thread() {
        public void run() {
            try {
                List dailyGroups = getDailyGroups();
                // 窗口头尾的 MM-dd HH:mm 用于 header。
                java.text.SimpleDateFormat mdhm = new java.text.SimpleDateFormat("MM-dd HH:mm");
                String winStr = mdhm.format(new java.util.Date(windowStart)) + " ~ " + mdhm.format(new java.util.Date(windowEnd));
                String headerPrefix = "📊 " + (manual ? "每日红包统计[手动测试] (" : "每日红包统计 (") + winStr + ")";

                List rows = new java.util.ArrayList();   // List<Object[]>
                android.database.sqlite.SQLiteDatabase db = rpDb();
                if (db == null) {
                    hbBgLog("[RP] " + hbNow() + " daily-send: db open fail, send heartbeat only.");
                } else if (dailyGroups.isEmpty()) {
                    hbBgLog("[RP] " + hbNow() + " daily-send: no daily groups, send heartbeat only.");
                } else {
                    android.database.Cursor c = null;
                    try {
                        // 用 ts 范围 (跨两个日历日, 别用 date 列)。grp IN (...) 动态拼占位符 (WHERE 不变)。
                        StringBuilder ph = new StringBuilder();
                        String[] args = new String[dailyGroups.size() + 2];
                        args[0] = String.valueOf(windowStart);
                        args[1] = String.valueOf(windowEnd);
                        for (int i = 0; i < dailyGroups.size(); i++) {
                            if (i > 0) ph.append(",");
                            ph.append("?");
                            args[i + 2] = normGroupId((String) dailyGroups.get(i));
                        }
                        // v1.5.0: ORDER BY grp,ts 便于按群分组遍历。
                        String sql = "SELECT ts,sender_name,title,qualified_count,qualifiers,group_name,grp FROM rp_record"
                            + " WHERE ts>=? AND ts<? AND grp IN (" + ph.toString() + ") ORDER BY grp ASC, ts ASC";
                        c = db.rawQuery(sql, args);
                        while (c.moveToNext()) rows.add(rpReadRow(c));
                    } finally { if (c != null) try { c.close(); } catch (Throwable t) {} }
                }

                // 组装 + 发送 (v1.9.1 §24.4)。
                //  - 有记录 → rpBuildGroupedMsgs 返回 List<Object[]{grp, msg}>, 每条按 cfgExportTargetFor(grp) 发到该群自己的目标 (未设回退全局默认 = 旧行为)。
                //  - 全窗口无记录 → 仍发一条总心跳, 发到全局默认 cfgExportTarget() (边缘场景, 不按群散发, 避免噪音)。
                int sent = 0;
                if (rows.isEmpty()) {
                    String def = cfgExportTarget();
                    if (def != null && !def.trim().isEmpty()) {
                        try { sendText(def.trim(), headerPrefix + "\n(过去24h无达标红包)"); sent = 1; } catch (Throwable t) { hbBgLog("[RP] " + hbNow() + " daily-send heartbeat sendText err: " + t); }
                    } else { hbBgLog("[RP] " + hbNow() + " daily-send: no global target for heartbeat, skip."); }
                } else {
                    List msgs = rpBuildGroupedMsgs(rows, headerPrefix);   // List<Object[]{grp, msg}>
                    for (int i = 0; i < msgs.size(); i++) {
                        try {
                            Object[] gm = (Object[]) msgs.get(i);
                            String grp = (String) gm[0];
                            String msg = (String) gm[1];
                            sendText(cfgExportTargetFor(grp), msg);
                            sent++;
                        } catch (Throwable t) { hbBgLog("[RP] " + hbNow() + " daily-send sendText err: " + t); }
                    }
                }
                hbBgLog("[RP] " + hbNow() + " daily-send: sent " + sent + " msg (per-group routed), rows=" + rows.size()
                    + ", groups=" + dailyGroups.size() + ", manual=" + manual + ". content NOT logged.");
            } catch (Throwable t) {
                hbBgLog("[RP] " + hbNow() + " daily-send thread err: " + t);
            }
        }
    }.start();
}

// v1.4.0 §20: 手动测试命令 (便于不等到7点验证)。立即按"过去24小时 (now-24h ~ now)"窗口对 rp_daily_groups 跑一次每日发送。
void rpDailyTest() {
    try {
        long now = System.currentTimeMillis();
        long windowStart = now - 24L * 3600L * 1000L;
        hbBgLog("[RP] " + hbNow() + " daily-test: manual trigger, window=past 24h.");
        rpDailySend(windowStart, now, true);
    } catch (Throwable t) {
        hbBgLog("[RP] " + hbNow() + " daily-test err: " + t);
    }
}

// ================================================================
// ============ v1.11.0 §25: 伸手党治理 — 只读查 GroupAdmin 库 last_speak ============
// ================================================================
// 只读查 groupadmin.db 的 speak 表, 返回某人在某群的 last_speak (毫秒)。
//   -1 = 无 speak 行 / DB 打不开 / 异常 (跳过, 顺带覆盖"群管该群没开统计"=无行)
//    0 = 有行但 last_speak 为 NULL (从未发言 → 伸手党)
//   >0 = last_speak 毫秒值
// 独立连接、OPEN_READONLY、用完即关 (groupadmin.db 为 WAL, 只读不干扰其写)。整段 try/catch 降级。
long rpQueryLastSpeak(String groupId, String wxid) {
    if (groupId == null || wxid == null || groupId.isEmpty() || wxid.isEmpty()) return -1L;
    android.database.sqlite.SQLiteDatabase db = null;
    android.database.Cursor c = null;
    try {
        db = android.database.sqlite.SQLiteDatabase.openDatabase(GA_DB_PATH, (android.database.sqlite.SQLiteDatabase.CursorFactory) null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY);
        c = db.rawQuery("SELECT last_speak FROM speak WHERE grp=? AND wxid=?", new String[]{ groupId, wxid });
        if (c == null || !c.moveToFirst()) return -1L;     // 无行 → 跳过
        if (c.isNull(0)) return 0L;                         // 有行但 NULL → 从未发言
        return c.getLong(0);
    } catch (Throwable t) { return -1L; }                   // DB 打不开/异常 → 跳过(降级)
    finally { try { if (c != null) c.close(); } catch (Throwable t) {} try { if (db != null) db.close(); } catch (Throwable t) {} }
}

// ================================================================
// ============ 详情页: 后台反射读明细 → 提取 → 关页 → 分档 @ ============
// ================================================================
void hbDetailExtract(Object root, String key) {
    try {
        if (key == null) { hbBgLog("[RP] " + hbNow() + " detail: key null, skip. clear in-flight."); rpClearInFlight(); return; }
        if (DONE.contains(key)) { hbBgLog("[RP] " + hbNow() + " detail: already done, skip. clear in-flight."); rpClearInFlight(); return; }

        // v1.1.0 §15.4: 归属信息从 CUR_JOB 取 (单飞下即当前 job)。
        Object[] job = CUR_JOB;
        String talker = (job != null) ? (String) job[JOB_TALKER] : null;
        long msgid = 0L;
        try { if (job != null) msgid = ((Long) job[JOB_MSGID]).longValue(); } catch (Throwable t) { msgid = 0L; }
        final long fMsgid = msgid;
        // v1.3.0 §17: 发包人 wxid + 标题 (供统计 DB 写入用; 仅本后台反射线程读, 不碰热路径)。
        String jobSender = null, jobTitle = null;
        try { if (job != null) jobSender = (String) job[JOB_SENDER]; } catch (Throwable t) { jobSender = null; }
        try { if (job != null) jobTitle = (String) job[JOB_TITLE]; } catch (Throwable t) { jobTitle = null; }
        // v1.7.0 §22: 包类型 (worker 已判定并回填 job[JOB_ISCUSTOM])。仅用于选档, 透传给 hbTierAndSend。
        boolean jobIsCustom = false;
        try { if (job != null && job[JOB_ISCUSTOM] != null) jobIsCustom = ((Boolean) job[JOB_ISCUSTOM]).booleanValue(); } catch (Throwable t) { jobIsCustom = false; }
        final boolean fIsCustom = jobIsCustom;

        // BFS 找领取者 List
        Object listObj = hbFindReceiverList(root);
        if (listObj == null) {
            hbBgLog("[RP] " + hbNow() + " detail: no receiver list found (reflect). clear in-flight.");
            // 关页, 不重试 (已到详情页说明领完, 列表没读到是版本漂移问题)
            finishDetailAndCover(key);
            DONE.add(key);   // 已到详情页=领完, 不重入队
            SEEN.clear();
            rpClearInFlight();
            return;
        }

        // 提取每个元素
        List receivers = new java.util.ArrayList();  // Object[]{nickname, amountCent(long), wxid}
        int size = 0;
        boolean isArr = false;
        java.util.List asList = null;
        if (listObj instanceof java.util.List) { asList = (java.util.List) listObj; size = asList.size(); }
        else if (listObj.getClass().isArray()) { isArr = true; size = java.lang.reflect.Array.getLength(listObj); }

        String pathLog = null;
        for (int i = 0; i < size; i++) {
            Object el = isArr ? java.lang.reflect.Array.get(listObj, i) : asList.get(i);
            if (el == null) continue;
            Object[] tri = hbExtractTriple(el);
            if (tri == null) continue;
            if (pathLog == null) pathLog = (String) tri[3];
            receivers.add(new Object[]{ tri[0], tri[1], tri[2] });
        }

        hbBgLog("[RP] " + hbNow() + " detail: extracted " + receivers.size() + "/" + size
            + " receivers (path=" + pathLog + "). nicknames/amounts/wxids NOT logged (privacy).");

        // ---- 普通红包(均分)过滤: 仅处理拼手气。已成功反射到列表后、分档发消息之前判断。----
        // 拼手气 = 金额随机各不同; 普通 = 每人金额相等。
        // 领取者 >=2 人 且所有金额(分, f 字段)全部相等 → 普通红包 → 不发任何消息。
        // 1 人无法判定按正常处理(继续发, 概率极低且无害); 反射不到列表的情况上面已 return, 不在此误判。
        try {
            int rn = receivers.size();
            if (rn >= 2) {
                long firstCent = ((Long) ((Object[]) receivers.get(0))[1]).longValue();
                boolean allEqual = true;
                for (int i = 1; i < rn; i++) {
                    long c = ((Long) ((Object[]) receivers.get(i))[1]).longValue();
                    if (c != firstCent) { allEqual = false; break; }
                }
                if (allEqual) {
                    hbBgLog("[RP] " + hbNow() + " skip normal(equal amounts): " + rn + " receivers all equal. no message sent. clear in-flight.");
                    // 清状态、关页, 不发任何消息。§15.5 退出点 → 清在飞。
                    finishDetailAndCover(key);
                    DONE.add(key);
                    SEEN.clear();
                    rpClearInFlight();
                    return;
                }
            }
        } catch (Throwable t) {
            hbBgLog("[RP] " + hbNow() + " normal-filter err: " + t + " (treat as 拼手气, continue).");
        }

        // ---- v1.3.0 §17.4: 统计 DB 写入 (写入点铁律) ----
        // 此处仍在【后台反射线程】(由 hbOnActivityResume detail 分支 new Thread().start() 调入), 已拿到 receivers
        // 且普通红包过滤已通过(拼手气), 在关页(下面 finishDetailAndCover)之前。达标 >=1 → INSERT OR REPLACE 一行。
        // 整段独立 try/catch(Throwable), 失败只 hbBgLog 一行(脱敏), 绝不抛/不卡关页/不发提醒/不进 UI 线程。
        try { rpRecordStats(key, talker, jobSender, jobTitle, receivers); }
        catch (Throwable t) { hbBgLog("[RP] " + hbNow() + " stats-db: write threw (ignored, degrade): " + t); }

        // v1.11.2 §25: 伸手党判定 — 基准=红包检测时刻 job[JOB_DETECTMS](全员统一; 当前微信版本领取者模型无 per-person 领取时刻)。
        // 整段移入 fire-and-forget 后台线程(不占 IN_FLIGHT 单飞锁); 安全网: packetMs<=0/开关关/ls<0/上限 → 跳过; flush 容差防刚发言误判。
        try {
            long packetMs = 0L;
            try { if (job != null && job[JOB_DETECTMS] != null) packetMs = ((Long) job[JOB_DETECTMS]).longValue(); } catch (Throwable t) { packetMs = 0L; }
            if (talker != null && packetMs > 0L && cfgFreeloaderOn(talker)) {
                final String fTalker = talker;
                final long fPacketMs = packetMs;
                final java.util.List fSnap = new java.util.ArrayList();
                for (int i = 0; i < receivers.size(); i++) {
                    Object[] r = (Object[]) receivers.get(i);
                    String w = (r.length > 2) ? (String) r[2] : null;
                    if (w != null && !w.isEmpty()) fSnap.add(w);
                }
                new Thread(new Runnable() {
                    public void run() {
                        try {
                            int win = cfgFreeloaderWin(fTalker);
                            long winMs = (long) win * 60000L;
                            int warned = 0, capped = 0;
                            for (int i = 0; i < fSnap.size(); i++) {
                                String w = (String) fSnap.get(i);
                                long ls = rpQueryLastSpeak(fTalker, w);
                                if (ls < 0L) continue;                                   // 无 speak 行/DB 不可用 → 跳过
                                if (ls == 0L || ls < fPacketMs - winMs - FREELOADER_FLUSH_GRACE_MS) {   // 从未发言 或 红包前 N 分(含容差)无发言
                                    if (warned >= FREELOADER_MAX_WARN) { capped++; continue; }
                                    sendText(fTalker, "[AtWx=" + w + "] 警告 " + win + "分钟内未发言");
                                    warned++;
                                    try { Thread.sleep(800L); } catch (Throwable t) {}
                                }
                            }
                            hbBgLog("[RP] " + hbNow() + " freeloader: win=" + win + "min, warned=" + warned + ", capped=" + capped + "/" + fSnap.size() + " (anchor=packetMs, wxid NOT logged).");
                        } catch (Throwable t) { hbBgLog("[RP] " + hbNow() + " freeloader judge err: " + t); }
                    }
                }).start();
            }
        } catch (Throwable t) { hbBgLog("[RP] " + hbNow() + " freeloader outer err: " + t); }

        // 关页 (详情 + 封面)
        finishDetailAndCover(key);

        // 分档 + 发一条提醒 (v1.1.0 §16: 传 msgid 用于引用原红包; v1.7.0 §22: 传 isCustom 选档)
        if (talker != null) hbTierAndSend(talker, fMsgid, receivers, fIsCustom);
        else hbBgLog("[RP] " + hbNow() + " detail: talker null, cannot send.");

        // 标记完成 + 清状态 (§15.5 退出点 → 清在飞)
        DONE.add(key);
        SEEN.clear();
        rpClearInFlight();
    } catch (Throwable t) {
        hbBgLog("[RP] " + hbNow() + " detail extract err: " + t + ". finish + clear in-flight.");
        try { finishDetailAndCover(key); } catch (Throwable t2) {}
        try { if (key != null) DONE.add(key); SEEN.clear(); rpClearInFlight(); } catch (Throwable t2) {}
    }
}

void finishDetailAndCover(String key) {
    try {
        final Activity top = getTopActivity();
        // v1.0.6 §13.5: 仅当前台是红包页(类名含 luckymoney)才 finish, 避免误关用户正在看的页面。
        if (top != null && hbIsLuckyMoneyTop(top)) {
            top.runOnUiThread(new Runnable() {
                public void run() { try { top.finish(); } catch (Throwable t) {} }
            });
        } else if (top != null) {
            hbBgLog("[RP] " + hbNow() + " finishDetailAndCover skipped: top not luckymoney.");
        }
    } catch (Throwable t) {}
    if (key != null) COVER_ACT.remove(key);
}

// ============ 结构化字段提取 (抗版本) — 见 SPEC §5 ============
// 返回 Object[]{nickname(String), amountCent(Long), wxid(String), pathLog(String)}; 失败返回 null。
Object[] hbExtractTriple(Object el) {
    try {
        java.util.Map fields = hbReadFields(el);  // name -> value (含继承链)
        if (fields == null || fields.isEmpty()) return null;

        // 金额: 首选名为 f 的 long/int, 值>=0; 否则遍历 long/int 找合理值。
        Long amount = null;
        String amtPath = "?";
        Object fv = fields.get("f");
        if (fv instanceof Number && ((Number) fv).longValue() >= 0) { amount = Long.valueOf(((Number) fv).longValue()); amtPath = "f"; }
        if (amount == null) {
            java.util.Iterator it = fields.entrySet().iterator();
            while (it.hasNext()) {
                java.util.Map.Entry e = (java.util.Map.Entry) it.next();
                Object val = e.getValue();
                String nm = (String) e.getKey();
                if (nm.equals("g")) continue; // g = 领取时间戳, 排除
                if (val instanceof Long || val instanceof Integer) {
                    long lv = ((Number) val).longValue();
                    if (lv >= 0 && lv < 100000000L) { amount = Long.valueOf(lv); amtPath = nm + "(struct)"; break; }
                }
            }
        }

        // wxid: 首选名为 n 的 String 且像 wxid; 否则遍历 String 找 wxid_ 开头。
        String wxid = null;
        String wxPath = "?";
        Object nv = fields.get("n");
        if (nv instanceof String && hbLooksWxid((String) nv)) { wxid = (String) nv; wxPath = "n"; }
        if (wxid == null) {
            java.util.Iterator it = fields.entrySet().iterator();
            while (it.hasNext()) {
                java.util.Map.Entry e = (java.util.Map.Entry) it.next();
                Object val = e.getValue();
                if (val instanceof String && hbLooksWxid((String) val)) { wxid = (String) val; wxPath = (String) e.getKey() + "(struct)"; break; }
            }
        }

        // 昵称: 首选名为 d 的 String (非 wxid/非纯数字/非"手气最佳"); 否则遍历。
        String nick = null;
        String nkPath = "?";
        Object dv = fields.get("d");
        if (dv instanceof String && hbLooksNick((String) dv)) { nick = (String) dv; nkPath = "d"; }
        if (nick == null) {
            java.util.Iterator it = fields.entrySet().iterator();
            while (it.hasNext()) {
                java.util.Map.Entry e = (java.util.Map.Entry) it.next();
                Object val = e.getValue();
                String nm = (String) e.getKey();
                if (nm.equals("n")) continue; // n 是 wxid
                if (val instanceof String && hbLooksNick((String) val)) { nick = (String) val; nkPath = nm + "(struct)"; break; }
            }
        }

        if (amount == null && wxid == null) return null;
        if (nick == null) nick = (wxid != null ? wxid : "?");
        if (amount == null) amount = Long.valueOf(0L);
        String pathLog = "amt=" + amtPath + ",wx=" + wxPath + ",nk=" + nkPath;
        return new Object[]{ nick, amount, wxid, pathLog };
    } catch (Throwable t) { return null; }
}

// 读元素所有非 static 字段 (含继承链 ≤3 层) → name->value Map。
java.util.Map hbReadFields(Object el) {
    java.util.LinkedHashMap m = new java.util.LinkedHashMap();
    try {
        Class ec = el.getClass();
        int hops = 0;
        while (ec != null && hops <= 3) {
            java.lang.reflect.Field[] fs = null;
            try { fs = ec.getDeclaredFields(); } catch (Throwable t) { fs = null; }
            if (fs != null) {
                for (int i = 0; i < fs.length; i++) {
                    java.lang.reflect.Field f = fs[i];
                    try {
                        if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                        f.setAccessible(true);
                        String nm = f.getName();
                        if (!m.containsKey(nm)) {
                            Object v = null;
                            try { v = f.get(el); } catch (Throwable t) { v = null; }
                            m.put(nm, v);
                        }
                    } catch (Throwable t) {}
                }
            }
            try { ec = ec.getSuperclass(); } catch (Throwable t) { ec = null; }
            hops++;
        }
    } catch (Throwable t) {}
    return m;
}

boolean hbLooksWxid(String s) {
    if (s == null || s.isEmpty()) return false;
    if (s.startsWith("wxid_")) return true;
    // 像 wxid: 全英数下划线, 长度合理, 含字母 (排除纯数字时间戳/金额)
    if (s.length() < 6 || s.length() > 40) return false;
    boolean hasLetter = false;
    for (int i = 0; i < s.length(); i++) {
        char c = s.charAt(i);
        boolean ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_' || c == '-';
        if (!ok) return false;
        if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) hasLetter = true;
    }
    return hasLetter;
}

boolean hbLooksNick(String s) {
    if (s == null || s.isEmpty()) return false;
    if (hbLooksWxid(s)) return false;          // 非 wxid
    if (s.indexOf("@chatroom") >= 0) return false;
    if (s.indexOf("http") >= 0 || s.indexOf("://") >= 0) return false;
    if (s.equals("手气最佳")) return false;
    // 非纯数字 (排除时间戳)
    boolean allDigit = true;
    for (int i = 0; i < s.length(); i++) { char c = s.charAt(i); if (c < '0' || c > '9') { allDigit = false; break; } }
    if (allDigit) return false;
    if (s.length() > 30) return false;          // 昵称一般不长
    return true;
}

// ============ 分档 + 发一条提醒 (SPEC §6; v1.1.0 §16: 引用原红包) ============
// msgid = 原红包消息的本地 msgId (来自 CUR_JOB), 用于引用条 rpSendQuote 引用原红包。v1.1.2: 引用群规条(无@) + @条(sendText 真@), 详见函数内。
void hbTierAndSend(String talker, long msgid, List receivers) { hbTierAndSend(talker, msgid, receivers, false); }
// v1.7.0 §22.3: 加 isCustom 参数 — 仅选档来源按类型切换 (getTiersByType), 其余(限频/排除/分档/两条消息)一行不动。
void hbTierAndSend(String talker, long msgid, List receivers, boolean isCustom) {
    try {
        // ---- v1.0.6 §13.3: 同群 @ 限频。两条红包提醒间隔 < rp_msg_min_gap_sec 则跳过本次。----
        // 在分档/构造前就判, 避免无谓计算; talker = 群 id。
        long gapMs = cfgMsgGap() * 1000L;
        if (gapMs > 0L && talker != null) {
            Object lastObj = RP_LAST_MSG.get(talker);
            if (lastObj != null) {
                long last = ((Long) lastObj).longValue();
                long since = System.currentTimeMillis() - last;
                if (since < gapMs) {
                    hbBgLog("[RP] " + hbNow() + " rate-limit: same-group last msg " + (since / 1000L) + "s ago < gap "
                        + cfgMsgGap() + "s. skip this reminder.");
                    return;
                }
            }
        }

        // v1.6.0: 每群独立 1-4 档 (未配置该群则 getTiers 回退全局三档, 旧行为不变)。
        //         此处在后台 worker 线程 (hbTierAndSend), 非 onHandleMsg 热路径, 满足约束。
        // v1.7.0 §22.3: 档表来源按包类型切换 — 定制读 rp_tiers_custom_<gid>(未配回退普通), 普通读 getTiers。其余逻辑全不变。
        List tiers = getTiersByType(talker, isCustom);
        int nTier = tiers.size();
        int lowThr = ((Integer) ((Object[]) tiers.get(0))[0]).intValue();   // 最低档阈值(元)
        long lowCent = (long) lowThr * 100L;

        // ---- v1.0.6 §12: 分档前先剔除本群排除名单的 wxid (不 @、不计入达标总人数 → 影响 >上限 判定)。----
        List excl = getExcludeList(talker);
        int excludedHit = 0;

        // 每档一个 wxid 列表 (index 0 = 档1 最低)。
        List groups = new java.util.ArrayList();
        for (int gi = 0; gi < nTier; gi++) groups.add(new java.util.ArrayList());

        for (int i = 0; i < receivers.size(); i++) {
            Object[] r = (Object[]) receivers.get(i);
            long cent = ((Long) r[1]).longValue();
            String wxid = (String) r[2];
            if (wxid == null || wxid.isEmpty()) continue;   // @ 需要 wxid
            // 排除名单: 永不 @、不计入达标 (本群)
            boolean skip = false;
            for (int j = 0; j < excl.size(); j++) { if (wxid.equals(((String) excl.get(j)).trim())) { skip = true; break; } }
            if (skip) { excludedHit++; continue; }
            // 严格大于, 归最高满足档 (1-based)。tierOf 从最高档往低判。
            int tn = tierOf(tiers, cent);
            if (tn >= 1) ((List) groups.get(tn - 1)).add(wxid);
        }

        // 达标总人数 = 各档之和 (金额 > 最低档阈值 的全部领取者, 已剔除排除名单)
        int totalQualified = 0;
        for (int gi = 0; gi < nTier; gi++) totalQualified += ((List) groups.get(gi)).size();

        // 达标 0 人 → 不发 (维持现状)
        if (totalQualified == 0) {
            hbBgLog("[RP] " + hbNow() + " tier: nobody qualified (>" + lowThr + "元, excluded=" + excludedHit + "). no message sent. (" + nTier + " tiers)");
            return;
        }

        int atLimit = cfgAtLimit();

        // v1.1.4 §16: 发提醒拆两条 (达标≥1 才发), 严格顺序: 先第一条(引用群规) → 隔 rp_two_msg_gap_sec 秒 → 第二条(@)。
        //   v1.6.0: 第一条群规改为 N 档循环 (列全档, 无随机无语气), 连接词固定"过X元", 动作用各档原样。
        //   v1.7.1 §22.6: 定制包第一条改用定制前缀 cfgCustomRulePrefix(talker)(默认「定制包请按要求执行」), 普通包仍用 cfgRulePrefix();
        //     档表已是 getTiersByType(talker,isCustom) 选出的那套(定制档动作), 各档仍「，过{阈值}元{动作}」, 仅前缀按类型切换。
        StringBuilder ruleSb = new StringBuilder();
        ruleSb.append(isCustom ? cfgCustomRulePrefix(talker) : cfgRulePrefix());
        for (int gi = 0; gi < nTier; gi++) {
            Object[] te = (Object[]) tiers.get(gi);
            ruleSb.append("，过").append(((Integer) te[0]).intValue()).append("元");
            // v1.6.0a: 动作未设(占位"未设")或空 → 只发阈值不发动作词, 不让"未设"泄漏进群消息。
            String _act = (String) te[1];
            if (_act != null && !_act.trim().isEmpty() && !"未设".equals(_act.trim())) ruleSb.append(_act);
        }
        String rule = ruleSb.toString();

        // 第一条 (引用群规条): 达标≥1 必发。引用原红包, 失败回退 sendText(同一固定群规文本)。
        rpSendQuote(talker, msgid, rule);

        // v1.3.1 §16: 第二条文本 (msg2)。两种情况都带 【查包】 前缀、都隔 gap 秒延迟发 (同一机制)。
        //   ≤ at上限 → 第二条 = 【查包】 + 逐档随机@文本 (真@到人, 仅非空档)。
        //   > at上限 → 第二条 = 【查包】本次过{最低档阈值}元共{Y}人... (纯文字, 无@)。
        // v1.7.2 §22.6: 第二条(@条/notice)【查包】前缀, 定制包加「定制包」三字 → 【查包】定制包。
        String checkPrefix = isCustom ? "【查包】定制包" : "【查包】";
        String msg2 = null;
        if (totalQualified > atLimit) {
            // > at上限: 第二条为汇总 notice (无@, 纯文字)。{最低档阈值}(元), {Y}=达标总人数(已剔除排除名单)。
            msg2 = checkPrefix + "本次过" + lowThr + "元共" + totalQualified + "人，人数较多不逐一提醒，请大家自觉。";
            hbBgLog("[RP] " + hbNow() + " tier: qualified=" + totalQualified + " > at_limit=" + atLimit
                + " (excluded=" + excludedHit + ") -> 1.N-tier quoted group-rule (sent now), 2.【查包】summary notice (NO @, delayed " + cfgTwoMsgGap() + "s). tiers=" + nTier + ".");
        } else {
            // 1 ~ at上限 → 第二条 (@条): 整体开头加 【查包】 前缀, 随后逐档 [AtWx=] 真@ (仅非空档)。
            StringBuilder sb = new StringBuilder();
            boolean first = true;
            for (int gi = 0; gi < nTier; gi++) {
                List g = (List) groups.get(gi);
                if (g.isEmpty()) continue;
                Object[] te = (Object[]) tiers.get(gi);
                if (!first) sb.append("\n");
                appendTierLine(sb, g, ((Integer) te[0]).intValue(), (String) te[1]);
                first = false;
            }
            String atText = sb.toString();
            if (atText.length() > 0) msg2 = checkPrefix + atText;   // v1.3.1: 整体开头加 【查包】(v1.7.2: 定制包 →【查包】定制包), 在所有 [AtWx=]/逐档文本之前
            hbBgLog("[RP] " + hbNow() + " tier: qualified=" + totalQualified + " <= at_limit=" + atLimit
                + " (excluded=" + excludedHit + ") -> 1.N-tier quoted group-rule (sent now), 2.【查包】+@msg via sendText (delayed " + cfgTwoMsgGap() + "s for ordering). tiers=" + nTier + ".");
        }

        // v1.3.1 §16: 第二条统一走"第一条后隔 rp_two_msg_gap_sec 秒发"的同一机制 (final 捕获进闭包, fire-and-forget, 不进在飞锁)。
        // 两种第二条 (≤上限的@条 / >上限的notice) 都带 【查包】、都隔 gap 秒。回调整段 try/catch。
        if (msg2 != null && msg2.length() > 0) {
            final String fMsg2 = msg2;
            final String fTalker = talker;
            long twoGapMs = cfgTwoMsgGap() * 1000L;
            if (twoGapMs < 0L) twoGapMs = 0L;
            delay((int) twoGapMs, new Runnable() {
                public void run() {
                    try { rpSendAt(fTalker, fMsg2); }   // §16: sendText (@条@真生效; notice 纯文字也走 sendText)。引用条已单发, 延迟保序。
                    catch (Throwable t2) { hbBgLog("[RP] " + hbNow() + " delayed 2nd msg send err: " + t2); }
                }
            });
        }

        // v1.0.6 §13.3: 本次提醒 (整体) 发出后更新本群上次发送时间 (限频; 两条算一次)。
        if (talker != null) RP_LAST_MSG.put(talker, Long.valueOf(System.currentTimeMillis()));
    } catch (Throwable t) {
        hbBgLog("[RP] " + hbNow() + " tier+send err: " + t);
    }
}

// v1.0.7 §14: 格式 = {@这些人}{随机连接词(已填阈值)}{随机语气包裹(已填动作原文)}。动作核心词+档位原样, 仅连接词+语气包裹随机。
void appendTierLine(StringBuilder sb, List wxids, int threshold, String text) {
    for (int i = 0; i < wxids.size(); i++) sb.append("[AtWx=").append((String) wxids.get(i)).append("]");
    // v1.6.0a: 动作未设(占位"未设")或空 → @文本只保留阈值连接词(去尾部逗号), 不带动作语气包裹, 不发"未设"二字。
    String _t = (text == null) ? "" : text.trim();
    if (_t.isEmpty() || "未设".equals(_t)) {
        String conn = pickConnector(threshold);
        if (conn != null) {
            // 去掉连接词尾部的中/英文逗号 (无动作时收尾干净: "超过20元，" → "超过20元")
            while (conn.length() > 0) {
                char last = conn.charAt(conn.length() - 1);
                if (last == '，' || last == ',' || last == ' ') conn = conn.substring(0, conn.length() - 1);
                else break;
            }
        } else conn = "超过" + threshold + "元";
        sb.append(" ").append(conn);
        return;
    }
    sb.append(" ").append(pickConnector(threshold)).append(pickActionPhrase(text));
}

// ================================================================
// ============ 反射: BFS 找领取者 List (后台线程, 强上界) ============
// ================================================================
Object hbFindReceiverList(Object root) {
    if (root == null) return null;
    java.util.Set visited = new java.util.HashSet();
    java.util.ArrayDeque queue = new java.util.ArrayDeque();
    queue.add(new Object[]{ root, Integer.valueOf(0) });
    visited.add(Integer.valueOf(System.identityHashCode(root)));
    int visitCount = 0;
    try {
        while (!queue.isEmpty()) {
            if (visitCount >= MAX_VISIT) break;
            Object[] entry = (Object[]) queue.poll();
            Object obj = entry[0];
            int depth = ((Integer) entry[1]).intValue();
            visitCount++;
            if (obj == null) continue;

            if (hbIsReceiverList(obj)) return obj;
            if (depth >= MAX_DEPTH) continue;
            if (hbIsLeaf(obj)) continue;

            Class c = obj.getClass();
            int superHops = 0;
            while (c != null && superHops <= 3) {
                if (visitCount >= MAX_VISIT) break;
                java.lang.reflect.Field[] fields = null;
                try { fields = c.getDeclaredFields(); } catch (Throwable t) { fields = null; }
                if (fields != null) {
                    for (int i = 0; i < fields.length; i++) {
                        if (visitCount >= MAX_VISIT) break;
                        java.lang.reflect.Field f = fields[i];
                        try {
                            if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                            f.setAccessible(true);
                            Object child = null;
                            try { child = f.get(obj); } catch (Throwable t) { continue; }
                            if (child == null) continue;
                            Integer h = Integer.valueOf(System.identityHashCode(child));
                            if (visited.contains(h)) continue;
                            visited.add(h);
                            if (hbIsLeaf(child)) continue;
                            queue.add(new Object[]{ child, Integer.valueOf(depth + 1) });
                        } catch (Throwable t) { continue; }
                    }
                }
                try { c = c.getSuperclass(); } catch (Throwable t) { c = null; }
                superHops++;
            }
        }
    } catch (Throwable t) {
        hbBgLog("[RP] " + hbNow() + " find-list BFS err: " + t + " (visited=" + visitCount + ").");
    }
    return null;
}

// 候选判定: 非空 List/数组, 元素类名匹配领取者特征 (luckymoney 包 或 含 receiver/detail/hb/member)。
boolean hbIsReceiverList(Object obj) {
    try {
        Object firstElem = null;
        int size = 0;
        if (obj instanceof java.util.List) {
            java.util.List lst = (java.util.List) obj;
            size = lst.size();
            if (size == 0) return false;
            firstElem = lst.get(0);
        } else if (obj.getClass().isArray()) {
            size = java.lang.reflect.Array.getLength(obj);
            if (size == 0) return false;
            firstElem = java.lang.reflect.Array.get(obj, 0);
        } else return false;
        if (firstElem == null) return false;
        String ecn = firstElem.getClass().getName();
        if (ecn == null) return false;
        if (ecn.startsWith(LM_PKG)) return true;
        String low = ecn.toLowerCase();
        if (low.indexOf("receiver") >= 0 || low.indexOf("detail") >= 0
            || low.indexOf("hb") >= 0 || low.indexOf("member") >= 0
            || low.indexOf("luckymoney") >= 0) return true;
        return false;
    } catch (Throwable t) { return false; }
}

boolean hbIsLeaf(Object o) {
    if (o == null) return true;
    if (o instanceof Number) return true;
    if (o instanceof String) return true;
    if (o instanceof Boolean) return true;
    if (o instanceof Character) return true;
    if (o instanceof Enum) return true;
    if (o instanceof Class) return true;
    if (o instanceof java.math.BigDecimal) return true;
    return false;
}

// ================================================================
// ============ 配置 Dialog (参考 GroupAdmin) ============
// ================================================================
int _rpC(String s) { return android.graphics.Color.parseColor(s); }
android.graphics.drawable.GradientDrawable _rpRound(String fill, int radius) {
    android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
    d.setColor(_rpC(fill));
    d.setCornerRadius(radius);
    return d;
}
android.widget.Button _rpBtn(Activity ctx, String text, String fillColor) {
    int dp = (int) ctx.getResources().getDisplayMetrics().density;
    android.widget.Button b = new android.widget.Button(ctx);
    b.setText(text);
    b.setTextColor(_rpC("#E8E9EC"));
    b.setBackground(_rpRound(fillColor, 24));
    b.setAllCaps(false);
    b.setTextSize(14);
    b.setPadding(20 * dp, 12 * dp, 20 * dp, 12 * dp);
    b.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));
    return b;
}
android.widget.EditText _rpEdit(Activity ctx, String val, boolean numeric) {
    int dp = (int) ctx.getResources().getDisplayMetrics().density;
    android.widget.EditText e = new android.widget.EditText(ctx);
    e.setText(val);
    e.setTextColor(_rpC("#E8E9EC"));
    e.setHintTextColor(_rpC("#7C828E"));
    e.setBackground(_rpRound("#252934", 12));
    e.setPadding(12 * dp, 8 * dp, 12 * dp, 8 * dp);
    e.setTextSize(14);
    if (numeric) e.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
    e.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));
    return e;
}
void _rpLabel(android.widget.LinearLayout root, Activity ctx, String text) {
    int dp = (int) ctx.getResources().getDisplayMetrics().density;
    android.widget.TextView tv = new android.widget.TextView(ctx);
    tv.setText(text);
    tv.setTextColor(_rpC("#E8E9EC"));
    tv.setTextSize(13);
    tv.setPadding(0, 8 * dp, 0, 4 * dp);
    root.addView(tv);
}
void _rpSpacer(android.widget.LinearLayout root, Activity ctx, int h) {
    android.view.View v = new android.view.View(ctx);
    v.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
        android.widget.LinearLayout.LayoutParams.MATCH_PARENT, h));
    root.addView(v);
}

// WAuxiliary 设置入口
void openSettings() {
    try {
        // 无 groupId 上下文 → 全局配置 Dialog (启用开关那一行用提示文字代替)
        showConfigDialog(null);
    } catch (Throwable t) { try { toast("打开设置失败: " + t); } catch (Throwable t2) {} }
}

// ================================================================
// ===== v1.8.0 §23: 设置页拆分 (主菜单 Dialog + 二级子页 Dialog) =====
//   只重构 Dialog 展示层 — worker / onHandleMsg 热路径 / 文字命令 / 存储键 全不动。
//   读取/校验/写入键全沿用既有 helper (setTiers/rpParseTierBox/setCustomOn/saveIntField/...)。
//   每个子页保存「只写本页对应的 key」, 互不影响其它配置。
//   §7 ANR: 只构建 Dialog/EditText/Button, 绝不做 UI 节点遍历; 排除名单沿用 rpRebuildExcludeRows。
// ================================================================

// 通用子页根容器 (VERTICAL LinearLayout + 暗色背景 + padding)。
android.widget.LinearLayout _rpPageRoot(Activity ctx) {
    int dp = (int) ctx.getResources().getDisplayMetrics().density;
    android.widget.LinearLayout root = new android.widget.LinearLayout(ctx);
    root.setOrientation(android.widget.LinearLayout.VERTICAL);
    root.setPadding(16 * dp, 16 * dp, 16 * dp, 16 * dp);
    root.setBackground(_rpRound("#0F1115", 0));
    return root;
}

// 通用子页弹窗骨架: 把 root 包进 ScrollView, AlertDialog(标题, setView, 「💾 保存」, 「← 返回」)。
//   「保存」: 调 onSave.run() 后回主菜单 showConfigDialog(groupId)。
//   「返回」: 直接回主菜单 (不保存)。
//   两个回调都各自 try/catch, 异常 toast 不崩 Dialog。
void _rpShowSubPage(final Activity ctx, final String groupId, String dlgTitle, android.widget.LinearLayout root, final Runnable onSave) {
    android.widget.ScrollView sv = new android.widget.ScrollView(ctx);
    sv.addView(root);
    sv.setBackground(_rpRound("#0F1115", 0));
    new android.app.AlertDialog.Builder(ctx)
        .setTitle(dlgTitle)
        .setView(sv)
        .setPositiveButton("💾 保存", new android.content.DialogInterface.OnClickListener() {
            public void onClick(android.content.DialogInterface d, int which) {
                try { if (onSave != null) onSave.run(); } catch (Throwable t) { try { toast("保存失败: " + t); } catch (Throwable t2) {} }
                try { showConfigDialog(groupId); } catch (Throwable t) {}
            }
        })
        .setNegativeButton("← 返回", new android.content.DialogInterface.OnClickListener() {
            public void onClick(android.content.DialogInterface d, int which) {
                try { showConfigDialog(groupId); } catch (Throwable t) {}
            }
        })
        .create()
        .show();
}

// 主菜单 Dialog (§23.1): 顶部本群状态摘要 + 一列入口按钮。groupId 非空=群上下文; null=全局上下文 (隐藏群专属项)。
void showConfigDialog(final String groupId) {
    final Activity ctx = getTopActivity();
    if (ctx == null) { try { toast("无法获取 Activity"); } catch (Throwable t) {} return; }
    try {
        int dp = (int) ctx.getResources().getDisplayMetrics().density;
        // v1.8.1: 捕获主菜单 dialog, 入口按钮开子页前先 dismiss 它 → 任意时刻只一个 Dialog (修「关闭」失效=对话框堆叠)。
        final android.app.AlertDialog[] menuHolder = new android.app.AlertDialog[1];
        android.widget.LinearLayout root = _rpPageRoot(ctx);

        android.widget.TextView title = new android.widget.TextView(ctx);
        title.setText("🧧 红包统计 v1.11.2 设置");
        title.setTextColor(_rpC("#E8E9EC"));
        title.setTextSize(18);
        title.setPadding(0, 0, 0, 8 * dp);
        root.addView(title);

        // ===== 顶部状态摘要 (§23.1) =====
        android.widget.TextView summary = new android.widget.TextView(ctx);
        summary.setTextColor(_rpC("#7C828E"));
        summary.setTextSize(12);
        StringBuilder sb = new StringBuilder();
        if (groupId != null) {
            sb.append("群: ").append(groupId).append("\n");
            sb.append("启用: ").append(isGroupEnabled(groupId) ? "✅ 开" : "❌ 关").append("\n");
            sb.append("每日定时: ").append(isDailyEnabled(groupId) ? ("⏰ 开 (每天" + cfgDailyHour() + "点)") : "关").append("\n");
            String kw = "";
            try { kw = getCustomKw(groupId); } catch (Throwable t) { kw = ""; }
            sb.append("定制: ").append(isCustomOn(groupId) ? ("✅ 开 关键字「" + (kw == null ? "" : kw) + "」") : "关");
        } else {
            sb.append("全局面板 (无群上下文)\n本群启用/每日定时/排除名单请在目标群内操作。\n此处配置全局默认阈值/文案/延迟/at上限。");
        }
        summary.setText(sb.toString());
        root.addView(summary);
        _rpSpacer(root, ctx, 10 * dp);

        // ===== 入口按钮 =====
        if (groupId != null) {
            // 群上下文: 全部入口。
            android.widget.Button bBasic = _rpBtn(ctx, "📌 基础开关", "#5CB6FF");
            bBasic.setOnClickListener(new android.view.View.OnClickListener() {
                public void onClick(android.view.View v) { try { if (menuHolder[0] != null) menuHolder[0].dismiss(); } catch (Throwable td) {} try { showPageBasic(groupId); } catch (Throwable t) { try { toast("打开失败: " + t); } catch (Throwable t2) {} } }
            });
            root.addView(bBasic);
            _rpSpacer(root, ctx, 6 * dp);
        }
        android.widget.Button bNormal = _rpBtn(ctx, "🧧 普通档表", "#5CB6FF");
        bNormal.setOnClickListener(new android.view.View.OnClickListener() {
            public void onClick(android.view.View v) { try { if (menuHolder[0] != null) menuHolder[0].dismiss(); } catch (Throwable td) {} try { showPageNormalTiers(groupId); } catch (Throwable t) { try { toast("打开失败: " + t); } catch (Throwable t2) {} } }
        });
        root.addView(bNormal);
        _rpSpacer(root, ctx, 6 * dp);

        if (groupId != null) {
            android.widget.Button bCustom = _rpBtn(ctx, "🎯 定制包", "#5CB6FF");
            bCustom.setOnClickListener(new android.view.View.OnClickListener() {
                public void onClick(android.view.View v) { try { if (menuHolder[0] != null) menuHolder[0].dismiss(); } catch (Throwable td) {} try { showPageCustom(groupId); } catch (Throwable t) { try { toast("打开失败: " + t); } catch (Throwable t2) {} } }
            });
            root.addView(bCustom);
            _rpSpacer(root, ctx, 6 * dp);
        }

        android.widget.Button bDelay = _rpBtn(ctx, "⏱️ 延迟 & @上限", "#5CB6FF");
        bDelay.setOnClickListener(new android.view.View.OnClickListener() {
            public void onClick(android.view.View v) { try { if (menuHolder[0] != null) menuHolder[0].dismiss(); } catch (Throwable td) {} try { showPageDelay(groupId); } catch (Throwable t) { try { toast("打开失败: " + t); } catch (Throwable t2) {} } }
        });
        root.addView(bDelay);
        _rpSpacer(root, ctx, 6 * dp);

        if (groupId != null) {
            android.widget.Button bExcl = _rpBtn(ctx, "🚫 排除名单", "#5CB6FF");
            bExcl.setOnClickListener(new android.view.View.OnClickListener() {
                public void onClick(android.view.View v) { try { if (menuHolder[0] != null) menuHolder[0].dismiss(); } catch (Throwable td) {} try { showPageExclude(groupId); } catch (Throwable t) { try { toast("打开失败: " + t); } catch (Throwable t2) {} } }
            });
            root.addView(bExcl);
            _rpSpacer(root, ctx, 6 * dp);
        }

        // v1.11.0 §25: 伸手党治理子页 (仅群上下文)。
        if (groupId != null) {
            android.widget.Button bFree = _rpBtn(ctx, "🖐 伸手党治理", "#5CB6FF");
            bFree.setOnClickListener(new android.view.View.OnClickListener() {
                public void onClick(android.view.View v) { try { if (menuHolder[0] != null) menuHolder[0].dismiss(); } catch (Throwable td) {} try { showPageFreeloader(groupId); } catch (Throwable t) { try { toast("打开失败: " + t); } catch (Throwable t2) {} } }
            });
            root.addView(bFree);
            _rpSpacer(root, ctx, 6 * dp);
        }

        // v1.9.0 §24: 转发对象子页 (选好友/群 + 设置后通知)。导出/每日定时均沿用 cfgExportTarget(), 此处可改目标。
        android.widget.Button bForward = _rpBtn(ctx, "📮 转发对象", "#5CB6FF");
        bForward.setOnClickListener(new android.view.View.OnClickListener() {
            public void onClick(android.view.View v) { try { if (menuHolder[0] != null) menuHolder[0].dismiss(); } catch (Throwable td) {} try { showPageForward(groupId); } catch (Throwable t) { try { toast("打开失败: " + t); } catch (Throwable t2) {} } }
        });
        root.addView(bForward);
        _rpSpacer(root, ctx, 6 * dp);

        // 导出: 直接动作 (不进子页, §23.1)。
        android.widget.Button bExport = _rpBtn(ctx, "📤 导出今日 → 私聊", "#3FB27F");
        bExport.setOnClickListener(new android.view.View.OnClickListener() {
            public void onClick(android.view.View v) {
                try { rpExportToday(); } catch (Throwable t) { try { toast("❌ 导出失败: " + t); } catch (Throwable t2) {} }
            }
        });
        root.addView(bExport);

        android.widget.ScrollView sv = new android.widget.ScrollView(ctx);
        sv.addView(root);
        sv.setBackground(_rpRound("#0F1115", 0));
        menuHolder[0] = new android.app.AlertDialog.Builder(ctx)
            .setView(sv)
            .setNegativeButton("关闭", null)
            .create();
        menuHolder[0].show();
    } catch (Throwable t) { try { toast("打开设置失败: " + t); } catch (Throwable t2) {} }
}

// ----- 子页: 📌 基础开关 (§23.2): 本群启用 toggle + 每日定时 toggle。即时生效, 无「保存」语义键 (toggle 当场写)。 -----
void showPageBasic(final String groupId) {
    final Activity ctx = getTopActivity();
    if (ctx == null) { try { toast("无法获取 Activity"); } catch (Throwable t) {} return; }
    try {
        int dp = (int) ctx.getResources().getDisplayMetrics().density;
        android.widget.LinearLayout root = _rpPageRoot(ctx);

        android.widget.TextView gid = new android.widget.TextView(ctx);
        gid.setText("群: " + groupId);
        gid.setTextColor(_rpC("#7C828E"));
        gid.setTextSize(11);
        root.addView(gid);

        // 本群启用开关 (即时生效, 沿用 enableGroup/disableGroup)。
        final boolean[] enabledHolder = new boolean[]{ isGroupEnabled(groupId) };
        final android.widget.Button toggleBtn = _rpBtn(ctx, enabledHolder[0] ? "🔕 关闭本群红包统计" : "✅ 开启本群红包统计", enabledHolder[0] ? "#FF6B6B" : "#5CB6FF");
        toggleBtn.setOnClickListener(new android.view.View.OnClickListener() {
            public void onClick(android.view.View v) {
                if (enabledHolder[0]) { disableGroup(groupId); enabledHolder[0] = false; toggleBtn.setText("✅ 开启本群红包统计"); toggleBtn.setBackground(_rpRound("#5CB6FF", 24)); toast("已关闭本群"); }
                else { enableGroup(groupId); enabledHolder[0] = true; toggleBtn.setText("🔕 关闭本群红包统计"); toggleBtn.setBackground(_rpRound("#FF6B6B", 24)); toast("已开启本群"); }
            }
        });
        root.addView(toggleBtn);
        _rpSpacer(root, ctx, 6 * dp);

        // v1.9.2 §24: 每日定时开关 + 推送时间已迁至「📮 转发对象」子页, 本页只留「本群启用」。

        android.widget.TextView hint = new android.widget.TextView(ctx);
        hint.setText("提示: 开关点击即时生效 (无需保存)。每日定时开关/推送时间见「📮 转发对象」子页。");
        hint.setTextColor(_rpC("#7C828E"));
        hint.setTextSize(11);
        root.addView(hint);

        // 本页无延迟写 key (toggle 即时生效), onSave 传 null → 「保存」等同返回主菜单。
        _rpShowSubPage(ctx, groupId, "📌 基础开关", root, null);
    } catch (Throwable t) { try { toast("打开基础开关失败: " + t); } catch (Throwable t2) {} }
}

// ----- 子页: 🧧 普通档表 (§23.2): 群上下文=本群档表多行 EditText; 全局=全局默认三档阈值+文案。 -----
void showPageNormalTiers(final String groupId) {
    final Activity ctx = getTopActivity();
    if (ctx == null) { try { toast("无法获取 Activity"); } catch (Throwable t) {} return; }
    try {
        int dp = (int) ctx.getResources().getDisplayMetrics().density;
        android.widget.LinearLayout root = _rpPageRoot(ctx);

        if (groupId != null) {
            // 本群档表: 单个多行 EditText (每行 "阈值 动作"), 保存复用 rpParseTierBox 逐行解析 + setTiers。
            _rpLabel(root, ctx, "本群档位 (1-10 行, 每行: 阈值 动作; 阈值正整数递增)");
            StringBuilder pre = new StringBuilder();
            List _tt = getTiers(groupId);
            for (int i = 0; i < _tt.size(); i++) {
                Object[] te = (Object[]) _tt.get(i);
                if (i > 0) pre.append("\n");
                pre.append(((Integer) te[0]).intValue()).append(" ").append((String) te[1]);
            }
            final android.widget.EditText tierBox = _rpEdit(ctx, pre.toString(), false);
            tierBox.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
            tierBox.setMinLines(2); tierBox.setMaxLines(11); tierBox.setSingleLine(false);
            root.addView(tierBox);

            final String fGroupId = groupId;
            Runnable onSave = new Runnable() {
                public void run() {
                    try {
                        // 复用 rpParseTierBox: 全无效不保存(不破坏原表), 部分有效存有效行。只写本群档表键。
                        Object[] res = rpParseTierBox(tierBox.getText().toString());
                        List parsed = (List) res[0];
                        StringBuilder errs = (StringBuilder) res[1];
                        int errCount = ((Integer) res[2]).intValue();
                        boolean overflow = ((Boolean) res[3]).booleanValue();
                        if (parsed.isEmpty()) {
                            toast("❌ 档位为空或全部无效, 未保存档位" + (errs.length() > 0 ? ": " + errs.toString() : ""));
                        } else {
                            setTiers(fGroupId, parsed);
                            if (errCount > 0 || overflow) toast("⚠️ 部分档位行有误已忽略 (" + errs.toString() + "), 已存 " + parsed.size() + " 档");
                            else toast("✅ 已存 " + parsed.size() + " 档");
                        }
                    } catch (Throwable t) { try { toast("档表保存失败: " + t); } catch (Throwable t2) {} }
                }
            };
            _rpShowSubPage(ctx, groupId, "🧧 普通档表", root, onSave);
        } else {
            // 全局默认三档 (cfgT1/2/3 + cfgTxt1/2/3), 作为未配置群的回退源。保存只写 K_T*/K_TXT*。
            _rpLabel(root, ctx, "全局默认三档阈值 (元, 严格大于; 仅作未配置群的回退)");
            final android.widget.EditText e1 = _rpEdit(ctx, String.valueOf(cfgT1()), true); root.addView(e1);
            _rpSpacer(root, ctx, 4 * dp);
            final android.widget.EditText e2 = _rpEdit(ctx, String.valueOf(cfgT2()), true); root.addView(e2);
            _rpSpacer(root, ctx, 4 * dp);
            final android.widget.EditText e3 = _rpEdit(ctx, String.valueOf(cfgT3()), true); root.addView(e3);
            _rpLabel(root, ctx, "全局默认三档动作核心词 (原样, 周围措辞会自动随机)");
            final android.widget.EditText t1 = _rpEdit(ctx, cfgTxt1(), false); root.addView(t1);
            _rpSpacer(root, ctx, 4 * dp);
            final android.widget.EditText t2 = _rpEdit(ctx, cfgTxt2(), false); root.addView(t2);
            _rpSpacer(root, ctx, 4 * dp);
            final android.widget.EditText t3 = _rpEdit(ctx, cfgTxt3(), false); root.addView(t3);

            Runnable onSave = new Runnable() {
                public void run() {
                    try {
                        saveIntField(K_T1, e1); saveIntField(K_T2, e2); saveIntField(K_T3, e3);
                        saveStrField(K_TXT1, t1); saveStrField(K_TXT2, t2); saveStrField(K_TXT3, t3);
                        toast("✅ 全局默认档已保存");
                    } catch (Throwable t) { try { toast("保存失败: " + t); } catch (Throwable t2) {} }
                }
            };
            _rpShowSubPage(ctx, groupId, "🧧 全局默认档", root, onSave);
        }
    } catch (Throwable t) { try { toast("打开普通档表失败: " + t); } catch (Throwable t2) {} }
}

// ----- 子页: 🎯 定制包 (§22.4/§22.6, 仅群上下文): 定制开关(仅此处可改)+关键字+定制档表+定制群规前缀。 -----
void showPageCustom(final String groupId) {
    final Activity ctx = getTopActivity();
    if (ctx == null) { try { toast("无法获取 Activity"); } catch (Throwable t) {} return; }
    if (groupId == null) { try { toast("全局面板无定制包配置 (需群上下文)"); } catch (Throwable t) {} return; }
    try {
        int dp = (int) ctx.getResources().getDisplayMetrics().density;
        android.widget.LinearLayout root = _rpPageRoot(ctx);

        _rpLabel(root, ctx, "—— 定制包 (祝福语包含关键字时改用定制档表) ——");
        // ☑ 是否开启定制 (仅此处可改 rp_custom_on)。
        final boolean[] customOnHolder = new boolean[]{ isCustomOn(groupId) };
        final android.widget.Button customToggle = _rpBtn(ctx, customOnHolder[0] ? "✅ 已开启定制 (点击关闭)" : "🔴 未开启定制 (点击开启)", customOnHolder[0] ? "#FF6B6B" : "#5CB6FF");
        customToggle.setOnClickListener(new android.view.View.OnClickListener() {
            public void onClick(android.view.View v) {
                if (customOnHolder[0]) { customOnHolder[0] = false; customToggle.setText("🔴 未开启定制 (点击开启)"); customToggle.setBackground(_rpRound("#5CB6FF", 24)); }
                else { customOnHolder[0] = true; customToggle.setText("✅ 已开启定制 (点击关闭)"); customToggle.setBackground(_rpRound("#FF6B6B", 24)); }
            }
        });
        root.addView(customToggle);
        // 📝 定制关键字 (rp_custom_kw)。
        _rpLabel(root, ctx, "定制关键字 (包含匹配祝福语; 空=不启用)");
        final android.widget.EditText customKwBox = _rpEdit(ctx, getCustomKw(groupId), false); root.addView(customKwBox);
        // 📋 定制档表 (rp_tiers_custom; 每行 阈值 动作; 复用普通档表逐行解析)。
        _rpLabel(root, ctx, "定制档位 (1-10 行, 每行: 阈值 动作; 未配置则回退普通档)");
        StringBuilder cpre = new StringBuilder();
        List _ctt = getTiersByType(groupId, true);
        for (int i = 0; i < _ctt.size(); i++) {
            Object[] cte = (Object[]) _ctt.get(i);
            if (i > 0) cpre.append("\n");
            cpre.append(((Integer) cte[0]).intValue()).append(" ").append((String) cte[1]);
        }
        final android.widget.EditText customTierBox = _rpEdit(ctx, cpre.toString(), false);
        customTierBox.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        customTierBox.setMinLines(2); customTierBox.setMaxLines(11); customTierBox.setSingleLine(false);
        root.addView(customTierBox);
        // v1.7.1 §22.6: 定制群规前缀 (rp_custom_rule_prefix_<gid>)。
        _rpLabel(root, ctx, "定制群规前缀 (定制包第一条群规文本; 空=默认「" + DEF_CUSTOM_RULE_PREFIX + "」)");
        final android.widget.EditText customRulePrefixBox = _rpEdit(ctx, cfgCustomRulePrefix(groupId), false); root.addView(customRulePrefixBox);

        final String fGroupId = groupId;
        Runnable onSave = new Runnable() {
            public void run() {
                try {
                    // 只写定制相关键 (rp_custom_on / rp_custom_kw / rp_custom_rule_prefix / rp_tiers_custom)。
                    setCustomOn(fGroupId, customOnHolder[0]);
                    setCustomKw(fGroupId, customKwBox.getText().toString());
                    putString(customRulePrefixKey(fGroupId), customRulePrefixBox.getText().toString().trim());
                    // 定制档表: 复用 rpParseTierBox; 全无效不保存(不破坏原表), 部分有效存有效行。
                    Object[] cres = rpParseTierBox(customTierBox.getText().toString());
                    List cparsed = (List) cres[0];
                    StringBuilder cerrs = (StringBuilder) cres[1];
                    int cerrCount = ((Integer) cres[2]).intValue();
                    boolean coverflow = ((Boolean) cres[3]).booleanValue();
                    if (cparsed.isEmpty()) {
                        toast("⚠️ 定制档位为空或全部无效, 未保存定制档位 (开关/关键字/前缀已存)" + (cerrs.length() > 0 ? ": " + cerrs.toString() : ""));
                    } else {
                        setTiersToKey(tiersCustomKey(fGroupId), cparsed);
                        if (cerrCount > 0 || coverflow) toast("⚠️ 部分定制档位行有误已忽略 (" + cerrs.toString() + "), 已存 " + cparsed.size() + " 定制档");
                        else toast("✅ 定制配置已保存");
                    }
                } catch (Throwable t) { try { toast("定制保存失败: " + t); } catch (Throwable t2) {} }
            }
        };
        _rpShowSubPage(ctx, groupId, "🎯 定制包", root, onSave);
    } catch (Throwable t) { try { toast("打开定制包失败: " + t); } catch (Throwable t2) {} }
}

// ----- 子页: ⏱️ 延迟 & @上限 (§23.2): 首次/重试1/重试2/重试3(秒) + at上限。保存只写 K_DELAY/K_RETRY1/K_RETRY2/K_RETRY3/K_AT_LIMIT。 -----
void showPageDelay(final String groupId) {
    final Activity ctx = getTopActivity();
    if (ctx == null) { try { toast("无法获取 Activity"); } catch (Throwable t) {} return; }
    try {
        int dp = (int) ctx.getResources().getDisplayMetrics().density;
        android.widget.LinearLayout root = _rpPageRoot(ctx);

        _rpLabel(root, ctx, "首次延迟 / 重试1 / 重试2 / 重试3 (秒)");
        final android.widget.EditText d0 = _rpEdit(ctx, String.valueOf(cfgDelay()), true); root.addView(d0);
        _rpSpacer(root, ctx, 4 * dp);
        final android.widget.EditText d1 = _rpEdit(ctx, String.valueOf(cfgRetry1()), true); root.addView(d1);
        _rpSpacer(root, ctx, 4 * dp);
        final android.widget.EditText d2 = _rpEdit(ctx, String.valueOf(cfgRetry2()), true); root.addView(d2);
        _rpSpacer(root, ctx, 4 * dp);
        final android.widget.EditText d3 = _rpEdit(ctx, String.valueOf(cfgRetry3()), true); root.addView(d3);   // v1.10.0 §4: 重试3 (末档, 默认1小时)
        _rpLabel(root, ctx, "at 上限 (达标总人数超此值改发无@群规消息)");
        final android.widget.EditText al = _rpEdit(ctx, String.valueOf(cfgAtLimit()), true); root.addView(al);

        Runnable onSave = new Runnable() {
            public void run() {
                try {
                    saveLongField(K_DELAY, d0); saveLongField(K_RETRY1, d1); saveLongField(K_RETRY2, d2); saveLongField(K_RETRY3, d3);
                    saveIntField(K_AT_LIMIT, al);
                    toast("✅ 延迟 & @上限已保存");
                } catch (Throwable t) { try { toast("保存失败: " + t); } catch (Throwable t2) {} }
            }
        };
        _rpShowSubPage(ctx, groupId, "⏱️ 延迟 & @上限", root, onSave);
    } catch (Throwable t) { try { toast("打开延迟设置失败: " + t); } catch (Throwable t2) {} }
}

// ----- 子页: 🖐 伸手党治理 (§25, 仅群上下文): 开关 toggle + 窗口(分钟) EditText。保存只写 rp_freeloader_on/win_<gid>。 -----
void showPageFreeloader(final String groupId) {
    final Activity ctx = getTopActivity();
    if (ctx == null) { try { toast("无法获取 Activity"); } catch (Throwable t) {} return; }
    if (groupId == null) { try { toast("伸手党治理需在目标群内设置"); } catch (Throwable t) {} return; }
    try {
        int dp = (int) ctx.getResources().getDisplayMetrics().density;
        android.widget.LinearLayout root = _rpPageRoot(ctx);

        _rpLabel(root, ctx, "—— 伸手党治理 (抢到前N分钟未发言→发群管警告, 累计可触发踢人) ——");
        // 开关 toggle (保存时写 rp_freeloader_on)。
        final boolean[] onHolder = new boolean[]{ cfgFreeloaderOn(groupId) };
        final android.widget.Button freeToggle = _rpBtn(ctx, onHolder[0] ? "✅ 已开启 (点击关闭)" : "🔴 未开启 (点击开启)", onHolder[0] ? "#FF6B6B" : "#5CB6FF");
        freeToggle.setOnClickListener(new android.view.View.OnClickListener() {
            public void onClick(android.view.View v) {
                if (onHolder[0]) { onHolder[0] = false; freeToggle.setText("🔴 未开启 (点击开启)"); freeToggle.setBackground(_rpRound("#5CB6FF", 24)); }
                else { onHolder[0] = true; freeToggle.setText("✅ 已开启 (点击关闭)"); freeToggle.setBackground(_rpRound("#FF6B6B", 24)); }
            }
        });
        root.addView(freeToggle);
        // 窗口 EditText (分钟, 1-1440)。
        _rpLabel(root, ctx, "判定窗口 (分钟, 1-1440; 默认30)");
        final android.widget.EditText winBox = _rpEdit(ctx, String.valueOf(cfgFreeloaderWin(groupId)), true); root.addView(winBox);

        android.widget.TextView hint = new android.widget.TextView(ctx);
        hint.setText("说明: 抢到红包者在抢到时刻往前该窗口内未在本群发言 → 自动发 [警告] 命令交群管执行。只发命令不直接踢人。");
        hint.setTextColor(_rpC("#7C828E"));
        hint.setTextSize(11);
        root.addView(hint);

        final String fGroupId = groupId;
        Runnable onSave = new Runnable() {
            public void run() {
                try {
                    putString(K_FREELOADER_ON_PREFIX + fGroupId, onHolder[0] ? "1" : "");
                    // 窗口校验 1-1440, 越界/非法不写 (保留原值)。
                    String wv = winBox.getText().toString().trim();
                    boolean winOk = false;
                    try { int n = Integer.parseInt(wv); if (n >= 1 && n <= 1440) { putString(K_FREELOADER_WIN_PREFIX + fGroupId, String.valueOf(n)); winOk = true; } } catch (Throwable t) {}
                    if (winOk) toast("✅ 伸手党治理已保存 (" + (onHolder[0] ? "开" : "关") + ", 窗口" + cfgFreeloaderWin(fGroupId) + "分钟)");
                    else toast("⚠️ 开关已存; 窗口需 1-1440 整数, 未改 (当前" + cfgFreeloaderWin(fGroupId) + "分钟)");
                } catch (Throwable t) { try { toast("保存失败: " + t); } catch (Throwable t2) {} }
            }
        };
        _rpShowSubPage(ctx, groupId, "🖐 伸手党治理", root, onSave);
    } catch (Throwable t) { try { toast("打开伸手党治理失败: " + t); } catch (Throwable t2) {} }
}

// ----- 子页: 🚫 排除名单 (§12, 仅群上下文): 成员列表 + 移除 (沿用 rpRebuildExcludeRows)。无独立「保存」(移除即时生效)。 -----
void showPageExclude(final String groupId) {
    final Activity ctx = getTopActivity();
    if (ctx == null) { try { toast("无法获取 Activity"); } catch (Throwable t) {} return; }
    if (groupId == null) { try { toast("全局面板无排除名单 (需群上下文)"); } catch (Throwable t) {} return; }
    try {
        android.widget.LinearLayout root = _rpPageRoot(ctx);
        _rpLabel(root, ctx, "红包提醒排除名单 (此名单的人永不被 @ / 不计入上限)");
        final android.widget.LinearLayout exclBox = new android.widget.LinearLayout(ctx);
        exclBox.setOrientation(android.widget.LinearLayout.VERTICAL);
        root.addView(exclBox);
        rpRebuildExcludeRows(ctx, exclBox, groupId);   // 沿用现有逻辑/移除按钮 (即时刷新, 合规无遍历)

        android.widget.TextView hint = new android.widget.TextView(ctx);
        hint.setText("提示: 在目标群内用 bot 账号 @某人 发『红包排除』可加入。移除点击即时生效。");
        hint.setTextColor(_rpC("#7C828E"));
        hint.setTextSize(11);
        root.addView(hint);

        // 排除名单移除即时生效, 无延迟写 key, onSave 传 null → 「保存」等同返回主菜单。
        _rpShowSubPage(ctx, groupId, "🚫 排除名单", root, null);
    } catch (Throwable t) { try { toast("打开排除名单失败: " + t); } catch (Throwable t2) {} }
}

// ----- 子页: 📮 转发对象 (v1.9.0 §24.2): 显当前目标 + 选好友/选群 + 手动输入兜底。 -----
//   选好友/选群: 后台线程调 getFriendList()/getGroupList() (缓存 sCachedFriendList/sCachedGroupList),
//   完成后 Handler(Looper.getMainLooper()).post 回主线程弹单选 AlertDialog (setItems, 无 UI 遍历, §7 ANR)。
//   选中/手填 → 存 rp_export_target + rp_export_target_name → toast → sendForwardNotice(§24.3)。
void showPageForward(final String groupId) {
    final Activity ctx = getTopActivity();
    if (ctx == null) { try { toast("无法获取 Activity"); } catch (Throwable t) {} return; }
    try {
        android.widget.LinearLayout root = _rpPageRoot(ctx);

        final boolean hasGroup = (normGroupId(groupId).length() > 0);
        // 当前目标 (§24.2 顶部): 显示【当前群】的转发对象 (按群, 未设回退全局默认)。无群上下文则提示需在群内设置。
        android.widget.TextView cur = new android.widget.TextView(ctx);
        if (hasGroup) {
            String curT = cfgExportTargetFor(groupId);
            String curN = cfgExportTargetNameFor(groupId);
            String curTail = (curT != null && curT.length() >= 4) ? curT.substring(curT.length() - 4) : curT;
            cur.setText("本群转发对象: " + ((curN != null && !curN.isEmpty()) ? curN : "(未命名)")
                + (rpTargetIsGroup(curT) ? "（群）" : "（好友）") + " 尾4: " + curTail);
        } else {
            cur.setText("⚠️ 无群上下文, 请在群内打开本设置以设置该群转发对象");
        }
        cur.setTextColor(_rpC("#E8E9EC"));
        cur.setTextSize(13);
        root.addView(cur);
        _rpLabel(root, ctx, "（本群每日定时/手动导出都发到此对象; 未设则回退全局默认）");

        // 选好友 / 选群 按钮。
        final android.widget.Button bFriend = _rpBtn(ctx, "👤 选好友", "#5CB6FF");
        bFriend.setOnClickListener(new android.view.View.OnClickListener() {
            public void onClick(android.view.View v) { try { rpPickTarget(ctx, groupId, false); } catch (Throwable t) { try { toast("打开失败: " + t); } catch (Throwable t2) {} } }
        });
        root.addView(bFriend);
        _rpSpacer(root, ctx, 6);

        final android.widget.Button bGroup = _rpBtn(ctx, "👥 选群", "#5CB6FF");
        bGroup.setOnClickListener(new android.view.View.OnClickListener() {
            public void onClick(android.view.View v) { try { rpPickTarget(ctx, groupId, true); } catch (Throwable t) { try { toast("打开失败: " + t); } catch (Throwable t2) {} } }
        });
        root.addView(bGroup);

        // 手动输入兜底 (§24.2)。
        _rpLabel(root, ctx, "手动输入 (列表找不到时): 填 wxid 或 xxx@chatroom");
        final android.widget.EditText manualBox = _rpEdit(ctx, "", false);
        root.addView(manualBox);
        final android.widget.Button bSaveManual = _rpBtn(ctx, "💾 保存手填", "#3FB27F");
        bSaveManual.setOnClickListener(new android.view.View.OnClickListener() {
            public void onClick(android.view.View v) {
                try {
                    String t = manualBox.getText().toString().trim();
                    if (t.isEmpty()) { try { toast("请输入 wxid 或 xxx@chatroom"); } catch (Throwable t2) {} return; }
                    rpApplyForwardTarget(groupId, t, t);   // 手填: 显示名同 target, 作用当前群
                } catch (Throwable t) { try { toast("保存失败: " + t); } catch (Throwable t2) {} }
            }
        });
        root.addView(bSaveManual);

        // v1.9.2 §20/§24: —— 每日定时 (从基础开关页迁来) ——
        _rpLabel(root, ctx, "—— 每日定时统计 ——");

        // 📅 本群每日定时开关 (即时生效, 独立于红包统计开关; 沿用 enable/disableDailyGroup)。
        if (hasGroup) {
            final boolean[] dailyHolder = new boolean[]{ isDailyEnabled(groupId) };
            final android.widget.Button dailyBtn = _rpBtn(ctx, dailyHolder[0] ? "✅ 已开启每日定时 (点击关闭)" : "🔴 未开启每日定时 (点击开启)", dailyHolder[0] ? "#FF6B6B" : "#5CB6FF");
            dailyBtn.setOnClickListener(new android.view.View.OnClickListener() {
                public void onClick(android.view.View v) {
                    try {
                        if (dailyHolder[0]) { disableDailyGroup(groupId); dailyHolder[0] = false; dailyBtn.setText("🔴 未开启每日定时 (点击开启)"); dailyBtn.setBackground(_rpRound("#5CB6FF", 24)); toast("已关闭本群每日定时"); }
                        else { enableDailyGroup(groupId); dailyHolder[0] = true; dailyBtn.setText("✅ 已开启每日定时 (点击关闭)"); dailyBtn.setBackground(_rpRound("#FF6B6B", 24)); toast("已开启本群每日定时"); }
                        try { showPageForward(groupId); } catch (Throwable t) {}   // 即时写后刷新本页
                    } catch (Throwable t) { try { toast("切换失败: " + t); } catch (Throwable t2) {} }
                }
            });
            root.addView(dailyBtn);
        } else {
            android.widget.TextView dn = new android.widget.TextView(ctx);
            dn.setText("⚠️ 每日定时开关需在群内设置 (当前无群上下文)");
            dn.setTextColor(_rpC("#7C828E"));
            dn.setTextSize(12);
            root.addView(dn);
        }

        // ⏰ 每日推送时间 (全局, 影响所有群; 沿用 K_DAILY_HOUR/cfgDailyHour, 0-23 夹取)。
        _rpLabel(root, ctx, "⏰ 每日推送时间 (小时, 0-23; 全局, 影响所有群)");
        final android.widget.EditText hourBox = _rpEdit(ctx, String.valueOf(cfgDailyHour()), true);
        root.addView(hourBox);

        // 「💾 保存」校验小时 0-23 后写 K_DAILY_HOUR (全局); 定时开关已即时生效, 不在此写。
        Runnable onSave = new Runnable() {
            public void run() {
                try {
                    String s = hourBox.getText().toString().trim();
                    if (s.isEmpty()) return;
                    int h;
                    try { h = Integer.parseInt(s); } catch (Throwable t) { try { toast("❌ 推送时间需为 0-23 的整数"); } catch (Throwable t2) {} return; }
                    if (h < 0 || h > 23) { try { toast("❌ 推送时间需在 0-23 之间"); } catch (Throwable t2) {} return; }
                    putString(K_DAILY_HOUR, String.valueOf(h));
                    try { toast("✅ 每日推送时间已设为 " + h + " 点 (全局)"); } catch (Throwable t2) {}
                } catch (Throwable t) { try { toast("保存失败: " + t); } catch (Throwable t2) {} }
            }
        };
        _rpShowSubPage(ctx, groupId, "📮 转发对象", root, onSave);
    } catch (Throwable t) { try { toast("打开转发对象失败: " + t); } catch (Throwable t2) {} }
}

// v1.9.0 §24.2: 后台线程加载好友/群 list (缓存), 完成后主线程弹单选 AlertDialog。isGroup=true 选群, false 选好友。
void rpPickTarget(final Activity ctx, final String groupId, final boolean isGroup) {
    try {
        try { toast("正在加载…"); } catch (Throwable t) {}
        new Thread(new Runnable() {
            public void run() {
                try {
                    final java.util.List list;
                    if (isGroup) {
                        if (sCachedGroupList == null) { try { sCachedGroupList = getGroupList(); } catch (Throwable t) { sCachedGroupList = null; } }
                        list = sCachedGroupList;
                    } else {
                        if (sCachedFriendList == null) { try { sCachedFriendList = getFriendList(); } catch (Throwable t) { sCachedFriendList = null; } }
                        list = sCachedFriendList;
                    }
                    // 主线程构建/弹 Dialog (§7 ANR: 只 setItems 单选, 无 UI 遍历)。
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
                        public void run() {
                            try {
                                if (list == null || list.isEmpty()) { try { toast("未获取到列表"); } catch (Throwable t) {} return; }
                                final int n = list.size();
                                final String[] ids = new String[n];
                                final String[] names = new String[n];
                                final String[] items = new String[n];
                                for (int i = 0; i < n; i++) {
                                    Object info = list.get(i);
                                    String id = ""; String nm = "";
                                    if (isGroup) {
                                        try { Object r = info.getClass().getMethod("getRoomId").invoke(info); if (r != null) id = r.toString(); } catch (Throwable t) {}
                                        try { Object nn = info.getClass().getMethod("getName").invoke(info); if (nn != null) nm = nn.toString(); } catch (Throwable t) {}
                                        if (nm == null || nm.trim().isEmpty()) nm = id;
                                        items[i] = "🏠 " + nm + "\nID:" + id;
                                    } else {
                                        try { Object w = info.getClass().getMethod("getWxid").invoke(info); if (w != null) id = w.toString(); } catch (Throwable t) {}
                                        String rmk = null; String nick = null;
                                        try { Object r = info.getClass().getMethod("getRemark").invoke(info); if (r != null) rmk = r.toString().trim(); } catch (Throwable t) {}
                                        try { Object nn = info.getClass().getMethod("getNickname").invoke(info); if (nn != null) nick = nn.toString().trim(); } catch (Throwable t) {}
                                        if (rmk != null && !rmk.isEmpty()) nm = rmk;
                                        else if (nick != null && !nick.isEmpty()) nm = nick;
                                        else nm = id;
                                        items[i] = "👤 " + nm + "\nID:" + id;
                                    }
                                    ids[i] = id; names[i] = nm;
                                }
                                new android.app.AlertDialog.Builder(ctx)
                                    .setTitle(isGroup ? "👥 选群" : "👤 选好友")
                                    .setItems(items, new android.content.DialogInterface.OnClickListener() {
                                        public void onClick(android.content.DialogInterface d, int which) {
                                            try {
                                                if (which < 0 || which >= ids.length) return;
                                                rpApplyForwardTarget(groupId, ids[which], names[which]);
                                            } catch (Throwable t) { try { toast("选择失败: " + t); } catch (Throwable t2) {} }
                                        }
                                    })
                                    .setNegativeButton("取消", null)
                                    .create()
                                    .show();
                            } catch (Throwable t) { try { toast("弹窗失败: " + t); } catch (Throwable t2) {} }
                        }
                    });
                } catch (Throwable t) {
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
                        public void run() { try { toast("加载失败"); } catch (Throwable t2) {} }
                    });
                }
            }
        }).start();
    } catch (Throwable t) { try { toast("加载失败: " + t); } catch (Throwable t2) {} }
}

// v1.9.1 §24.2/§24.3: 写【按群】目标 + 显示名 (rp_export_target_<gid>/name_<gid>) → toast → sendForwardNotice(groupId, target)。target trim 非空才写。
//   无群上下文 (groupId 为空) → 不写按群键, 提示需在群内设置。
void rpApplyForwardTarget(String groupId, String target, String name) {
    try {
        if (target == null) return;
        final String t = target.trim();
        if (t.isEmpty()) { try { toast("目标为空"); } catch (Throwable t2) {} return; }
        String g = normGroupId(groupId);
        if (g.isEmpty()) { try { toast("请在群内设置转发对象"); } catch (Throwable t2) {} return; }
        String nm = (name == null) ? "" : name.trim();
        try { putString(K_EXPORT_TARGET + "_" + g, t); } catch (Throwable e) {}
        try { putString(K_EXPORT_TARGET_NAME + "_" + g, nm); } catch (Throwable e) {}
        try { toast("✅ 本群转发对象已设为: " + (nm.isEmpty() ? t : nm)); } catch (Throwable e) {}
        sendForwardNotice(g, t);
    } catch (Throwable t) { try { toast("设置失败: " + t); } catch (Throwable t2) {} }
}

// v1.9.1 §24.3: 设置后给目标发一条通知 (好友=私聊; 群=发在群, 已与用户确认接受)。文本实时取配置, 【只讲当前群】。
//   当前群名用 rpGroupName(gid) 取 (本插件已验证的 getGroupList→getRoomId→getName 反射; 取不到回退 gid)。整段 try/catch, 失败 toast 不崩。
void sendForwardNotice(final String groupId, final String target) {
    try {
        if (target == null || target.trim().isEmpty()) return;
        int hour = cfgDailyHour();
        String gn = null;
        try { gn = rpGroupName(groupId); } catch (Throwable t) { gn = null; }
        if (gn == null || gn.trim().isEmpty()) gn = (groupId == null) ? "" : groupId;

        StringBuilder msg = new StringBuilder();
        msg.append("✅ 本群「").append(gn).append("」红包统计转发已开启到这里\n");
        msg.append("每日 ").append(hour).append(":00 推送过去24小时统计\n");
        msg.append("范围: ").append(hour).append(":00 ~ 次日").append(hour).append(":00\n");
        msg.append("(可在该群「红包统计设置 → 转发对象」调整)");
        final String text = msg.toString();
        try { sendText(target, text); } catch (Throwable t) { try { toast("通知发送失败 (目标已保存)"); } catch (Throwable t2) {} }
    } catch (Throwable t) { try { toast("通知发送失败: " + t); } catch (Throwable t2) {} }
}

// v1.0.6 §12: 重建排除名单行 (每人一行: 昵称/wxid + "移除"按钮)。移除后即时刷新本块。
void rpRebuildExcludeRows(final Activity ctx, final android.widget.LinearLayout box, final String groupId) {
    try {
        box.removeAllViews();
        int dp = (int) ctx.getResources().getDisplayMetrics().density;
        List l = getExcludeList(groupId);
        if (l == null || l.isEmpty()) {
            android.widget.TextView empty = new android.widget.TextView(ctx);
            empty.setText("(本群暂无排除名单)");
            empty.setTextColor(_rpC("#7C828E"));
            empty.setTextSize(12);
            empty.setPadding(0, 4 * dp, 0, 4 * dp);
            box.addView(empty);
            return;
        }
        for (int i = 0; i < l.size(); i++) {
            final String wxid = ((String) l.get(i)).trim();
            if (wxid.isEmpty()) continue;
            // v1.8.1: 用 rpSenderName(群名片→微信昵称→wxid, 与统计同口径) 取名字, 显示「昵称（…尾4）」。
            String nm = null;
            try { nm = rpSenderName(wxid, groupId); } catch (Throwable t) { nm = null; }
            String tail = (wxid.length() > 4) ? wxid.substring(wxid.length() - 4) : wxid;
            String nick;
            if (nm != null && !nm.isEmpty() && !nm.equals(wxid)) {
                nick = nm + "（…" + tail + "）";   // 有昵称: 昵称 + wxid 尾4 便于区分
            } else {
                nick = (wxid.length() > 16) ? (wxid.substring(0, 16) + "…") : wxid;   // 拿不到昵称才显示 wxid
            }

            android.widget.LinearLayout row = new android.widget.LinearLayout(ctx);
            row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            row.setPadding(0, 3 * dp, 0, 3 * dp);
            row.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));

            android.widget.TextView name = new android.widget.TextView(ctx);
            name.setText(nick);
            name.setTextColor(_rpC("#E8E9EC"));
            name.setTextSize(13);
            android.widget.LinearLayout.LayoutParams nlp = new android.widget.LinearLayout.LayoutParams(
                0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            nlp.gravity = android.view.Gravity.CENTER_VERTICAL;
            name.setLayoutParams(nlp);
            row.addView(name);

            android.widget.Button rm = new android.widget.Button(ctx);
            rm.setText("移除");
            rm.setTextColor(_rpC("#E8E9EC"));
            rm.setBackground(_rpRound("#FF6B6B", 18));
            rm.setAllCaps(false);
            rm.setTextSize(12);
            rm.setPadding(16 * dp, 6 * dp, 16 * dp, 6 * dp);
            rm.setOnClickListener(new android.view.View.OnClickListener() {
                public void onClick(android.view.View v) {
                    try {
                        java.util.List one = new java.util.ArrayList(); one.add(wxid);
                        removeExclude(groupId, one);
                        toast("✅ 已移出排除名单");
                        rpRebuildExcludeRows(ctx, box, groupId);   // 即时刷新
                    } catch (Throwable t) { try { toast("移除失败: " + t); } catch (Throwable t2) {} }
                }
            });
            row.addView(rm);
            box.addView(row);
        }
    } catch (Throwable t) {
        try { hbBgLog("[RP] " + hbNow() + " rebuild-exclude-rows err: " + t); } catch (Throwable t2) {}
    }
}

void saveIntField(String key, android.widget.EditText e) {
    try { String s = e.getText().toString().trim(); if (!s.isEmpty()) { Integer.parseInt(s); putString(key, s); } } catch (Throwable t) {}
}
void saveLongField(String key, android.widget.EditText e) {
    try { String s = e.getText().toString().trim(); if (!s.isEmpty()) { Long.parseLong(s); putString(key, s); } } catch (Throwable t) {}
}
void saveStrField(String key, android.widget.EditText e) {
    try { String s = e.getText().toString().trim(); if (!s.isEmpty()) putString(key, s); } catch (Throwable t) {}
}

// ================================================================
// ============ 工具 ============
// ================================================================
String hbTextSkeleton(String s) {
    try {
        if (s == null) return "null";
        String t = s.replace('\n', ' ').trim();
        if (t.length() <= 12) return t;
        return t.substring(0, 12) + "…(len=" + t.length() + ")";
    } catch (Throwable t) { return "?"; }
}

// v1.4.0: 聚合落盘一行 perf 并清零窗口。由 rpWorkerTick 定时调 + 热路径兜底调。
// 落盘放后台线程 (热路径若兜底命中也不阻塞 IO)。perf.log 只写耗时/计数, 绝不写明文。
// 先快照 + 清零 (减少与热路径累加的竞态窗口; BeanShell 无原子, 容忍极小误差, perf 仅供参考), 再后台写文件。
void perfFlush() {
    try {
        long n = PERF_N;
        if (n <= 0L) return;
        final long fN = n;
        final long fSum = PERF_SUM_NS;
        final long fMax = PERF_MAX_NS;
        // v1.6.2: spike 归因字段快照。
        final long fRpN = PERF_RP_N;
        final long fRpSum = PERF_RP_SUM_NS;
        final long fRpMax = PERF_RP_MAX_NS;
        final long fNormN = PERF_NORM_N;
        final long fNormSum = PERF_NORM_SUM_NS;
        final long fNormMax = PERF_NORM_MAX_NS;
        final long fSpikeN = PERF_SPIKE_N;
        final long fSpikeRp = PERF_SPIKE_RP_N;
        final long fSpikeNorm = PERF_SPIKE_NORM_N;
        final long fExN = PERF_EXTRACT_N;
        final long fExSum = PERF_EXTRACT_SUM_NS;
        final long fExMax = PERF_EXTRACT_MAX_NS;
        // 清零窗口 (快照后立即清, 新样本进下一窗口)。v1.6.2 新增组同步清零。
        PERF_N = 0L; PERF_SUM_NS = 0L; PERF_MAX_NS = 0L;
        PERF_RP_N = 0L; PERF_RP_SUM_NS = 0L; PERF_RP_MAX_NS = 0L;
        PERF_NORM_N = 0L; PERF_NORM_SUM_NS = 0L; PERF_NORM_MAX_NS = 0L;
        PERF_SPIKE_N = 0L; PERF_SPIKE_RP_N = 0L; PERF_SPIKE_NORM_N = 0L;
        PERF_EXTRACT_N = 0L; PERF_EXTRACT_SUM_NS = 0L; PERF_EXTRACT_MAX_NS = 0L;
        new Thread() {
            public void run() {
                try {
                    long avgUs = (fSum / fN) / 1000L;
                    long maxUs = fMax / 1000L;
                    long nowMs = System.currentTimeMillis();
                    String iso = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date(nowMs));
                    // v1.6.2: A/B 分类均值/最大 (各自 n 为 0 时置 0, 防除零)。
                    long rpAvgUs = (fRpN > 0L) ? ((fRpSum / fRpN) / 1000L) : 0L;
                    long rpMaxUs = fRpMax / 1000L;
                    long normAvgUs = (fNormN > 0L) ? ((fNormSum / fNormN) / 1000L) : 0L;
                    long normMaxUs = fNormMax / 1000L;
                    long exAvgUs = (fExN > 0L) ? ((fExSum / fExN) / 1000L) : 0L;
                    long exMaxUs = fExMax / 1000L;
                    String line = "ts=" + nowMs + " iso=" + iso + " n=" + fN
                        + " ohm_avg_us=" + avgUs + " ohm_max_us=" + maxUs
                        + " rp_msg_n=" + fRpN + " rp_msg_avg_us=" + rpAvgUs + " rp_msg_max_us=" + rpMaxUs
                        + " norm_avg_us=" + normAvgUs + " norm_max_us=" + normMaxUs
                        + " spike_n=" + fSpikeN + " spike_rp=" + fSpikeRp + " spike_norm=" + fSpikeNorm
                        + " rp_extract_n=" + fExN + " rp_extract_avg_us=" + exAvgUs + " rp_extract_max_us=" + exMaxUs + "\n";
                    java.io.FileWriter fw = new java.io.FileWriter(PERF_LOG, true);  // append
                    try { fw.write(line); } finally { fw.close(); }
                } catch (Throwable t) {
                    try { log("RedPacketStats perf flush fail: " + t); } catch (Throwable t2) {}
                }
            }
        }.start();
    } catch (Throwable t) {
        try { log("RedPacketStats perfFlush err: " + t); } catch (Throwable t2) {}
    }
}

void hbBgLog(final String content) {
    try {
        new Thread() {
            public void run() {
                try {
                    java.io.FileWriter fw = new java.io.FileWriter(HB_LOG, true);
                    try { fw.write(content); fw.write("\n"); } finally { fw.close(); }
                } catch (Throwable t) {
                    try { log("RedPacketStats bg write fail: " + t); } catch (Throwable t2) {}
                }
            }
        }.start();
    } catch (Throwable t) {}
}

String hbNow() {
    try { return new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date()); }
    catch (Throwable t) { return "?"; }
}
