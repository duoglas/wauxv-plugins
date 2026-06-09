// GroupAdmin v1.19.2 - 群管理: 踢人 / 黑名单 / 警告 / 潜水清理 / 白名单 / 全局配置 / 三层权限
//   - v1.19.2: [严重修复] recordSpeak flush 的 UPSERT last_speak=max(last_speak,excluded) 在 last_speak 为 NULL 时 SQLite max(NULL,x)=NULL → 先建基线再发言者永远卡 NULL(发言统计/潜水/伸手党失真)。改 max(coalesce(last_speak,excluded),excluded) 兜底。
//   - v1.19.1: 警告防误踢 — 自动来源警告(伸手党等, reason 非空)额外豁免管理员/群主/原生群主 (canActOn 对 owner→admin 不挡, 潜水管理抢红包不再被自动流程累计→踢出); 手动 `@TA 警告` 行为不变 (SPEC §4.4)
//   - v1.19.0: 警告带理由 — `@TA 警告 <理由>` 解析并透传; doWarn 通知末尾拼 " · 理由"; 自动来源(理由非空)对豁免对象(权限不足/白名单)静默跳过, 不发报错 (手动警告行为不变) (SPEC 警告系统)
//   - v1.18.0: 活动判定=任意消息类型 — 非文本消息(图片/表情/语音/红包/文件等)也计入发言活动, 修复只发非文本消息者被 #潜水 误判潜水 (SPEC §3/§6.10 活动采集前置)
//   - v1.17.2: 踢人安全 — @所有人(notify@all)不能踢 (@路径踢/请动作前置守卫 _isAtAll)
//   - v1.17.1: 管理员时效标签文案 "失效 X" → "有效期至 X"(列表/群消息同源 adminExpiryLabel)
// 完整命令清单见 README.md 或 WAuxiliary 主面板插件列表 → 设置 (openSettings)
// 重要变化历程:
//   - v1.7: 潜水成员清理 (#潜水 N / #踢潜水) + bot 群管权限自检 (#群权限自检)
//   - v1.8: 开启群管时即建立潜水基线 + onLoad 兜底已启用群
//   - v1.9: 群管命令统一收紧到管理员+, 普通成员发命令静默忽略
//   - v1.10: 实现 openSettings() — WAuxiliary 主面板插件列表点设置弹状态/命令 Dialog
//   - v1.11: bot 在群里发 `群管设置` 弹本群图形化配置 Dialog (onClickSendBtn 拦截)
//            含本群启用开关 / 状态摘要 / 黑+保护+警告逐项操作 / 潜水基线重建 / bot 权限重置 / 默认天数
//   - v1.12: 白名单 (本群+全局, 免警告+免进群检测) / 全局黑名单 (任何启用群进入即踢)
//            警告详情 (@TA 警告详情) / 清除警告名单 (一键全清, 仅群主) / 严格模式 (仅群主)
//            doWarn 加时间+来源记录; GUI 主 Dialog 加白名单按钮
//   - v1.13: 微信原生群主/管理员保护, 支持自动探测 + 手动登记 fallback
//   - v1.14: 管理员权限按群拆分 (admins_<groupId>); 微信原生管理员用本群体系
//   - v1.15: [性能] 潜水采集 (lsg_/fsg_) 落盘从 config.prop 整群 CSV pivot 到自有 SQLite (groupadmin.db);
//            热路径只写内存增量 O(1) + 后台批量 UPSERT + onLoad 一次性迁移 CSV→DB (消除 VH-01 消息卡顿)
//            [警告增强] 引用回复(非@)可触发全部管理动作 (appmsg, 动作词在 <title>, target=getQuoteMsg().getSendTalker());
//            警告自动踢上限默认 3→10 且每群可经设置 Dialog / `警告上限 N` 自定义; 满上限踢出成功后清零计数
//   - v1.16: [黑名单安全化] 拉黑入口从过简单方式 (@TA拉黑/@TA黑名单/addsb/delbk) 改为显式命令防误触:
//            `添加黑名单 @XX [@YY...]` (加黑+踢, 复用 doBan, 可@多人) / `去除黑名单 @XX [...]` /
//            `去除黑名单【N】` (按 "黑名单" 列表 1-based 序号移除, 容错【】[]全半角+前导0)。
//            "黑名单" 查询列表每项加序号【NN】。旧入口 (@TA拉黑/@TA黑名单/addsb/delbk) 保留识别但改为引导提示, 不再直接拉黑。
//            仅动黑名单增删入口; 踢/警告/保护/管理员/潜水/SQLite 落盘等一律未动。
//   - v1.16.1: [诊断] onHandleMsg 分段计时埋点(诊断用) — 普通消息路径 seg1~seg5 (nanoTime+整型累加),
//              perf.log 追加 seg{1..5}_avg_us/max_us 列, 定位 ~26ms 去向; 不改任何业务逻辑。
//   - v1.16.2: [性能] enabled 群内存缓存(消除每条消息 FUSE 读, seg2 ~26ms→~0) — isGroupEnabled 不再每条消息读
//              config.prop, 改 ENABLED_CACHE HashSet (TTL 60s 兜底外部改动; enable/disable 变更后立即刷新保即时生效);
//              加载失败保留旧缓存, 缓存异常回退直接读 (闸门正确性优先)。仅动 isGroupEnabled 读路径+缓存维护。
//   - v1.16.3: [健壮性/C-PLUGIN-05] onLoad 防御登录态未就绪——getLoginWxid 安全取值 (取不到用占位串, 日志永不抛) +
//              关键初始化分段 try/catch (日志块/延迟基线调度块/flush 循环启动块互不连坐); 修 VH-02 冷启动
//              getLoginWxid 抛 NoResetUinStack 导致 onLoad 第一行中断、flush 循环不启动而丢脏增量。仅动 onLoad, 不碰热路径。
//   - v1.17.0: [管理员时效] 加管理员可选 永久 / N 天到期: 新增 per-group 键 admin_exp_<gid> (CSV wxid:到期毫秒;...,
//              只存有时效者, 不在其中=永久 → admins_<gid> 格式不动, 老数据全是永久, 完全向后兼容);
//              管理员列表 (群命令 showAdmins + Dialog _adminRow) 显示 "永久"/"失效 yyyy-MM-dd";
//              到期惰性清除 purgeExpiredAdmins (仅在 isAuthorized/showAdmins/showAdminListDialog 等管理函数入口跑, 绝不进每条消息热路径);
//              @命令尾随天数: `@TA 管理员 7` = 7天 (仅"管理员"动作识别尾随整数, 其它动作不变); 引用回复路径同样支持;
//              Dialog 加天数 EditText (空=永久)。Dialog 路径保持静默(只 toast); 群 @命令路径保留群消息(含时长/失效日期)。
// 不再过滤 isSend() — 机器人账号自己发命令也响应

import java.util.*;
import android.app.*;
import android.content.*;
import android.view.*;
import android.widget.*;
import android.graphics.*;
import android.graphics.drawable.*;

int WARN_AUTO_KICK = 10;   // 警告几次自动踢 (默认值; 每群可经 wk_<group> 覆盖, 见 getWarnKick)

// ========== 性能埋点 (T1 / RB-3 / C-PERF-04) ==========
// 热路径只做 nanoTime + 内存累加, 不每条消息写文件/写 log (RISK-5: 埋点不得反噬热路径)。
// 每累计 PERF_FLUSH_EVERY 条消息聚合落盘一行到独立 perf.log (不污染 plugin.log)。
// perf.log 只写耗时/计数, 绝不写 wxid/群名/群ID/消息内容。
int  PERF_FLUSH_EVERY  = 200;   // 每 N 条消息聚合落盘一行
long PERF_N            = 0;     // 当前窗口样本数 (onHandleMsg 调用数)
long PERF_OHM_SUM_NS   = 0;     // 窗口 onHandleMsg 累计耗时 (ns)
long PERF_OHM_MAX_NS   = 0;     // 窗口 onHandleMsg 最大耗时 (ns)
long PERF_RS_N         = 0;     // 窗口 recordSpeak 调用数
long PERF_RS_SUM_NS    = 0;     // 窗口 recordSpeak 累计耗时 (ns)
long PERF_RS_MAX_NS    = 0;     // 窗口 recordSpeak 最大耗时 (ns)
long PERF_CMD_N        = 0;     // 窗口命令分支计数 (文本命令 / @ 动作; 与 normal 互斥)
long PERF_NORMAL_N     = 0;     // 窗口普通聊天消息分支计数 (到达 L3 采集且非命令)
String PERF_LOG_PATH   = "/storage/emulated/0/Android/media/com.tencent.mm/WAuxiliary/Plugin/GroupAdmin/perf.log";

// ===== 分段计时埋点 (v1.16.1, 诊断用; 仿 PERF_OHM_*/PERF_RS_* 风格) =====
// 目的: 定位"普通消息每条 ~28ms 里 recordSpeak(2.2ms) 之外那 ~26ms 在哪段"。
// 只对【普通消息(非命令)路径】累加 (命令路径不细分)。每段 = 一对 nanoTime 之差, 顶层 long 累加 sum/max。
// 段划分 (onHandleMsgBody 内, 仅顺序执行的几个主要阶段; 行号以 v1.16.1 为准):
//   seg1 入口解析:   isChatroom()/isText()/getContent()/trim + getTalker()/getSendTalker()/getAtUserList()
//                    —— 全部 msg.getXxx() 反射调用 (L1885-1905 一带)。
//   seg2 启用闸门:   群管设置探测 + 一串特殊命令 content.equals(...) 判定, 直到 isGroupEnabled(groupId) 这个 store 读闸门
//                    (普通消息这些 equals 全 miss, 落到 isGroupEnabled 通过)。
//   seg3 recordSpeak: L3 SQLite 内存入队 (已知 ~2.2ms, 与既有 PERF_RS_* 同区间, 作对照; 这里独立再夹一次让 seg 和可对齐 ohm)。
//   seg4 命令判定+鉴权: isCmd 的一长串 ||(startsWith/equals) 拼装 + isAuthorized 闸门 (普通消息 isCmd=false, 直接过)。
//   seg5 token/动作判定: content.split(正则) + isActionTok + hasAt 判定; 普通文本消息在此分支末尾 return (这是普通消息的主出口)。
// 注: lookupName / getQuoteMsg / XML 解析都【不在】普通消息路径上 (仅命令/引用/@动作路径), 故不分段。
long PERF_SEG_N        = 0;     // 只对"走到普通消息主出口"的样本计数 (seg 各段的分母)
long PERF_SEG1_SUM_NS  = 0; long PERF_SEG1_MAX_NS = 0;
long PERF_SEG2_SUM_NS  = 0; long PERF_SEG2_MAX_NS = 0;
long PERF_SEG3_SUM_NS  = 0; long PERF_SEG3_MAX_NS = 0;
long PERF_SEG4_SUM_NS  = 0; long PERF_SEG4_MAX_NS = 0;
long PERF_SEG5_SUM_NS  = 0; long PERF_SEG5_MAX_NS = 0;

// 落盘一行聚合并清零窗口。仅在 try/catch 内调用; 自身再包一层防御。
void perfFlush() {
    try {
        long n = PERF_N;
        if (n <= 0) return;
        long ohmAvgUs = (PERF_OHM_SUM_NS / n) / 1000L;
        long ohmMaxUs = PERF_OHM_MAX_NS / 1000L;
        long rsN = PERF_RS_N;
        long rsAvgUs = rsN > 0 ? (PERF_RS_SUM_NS / rsN) / 1000L : 0;
        long rsMaxUs = PERF_RS_MAX_NS / 1000L;
        long nowMs = System.currentTimeMillis();
        String iso = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date(nowMs));
        // 分段计时 (v1.16.1): 只对普通消息主出口样本聚合; 分母独立用 seg_n。
        long segN = PERF_SEG_N;
        long seg1AvgUs = segN > 0 ? (PERF_SEG1_SUM_NS / segN) / 1000L : 0;
        long seg2AvgUs = segN > 0 ? (PERF_SEG2_SUM_NS / segN) / 1000L : 0;
        long seg3AvgUs = segN > 0 ? (PERF_SEG3_SUM_NS / segN) / 1000L : 0;
        long seg4AvgUs = segN > 0 ? (PERF_SEG4_SUM_NS / segN) / 1000L : 0;
        long seg5AvgUs = segN > 0 ? (PERF_SEG5_SUM_NS / segN) / 1000L : 0;
        long seg1MaxUs = PERF_SEG1_MAX_NS / 1000L;
        long seg2MaxUs = PERF_SEG2_MAX_NS / 1000L;
        long seg3MaxUs = PERF_SEG3_MAX_NS / 1000L;
        long seg4MaxUs = PERF_SEG4_MAX_NS / 1000L;
        long seg5MaxUs = PERF_SEG5_MAX_NS / 1000L;
        String line = "ts=" + nowMs + " iso=" + iso + " n=" + n
                + " ohm_avg_us=" + ohmAvgUs + " ohm_max_us=" + ohmMaxUs
                + " rs_n=" + rsN + " rs_avg_us=" + rsAvgUs + " rs_max_us=" + rsMaxUs
                + " cmd=" + PERF_CMD_N + " normal=" + PERF_NORMAL_N
                + " seg_n=" + segN
                + " seg1_avg_us=" + seg1AvgUs + " seg1_max_us=" + seg1MaxUs
                + " seg2_avg_us=" + seg2AvgUs + " seg2_max_us=" + seg2MaxUs
                + " seg3_avg_us=" + seg3AvgUs + " seg3_max_us=" + seg3MaxUs
                + " seg4_avg_us=" + seg4AvgUs + " seg4_max_us=" + seg4MaxUs
                + " seg5_avg_us=" + seg5AvgUs + " seg5_max_us=" + seg5MaxUs + "\n";
        java.io.FileWriter fw = new java.io.FileWriter(PERF_LOG_PATH, true);  // append
        try { fw.write(line); } finally { fw.close(); }
    } catch (Throwable t) {
        // 落盘失败不影响热路径; 一次性记到 plugin.log 便于排查
        try { log("perf flush fail: " + t); } catch (Throwable t2) {}
    } finally {
        // 无论成功失败都清零窗口, 避免坏窗口无限累积
        PERF_N = 0; PERF_OHM_SUM_NS = 0; PERF_OHM_MAX_NS = 0;
        PERF_RS_N = 0; PERF_RS_SUM_NS = 0; PERF_RS_MAX_NS = 0;
        PERF_CMD_N = 0; PERF_NORMAL_N = 0;
        // v1.16.1 分段计时一并清零
        PERF_SEG_N = 0;
        PERF_SEG1_SUM_NS = 0; PERF_SEG1_MAX_NS = 0;
        PERF_SEG2_SUM_NS = 0; PERF_SEG2_MAX_NS = 0;
        PERF_SEG3_SUM_NS = 0; PERF_SEG3_MAX_NS = 0;
        PERF_SEG4_SUM_NS = 0; PERF_SEG4_MAX_NS = 0;
        PERF_SEG5_SUM_NS = 0; PERF_SEG5_MAX_NS = 0;
    }
}

// ========== L3 潜水采集异步化 (T2/T2′ / RB-1/RB-2 / VH-01 / C-PERF-01/02/03 / C-ARCH-02/03) ==========
// 热路径 recordSpeak 只往内存脏增量 Map 写 (O(1)), 绝不碰 getString/putString/DB/磁盘。
// 后台 delay() 自我重调度循环 (默认 30s) 把脏增量批量 UPSERT 进自有 SQLite 库 (行级, 与群人数无关)。
// 并发: 消息线程写 dirty、flush 线程换出 dirty, 都在 synchronized(L3_LOCK) 内, 杜绝 ConcurrentModificationException / 丢增量。
int    L3_FLUSH_MS   = 30000;          // 后台 flush 周期 (ms)
Object L3_LOCK       = new Object();   // 保护 L3_dirtyLs / L3_dirtySeen 的内存锁 (热路径 recordSpeak 只用这把)
// T2′ (C-PLUGIN-03): 串行化整段 flush (换出脏 Map + 写 DB + 提交) 的独立锁。
// 与 L3_LOCK 分开。热路径 recordSpeak 绝不获取 FLUSH_LOCK、绝不碰 L3_DB (普通消息永不阻塞磁盘锁, 保 C-PERF-03)。
// 锁序恒为 FLUSH_LOCK 外、L3_LOCK 内 (仅 l3Flush 在 FLUSH_LOCK 内做一小段 L3_LOCK 换出); recordSpeak 只持 L3_LOCK → 无环 → 无死锁。
Object FLUSH_LOCK    = new Object();   // 串行化 l3Flush (后台 flush + 命令路径 flush-before-read)
Map    L3_dirtyLs    = new HashMap();  // groupId -> (Map wxid->ts) 待落盘的 last_speak 增量
Map    L3_dirtySeen  = new HashMap();  // groupId -> (Map wxid->ts) 待落盘的 first_seen 候选
boolean L3_flushStarted = false;       // 后台 flush 循环是否已启动 (防重复调度)
// ===== T4: L3 降级 (C-ARCH-02 / RB-4) =====
// 两个稳健触发器: (1) 内存缓冲条数上界 (2) flush 连续失败。满足任一 → L3_DEGRADED, recordSpeak 丢弃新增量。
// 红线: 降级只影响 recordSpeak 采集入口; L1/L2 命令/flush-before-read/DB 查询永不读 L3_DEGRADED。
// 刻意不做 timing (耗时超标) 触发器: 单条耗时抖动大易误触发, 缓冲/失败两触发器已覆盖压力与出错两类根因。
long   L3_flushFailStreak = 0;          // 连续 flush 失败次数 (DB 打不开/写不进; flush 成功一轮归零)
long   L3_dirtyCount      = 0;          // O(1) running counter: 脏增量总条数 (L3_LOCK 内 ++/清零, 热路径不求 size)
int    L3_BUFFER_CAP      = 5000;       // 脏增量条数上界: ≥ 此值进入降级 (保护内存)
int    L3_FAIL_CAP        = 5;          // flush 连续失败阈值: ≥ 此值进入降级 (DB 持续不可用)
boolean L3_DEGRADED       = false;      // 降级标志: 真则 recordSpeak 直接 return (廉价布尔读)

// ===== T2′ SQLite 落盘底座 (替代 config.prop 整群 CSV) =====
// 表: speak(grp,wxid,last_speak,first_seen, PK(grp,wxid)) + 索引 (grp,last_speak)。
// 连接单例: 顶层缓存一个 SQLiteDatabase, 懒打开, onUnload close。SQLiteDatabase 内部锁线程安全。
String L3_DB_PATH = "/storage/emulated/0/Android/media/com.tencent.mm/WAuxiliary/Plugin/GroupAdmin/groupadmin.db";
android.database.sqlite.SQLiteDatabase L3_DB = null;   // 连接单例 (懒打开)

// 懒打开 DB 单例 + 建表/建索引/设 WAL。仅在 FLUSH_LOCK / 命令路径里调用 (非热路径)。返回可用连接, 失败返回 null。
android.database.sqlite.SQLiteDatabase l3Db() {
    // 快路径: 已打开直接返回, 不抢锁 (写路径 flush 高频调本方法, 命中即走这里)。
    if (L3_DB != null && L3_DB.isOpen()) return L3_DB;
    // 慢路径 (仅首次/重开): 整段 检查 null→打开→赋值 串行到 FLUSH_LOCK, 杜绝两线程并发各 open 一次泄漏连接 (Santa A#5)。
    // FLUSH_LOCK 可重入: 写路径已持 FLUSH_LOCK 调本方法安全; 读路径仅首次短暂取锁打开连接, 不在热路径。
    // 锁序不变: l3Db 内不再嵌套取其它锁 (FLUSH_LOCK 仍是最外层), 无新死锁。
    synchronized (FLUSH_LOCK) {
        if (L3_DB != null && L3_DB.isOpen()) return L3_DB;   // 双重检查: 等锁期间别的线程可能已打开
        try {
            android.database.sqlite.SQLiteDatabase db =
                android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(L3_DB_PATH, (android.database.sqlite.SQLiteDatabase.CursorFactory) null);
            db.execSQL("CREATE TABLE IF NOT EXISTS speak(grp TEXT, wxid TEXT, last_speak INTEGER, first_seen INTEGER, PRIMARY KEY(grp,wxid))");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_speak_grp_last ON speak(grp,last_speak)");
            // WAL 必须走 rawQuery: 该 PRAGMA 返回一行, execSQL 会拒绝并抛异常 (spike 已确认)。
            try { android.database.Cursor c = db.rawQuery("PRAGMA journal_mode=WAL", (String[]) null); if (c != null) c.close(); } catch (Throwable t) {}
            try { db.execSQL("PRAGMA synchronous=NORMAL"); } catch (Throwable t) {}
            L3_DB = db;
            return L3_DB;
        } catch (Throwable t) {
            try { log("l3Db open fail: " + t); } catch (Throwable t2) {}
            return null;
        }
    }
}

// 查某群所有 (wxid -> last_speak)。命令路径调用 (doShowInactive); 调用前应已 flush-before-read。
Map l3QueryLastSpeak(String groupId) {
    Map m = new HashMap();
    android.database.sqlite.SQLiteDatabase db = l3Db();
    if (db == null) return m;
    android.database.Cursor c = null;
    try {
        c = db.rawQuery("SELECT wxid,last_speak FROM speak WHERE grp=? AND last_speak IS NOT NULL", new String[]{ groupId });
        while (c.moveToNext()) {
            String w = c.getString(0);
            if (w != null && !c.isNull(1)) m.put(w, Long.valueOf(c.getLong(1)));
        }
    } catch (Throwable t) { try { log("l3QueryLastSpeak fail: " + t); } catch (Throwable t2) {} }
    finally { if (c != null) try { c.close(); } catch (Throwable t) {} }
    return m;
}

// 查某群所有 (wxid -> first_seen)。
Map l3QueryFirstSeen(String groupId) {
    Map m = new HashMap();
    android.database.sqlite.SQLiteDatabase db = l3Db();
    if (db == null) return m;
    android.database.Cursor c = null;
    try {
        c = db.rawQuery("SELECT wxid,first_seen FROM speak WHERE grp=? AND first_seen IS NOT NULL", new String[]{ groupId });
        while (c.moveToNext()) {
            String w = c.getString(0);
            if (w != null && !c.isNull(1)) m.put(w, Long.valueOf(c.getLong(1)));
        }
    } catch (Throwable t) { try { log("l3QueryFirstSeen fail: " + t); } catch (Throwable t2) {} }
    finally { if (c != null) try { c.close(); } catch (Throwable t) {} }
    return m;
}

// 该群有 first_seen 的行数 (基线是否已建立)。
int l3CountFirstSeen(String groupId) {
    android.database.sqlite.SQLiteDatabase db = l3Db();
    if (db == null) return 0;
    android.database.Cursor c = null;
    try {
        c = db.rawQuery("SELECT count(*) FROM speak WHERE grp=? AND first_seen IS NOT NULL", new String[]{ groupId });
        if (c.moveToFirst()) return c.getInt(0);
    } catch (Throwable t) { try { log("l3CountFirstSeen fail: " + t); } catch (Throwable t2) {} }
    finally { if (c != null) try { c.close(); } catch (Throwable t) {} }
    return 0;
}

// 单群批量 UPSERT first_seen (绝不改晚: 已有更早值不被覆盖)。用于 initFirstSeenBaseline / doShowInactive 冷启动。
// 返回新增 (此前 DB 无 first_seen) 的人数。非热路径 (命令/onLoad), 持 FLUSH_LOCK 串行化, 与后台 flush 互斥。
int l3UpsertFirstSeen(String groupId, List wxids, long ts) {
    if (wxids == null || wxids.isEmpty()) return 0;
    int added = 0;
    synchronized (FLUSH_LOCK) {
        android.database.sqlite.SQLiteDatabase db = l3Db();
        if (db == null) return 0;
        try {
            // 先查现有, 算 added (语义对齐旧 initFirstSeenBaseline 返回"新设置人数")
            Map cur = l3QueryFirstSeen(groupId);
            db.beginTransaction();
            try {
                android.database.sqlite.SQLiteStatement st = db.compileStatement(
                    "INSERT INTO speak(grp,wxid,last_speak,first_seen) VALUES(?,?,NULL,?) "
                    + "ON CONFLICT(grp,wxid) DO UPDATE SET first_seen=min(first_seen,excluded.first_seen)");
                try {
                    for (int i = 0; i < wxids.size(); i++) {
                        String w = (String) wxids.get(i);
                        if (w == null || w.isEmpty()) continue;
                        if (!cur.containsKey(w)) added++;
                        st.clearBindings();
                        st.bindString(1, groupId);
                        st.bindString(2, w);
                        st.bindLong(3, ts);
                        st.execute();
                    }
                } finally { st.close(); }
                db.setTransactionSuccessful();
            } finally { db.endTransaction(); }
        } catch (Throwable t) { try { log("l3UpsertFirstSeen fail group=" + groupId + " " + t); } catch (Throwable t2) {} }
    }
    return added;
}

// 强制重建基线: 清空该群 first_seen, 再按当前成员重设 (语义对齐旧 rebuildBaselineForce: 清空 fsg_ 再 initBaseline)。
// 清 first_seen 用 UPDATE SET first_seen=NULL (保留 last_speak 行, 不删行)。
void l3ClearFirstSeen(String groupId) {
    synchronized (FLUSH_LOCK) {
        android.database.sqlite.SQLiteDatabase db = l3Db();
        if (db == null) return;
        try { db.execSQL("UPDATE speak SET first_seen=NULL WHERE grp=?", new String[]{ groupId }); }
        catch (Throwable t) { try { log("l3ClearFirstSeen fail: " + t); } catch (Throwable t2) {} }
    }
}

// 该群 DB 里 last_speak / first_seen 各自非空的行数 (迁移校验用)。
int[] l3CountGroup(String groupId) {
    int[] r = new int[]{0, 0};
    android.database.sqlite.SQLiteDatabase db = l3Db();
    if (db == null) return r;
    android.database.Cursor c = null;
    try {
        c = db.rawQuery("SELECT sum(last_speak IS NOT NULL), sum(first_seen IS NOT NULL) FROM speak WHERE grp=?", new String[]{ groupId });
        if (c.moveToFirst()) { r[0] = c.isNull(0) ? 0 : c.getInt(0); r[1] = c.isNull(1) ? 0 : c.getInt(1); }
    } catch (Throwable t) { try { log("l3CountGroup fail: " + t); } catch (Throwable t2) {} }
    finally { if (c != null) try { c.close(); } catch (Throwable t) {} }
    return r;
}

// T2′: 一次性迁移旧 lsg_/fsg_ CSV → SQLite。migrated 标志守卫只迁一次。校验通过才清旧 key + 置 migrated。
// 不可逆 (T0 已备份 config.prop)。仅在 onLoad 后台 delay 调用 (非热路径)。
void l3MigrateCsvToDb() {
    if ("1".equals(getString("l3_sqlite_migrated", "0"))) return;   // 已迁过
    android.database.sqlite.SQLiteDatabase db = l3Db();
    if (db == null) { log("l3 migrate: DB open fail, 跳过本次 (下次重试)"); return; }

    List groups = getEnabledGroups();
    int gOk = 0, gFail = 0;
    boolean allOk = true;
    synchronized (FLUSH_LOCK) {
        for (int i = 0; i < groups.size(); i++) {
            String g = (String) groups.get(i);
            String lcsv = getString("lsg_" + g, "");
            String fcsv = getString("fsg_" + g, "");
            if (lcsv.isEmpty() && fcsv.isEmpty()) continue;   // 该群无旧数据
            Map lm = parseTimeCsv(lcsv);
            Map fm = parseTimeCsv(fcsv);
            int srcLs = lm.size();
            int srcFs = fm.size();
            try {
                db.beginTransaction();
                try {
                    android.database.sqlite.SQLiteStatement upLs = db.compileStatement(
                        "INSERT INTO speak(grp,wxid,last_speak,first_seen) VALUES(?,?,?,NULL) "
                        + "ON CONFLICT(grp,wxid) DO UPDATE SET last_speak=excluded.last_speak");
                    android.database.sqlite.SQLiteStatement upFs = db.compileStatement(
                        "INSERT INTO speak(grp,wxid,last_speak,first_seen) VALUES(?,?,NULL,?) "
                        + "ON CONFLICT(grp,wxid) DO UPDATE SET first_seen=excluded.first_seen");
                    try {
                        Iterator it = lm.entrySet().iterator();
                        while (it.hasNext()) {
                            Map.Entry e = (Map.Entry) it.next();
                            String w = (String) e.getKey(); Long ts = (Long) e.getValue();
                            if (w == null || ts == null) continue;
                            upLs.clearBindings(); upLs.bindString(1, g); upLs.bindString(2, w); upLs.bindLong(3, ts.longValue()); upLs.execute();
                        }
                        Iterator it2 = fm.entrySet().iterator();
                        while (it2.hasNext()) {
                            Map.Entry e = (Map.Entry) it2.next();
                            String w = (String) e.getKey(); Long ts = (Long) e.getValue();
                            if (w == null || ts == null) continue;
                            upFs.clearBindings(); upFs.bindString(1, g); upFs.bindString(2, w); upFs.bindLong(3, ts.longValue()); upFs.execute();
                        }
                    } finally { upLs.close(); upFs.close(); }
                    db.setTransactionSuccessful();
                } finally { db.endTransaction(); }
                // 校验: DB 该群 last_speak/first_seen 行数 应 == 源 CSV 条目数
                int[] cnt = l3CountGroup(g);
                if (cnt[0] == srcLs && cnt[1] == srcFs) {
                    putString("lsg_" + g, "");
                    putString("fsg_" + g, "");
                    gOk++;
                    log("l3 migrate group=" + g + " OK: lsg " + srcLs + "→" + cnt[0] + ", fsg " + srcFs + "→" + cnt[1] + " (旧 key 已清)");
                } else {
                    allOk = false; gFail++;
                    log("l3 migrate group=" + g + " 校验不过: lsg src=" + srcLs + " db=" + cnt[0] + ", fsg src=" + srcFs + " db=" + cnt[1] + " (保留旧 key)");
                }
            } catch (Throwable t) {
                allOk = false; gFail++;
                try { log("l3 migrate group=" + g + " 异常, 保留旧 key: " + t); } catch (Throwable t2) {}
            }
        }
    }
    if (allOk) {
        putString("l3_sqlite_migrated", "1");
        log("l3 migrate DONE: " + gOk + " 群迁入并清旧 key, migrated=1");
    } else {
        log("l3 migrate INCOMPLETE: ok=" + gOk + " fail=" + gFail + ", 不置 migrated (下次 onLoad 重试)");
    }
}

// 后台 flush 循环: 自我重调度。整体 try/catch(Throwable), finally 无条件再 delay 下一轮 → 异常绝不停摆 (RISK-2)。
void l3FlushLoop() {
    try {
        l3Flush();
    } catch (Throwable t) {
        try { log("l3 flush loop err: " + t); } catch (Throwable t2) {}
    } finally {
        try {
            delay(L3_FLUSH_MS, new Runnable() { public void run() { l3FlushLoop(); } });
        } catch (Throwable t) {
            try { log("l3 flush reschedule fail: " + t); } catch (Throwable t2) {}
        }
    }
}

// 同步把内存脏增量批量 UPSERT 进 SQLite。可被后台循环、flush-before-read、onUnload 复用。
// 原子换出脏 Map 后慢慢处理, L3_LOCK 只在换出瞬间持有, 不阻塞消息线程做 DB IO。
void l3Flush() {
    // T2′: 整段 (换出脏 Map → 批量 UPSERT → 提交 → perfFlush) 串行到 FLUSH_LOCK 下。
    // FLUSH_LOCK 只保证: 命令路径调本方法时会等在途 flush 提交完, 且本次把当前内存增量提交进 DB 后才返回。
    // flush-before-read 的正确性不靠 "FLUSH_LOCK 串行化保证读到已提交" (随后的 query 在 FLUSH_LOCK 外, 期间可能又有新 flush),
    // 而是靠 UPSERT 的单调语义: last_speak=max(...)、first_seen=min(...) — 任何并发顺序都只会让值更新更不误踢, 不丢已提交的较新发言。
    // 锁序固定: FLUSH_LOCK 外、L3_LOCK 内 (换出脏 Map 那一小段)。recordSpeak 只持 L3_LOCK, 永不持 FLUSH_LOCK → 无死锁。
    synchronized (FLUSH_LOCK) {
        Map ls = null;
        Map fs = null;
        // L3_LOCK 只在原子换出脏 Map 的瞬间持有 (嵌套在 FLUSH_LOCK 内, 锁序固定),
        // 绝不在 L3_LOCK 内做 DB IO, 否则会让消息线程的 recordSpeak (同一把内存锁) 等待写库。
        synchronized (L3_LOCK) {
            if (!L3_dirtyLs.isEmpty() || !L3_dirtySeen.isEmpty()) {
                ls = L3_dirtyLs; L3_dirtyLs = new HashMap();
                fs = L3_dirtySeen; L3_dirtySeen = new HashMap();
            }
            L3_dirtyCount = 0;   // T4: 换出即清零 running counter (与 recordSpeak ++ 对称, 都在 L3_LOCK 内)
        }
        if (ls == null) {
            // 无脏增量: 仅驱动 perf 落盘 (热路径已不做文件 IO)
            try { perfFlush(); } catch (Throwable t) {}
            return;
        }
        boolean anyFail = false;
        android.database.sqlite.SQLiteDatabase db = l3Db();
        if (db == null) {
            // DB 打不开: 算一次失败, 但不把换出的增量丢回 (避免无限堆积); 已知会丢 ≤一周期, RISK-4 范围内。
            L3_flushFailStreak++;
            l3MaybeDegradeOnFail();   // T4 触发器(2): 连续失败到顶 → 降级
            try { perfFlush(); } catch (Throwable t) {}
            return;
        }
        try {
            db.beginTransaction();
            try {
                // last_speak: ON CONFLICT 取 max (单调, 不会改旧). first_seen 一并以增量 ts 初值, 冲突时取 min。
                // v1.19.2 修复: 旧版 max(last_speak,excluded) 在 last_speak 为 NULL 时, SQLite 标量 max(NULL,x)=NULL →
                //   先被建基线(last_speak=NULL)再发言的人永远卡 NULL(发言统计/潜水/伸手党全失真)。改用 coalesce 兜底 NULL(对齐 first_seen 写法)。
                android.database.sqlite.SQLiteStatement upLs = db.compileStatement(
                    "INSERT INTO speak(grp,wxid,last_speak,first_seen) VALUES(?,?,?,?) "
                    + "ON CONFLICT(grp,wxid) DO UPDATE SET last_speak=max(coalesce(last_speak,excluded.last_speak),excluded.last_speak), "
                    + "first_seen=min(coalesce(first_seen,excluded.first_seen),excluded.first_seen)");
                // first_seen 候选: 单独 UPSERT, 冲突时 first_seen 取 min (绝不改晚), 不动 last_speak。
                android.database.sqlite.SQLiteStatement upFs = db.compileStatement(
                    "INSERT INTO speak(grp,wxid,last_speak,first_seen) VALUES(?,?,NULL,?) "
                    + "ON CONFLICT(grp,wxid) DO UPDATE SET first_seen=min(coalesce(first_seen,excluded.first_seen),excluded.first_seen)");
                try {
                    Iterator git = ls.entrySet().iterator();
                    while (git.hasNext()) {
                        Map.Entry ge = (Map.Entry) git.next();
                        String g = (String) ge.getKey();
                        Map inc = (Map) ge.getValue();
                        if (inc == null) continue;
                        Iterator it = inc.entrySet().iterator();
                        while (it.hasNext()) {
                            Map.Entry e = (Map.Entry) it.next();
                            String w = (String) e.getKey();
                            Long ts = (Long) e.getValue();
                            if (w == null || ts == null) continue;
                            upLs.clearBindings();
                            upLs.bindString(1, g);
                            upLs.bindString(2, w);
                            upLs.bindLong(3, ts.longValue());
                            upLs.bindLong(4, ts.longValue());   // first_seen 初值 = 本次发言 ts (冲突取 min)
                            upLs.execute();
                        }
                    }
                    Iterator fgit = fs.entrySet().iterator();
                    while (fgit.hasNext()) {
                        Map.Entry ge = (Map.Entry) fgit.next();
                        String g = (String) ge.getKey();
                        Map fcand = (Map) ge.getValue();
                        if (fcand == null) continue;
                        Iterator it = fcand.entrySet().iterator();
                        while (it.hasNext()) {
                            Map.Entry e = (Map.Entry) it.next();
                            String w = (String) e.getKey();
                            Long ts = (Long) e.getValue();
                            if (w == null || ts == null) continue;
                            upFs.clearBindings();
                            upFs.bindString(1, g);
                            upFs.bindString(2, w);
                            upFs.bindLong(3, ts.longValue());
                            upFs.execute();
                        }
                    }
                } finally { upLs.close(); upFs.close(); }
                db.setTransactionSuccessful();
            } finally { db.endTransaction(); }
        } catch (Throwable t) {
            anyFail = true;
            try { log("l3 flush db upsert fail: " + t); } catch (Throwable t2) {}
        }
        if (anyFail) {
            L3_flushFailStreak++;
            l3MaybeDegradeOnFail();   // T4 触发器(2): 连续失败到顶 → 降级
        } else {
            L3_flushFailStreak = 0;
            l3MaybeRecover();         // T4 恢复: 成功一轮 + 缓冲已排空到低水位 → 解除降级
        }
        // perf 落盘也搬到后台 (热路径零磁盘 IO, C-PERF-03)
        try { perfFlush(); } catch (Throwable t) {}
    }
}

// ========== T4 降级触发/恢复 (仅 FLUSH_LOCK 内 l3Flush 调用; 非热路径) ==========
// 触发器(2): flush 连续失败到阈值 → 进入降级。读 dirtyCount 时短取 L3_LOCK 保一致 (锁序 FLUSH_LOCK 外, L3_LOCK 内, 不破坏现有序)。
void l3MaybeDegradeOnFail() {
    if (L3_DEGRADED) return;
    if (L3_flushFailStreak >= L3_FAIL_CAP) {
        long dc; synchronized (L3_LOCK) { dc = L3_dirtyCount; }
        L3_DEGRADED = true;
        perfDegrade(true, "fail_streak", dc, L3_flushFailStreak);
    }
}

// 恢复: flush 成功一轮 (streak 已归零) 且换出后缓冲已排空到低水位 (< CAP/2) → 解除降级, recordSpeak 恢复采集。
void l3MaybeRecover() {
    if (!L3_DEGRADED) return;
    long dc; synchronized (L3_LOCK) { dc = L3_dirtyCount; }
    if (L3_flushFailStreak == 0 && dc < (L3_BUFFER_CAP / 2)) {
        L3_DEGRADED = false;
        perfDegrade(false, null, dc, L3_flushFailStreak);
    }
}

// 降级留痕: 往 perf.log 追加一行标记 (绝不含 wxid/群名/群ID/内容)。enter=true 进入, false 解除。
void perfDegrade(boolean enter, String reason, long dirty, long failStreak) {
    try {
        long nowMs = System.currentTimeMillis();
        String iso = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date(nowMs));
        String line = "ts=" + nowMs + " iso=" + iso + " L3_DEGRADE "
                + (enter ? ("enter reason=" + reason) : "exit")
                + " dirty=" + dirty + " fail_streak=" + failStreak + "\n";
        java.io.FileWriter fw = new java.io.FileWriter(PERF_LOG_PATH, true);
        try { fw.write(line); } finally { fw.close(); }
    } catch (Throwable t) {
        try { log("perf degrade mark fail: " + t); } catch (Throwable t2) {}
    }
}

// ========== 工具 ==========
List parseCsv(String csv) {
    List r = new ArrayList();
    if (csv == null || csv.isEmpty()) return r;
    String[] arr = csv.split(",");
    for (int i = 0; i < arr.length; i++) {
        String s = arr[i].trim();
        if (!s.isEmpty()) r.add(s);
    }
    return r;
}

String joinCsv(List list) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < list.size(); i++) {
        if (i > 0) sb.append(",");
        sb.append(list.get(i));
    }
    return sb.toString();
}

String normGroupId(String groupId) {
    if (groupId == null) return "";
    String s = groupId.trim().replace(" ", "").replace(" ", "").replace(" ", "");
    int end = s.indexOf("@chatroom");
    if (end >= 0) {
        int start = end;
        while (start > 0) {
            char ch = s.charAt(start - 1);
            boolean ok = (ch >= '0' && ch <= '9') || (ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z') || ch == '_' || ch == '-';
            if (!ok) break;
            start--;
        }
        return s.substring(start, end + 9);
    }
    return s;
}

String safeGroupDebug(String groupId) {
    String s = normGroupId(groupId);
    return "len=" + s.length() + ",hash=" + Integer.toHexString(s.hashCode());
}

void logGroupEnabledDebug(String groupId) {
    try {
        String g = normGroupId(groupId);
        List l = getEnabledGroups();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < l.size(); i++) {
            if (i > 0) sb.append(";");
            sb.append(safeGroupDebug((String) l.get(i)));
        }
        log("group-enabled debug: current=" + safeGroupDebug(g) + ", enabledCount=" + l.size() + ", enabled=" + sb.toString() + ", match=" + isGroupEnabled(g));
    } catch (Exception e) { log("group-enabled debug err: " + e.getClass().getName()); }
}

void logEnabledGroupNames() {
    try {
        List enabled = getEnabledGroups();
        List groups = getGroupList();
        log("group-list probe: enabledCount=" + enabled.size() + ", groupListCount=" + (groups == null ? 0 : groups.size()));
        if (groups == null) return;
        for (int i = 0; i < groups.size(); i++) {
            Object info = groups.get(i);
            String rid = "";
            String name = "";
            try { Object r = info.getClass().getMethod("getRoomId").invoke(info); if (r != null) rid = r.toString(); } catch (Exception e) {}
            try { Object n = info.getClass().getMethod("getName").invoke(info); if (n != null) name = n.toString(); } catch (Exception e) {}
            if (isGroupEnabled(rid) || name.indexOf("群A") >= 0 || name.indexOf("备用") >= 0 || name.indexOf("养群") >= 0 || name.indexOf("学院") >= 0) {
                log("group-list probe: roomId=" + rid + ", enabled=" + isGroupEnabled(rid) + ", name=" + name);
            }
        }
    } catch (Exception e) { log("group-list probe err: " + e.getClass().getName() + ": " + e.getMessage()); }
}

// 时间戳 CSV: wxid|ts,wxid|ts,...
Map parseTimeCsv(String csv) {
    Map m = new HashMap();
    if (csv == null || csv.isEmpty()) return m;
    String[] arr = csv.split(",");
    for (int i = 0; i < arr.length; i++) {
        String p = arr[i].trim();
        if (p.isEmpty()) continue;
        int sep = p.indexOf('|');
        if (sep <= 0 || sep >= p.length() - 1) continue;
        try { m.put(p.substring(0, sep), Long.valueOf(p.substring(sep + 1))); } catch (Exception e) {}
    }
    return m;
}
String buildTimeCsv(Map m) {
    StringBuilder sb = new StringBuilder();
    Iterator it = m.entrySet().iterator();
    boolean first = true;
    while (it.hasNext()) {
        Map.Entry e = (Map.Entry) it.next();
        if (!first) sb.append(',');
        sb.append(e.getKey()).append('|').append(e.getValue());
        first = false;
    }
    return sb.toString();
}

// 编号区间解析: "1,3,5-8" → [1,3,5,6,7,8]
List parseIndexRange(String s, int max) {
    Set seen = new LinkedHashSet();
    if (s == null) return new ArrayList();
    String[] parts = s.split(",");
    for (int i = 0; i < parts.length; i++) {
        String p = parts[i].trim();
        if (p.isEmpty()) continue;
        int dash = p.indexOf('-');
        if (dash > 0 && dash < p.length() - 1) {
            try {
                int a = Integer.parseInt(p.substring(0, dash).trim());
                int b = Integer.parseInt(p.substring(dash + 1).trim());
                if (a > b) { int t = a; a = b; b = t; }
                if (a < 1) a = 1;
                if (b > max) b = max;
                for (int x = a; x <= b; x++) seen.add(Integer.valueOf(x));
            } catch (Exception e) {}
        } else {
            try {
                int x = Integer.parseInt(p);
                if (x >= 1 && x <= max) seen.add(Integer.valueOf(x));
            } catch (Exception e) {}
        }
    }
    return new ArrayList(seen);
}

List getBlacklist(String groupId) { return parseCsv(getString("bl_" + groupId, "")); }
void setBlacklist(String groupId, List bl) { putString("bl_" + groupId, joinCsv(bl)); }

// ========== bot 群管权限缓存 (lazy learning) ==========
// 值: "admin"=已确认有权, "none"=已确认无权, "unknown"=没操作过
String botPrivState(String groupId) { return getString("bp_" + groupId, "unknown"); }
void   setBotPriv(String groupId, String state) { putString("bp_" + groupId, state); }

boolean isPermissionError(Exception e) {
    if (e == null) return false;
    String m = e.getMessage();
    if (m == null) return false;
    String lo = m.toLowerCase();
    if (lo.indexOf("permission") >= 0 || lo.indexOf("admin") >= 0 ||
        lo.indexOf("not allowed") >= 0 || lo.indexOf("forbidden") >= 0 ||
        lo.indexOf("denied") >= 0) return true;
    if (m.indexOf("权限") >= 0 || m.indexOf("管理") >= 0 || m.indexOf("无权") >= 0) return true;
    return false;
}

// 返回: true=成功, false=失败 (调用方决定要不要 sendText)
// 调用前应自行检查 botPrivState(groupId) != "none"
boolean tryKickSilent(String groupId, String wxid) {
    try {
        delChatroomMember(groupId, wxid);
        setBotPriv(groupId, "admin");
        return true;
    } catch (Exception e) {
        if (isPermissionError(e)) setBotPriv(groupId, "none");
        log("kick fail group=" + groupId + " wxid=" + wxid + " err=" + e.getMessage());
        return false;
    }
}

void warnNoBotPriv(String groupId) {
    sendText(groupId, "❌ 机器人在本群没有群管理员权限，无法执行踢人\n请群主先把机器人在群里提升为管理员\n之后发 #群权限自检 重置检测");
}

// ========== 潜水追踪 ==========
// 存: lsg_<groupId> = wxid|ts,...  (last_speak), fsg_<groupId> = wxid|ts,... (first_seen)
// T2: 热路径只入内存脏增量 (O(1)), 绝不碰磁盘。落盘由后台 l3FlushLoop / flush-before-read / onUnload 完成。
void recordSpeak(String groupId, String wxid) {
    if (groupId == null || wxid == null || groupId.isEmpty() || wxid.isEmpty()) return;
    // T4 降级: 入口廉价判断 (一个布尔读 + 判断, O(1))。降级期丢弃 L3 采集, 保收发, 不影响 L1/L2。
    if (L3_DEGRADED) return;
    Long now = Long.valueOf(System.currentTimeMillis());
    synchronized (L3_LOCK) {
        Map ls = (Map) L3_dirtyLs.get(groupId);
        if (ls == null) { ls = new HashMap(); L3_dirtyLs.put(groupId, ls); }
        // T4: 只在新 key 时 +1 running counter (覆盖已有 key 不增条数), 与 flush 换出清零对称。
        if (!ls.containsKey(wxid)) L3_dirtyCount++;
        ls.put(wxid, now);   // last_speak: 同周期内多次发言取最后一次 (覆盖即可, flush 再与磁盘取较新)

        Map fs = (Map) L3_dirtySeen.get(groupId);
        if (fs == null) { fs = new HashMap(); L3_dirtySeen.put(groupId, fs); }
        // first_seen 候选: 只在本周期首次见到时记一次, 不覆盖 (flush 落盘时再保证不覆盖磁盘上更早的)
        if (!fs.containsKey(wxid)) { L3_dirtyCount++; fs.put(wxid, now); }

        // T4 触发器(1) 缓冲上界: O(1) 计数到顶 → 进入降级 (留痕含原因/条数), 后续 recordSpeak 直接丢弃。
        if (!L3_DEGRADED && L3_dirtyCount >= L3_BUFFER_CAP) {
            L3_DEGRADED = true;
            perfDegrade(true, "buffer_cap", L3_dirtyCount, L3_flushFailStreak);
        }
    }
}

void recordFirstSeen(String groupId, String wxid) {
    if (groupId == null || wxid == null || wxid.isEmpty()) return;
    // T2′: first_seen UPSERT 进 SQLite, 冲突取 min (绝不覆盖更早的)。命令/入群路径 (L1/L2), 非热路径。
    List one = new ArrayList();
    one.add(wxid);
    l3UpsertFirstSeen(groupId, one, System.currentTimeMillis());
}

// 把群里所有当前成员中没 first_seen 的人设为 now (建立基线)
// 返回新设置的人数 (此前 DB 无 first_seen 的人)
int initFirstSeenBaseline(String groupId) {
    if (groupId == null || groupId.isEmpty()) return 0;
    List members = null;
    try { members = getGroupMemberList(groupId); } catch (Exception e) { log("baseline: getGroupMemberList fail " + groupId + " " + e); }
    if (members == null || members.isEmpty()) return 0;
    // T2′: 批量 UPSERT first_seen 进 SQLite, 冲突取 min (不覆盖更早), 返回新增人数。命令路径, 非热路径。
    return l3UpsertFirstSeen(groupId, members, System.currentTimeMillis());
}

// 受保护名单 (手动维护, 用于微信原生群主等无法自动识别的人)
List getProtected(String groupId) { return parseCsv(getString("protected_" + groupId, "")); }

// ==== 白名单 + 全局配置 + 严格模式 (v1.12) ====
List getWhitelist(String groupId) { return parseCsv(getString("wt_" + groupId, "")); }
void setWhitelist(String groupId, List wt) { putString("wt_" + groupId, joinCsv(wt)); }

List getGlobalBlacklist() { return parseCsv(getString("bl_global", "")); }
void setGlobalBlacklist(List bl) { putString("bl_global", joinCsv(bl)); }
boolean isGlobalBlacklist(String wxid) { return wxid != null && !wxid.isEmpty() && getGlobalBlacklist().contains(wxid); }

List getGlobalWhitelist() { return parseCsv(getString("wt_global", "")); }
void setGlobalWhitelist(List wt) { putString("wt_global", joinCsv(wt)); }
boolean isGlobalWhitelist(String wxid) { return wxid != null && !wxid.isEmpty() && getGlobalWhitelist().contains(wxid); }

// 合并视图: 是否在白名单(本群 OR 全局)
boolean isWhitelistAny(String groupId, String wxid) {
    if (wxid == null || wxid.isEmpty()) return false;
    return isGlobalWhitelist(wxid) || getWhitelist(groupId).contains(wxid);
}

// 严格模式: 开启时所有命令仅群主, 否则管理员+
boolean isStrictMode() { return "1".equals(getString("strict_mode", "0")); }
void setStrictMode(boolean on) { putString("strict_mode", on ? "1" : "0"); }

// ==== 微信原生群主 / 管理员保护 (v1.13) ====
// 自动探测 GroupData/ChatRoomInfo 群主字段; 微信管理员不再单独登记, 使用本群管理员体系
// 登记后, canActOn 拒绝任何人对其执行踢/拉黑/警告
List getNativeOwners(String groupId)  { return parseCsv(getString("nat_owner_" + groupId, "")); }

// v1.14.0: 群主保留自动检测；微信管理员不再单独登记, 使用本群管理员体系
boolean isNativeOwner(String groupId, String wxid)  {
    if (wxid == null || wxid.isEmpty()) return false;
    if (getNativeOwners(groupId).contains(wxid)) return true;     // 手动登记
    String auto = _detectNativeOwner(groupId);                    // 自动检测
    return auto != null && auto.equals(wxid);
}

Object _getGroupDataObj(Object info) {
    try { return info.getClass().getMethod("getGroupData").invoke(info); }
    catch (Exception e) { return null; }
}

Object _readRoleMethod(Object src, String name) {
    if (src == null) return null;
    try { return src.getClass().getMethod(name).invoke(src); }
    catch (Exception e) {}
    try {
        java.lang.reflect.Method m = src.getClass().getDeclaredMethod(name);
        m.setAccessible(true);
        return m.invoke(src);
    } catch (Exception e) {}
    return null;
}

Object _readRoleField(Object src, String name) {
    if (src == null) return null;
    Class c = src.getClass();
    while (c != null) {
        try {
            java.lang.reflect.Field f = c.getDeclaredField(name);
            f.setAccessible(true);
            return f.get(src);
        } catch (Exception e) {}
        c = c.getSuperclass();
    }
    return null;
}

String _roleValueToString(Object r) {
    if (r == null) return null;
    if (r instanceof List) {
        List l = (List) r;
        if (l.isEmpty()) return null;
        Object one = l.get(0);
        return one == null ? null : one.toString();
    }
    if (r instanceof byte[]) {
        List ids = _extractWxidsFromBytes((byte[]) r);
        return ids.isEmpty() ? null : ids.get(0).toString();
    }
    String s = r.toString().replace("[", "").replace("]", "").trim();
    if (s.isEmpty()) return null;
    String[] arr = s.split("[,;\\s]+");
    return arr.length > 0 ? arr[0].trim() : s;
}

List _extractWxidsFromBytes(byte[] bs) {
    List out = new ArrayList();
    if (bs == null || bs.length == 0) return out;
    StringBuilder cur = new StringBuilder();
    for (int i = 0; i < bs.length; i++) {
        int c = bs[i] & 0xff;
        boolean ok = (c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || c == '_' || c == '-' || c == '@' || c == '.';
        if (ok) cur.append((char) c);
        else {
            _addWxidToken(cur.toString(), out);
            cur.setLength(0);
        }
    }
    _addWxidToken(cur.toString(), out);
    return out;
}

void _addWxidToken(String s, List out) {
    if (s == null || s.length() < 4) return;
    String[] arr = s.split("[,;\\s]+");
    for (int i = 0; i < arr.length; i++) {
        String one = arr[i].trim();
        if ((one.startsWith("wxid_") || one.endsWith("@chatroom") || one.startsWith("gh_")) && !out.contains(one)) out.add(one);
    }
}

String _readRoleString(Object src, String csvNames) {
    if (src == null) return null;
    String[] names = csvNames.split(",");
    for (int i = 0; i < names.length; i++) {
        Object r = _readRoleMethod(src, names[i]);
        String v = _roleValueToString(r);
        if (v != null && !v.isEmpty()) return v;
        r = _readRoleField(src, names[i]);
        v = _roleValueToString(r);
        if (v != null && !v.isEmpty()) return v;
    }
    return null;
}

void _addRoleValue(Object r, List out) {
    if (r == null) return;
    if (r instanceof List) {
        List l = (List) r;
        for (int i = 0; i < l.size(); i++) {
            Object one = l.get(i);
            if (one != null && !out.contains(one.toString())) out.add(one.toString());
        }
        return;
    }
    if (r instanceof byte[]) {
        List ids = _extractWxidsFromBytes((byte[]) r);
        for (int i = 0; i < ids.size(); i++) if (!out.contains(ids.get(i))) out.add(ids.get(i));
        return;
    }
    String s = r.toString().replace("[", "").replace("]", "").trim();
    if (s.isEmpty()) return;
    String[] arr = s.split("[,;\\s]+");
    for (int k = 0; k < arr.length; k++) {
        String one = arr[k].trim();
        if (!one.isEmpty() && !out.contains(one)) out.add(one);
    }
}

void _addRoleList(Object src, String csvNames, List out) {
    if (src == null) return;
    String[] names = csvNames.split(",");
    for (int i = 0; i < names.length; i++) {
        int before = out.size();
        _addRoleValue(_readRoleMethod(src, names[i]), out);
        _addRoleValue(_readRoleField(src, names[i]), out);
        if (out.size() > before) return;
    }
}

Object _tryInvokeNoArg(Object src, String name) {
    if (src == null) return null;
    try { return src.getClass().getMethod(name).invoke(src); } catch (Exception e) {}
    try {
        java.lang.reflect.Method m = src.getClass().getDeclaredMethod(name);
        m.setAccessible(true);
        return m.invoke(src);
    } catch (Exception e) {}
    return null;
}

Object _tryInvokeOneString(Object src, String name, String arg) {
    if (src == null) return null;
    Class[] pts = new Class[1];
    pts[0] = String.class;
    Object[] args = new Object[1];
    args[0] = arg;
    try { return src.getClass().getMethod(name, pts).invoke(src, args); } catch (Exception e) {}
    try {
        java.lang.reflect.Method m = src.getClass().getDeclaredMethod(name, pts);
        m.setAccessible(true);
        return m.invoke(src, args);
    } catch (Exception e) {}
    return null;
}

Object _getSingletonOrCompanion(Class c) {
    if (c == null) return null;
    try {
        java.lang.reflect.Field f = c.getDeclaredField("INSTANCE");
        f.setAccessible(true);
        Object v = f.get(null);
        if (v != null) return v;
    } catch (Exception e) {}
    try {
        java.lang.reflect.Field f = c.getDeclaredField("Companion");
        f.setAccessible(true);
        Object v = f.get(null);
        if (v != null) return v;
    } catch (Exception e) {}
    try {
        java.lang.reflect.Field[] fs = c.getDeclaredFields();
        for (int i = 0; i < fs.length; i++) {
            fs[i].setAccessible(true);
            int mod = fs[i].getModifiers();
            if (!java.lang.reflect.Modifier.isStatic(mod)) continue;
            Object v = fs[i].get(null);
            if (v != null && c.isInstance(v)) return v;
        }
    } catch (Exception e) {}
    return null;
}

Object _detectChatRoomInfoObj(String groupId) {
    Object nested = null;
    try {
        List groups = getGroupList();
        if (groups != null) {
            for (int i = 0; i < groups.size(); i++) {
                Object info = groups.get(i);
                Object ridObj = _tryInvokeNoArg(info, "getRoomId");
                if (ridObj == null || !ridObj.toString().equals(groupId)) continue;
                nested = _findChatRoomInfoInside(info, groupId, 0);
                if (nested != null) return nested;
                nested = _findChatRoomInfoInside(_getGroupDataObj(info), groupId, 0);
                if (nested != null) return nested;
                break;
            }
        }
    } catch (Exception e) {}

    Object internal = _detectChatRoomInfoFromWauxvInternals(groupId);
    if (internal != null) return internal;

    String clsNames = "me.hd.wauxv.data.bean.db.ChatRoomInfo,me.hd.wauxv.data.db.ChatRoomInfoDao,me.hd.wauxv.data.db.ChatroomInfoDao,me.hd.wauxv.data.db.ChatRoomDao,me.hd.wauxv.data.db.ChatroomDao,me.hd.wauxv.data.dao.ChatRoomInfoDao,me.hd.wauxv.data.dao.ChatroomInfoDao,me.hd.wauxv.data.repository.ChatRoomInfoRepository,me.hd.wauxv.data.repository.ChatroomInfoRepository,me.hd.wauxv.data.db.ChatRoomInfoManager,me.hd.wauxv.data.db.ChatRoomInfoDatabase,me.hd.wauxv.data.db.WAuxiliaryDatabase,me.hd.wauxv.data.db.AppDatabase,me.hd.wauxv.data.WAuxiliaryDatabase,me.hd.wauxv.data.DataRepository";
    String methodNames = "getChatRoomInfo,getChatroomInfo,getChatRoomInfoById,getChatroomInfoById,getByRoomId,getByChatroomName,getByUsername,getByUserName,getByWxid,getById,queryChatRoomInfo,queryChatroomInfo,queryByRoomId,queryByChatroomName,findChatRoomInfo,findChatroomInfo,findByRoomId,findByChatroomName,loadChatRoomInfo,loadChatroomInfo,loadByRoomId,loadByChatroomName";
    String[] cs = clsNames.split(",");
    String[] ms = methodNames.split(",");
    for (int i = 0; i < cs.length; i++) {
        try {
            Class c = Class.forName(cs[i]);
            if (cs[i].endsWith("ChatRoomInfo")) continue;
            Object src = _getSingletonOrCompanion(c);
            if (src == null) src = c.newInstance();
            for (int k = 0; k < ms.length; k++) {
                Object r = _tryInvokeOneString(src, ms[k], groupId);
                if (_isChatRoomInfoFor(r, groupId)) return r;
            }
            Object r2 = _tryInvokeRoomDao(src, groupId, 0);
            if (r2 != null) return r2;
        } catch (Exception e) {}
    }
    return null;
}

boolean _isSafeProbeClass(Object obj) {
    if (obj == null) return false;
    String cn = obj.getClass().getName();
    if (cn.startsWith("java.") || cn.startsWith("android.")) return false;
    if (cn.startsWith("com.tencent.")) return false;
    return cn.indexOf("wauxv") >= 0 || cn.indexOf("ᛱ") >= 0 || cn.indexOf("ChatRoom") >= 0 || cn.indexOf("Group") >= 0;
}

boolean _isChatRoomInfoFor(Object obj, String groupId) {
    if (obj == null) return false;
    String cn = obj.getClass().getName();
    if (cn.indexOf("ChatRoomInfo") < 0 && _readRoleMethod(obj, "getLocalChatRoomWatchMembers") == null && _readRoleMethod(obj, "component19") == null) return false;
    String room = _roleValueToString(_readRoleMethod(obj, "getChatroomname"));
    if (room == null) room = _roleValueToString(_readRoleField(obj, "chatroomname"));
    if (room == null) room = _roleValueToString(_readRoleMethod(obj, "getRoomId"));
    if (room == null) room = _roleValueToString(_readRoleField(obj, "roomId"));
    return room == null || room.equals(groupId);
}

Object _findChatRoomInfoInside(Object obj, String groupId, int depth) {
    if (obj == null || depth > 2) return null;
    if (_isChatRoomInfoFor(obj, groupId)) return obj;
    if (!_isSafeProbeClass(obj)) return null;
    try {
        java.lang.reflect.Method[] ms = obj.getClass().getMethods();
        for (int i = 0; i < ms.length; i++) {
            Object r = _tryFindChatRoomInfoFromMethod(obj, ms[i], groupId, depth);
            if (r != null) return r;
        }
    } catch (Exception e) {}
    try {
        java.lang.reflect.Method[] ms = obj.getClass().getDeclaredMethods();
        for (int i = 0; i < ms.length; i++) {
            ms[i].setAccessible(true);
            Object r = _tryFindChatRoomInfoFromMethod(obj, ms[i], groupId, depth);
            if (r != null) return r;
        }
    } catch (Exception e) {}
    try {
        Class c = obj.getClass();
        while (c != null) {
            java.lang.reflect.Field[] fs = c.getDeclaredFields();
            for (int i = 0; i < fs.length; i++) {
                fs[i].setAccessible(true);
                Object v = fs[i].get(obj);
                if (_isChatRoomInfoFor(v, groupId)) return v;
                Object r = _findChatRoomInfoInside(v, groupId, depth + 1);
                if (r != null) return r;
            }
            c = c.getSuperclass();
        }
    } catch (Exception e) {}
    return null;
}

Object _tryFindChatRoomInfoFromMethod(Object obj, java.lang.reflect.Method m, String groupId, int depth) {
    if (obj == null || m == null) return null;
    try {
        if (m.getReturnType() == Void.TYPE) return null;
        if (m.getParameterTypes().length != 0) return null;
        String mn = m.getName().toLowerCase();
        if (mn.equals("getclass") || mn.indexOf("chatroom") < 0 && mn.indexOf("room") < 0 && mn.indexOf("data") < 0 && mn.indexOf("info") < 0) return null;
        Object r = m.invoke(obj);
        if (_isChatRoomInfoFor(r, groupId)) return r;
        return _findChatRoomInfoInside(r, groupId, depth + 1);
    } catch (Exception e) {}
    return null;
}

Class _loadWauxvClass(String name) {
    if (name == null || name.isEmpty()) return null;
    try { return Class.forName(name); } catch (Exception e) {}
    try {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl != null) return cl.loadClass(name);
    } catch (Exception e) {}
    try {
        List groups = getGroupList();
        if (groups != null && !groups.isEmpty()) {
            Object info = groups.get(0);
            if (info != null && info.getClass().getClassLoader() != null) {
                try { return info.getClass().getClassLoader().loadClass(name); } catch (Exception e) {}
            }
            Object data = _getGroupDataObj(info);
            if (data != null && data.getClass().getClassLoader() != null) {
                try { return data.getClass().getClassLoader().loadClass(name); } catch (Exception e) {}
            }
        }
    } catch (Exception e) {}
    return null;
}

Object _detectChatRoomInfoFromWauxvInternals(String groupId) {
    try {
        Class mapperClass = _loadWauxvClass("ᛱᛲᛳᛴᛵᛶᛷᲇᛸᲁᲈᲀᤞᤝ");
        Class queryClass = _loadWauxvClass("ᛱᛲᛳᛴᛵᛶᛷᲀᛸᤞᲇᲈᲁᤝ");
        if (mapperClass == null || queryClass == null) {
            log("native-admin detect: class load failed mapper=" + (mapperClass != null) + ", query=" + (queryClass != null));
            return null;
        }
        Object queryObj = _getSingletonOrCompanion(queryClass);
        Object cursor = _tryGetChatRoomCursor(queryObj == null ? queryClass : queryObj, groupId);
        if (cursor == null) { log("native-admin detect: no matching ChatRoomInfo cursor"); return null; }
        try {
            Object mapped = _tryMapChatRoomInfo(mapperClass, cursor, groupId);
            if (mapped != null) {
                log("native-admin detect: mapped " + mapped.getClass().getName());
                return mapped;
            }
            log("native-admin detect: cursor found but mapper returned null");
        } finally {
            try { ((java.io.Closeable) cursor).close(); } catch (Exception e) {}
        }
    } catch (Exception e) { log("_detectChatRoomInfoFromWauxvInternals err: " + e.getClass().getName() + ": " + e.getMessage()); }
    return null;
}

Object _tryGetChatRoomCursor(Object queryObj, String groupId) {
    if (queryObj == null) return null;
    int tried = 0;
    int invoked = 0;
    try {
        boolean classMode = queryObj instanceof Class;
        Class qc = classMode ? (Class) queryObj : queryObj.getClass();
        java.lang.reflect.Method[] ms = qc.getDeclaredMethods();
        for (int i = 0; i < ms.length; i++) {
            ms[i].setAccessible(true);
            Class[] pt = ms[i].getParameterTypes();
            if (ms[i].getReturnType().getName().equals("android.database.Cursor") && pt.length == 1 && pt[0].isArray()) {
                boolean isStatic = java.lang.reflect.Modifier.isStatic(ms[i].getModifiers());
                if (classMode && !isStatic) continue;
                tried++;
                List candidates = _collectCursorArgCandidates(qc, pt[0]);
                log("native-admin detect: cursor method#" + i + " candidates=" + candidates.size() + ", static=" + isStatic);
                for (int ci = 0; ci < candidates.size(); ci++) {
                    Object[] args = new Object[1];
                    args[0] = candidates.get(ci);
                    Object cursor = null;
                    try {
                        cursor = ms[i].invoke(classMode ? null : queryObj, args);
                        invoked++;
                        Object one = _moveCursorToGroup(cursor, groupId);
                        if (one != null) {
                            log("native-admin detect: cursor matched via method#" + i + ", candidate=" + ci + ", len=" + _arrayLength(args[0]) + ", static=" + isStatic);
                            return one;
                        }
                    } catch (Exception e) {
                        log("native-admin detect: cursor method#" + i + ", candidate=" + ci + " failed " + e.getClass().getName());
                    }
                    try { ((java.io.Closeable) cursor).close(); } catch (Exception e) {}
                }
            }
        }
    } catch (Exception e) { log("native-admin detect: cursor scan failed " + e.getClass().getName() + ": " + e.getMessage()); }
    log("native-admin detect: cursor methods tried=" + tried + ", invoked=" + invoked);
    return null;
}

List _collectCursorArgCandidates(Class queryClass, Class arrayType) {
    List out = new ArrayList();
    if (arrayType == null || !arrayType.isArray()) return out;
    Class elemType = arrayType.getComponentType();

    // 1) 保留旧路径：空数组。部分 DAO 查询方法可接受空条件。
    _addCursorArgCandidate(out, java.lang.reflect.Array.newInstance(elemType, 0), arrayType);

    // 2) DEX 原始调用常见路径：query 类上的静态参数数组字段。
    _addStaticCursorArrayFields(out, queryClass, arrayType);
    try {
        Class enclosing = queryClass.getEnclosingClass();
        if (enclosing != null) _addStaticCursorArrayFields(out, enclosing, arrayType);
    } catch (Exception e) {}
    try {
        Class declaring = queryClass.getDeclaringClass();
        if (declaring != null) _addStaticCursorArrayFields(out, declaring, arrayType);
    } catch (Exception e) {}

    // 3) Kotlin/object 或 enum-like 参数：参数元素类的静态单例，组装成一元素数组。
    Object singleton = _getSingletonOrCompanion(elemType);
    if (singleton != null && elemType.isInstance(singleton)) _addSingletonCursorArray(out, singleton, elemType, arrayType);
    _addStaticCursorSingletonFields(out, elemType, arrayType);

    return out;
}

void _addStaticCursorArrayFields(List out, Class srcClass, Class arrayType) {
    if (srcClass == null || arrayType == null) return;
    try {
        Class c = srcClass;
        while (c != null) {
            java.lang.reflect.Field[] fs = c.getDeclaredFields();
            for (int i = 0; i < fs.length; i++) {
                try {
                    int mod = fs[i].getModifiers();
                    if (!java.lang.reflect.Modifier.isStatic(mod)) continue;
                    fs[i].setAccessible(true);
                    Object v = fs[i].get(null);
                    _addCursorArgCandidate(out, v, arrayType);
                } catch (Exception e) {}
            }
            c = c.getSuperclass();
        }
    } catch (Exception e) {}
}

void _addStaticCursorSingletonFields(List out, Class elemType, Class arrayType) {
    if (elemType == null || arrayType == null) return;
    try {
        Class c = elemType;
        while (c != null) {
            java.lang.reflect.Field[] fs = c.getDeclaredFields();
            for (int i = 0; i < fs.length; i++) {
                try {
                    int mod = fs[i].getModifiers();
                    if (!java.lang.reflect.Modifier.isStatic(mod)) continue;
                    fs[i].setAccessible(true);
                    Object v = fs[i].get(null);
                    if (v != null && elemType.isInstance(v)) _addSingletonCursorArray(out, v, elemType, arrayType);
                } catch (Exception e) {}
            }
            c = c.getSuperclass();
        }
    } catch (Exception e) {}
}

void _addSingletonCursorArray(List out, Object singleton, Class elemType, Class arrayType) {
    if (singleton == null || elemType == null || arrayType == null) return;
    try {
        Object arr = java.lang.reflect.Array.newInstance(elemType, 1);
        java.lang.reflect.Array.set(arr, 0, singleton);
        _addCursorArgCandidate(out, arr, arrayType);
    } catch (Exception e) {}
}

void _addCursorArgCandidate(List out, Object arr, Class arrayType) {
    if (out == null || arr == null || arrayType == null) return;
    if (!arrayType.isInstance(arr)) return;
    int len = _arrayLength(arr);
    for (int i = 0; i < out.size(); i++) {
        Object old = out.get(i);
        if (old == arr) return;
        if (len == 0 && _arrayLength(old) == 0 && old != null && old.getClass().equals(arr.getClass())) return;
    }
    out.add(arr);
}

int _arrayLength(Object arr) {
    if (arr == null || !arr.getClass().isArray()) return -1;
    try { return java.lang.reflect.Array.getLength(arr); } catch (Exception e) { return -1; }
}

Object _moveCursorToGroup(Object cursor, String groupId) {
    if (cursor == null) return null;
    try {
        android.database.Cursor c = (android.database.Cursor) cursor;
        int rows = 0;
        int idx = c.getColumnIndex("chatroomname");
        if (idx < 0) idx = c.getColumnIndex("username");
        while (c.moveToNext()) {
            rows++;
            if (idx >= 0 && groupId.equals(c.getString(idx))) return cursor;
            String[] cols = c.getColumnNames();
            for (int i = 0; i < cols.length; i++) {
                try {
                    String v = c.getString(i);
                    if (groupId.equals(v)) return cursor;
                } catch (Exception e) {}
            }
        }
        log("native-admin detect: scanned cursor rows=" + rows + ", no group match");
    } catch (Exception e) { log("native-admin detect: move cursor failed " + e.getClass().getName() + ": " + e.getMessage()); }
    return null;
}

Object _tryMapChatRoomInfo(Class mapperClass, Object cursor, String groupId) {
    if (mapperClass == null || cursor == null) return null;
    int tried = 0;
    try {
        Object mapper = _getSingletonOrCompanion(mapperClass);
        boolean hasMapper = mapper != null;
        java.lang.reflect.Method[] ms = mapperClass.getDeclaredMethods();
        for (int i = 0; i < ms.length; i++) {
            ms[i].setAccessible(true);
            Class[] pt = ms[i].getParameterTypes();
            if (pt.length == 1 && pt[0].getName().equals("android.database.Cursor") && ms[i].getReturnType().getName().indexOf("ChatRoomInfo") >= 0) {
                boolean isStatic = java.lang.reflect.Modifier.isStatic(ms[i].getModifiers());
                if (!hasMapper && !isStatic) continue;
                tried++;
                Object[] args = new Object[1];
                args[0] = cursor;
                try {
                    Object r = ms[i].invoke(isStatic ? null : mapper, args);
                    if (_isChatRoomInfoFor(r, groupId)) { log("native-admin detect: mapper method#" + i + " matched, static=" + isStatic); return r; }
                } catch (Exception e) {
                    log("native-admin detect: mapper method#" + i + " failed " + e.getClass().getName());
                }
            }
        }
        if (!hasMapper) log("native-admin detect: mapper has no singleton, static methods only");
    } catch (Exception e) { log("native-admin detect: mapper scan failed " + e.getClass().getName() + ": " + e.getMessage()); }
    log("native-admin detect: mapper methods tried=" + tried);
    return null;
}

Object _tryInvokeRoomDao(Object src, String groupId, int depth) {
    if (src == null || depth > 2) return null;
    try {
        java.lang.reflect.Method[] ms = src.getClass().getMethods();
        for (int i = 0; i < ms.length; i++) {
            Object r = _tryInvokeRoomMethod(src, ms[i], groupId, depth);
            if (r != null) return r;
        }
    } catch (Exception e) {}
    try {
        java.lang.reflect.Method[] ms = src.getClass().getDeclaredMethods();
        for (int i = 0; i < ms.length; i++) {
            ms[i].setAccessible(true);
            Object r = _tryInvokeRoomMethod(src, ms[i], groupId, depth);
            if (r != null) return r;
        }
    } catch (Exception e) {}
    return null;
}

Object _tryInvokeRoomMethod(Object src, java.lang.reflect.Method m, String groupId, int depth) {
    if (src == null || m == null) return null;
    try {
        if (m.getReturnType() == Void.TYPE) return null;
        Class[] pt = m.getParameterTypes();
        String mn = m.getName().toLowerCase();
        if (pt.length == 0) {
            if (mn.indexOf("chatroom") < 0 && mn.indexOf("room") < 0 && mn.indexOf("dao") < 0) return null;
            Object r = m.invoke(src);
            if (r == null) return null;
            if (r.getClass().getName().indexOf("ChatRoomInfo") >= 0) return r;
            Object rr = _tryInvokeRoomDao(r, groupId, depth + 1);
            if (rr != null) return rr;
        }
        if (pt.length == 1 && pt[0] == String.class) {
            if (mn.indexOf("chatroom") < 0 && mn.indexOf("room") < 0 && mn.indexOf("query") < 0 && mn.indexOf("find") < 0 && mn.indexOf("get") < 0 && mn.indexOf("load") < 0) return null;
            Object[] args = new Object[1];
            args[0] = groupId;
            Object r = m.invoke(src, args);
            if (r == null) return null;
            if (r.getClass().getName().indexOf("ChatRoomInfo") >= 0) return r;
        }
    } catch (Exception e) {}
    return null;
}

// 自动检测 — 通过 reflection 同时探测 WAuxiliary GroupInfo / GroupData / ChatRoomInfo
String _detectNativeOwner(String groupId) {
    String names = "getOwner,getOwnerId,getRoomOwner,getRoomowner,getOwnerWxid,getRoomOwnerId,getChatroomOwner,getChatRoomOwner,getOwnerUserName,getOwnerUsername,component9,owner,ownerId,roomOwner,roomowner,ownerWxid,roomOwnerId,chatroomOwner,chatRoomOwner,ownerUserName,ownerUsername";
    try {
        List groups = getGroupList();
        for (int i = 0; i < groups.size(); i++) {
            Object info = groups.get(i);
            Object ridObj = info.getClass().getMethod("getRoomId").invoke(info);
            if (ridObj == null || !ridObj.toString().equals(groupId)) continue;
            String v = _readRoleString(info, names);
            if (v != null) return v;
            v = _readRoleString(_getGroupDataObj(info), names);
            if (v != null) return v;
            v = _readRoleString(_detectChatRoomInfoObj(groupId), names);
            if (v != null) return v;
            break;
        }
    } catch (Exception e) { log("_detectNativeOwner err: " + e.getMessage()); }
    String v = _readRoleString(_detectChatRoomInfoObj(groupId), names);
    return v;
}

void _logNativeRoleProbe(String groupId) {
    try {
        String owner = _detectNativeOwner(groupId);
        log("native-role probe: ownerAuto=" + (owner != null && !owner.isEmpty()));
    } catch (Exception e) {
        log("native-role probe err: " + e.getClass().getName() + ": " + e.getMessage());
    }
}

String _shortProbeValue(Object v) {
    if (v == null) return "null";
    String s = v.toString().replace('\n', ' ').replace('\r', ' ');
    if (s.length() > 80) s = s.substring(0, 80) + "...";
    return s;
}

void _probeObjectAPI(String tag, Object obj) {
    if (obj == null) { log("[probe] " + tag + " null"); return; }
    try {
        log("[probe] " + tag + " class: " + obj.getClass().getName());
        java.lang.reflect.Method[] ms = obj.getClass().getMethods();
        StringBuilder sb = new StringBuilder("[probe] " + tag + " methods: ");
        for (int i = 0; i < ms.length; i++) {
            if ((ms[i].getName().startsWith("get") || ms[i].getName().startsWith("is")) &&
                !ms[i].getName().equals("getClass")) {
                sb.append(ms[i].getName()).append("(");
                Class[] pt = ms[i].getParameterTypes();
                for (int k = 0; k < pt.length; k++) {
                    if (k > 0) sb.append(",");
                    sb.append(pt[k].getSimpleName());
                }
                sb.append(") ");
            }
        }
        log(sb.toString());

        Class c = obj.getClass();
        while (c != null) {
            java.lang.reflect.Field[] fs = c.getDeclaredFields();
            StringBuilder fb = new StringBuilder("[probe] " + tag + " fields@" + c.getSimpleName() + ": ");
            for (int i = 0; i < fs.length; i++) {
                try {
                    fs[i].setAccessible(true);
                    fb.append(fs[i].getName()).append("=").append(_shortProbeValue(fs[i].get(obj))).append("; ");
                } catch (Exception e) { fb.append(fs[i].getName()).append("=?; "); }
            }
            log(fb.toString());
            c = c.getSuperclass();
        }
    } catch (Exception e) { log("[probe] " + tag + " err: " + e.getMessage()); }
}

// onLoad 时 dump GroupInfo/GroupData 所有 getter/is 方法到 log, 帮助找真实 API 名
void _probeGroupInfoAPI() {
    try {
        List groups = getGroupList();
        if (groups == null || groups.isEmpty()) { log("[probe] getGroupList empty"); return; }
        Object info = groups.get(0);
        _probeObjectAPI("GroupInfo", info);
        _probeObjectAPI("GroupData", _getGroupDataObj(info));
    } catch (Exception e) { log("[probe] err: " + e.getMessage()); }
}
void setProtected(String groupId, List p) { putString("protected_" + groupId, joinCsv(p)); }

void doShowInactive(String groupId, String content) {
    String[] tk = content.split("[\\s\\u2005\\u00A0]+");
    int days = 7;
    if (tk.length >= 2) { try { days = Integer.parseInt(tk[1].trim()); } catch (Exception e) {} }
    if (days < 1) days = 1;
    if (days > 365) days = 365;

    List members = null;
    try { members = getGroupMemberList(groupId); } catch (Exception e) { log("getGroupMemberList fail: " + e); }
    if (members == null || members.isEmpty()) { sendText(groupId, "❌ 拿不到群成员列表"); return; }

    // T2′: flush-before-read — 最新发言可能仍在内存脏增量未落盘, 读前先同步 flush 提交进 DB, 避免刚发言者被误判潜水。
    // 注: 正确性由 UPSERT 单调 max(last_speak)/min(first_seen) 语义保证, 非靠 FLUSH_LOCK 串行化 (随后的 query 在锁外)。
    try { l3Flush(); } catch (Throwable t) { log("doShowInactive l3Flush fail: " + t); }

    long now = System.currentTimeMillis();
    long cutoff = now - (long) days * 86400000L;
    // T2′: lsg_/fsg_ 从 SQLite 查 (flush 已把内存增量提交进 DB)。
    Map ls = l3QueryLastSpeak(groupId);
    Map fs = l3QueryFirstSeen(groupId);
    boolean coldStart = fs.isEmpty();
    if (coldStart) {
        // 冷启动: 该群 DB 无任何 first_seen, 把所有当前成员的 first_seen 设为 now (批量 UPSERT, 冲突取 min)。
        List allMem = new ArrayList();
        for (int i = 0; i < members.size(); i++) allMem.add(members.get(i));
        l3UpsertFirstSeen(groupId, allMem, now);
        for (int i = 0; i < members.size(); i++) fs.put((String) members.get(i), Long.valueOf(now));
    }
    List prot = getProtected(groupId);
    String botWxid = getLoginWxid();

    if (coldStart) {
        sendText(groupId, "📊 已建立潜水基线 (共 " + members.size() + " 人)\n请等待 " + days + " 天后再发 #潜水 " + days);
        return;
    }

    List inactive = new ArrayList();
    int newJoinSkip = 0;
    for (int i = 0; i < members.size(); i++) {
        String w = (String) members.get(i);
        if (w == null || w.isEmpty()) continue;
        if (w.equals(botWxid)) continue;
        if (isAdminInGroup(groupId, w) || isOwner(w)) continue;
        if (isNativeOwner(groupId, w)) continue;
        if (prot.contains(w)) continue;

        Long fst = (Long) fs.get(w);
        if (fst == null) {
            // 新成员刚刚被加入 fsg, 跳过本轮
            recordFirstSeen(groupId, w);
            newJoinSkip++;
            continue;
        }
        if (fst.longValue() > cutoff) { newJoinSkip++; continue; }  // 新成员豁免

        Long lst = (Long) ls.get(w);
        if (lst != null && lst.longValue() > cutoff) continue;     // 期内说过话

        inactive.add(w);
    }

    if (inactive.isEmpty()) {
        StringBuilder sb = new StringBuilder();
        sb.append("✅ 过去 ").append(days).append(" 天本群无潜水成员");
        if (newJoinSkip > 0) sb.append("\n(").append(newJoinSkip).append(" 个新成员在豁免期)");
        sendText(groupId, sb.toString());
        return;
    }

    int cap = 80;
    int total = inactive.size();
    int show = total < cap ? total : cap;
    List shown = new ArrayList();
    StringBuilder sb = new StringBuilder();
    sb.append("📋 过去 ").append(days).append(" 天潜水名单 (共 ").append(total).append(" 人):\n");
    sb.append("━━━━━━\n");
    for (int i = 0; i < show; i++) {
        String w = (String) inactive.get(i);
        shown.add(w);
        sb.append(i + 1).append(". ").append(lookupName(w, groupId));
        Long lst = (Long) ls.get(w);
        if (lst != null) {
            long ago = (now - lst.longValue()) / 86400000L;
            sb.append("  ·  ").append(ago).append("天前");
        } else {
            Long fst = (Long) fs.get(w);
            long inGroup = fst != null ? (now - fst.longValue()) / 86400000L : 0;
            sb.append("  ·  从未发言 (在群 ").append(inGroup).append("天)");
        }
        sb.append('\n');
    }
    if (total > cap) sb.append("...（仅显示前 ").append(cap).append(" 个）\n");
    sb.append("━━━━━━\n");
    sb.append("批量踢: #踢潜水 1,3,5-8\n");
    sb.append("豁免某人: @TA 保护  ");
    if (newJoinSkip > 0) sb.append("\n(已跳过 ").append(newJoinSkip).append(" 个新成员)");

    putString("pending_kick_" + groupId, joinCsv(shown));
    putString("pending_kick_ts_" + groupId, String.valueOf(now));
    sendText(groupId, sb.toString());
}

void doKickInactive(String groupId, String sender, String content) {
    if (!isAdminInGroup(groupId, sender)) return;

    String[] tk = content.split("[\\s\\u2005\\u00A0]+", 2);
    if (tk.length < 2 || tk[1].trim().isEmpty()) {
        sendText(groupId, "用法: #踢潜水 1,3,5-8\n先发 #潜水 N 拿待踢名单");
        return;
    }

    List pending = parseCsv(getString("pending_kick_" + groupId, ""));
    if (pending.isEmpty()) { sendText(groupId, "❌ 没有待踢名单，先发 #潜水 N"); return; }

    String tsStr = getString("pending_kick_ts_" + groupId, "0");
    long ts = 0;
    try { ts = Long.parseLong(tsStr); } catch (Exception e) {}
    if (ts > 0 && System.currentTimeMillis() - ts > 1800000L) {
        sendText(groupId, "❌ 待踢名单已过期 (>30分钟), 请重新 #潜水 N");
        return;
    }

    List indexes = parseIndexRange(tk[1], pending.size());
    if (indexes.isEmpty()) { sendText(groupId, "❌ 编号无效"); return; }

    if (botPrivState(groupId).equals("none")) {
        warnNoBotPriv(groupId);
        return;
    }

    String botWxid = getLoginWxid();
    int okCnt = 0, skipCnt = 0, failCnt = 0;
    StringBuilder sb = new StringBuilder();
    sb.append("🦶 批量踢潜水 (共 ").append(indexes.size()).append(" 个):\n");
    sb.append("━━━━━━\n");

    boolean abortedByPriv = false;
    for (int i = 0; i < indexes.size(); i++) {
        int idx = ((Integer) indexes.get(i)).intValue();
        String w = (String) pending.get(idx - 1);
        String name = lookupName(w, groupId);

        // 二次防护
        if (w.equals(botWxid) || isAdminInGroup(groupId, w) || isOwner(w) || isNativeOwner(groupId, w) || getProtected(groupId).contains(w)) {
            sb.append("#").append(idx).append(" ").append(name).append(" ⏭ 跳过(受保护)\n");
            skipCnt++; continue;
        }

        if (tryKickSilent(groupId, w)) {
            sb.append("#").append(idx).append(" ").append(name).append(" ✅\n");
            okCnt++;
        } else {
            sb.append("#").append(idx).append(" ").append(name).append(" ❌\n");
            failCnt++;
            if (botPrivState(groupId).equals("none")) {
                sb.append("⚠️ 检测到机器人无群管权限, 中止剩余 ").append(indexes.size() - i - 1).append(" 个\n");
                abortedByPriv = true;
                break;
            }
        }
    }
    sb.append("━━━━━━\n");
    sb.append("✅ ").append(okCnt).append(" 成功 / ⏭ ").append(skipCnt).append(" 跳过 / ❌ ").append(failCnt).append(" 失败");
    if (abortedByPriv) sb.append("\n请群主把机器人提为群管理员, 然后 #群权限自检 重置缓存");
    sendText(groupId, sb.toString());

    putString("pending_kick_" + groupId, "");
    putString("pending_kick_ts_" + groupId, "");
}

void doCheckBotPriv(String groupId, String sender) {
    if (!isAdminInGroup(groupId, sender)) return;
    String old = botPrivState(groupId);
    setBotPriv(groupId, "unknown");
    sendText(groupId, "🔄 已清除本群权限缓存 (原状态: " + old + ")\n下次执行踢人时会重新检测");
}

void doProtect(String groupId, String sender, String target) {
    if (!isAdminInGroup(groupId, sender)) return;
    List p = getProtected(groupId);
    if (!p.contains(target)) p.add(target);
    setProtected(groupId, p);
    sendText(groupId, "🛡 已加入保护名单: " + lookupName(target, groupId) + "\n(潜水扫描时不会出现)");
}

void doUnprotect(String groupId, String sender, String target) {
    if (!isAdminInGroup(groupId, sender)) return;
    List p = getProtected(groupId);
    if (p.remove(target)) {
        setProtected(groupId, p);
        sendText(groupId, "✅ 已取消保护: " + lookupName(target, groupId));
    } else {
        sendText(groupId, "❌ 该用户不在保护名单");
    }
}

void showProtected(String groupId) {
    List p = getProtected(groupId);
    if (p.isEmpty()) { sendText(groupId, "📋 本群保护名单为空"); return; }
    StringBuilder sb = new StringBuilder();
    sb.append("🛡 保护名单 (").append(p.size()).append("):\n");
    for (int i = 0; i < p.size(); i++) sb.append("· ").append(lookupName((String) p.get(i), groupId)).append("\n");
    sendText(groupId, sb.toString());
}

// 启用群名单 (按群启用,默认全部关)
// v1.16.2: [性能/C-PERF-01,03] 启用群集合内存缓存 — 消除 isGroupEnabled 每条消息读 FUSE config.prop (~26ms/条)。
//   ENABLED_CACHE 存 normGroupId 后的群 id; TTL 60s 兜底外部 adb 改 config.prop; enable/disable 变更后立即刷新保即时生效。
//   加载失败保留旧缓存 (降级安全); isGroupEnabled 异常时回退直接读 getString (宁可慢不可错: 这是插件在本群是否生效的总闸门)。
java.util.HashSet ENABLED_CACHE = null;   // 已启用群 id 集合 (normGroupId 后); null=尚未加载
long ENABLED_CACHE_TS = 0;                 // 上次加载毫秒
long ENABLED_TTL_MS   = 60000;             // 缓存 TTL: 外部改 config.prop 最多此时长后生效

// 从 config.prop 读 enabled_groups, 全量 norm 后原子替换 ENABLED_CACHE。失败保留旧缓存只 log。
void loadEnabledCache() {
    try {
        List l = parseCsv(getString("enabled_groups", ""));
        java.util.HashSet ns = new java.util.HashSet();
        for (int i = 0; i < l.size(); i++) {
            String g = normGroupId((String) l.get(i));
            if (!g.isEmpty()) ns.add(g);
        }
        ENABLED_CACHE = ns;            // 原子替换
        ENABLED_CACHE_TS = System.currentTimeMillis();
    } catch (Throwable t) {
        log("loadEnabledCache failed (keep old cache): " + t);
    }
}

List getEnabledGroups() { return parseCsv(getString("enabled_groups", "")); }
boolean isGroupEnabled(String groupId) {
    String g = normGroupId(groupId);
    if (g.isEmpty()) return false;
    try {
        long now = System.currentTimeMillis();
        if (ENABLED_CACHE == null || (now - ENABLED_CACHE_TS) > ENABLED_TTL_MS) {
            loadEnabledCache();
        }
        if (ENABLED_CACHE != null) return ENABLED_CACHE.contains(g);
    } catch (Throwable t) {
        log("isGroupEnabled cache error, fallback to direct read: " + t);
    }
    // 回退: 缓存出错时直接读 (保证正确性, 与原逻辑完全一致)
    List l = getEnabledGroups();
    for (int i = 0; i < l.size(); i++) {
        if (g.equals(normGroupId((String) l.get(i)))) return true;
    }
    return false;
}
void enableGroup(String groupId) {
    String g = normGroupId(groupId);
    if (g.isEmpty()) return;
    List l = getEnabledGroups();
    for (int i = 0; i < l.size(); i++) {
        if (g.equals(normGroupId((String) l.get(i)))) return;
    }
    l.add(g);
    putString("enabled_groups", joinCsv(l));
    loadEnabledCache();   // 变更即时失效: 启用后下一条消息立即生效
}
// 包装: 启用 + 建立潜水基线, 返回新增的 first_seen 人数
int enableGroupWithBaseline(String groupId) {
    enableGroup(groupId);
    return initFirstSeenBaseline(groupId);
}
void disableGroup(String groupId) {
    String g = normGroupId(groupId);
    List l = getEnabledGroups();
    boolean changed = false;
    for (int i = l.size() - 1; i >= 0; i--) {
        if (g.equals(normGroupId((String) l.get(i)))) { l.remove(i); changed = true; }
    }
    if (changed) putString("enabled_groups", joinCsv(l));
    loadEnabledCache();   // 变更即时失效: 停用后下一条消息立即不再管该群
}

// 群主 (owners) — 顶层权限,机器人账号自动是
List getOwners() { return parseCsv(getString("super_owners", "")); }
boolean isOwner(String wxid) {
    if (wxid == null || wxid.isEmpty()) return false;
    if (wxid.equals(getLoginWxid())) return true;   // 机器人账号永远是群主
    return getOwners().contains(wxid);
}

// 管理员 (admins) — 群主自动是;  额外明确加进来的也是
List getGlobalAdminsLegacy() { return parseCsv(getString("super_admins", "")); }
List getAdmins(String groupId) { return parseCsv(getString("admins_" + normGroupId(groupId), "")); }
void setAdmins(String groupId, List admins) { putString("admins_" + normGroupId(groupId), joinCsv(admins)); }
boolean isAdminInGroup(String groupId, String wxid) {
    if (isOwner(wxid)) return true;
    if (wxid == null || wxid.isEmpty()) return false;
    return getAdmins(groupId).contains(wxid);
}

// ===== v1.17.0 管理员时效 (admin_exp_<gid>) =====
// 存储: CSV "wxid:到期毫秒;wxid:到期毫秒"。只存有时效的; 不在表里的管理员 = 永久。
// admins_<gid> 格式完全不动 → 老数据无 exp 记录 = 永久, 向后兼容。
// 全部 helper try/catch(Throwable), 异常退化为 0(永久), 绝不抛回微信。
java.util.Map _parseAdminExp(String groupId) {
    java.util.HashMap m = new java.util.HashMap();
    try {
        String raw = getString("admin_exp_" + normGroupId(groupId), "");
        if (raw == null || raw.isEmpty()) return m;
        String[] parts = raw.split(";");
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i].trim();
            if (p.isEmpty()) continue;
            int idx = p.lastIndexOf(":");
            if (idx <= 0 || idx >= p.length() - 1) continue;
            String w = p.substring(0, idx).trim();
            String ms = p.substring(idx + 1).trim();
            if (w.isEmpty()) continue;
            try { m.put(w, new Long(Long.parseLong(ms))); } catch (Throwable t) {}
        }
    } catch (Throwable t) {}
    return m;
}
void _saveAdminExp(String groupId, java.util.Map m) {
    try {
        StringBuilder sb = new StringBuilder();
        java.util.Iterator it = m.keySet().iterator();
        boolean first = true;
        while (it.hasNext()) {
            String w = (String) it.next();
            Long v = (Long) m.get(w);
            if (w == null || w.isEmpty() || v == null) continue;
            if (!first) sb.append(";");
            sb.append(w).append(":").append(v.longValue());
            first = false;
        }
        putString("admin_exp_" + normGroupId(groupId), sb.toString());
    } catch (Throwable t) {}
}
// 返回到期毫秒; 0 = 永久/无记录。
long getAdminExpiry(String groupId, String wxid) {
    try {
        if (wxid == null || wxid.isEmpty()) return 0L;
        Long v = (Long) _parseAdminExp(groupId).get(wxid);
        return v == null ? 0L : v.longValue();
    } catch (Throwable t) { return 0L; }
}
void setAdminExpiry(String groupId, String wxid, long millis) {
    try {
        if (wxid == null || wxid.isEmpty()) return;
        java.util.Map m = _parseAdminExp(groupId);
        m.put(wxid, new Long(millis));
        _saveAdminExp(groupId, m);
    } catch (Throwable t) {}
}
void clearAdminExpiry(String groupId, String wxid) {
    try {
        if (wxid == null || wxid.isEmpty()) return;
        java.util.Map m = _parseAdminExp(groupId);
        if (m.remove(wxid) != null) _saveAdminExp(groupId, m);
    } catch (Throwable t) {}
}
// 显示标签: "永久" 或 "有效期至 yyyy-MM-dd"。
String adminExpiryLabel(String groupId, String wxid) {
    try {
        long exp = getAdminExpiry(groupId, wxid);
        if (exp <= 0L) return "永久";
        String d = new java.text.SimpleDateFormat("yyyy-MM-dd").format(new java.util.Date(exp));
        return "有效期至 " + d;
    } catch (Throwable t) { return "永久"; }
}
// 惰性到期清除: 遍历 getAdmins, 对有 exp 且已过期者 → 从 admins 移除 + clearAdminExpiry, 变更则落盘。
// 静默 (不发群消息)。★ 只在管理相关函数 (isAuthorized/showAdmins/showAdminListDialog) 入口调用, 绝不进每条消息热路径 (C-PERF-01)。
void purgeExpiredAdmins(String groupId) {
    try {
        java.util.Map m = _parseAdminExp(groupId);
        if (m.isEmpty()) return;   // 无任何时效记录 → 无可清除 (老数据/纯永久群直接返回, 零开销)
        long now = System.currentTimeMillis();
        List admins = getAdmins(groupId);
        boolean changed = false;
        for (int i = admins.size() - 1; i >= 0; i--) {
            String w = (String) admins.get(i);
            Long v = (Long) m.get(w);
            if (v != null && now >= v.longValue()) {
                admins.remove(i);
                clearAdminExpiry(groupId, w);
                changed = true;
            }
        }
        if (changed) setAdmins(groupId, admins);
    } catch (Throwable t) {}
}

// v1.12: 严格模式收紧到仅群主, 否则等价本群管理员
boolean isAuthorized(String groupId, String wxid) {
    // v1.17.0 惰性到期清除: 此函数仅在命令/@动作/引用动作路径被调 (非每条普通消息),
    // 且 purgeExpiredAdmins 对无时效记录的群直接 return (零开销), 不违反 C-PERF-01。
    try { purgeExpiredAdmins(groupId); } catch (Throwable t) {}
    if (isStrictMode()) return isOwner(wxid);
    return isAdminInGroup(groupId, wxid);
}

// 权限判定: operator 是否能对 target 执行管理动作(踢/拉黑/警告等)
// 群主: 能对管理员和普通成员动手, 不能对其他群主
// 管理员: 只能对普通成员动手
// 普通: 啥也不能
boolean canActOn(String groupId, String operator, String target) {
    if (target == null || target.isEmpty()) return false;
    if (target.equals(operator)) return false;             // 不能动自己
    if (target.equals(getLoginWxid())) return false;       // 不能动机器人
    // v1.13: 微信原生群主/管理员保护 (登记的任何人都不能动 — 即使插件群主也不行)
    // v1.13.1: groupId 由调用方显式传入
    if (groupId != null && groupId.indexOf("@chatroom") >= 0) {
        if (isNativeOwner(groupId, target)) return false;
    }
    if (isOwner(operator)) {
        if (isOwner(target)) return false;                 // 插件群主互不能动
        return true;
    }
    if (isAdminInGroup(groupId, operator)) {
        if (isAdminInGroup(groupId, target)) return false;                 // 插件管理不能动管理/群主
        return true;
    }
    return false;
}

int getWarn(String groupId, String wxid) {
    try { return Integer.parseInt(getString("wn_" + groupId + "_" + wxid, "0")); }
    catch (Exception e) { return 0; }
}
void setWarn(String groupId, String wxid, int n) {
    if (n <= 0) putString("wn_" + groupId + "_" + wxid, "0");
    else putString("wn_" + groupId + "_" + wxid, "" + n);
}

// ---- W1: 每群警告自动踢上限 (默认 WARN_AUTO_KICK=10, 每群 wk_<group> 可覆盖) ----
// 有自定义值则用之 (夹取 1~99); 否则返回默认 10。所有显示与踢判定都改用本 getter。
int getWarnKick(String groupId) {
    if (groupId == null) return WARN_AUTO_KICK;
    String v = getString("wk_" + groupId, "");
    if (v == null || v.isEmpty()) return WARN_AUTO_KICK;
    try {
        int n = Integer.parseInt(v.trim());
        if (n < 1) n = 1; if (n > 99) n = 99;
        return n;
    } catch (Exception e) { return WARN_AUTO_KICK; }
}
// 写本群上限 (夹取 1~99)。返回夹取后的实际值。
int setWarnKick(String groupId, int n) {
    if (n < 1) n = 1; if (n > 99) n = 99;
    putString("wk_" + groupId, String.valueOf(n));
    return n;
}

List getWarnList(String groupId) {
    // 所有 wn_<groupId>_* 不便枚举(没有 getKeys),用单独 list 维护
    return parseCsv(getString("wl_" + groupId, ""));
}
void addWarnList(String groupId, String wxid) {
    List l = getWarnList(groupId);
    if (!l.contains(wxid)) {
        l.add(wxid);
        putString("wl_" + groupId, joinCsv(l));
    }
}
void delWarnList(String groupId, String wxid) {
    List l = getWarnList(groupId);
    if (l.remove(wxid)) putString("wl_" + groupId, joinCsv(l));
}

// 取可读名: 群名片 > 备注 > 昵称 > getFriendName > "未知"
// 全程不显示 wxid 给用户看
String lookupName(String wxid, String groupId) {
    if (wxid == null || wxid.isEmpty()) return "未知";
    if (groupId != null && !groupId.isEmpty()) {
        try {
            String n = getFriendDisplayName(wxid, groupId);
            if (n != null && !n.isEmpty()) return n;
        } catch (Exception e) {}
    }
    try {
        String n = getFriendRemarkName(wxid);
        if (n != null && !n.isEmpty()) return n;
    } catch (Exception e) {}
    try {
        String n = getFriendNickName(wxid);
        if (n != null && !n.isEmpty()) return n;
    } catch (Exception e) {}
    try {
        String n = getFriendName(wxid);
        if (n != null && !n.isEmpty()) return n;
    } catch (Exception e) {}
    return "未知";
}

// ========== 生命周期 ==========
void onLoad() {
    // [C-PLUGIN-05] 日志块: getLoginWxid 安全取值 (冷启动登录态/UIN栈未就绪会抛 NoResetUinStack),
    // 这里只是 cosmetic 日志, 取不到用占位串, 且整块 try/catch, 绝不阻断后续关键初始化。
    try {
        List enabled = getEnabledGroups();
        String _owner;
        try { _owner = getLoginWxid(); } catch (Throwable t) { _owner = "(login pending)"; }
        if (_owner == null) _owner = "(login pending)";
        log("GroupAdmin v1.19.2 loading, owner=" + _owner + ", enabled groups=" + enabled.size());
    } catch (Throwable t) {
        try { log("GroupAdmin v1.17.0 onLoad log block err: " + t); } catch (Throwable t2) {}
    }

    // [C-PLUGIN-05] 延迟基线调度块: 本身不依赖 getLoginWxid; 单独 try/catch 互不连坐。
    // 延迟 5 秒, 等微信进程把通讯录数据加载好, 再 (一次性迁移 CSV→DB) + 扫已启用但没基线的群
    try {
        delay(5000, new Runnable() {
            public void run() {
                // T2′: 一次性迁移旧 lsg_/fsg_ CSV → SQLite (migrated 标志守卫只迁一次, 不阻塞加载)。
                try { l3MigrateCsvToDb(); } catch (Throwable t) { try { log("l3 migrate err: " + t); } catch (Throwable t2) {} }

                List groups = getEnabledGroups();
                int touched = 0;
                int totalAdded = 0;
                for (int i = 0; i < groups.size(); i++) {
                    String g = (String) groups.get(i);
                    if (l3CountFirstSeen(g) > 0) continue;   // T2′: 已有基线 (DB 查), 跳过
                    int added = initFirstSeenBaseline(g);
                    if (added > 0) { touched++; totalAdded += added; }
                }
                log("GroupAdmin v1.19.2 baseline check: 已启用群 " + groups.size() + ", 新建基线群 " + touched + ", 新增 first_seen " + totalAdded + " 人");
                // _probeGroupInfoAPI();
            }
        });
    } catch (Throwable t) {
        try { log("l3 baseline schedule fail: " + t); } catch (Throwable t2) {}
    }

    // [C-PLUGIN-05] flush 循环启动块: 关键! 单独 try/catch, 不被前面任何块连累。
    // T2/T2′: 启动 L3 后台 flush 循环 (内存脏增量 → SQLite UPSERT + 顺带 perf 落盘)。防重复启动。
    try {
        if (!L3_flushStarted) {
            L3_flushStarted = true;
            try {
                delay(L3_FLUSH_MS, new Runnable() { public void run() { l3FlushLoop(); } });
            } catch (Throwable t) {
                L3_flushStarted = false;
                try { log("l3 flush loop start fail: " + t); } catch (Throwable t2) {}
            }
        }
    } catch (Throwable t) {
        try { log("l3 flush loop start block fail: " + t); } catch (Throwable t2) {}
    }
}
void onUnload() {
    // T2/T2′: 卸载/重载前把内存脏增量同步 UPSERT 进 DB, 防丢 (RISK-4)。
    try { l3Flush(); } catch (Throwable t) { try { log("onUnload l3Flush fail: " + t); } catch (Throwable t2) {} }
    // T1: 把当前未满窗口的埋点聚合落盘, 避免样本丢失 (l3Flush 已会触发一次, 这里兜底)。
    try { perfFlush(); } catch (Throwable t) {}
    // T2′: close DB 单例并置 null, 重载后 onLoad 重新懒开 (不搞 epoch)。
    try { synchronized (FLUSH_LOCK) { if (L3_DB != null) { L3_DB.close(); L3_DB = null; } } }
    catch (Throwable t) { try { log("onUnload db close fail: " + t); } catch (Throwable t2) {} }
    L3_flushStarted = false;
}

// ==== WAuxiliary 主面板插件列表点"设置"时触发 (v1.10) ====
// 弹一个 Dialog: 全局状态摘要 + 完整命令清单, 不需要进微信群也能查看
int _gaC(String s) { return android.graphics.Color.parseColor(s); }
android.graphics.drawable.GradientDrawable _gaRound(String fill, int radius) {
    android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
    g.setColor(_gaC(fill));
    g.setCornerRadius(radius);
    return g;
}

void openSettings() {
    Activity ctx = getTopActivity();
    if (ctx == null) { toast("无法获取 Activity"); return; }

    int enabledCnt = parseCsv(getString("enabled_groups", "")).size();
    int ownerCnt   = getOwners().size();
    int adminCnt   = getGlobalAdminsLegacy().size();

    StringBuilder sb = new StringBuilder();
    sb.append("GroupAdmin v1.14.0\n");
    sb.append("══════════════════════\n\n");
    sb.append("📊 全局状态\n");
    sb.append("• 已启用群数: ").append(enabledCnt).append("\n");
    sb.append("• 群主 (super_owners): ").append(ownerCnt).append(" 人\n");
    sb.append("• 旧全局管理员候选 (super_admins): ").append(adminCnt).append(" 人\n");
    sb.append("• 严格模式: ").append(isStrictMode() ? "✅ 开启 (仅群主)" : "🔓 关闭 (管理员+)").append("\n");
    sb.append("• 全局黑名单: ").append(getGlobalBlacklist().size()).append(" 人\n");
    sb.append("• 全局白名单: ").append(getGlobalWhitelist().size()).append(" 人\n");
    sb.append("• 机器人 wxid: ").append(getLoginWxid()).append("\n\n");
    sb.append("📋 命令清单 (v1.9 起统一管理员+, 普通成员静默忽略)\n");
    sb.append("──────────────────────\n\n");
    sb.append("【群级开关 — 仅群主】\n");
    sb.append("  开启群管 / 关闭群管 / 群管状态\n\n");
    sb.append("【移出群聊 / 黑名单】\n");
    sb.append("  @TA 踢\n");
    sb.append("  添加黑名单 @TA [@TA...]  加黑+踢 (可@多人)\n");
    sb.append("  去除黑名单 @TA [@TA...]  解除黑名单\n");
    sb.append("  去除黑名单【N】[【M】...] 按 \"黑名单\" 列表序号解除\n\n");
    sb.append("【潜水管理】\n");
    sb.append("  #潜水 7        列出过去 7 天没说话的人\n");
    sb.append("  #踢潜水 1,3,5-8  按编号批量踢\n");
    sb.append("  @TA 保护 / @TA 取消保护\n\n");
    sb.append("【警告系统 — 默认累计 ").append(WARN_AUTO_KICK).append(" 次自动踢, 各群可调】\n");
    sb.append("  @TA 警告 / @TA 警告-1 / @TA 清零\n");
    sb.append("  警告上限 N (本群设上限) / 引用回复 + 动作词亦可\n\n");
    sb.append("【管理员管理 — 仅群主】\n");
    sb.append("  @TA 管理员 / @TA 移除管理\n");
    sb.append("  @TA 群主 / @TA 移除群主\n");
    sb.append("  @TA 微信群主 / @TA 移除微信群主\n\n");
    sb.append("【查询】\n");
    sb.append("  群管帮助 / 黑名单 / 管理员\n");
    sb.append("  警告名单 / 保护名单\n\n");
    sb.append("【权限自检】\n");
    sb.append("  #群权限自检 — 重置缓存, 下次踢人重新检测\n\n");
    sb.append("📚 完整文档见插件目录 README.md");

    int pad = (int)(16 * ctx.getResources().getDisplayMetrics().density);

    TextView tv = new TextView(ctx);
    tv.setText(sb.toString());
    tv.setTextColor(_gaC("#E8E9EC"));
    tv.setTextSize(13);
    tv.setPadding(pad, pad, pad, pad);
    tv.setLineSpacing(0, 1.2f);
    tv.setBackground(_gaRound("#1A1D24", 0));

    ScrollView sv = new ScrollView(ctx);
    sv.addView(tv);
    sv.setBackground(_gaRound("#0F1115", 0));

    new AlertDialog.Builder(ctx)
        .setView(sv)
        .setPositiveButton("好的", null)
        .show();
}

// ========== 消息处理 ==========
// 性能埋点包装层 (T1 / C-PERF-04): 测本次 onHandleMsg 总耗时, 累加进窗口计数, 到阈值落盘。
// 埋点全程 try/catch, 任何异常都不影响消息处理 (热路径优先)。
void onHandleMsg(Object msg) {
    long _perfStart = 0;
    boolean _perfOn = false;
    try { _perfStart = System.nanoTime(); _perfOn = true; } catch (Throwable t) { _perfOn = false; }
    try {
        onHandleMsgBody(msg);
    } finally {
        if (_perfOn) {
            try {
                long dur = System.nanoTime() - _perfStart;
                PERF_N++;
                PERF_OHM_SUM_NS += dur;
                if (dur > PERF_OHM_MAX_NS) PERF_OHM_MAX_NS = dur;
                // T2: 热路径不再做文件 IO (C-PERF-03)。perf 落盘改由后台 l3Flush 循环驱动。
                // 兜底: 窗口异常膨胀 (后台循环长时间没跑) 时才在热路径 flush 一次, 防内存计数溢出/数据失真。
                if (PERF_N >= PERF_FLUSH_EVERY * 50L) perfFlush();
            } catch (Throwable t) { /* 埋点出错绝不影响消息 */ }
        }
    }
}

void onHandleMsgBody(Object msg) {
    // v1.16.1 分段计时: 各段边界时间戳 (ns) 与开关。捕获/累加全 try/catch 包裹, 失败即跳过计时, 绝不影响消息处理。
    // 仅"走到普通消息主出口"(下方 isTextMsg 无@无引用 return) 的样本才累加, 命令路径不细分。
    boolean _segOn = false;
    long _segT0 = 0; long _segT1 = 0; long _segT2 = 0; long _segT3 = 0; long _segT4 = 0;
    try { _segT0 = System.nanoTime(); _segOn = true; } catch (Throwable _segErr) { _segOn = false; }

    // 群聊闸门最廉价, 先判 (非群聊一律不处理)。
    if (!msg.isChatroom()) return;

    // W2 / WRISK-1: 普通文本走 isText() 通过的零额外成本路径 (与原行为一致, 不调 getQuoteMsg)。
    // 引用回复是 appmsg (isText()=false, getType()=822083633), getContent() 是整段 XML —— 不过 isText(),
    // 此时回退看 getContent() 是否有可读文本; 仅当非文本时才多读一次 getContent() 判空; 普通文本消息在第一分支即通过, 不付任何额外成本。
    // 注意: 这里绝不调用 getQuoteMsg() (那只在下方动作分发的 !isText 子分支里才调, 见 C-PERF-01)。
    boolean isTextMsg = msg.isText();
    String content;
    if (isTextMsg) {
        content = msg.getContent();
    } else {
        content = msg.getContent();
        if (content == null || content.trim().isEmpty()) {
            // v1.18.0 活动采集前置 (SPEC §6.10): 非文本且无可读文本 (图片/表情/语音/红包/文件等)
            // 在此早退点之前先把这条消息记为一次发言活动, 否则只发非文本消息的人 last_speak 永远为空,
            // 被 #潜水 N 误判潜水。热路径红线 (C-PERF-01): 只调 getTalker/getSendTalker/isGroupEnabled(缓存)/
            // recordSpeak(O(1) 内存写), 绝不调 getQuoteMsg/解析 XML; 整段 try/catch(Throwable) 包裹,
            // 异常只跳过采集、绝不抛入消息处理。L3_DEGRADED 期间 recordSpeak 自身照旧丢弃 (§7)。
            // 与下方 ~2188 行那次 recordSpeak 互斥: 空内容非文本在此早退, 文本/有内容 appmsg 走主路径, 不重复记。
            try {
                String _aGid = msg.getTalker();
                String _aSnd = msg.getSendTalker();
                if (_aSnd != null && !_aSnd.isEmpty() && isGroupEnabled(_aGid)) {
                    recordSpeak(_aGid, _aSnd);
                }
            } catch (Throwable _aErr) {}
            return;   // 非文本且无可读文本 → 采集后不再处理
        }
    }
    if (content == null) return;
    content = content.trim();
    if (content.isEmpty()) return;

    String groupId = msg.getTalker();
    String sender  = msg.getSendTalker();
    List   atList  = msg.getAtUserList();

    // v1.16.1: seg1 边界 (入口解析/反射 getXxx 完成)。
    if (_segOn) { try { _segT1 = System.nanoTime(); } catch (Throwable _segErr) { _segOn = false; } }

    // 明文消息内容不写入 plugin.log

    // v1.14.0: 发送按钮拦截失效时，用机器人自己发出的设置命令兜底触发非敏感探测。
    if ((content.equals("群管设置") || content.equals("群管配置") || content.equals("#群管设置") || content.equals("#群管配置")) && sender != null && sender.equals(getLoginWxid())) {
        _logNativeRoleProbe(groupId);
        return;
    }

    // ---- 启用/关闭/状态(不受 enabled 限制,owner 可随时操作) ----
    if (content.equals("开启群管") || content.equals("启用群管")) {
        if (!isOwner(sender)) return;
        if (isGroupEnabled(groupId)) {
            sendText(groupId, "ℹ️ 本群 GroupAdmin 已是启用状态");
        } else {
            int baseline = enableGroupWithBaseline(groupId);
            String tip = baseline > 0
                ? "\n📊 已建立潜水基线: " + baseline + " 人 (新成员加入会自动入基线)"
                : "\n📊 潜水基线已存在,保持不变";
            sendText(groupId, "✅ 本群 GroupAdmin 已启用 (发 '群管帮助' 看命令)" + tip);
        }
        return;
    }
    if (content.equals("关闭群管") || content.equals("禁用群管")) {
        if (!isOwner(sender)) return;
        if (!isGroupEnabled(groupId)) {
            sendText(groupId, "ℹ️ 本群 GroupAdmin 本来就没启用");
        } else {
            disableGroup(groupId);
            sendText(groupId, "🔕 本群 GroupAdmin 已停用 (黑名单/警告数据保留)");
        }
        return;
    }
    if (content.equals("群管状态")) {
        _logNativeRoleProbe(groupId);
        logGroupEnabledDebug(groupId);
        if (!isAdminInGroup(groupId, sender)) return;
        if (isGroupEnabled(groupId)) {
            sendText(groupId, "🟢 本群 GroupAdmin 已启用");
        } else {
            sendText(groupId, "🔴 本群 GroupAdmin 未启用\n群主发 '开启群管' 激活");
        }
        return;
    }
    if (content.equals("群管群列表")) {
        if (!isOwner(sender)) return;
        logEnabledGroupNames();
        sendText(groupId, "✅ 已把群列表写入 plugin.log");
        return;
    }

    // ---- 没启用就静默忽略其他所有命令 ----
    if (!isGroupEnabled(groupId)) return;

    // v1.16.1: seg2 边界 (群管设置探测 + 特殊命令 equals 链 + isGroupEnabled 闸门完成)。
    if (_segOn) { try { _segT2 = System.nanoTime(); } catch (Throwable _segErr) { _segOn = false; } }

    // ---- 潜水追踪: 每条群消息记录发言时间 + first_seen ----
    // T1 埋点: 单独测 recordSpeak 耗时 (T2 改造前 O(N) 同步 CSV 写的真实基线)。
    if (sender != null && !sender.isEmpty()) {
        long _rsStart = 0; boolean _rsOn = false;
        try { _rsStart = System.nanoTime(); _rsOn = true; } catch (Throwable t) { _rsOn = false; }
        try {
            recordSpeak(groupId, sender);
        } finally {
            if (_rsOn) {
                try {
                    long d = System.nanoTime() - _rsStart;
                    PERF_RS_N++;
                    PERF_RS_SUM_NS += d;
                    if (d > PERF_RS_MAX_NS) PERF_RS_MAX_NS = d;
                } catch (Throwable t) {}
            }
        }
    }

    // v1.16.1: seg3 边界 (recordSpeak 内存入队完成; 与既有 PERF_RS_* 同区间, 作对照)。
    if (_segOn) { try { _segT3 = System.nanoTime(); } catch (Throwable _segErr) { _segOn = false; } }

    // ---- 群管命令统一前置: 非管理员一律静默忽略 ----
    // (查询 + 潜水 + 自检等都要管理员权限. 普通人发了不响应也不报错, 避免被探测)
    boolean isCmd = content.equals("群管帮助") || content.equals("帮助 群管") ||
                    content.equals("黑名单") || content.equals("管理员") ||
                    content.equals("警告名单") || content.equals("保护名单") ||
                    content.equals("白名单") || content.equals("清除警告名单") ||
                    content.equals("全局黑名单") || content.equals("全局白名单") ||
                    content.startsWith("严格模式") || content.startsWith("警告上限") ||
                    content.startsWith("#潜水") || content.equals("潜水") ||
                    content.startsWith("#踢潜水") || content.startsWith("踢潜水") ||
                    content.equals("#群权限自检") || content.equals("群权限自检") ||
                    content.startsWith("添加黑名单") || content.startsWith("去除黑名单") ||
                    content.startsWith("addsb ") || content.startsWith("delbk ") ||
                    content.startsWith("addwt ") || content.startsWith("delwt ") ||
                    content.startsWith("addgb ") || content.startsWith("delgb ") ||
                    content.startsWith("addgw ") || content.startsWith("delgw ");
    // T1 埋点: 粗分类计数。normal = 到达 L3 采集路径的普通聊天消息; cmd = 命令消息(文本命令或后续 @ 动作)。
    // 二者互斥: 文本命令归 cmd, 其余先记 normal, 若后面命中 @ 动作再回拨到 cmd。
    boolean _perfCmd = isCmd;
    try { if (isCmd) PERF_CMD_N++; else PERF_NORMAL_N++; } catch (Throwable t) {}
    if (isCmd && !isAuthorized(groupId, sender)) {
        if (content.equals("管理员")) log("admin-query auth: group=" + safeGroupDebug(groupId) + ", sender=" + safeGroupDebug(sender) + ", owner=" + isOwner(sender) + ", groupAdmin=" + isAdminInGroup(groupId, sender) + ", strict=" + isStrictMode());
        return;
    }

    // v1.16.1: seg4 边界 (isCmd 长链拼装 + isAuthorized 闸门完成; 普通消息 isCmd=false 跳过闸门)。
    if (_segOn) { try { _segT4 = System.nanoTime(); } catch (Throwable _segErr) { _segOn = false; } }

    // ---- 查询命令 ----
    if (content.equals("群管帮助") || content.equals("帮助 群管")) { showHelp(groupId); return; }
    if (content.equals("黑名单")) { showBlacklist(groupId); return; }
    if (content.equals("管理员")) { showAdmins(groupId); return; }
    if (content.equals("警告名单")) { showWarnList(groupId); return; }
    if (content.equals("保护名单")) { showProtected(groupId); return; }
    // v1.12 查询
    if (content.equals("白名单")) { showWhitelist(groupId); return; }
    if (content.equals("全局黑名单")) { showGlobalBlacklist(groupId); return; }
    if (content.equals("全局白名单")) { showGlobalWhitelist(groupId); return; }
    // v1.12 控制
    if (content.equals("清除警告名单")) { doWarnClearAll(groupId, sender); return; }
    if (content.startsWith("严格模式")) { doStrictMode(groupId, sender, content); return; }
    // W1: 文字命令设本群警告上限 (admin+, 已过 isAuthorized 闸门)
    if (content.startsWith("警告上限")) { doSetWarnKick(groupId, content); return; }

    // ---- 潜水管理 ----
    if (content.startsWith("#潜水") || content.equals("潜水")) {
        doShowInactive(groupId, content); return;
    }
    if (content.startsWith("#踢潜水") || content.startsWith("踢潜水")) {
        doKickInactive(groupId, sender, content); return;
    }
    if (content.equals("#群权限自检") || content.equals("群权限自检")) {
        doCheckBotPriv(groupId, sender); return;
    }

    // ---- 黑名单显式命令 (v1.16: 取代过简单的 @TA拉黑 / addsb / delbk, 防误触) ----
    // 添加黑名单 @XX [@YY...] : 对每个被 @ 的人执行加黑+踢 (复用 doBan)
    if (content.startsWith("添加黑名单")) {
        if (!isAuthorized(groupId, sender)) return;
        doAddBlacklistByAt(groupId, sender, atList);
        return;
    }
    // 去除黑名单 @XX [@YY...]  或  去除黑名单【N】[【M】...] : 从本群黑名单移除
    if (content.startsWith("去除黑名单")) {
        if (!isAuthorized(groupId, sender)) return;
        doRemoveBlacklist(groupId, sender, content, atList);
        return;
    }

    // ---- by-wxid 命令(纯文本,无 @) ----
    // v1.16: addsb / delbk 过于简单, 已停用 → 引导到显式命令 (仍仅管理员可见此提示)
    if (content.startsWith("addsb ") || content.startsWith("delbk ")) {
        if (!isAuthorized(groupId, sender)) return;
        sendText(groupId, "ℹ️ 该命令已停用\n请用: 添加黑名单 @XX / 去除黑名单 @XX / 去除黑名单【序号】");
        return;
    }
    // v1.12 by-wxid: 白名单 / 全局黑 / 全局白
    if (content.startsWith("addwt ")) {
        if (!isAuthorized(groupId, sender)) return;
        String w = content.substring(6).trim();
        if (!w.isEmpty()) doWhitelist(groupId, sender, w);
        return;
    }
    if (content.startsWith("delwt ")) {
        if (!isAuthorized(groupId, sender)) return;
        String w = content.substring(6).trim();
        if (!w.isEmpty()) doUnwhitelist(groupId, sender, w);
        return;
    }
    if (content.startsWith("addgb ")) {
        if (!isAuthorized(groupId, sender)) return;
        String w = content.substring(6).trim();
        if (!w.isEmpty()) doAddGlobalBlacklist(groupId, w);
        return;
    }
    if (content.startsWith("delgb ")) {
        if (!isAuthorized(groupId, sender)) return;
        String w = content.substring(6).trim();
        if (!w.isEmpty()) doDelGlobalBlacklist(groupId, w);
        return;
    }
    if (content.startsWith("addgw ")) {
        if (!isAuthorized(groupId, sender)) return;
        String w = content.substring(6).trim();
        if (!w.isEmpty()) doAddGlobalWhitelist(groupId, w);
        return;
    }
    if (content.startsWith("delgw ")) {
        if (!isAuthorized(groupId, sender)) return;
        String w = content.substring(6).trim();
        if (!w.isEmpty()) doDelGlobalWhitelist(groupId, w);
        return;
    }

    // ---- 动作命令: 目标来自 @ (原路径) 或 引用回复 (W2) ----
    // 取最后一个 token (动作关键字)。★ 用 regex 拆分,覆盖 ASCII space + U+2005 (微信 @ 用的全角空格)。
    // lastTok/isAction 服务 @ 路径; 无 @ 路径按 isText 二分 (见下方): 普通文本用 lastTok, 引用回复(appmsg)从 <title> 另取。
    // 注意: 普通文本聊天的 split + isActionTok 都是纯内存/字符串判定 (无 IO/getQuoteMsg), 末尾非动作词立即 return (C-PERF-01)。
    String[] tokens = content.split("[\\s\\u2005\\u00A0]+");
    String lastTok = tokens.length > 0 ? tokens[tokens.length - 1].trim() : "";

    // v1.17.0: 仅对"管理员"动作识别尾随天数 (`@TA 管理员 7` → 动作="管理员" days=7)。
    // 纯字符串/内存判定, 仅在末尾命中"管理员"时才解析整数; 其它动作完全不变 (C-PERF-01: 不进普通消息热路径)。
    int actDays = 0;
    // v1.19.0: 识别 "@TA 警告 <理由>" → 动作="警告" 透传 warnReason (走带理由 doWarn, 静默豁免对象)。
    String warnReason = null;
    if (!isActionTok(lastTok) && tokens.length >= 2) {
        String prev = tokens[tokens.length - 2].trim();
        if (prev.equals("管理员") && _isPosIntTok(lastTok)) {
            try { actDays = Integer.parseInt(lastTok); } catch (Throwable t) { actDays = 0; }
            if (actDays > 0) lastTok = "管理员";   // 改写动作词为"管理员", 带 days
        } else if (prev.equals("警告")) {
            warnReason = lastTok; lastTok = "警告";   // 改写动作词为"警告", 带 reason
        }
    }
    boolean isAction = isActionTok(lastTok);

    boolean hasAt = atList != null && atList.size() > 0;

    if (hasAt) {
        // ===== @ 路径 (原行为完全不变, 仅"管理员"动作多透传 days) =====
        if (!isAction) return;
        // T1 埋点: @ 动作是命令, 之前误记为 normal, 回拨到 cmd (二者互斥)。
        if (!_perfCmd) { try { PERF_NORMAL_N--; PERF_CMD_N++; _perfCmd = true; } catch (Throwable t) {} }

        if (!isAuthorized(groupId, sender)) return;       // 非管理员/群主静默 (严格模式仅群主)

        // v1.17.2: 踢人若 @所有人 则拒绝 (防误踢/无意义目标)。
        if (lastTok.equals("踢") || lastTok.equals("请")) {
            boolean atAll = false;
            for (int i = 0; i < atList.size(); i++) { if (_isAtAll((String) atList.get(i))) { atAll = true; break; } }
            if (atAll) { sendText(groupId, "❌ 不能对 @所有人 执行踢人"); return; }
        }

        // 取第一个非自己的 @ 作为 target
        String me = getLoginWxid();
        String target = null;
        for (int i = 0; i < atList.size(); i++) {
            String w = (String) atList.get(i);
            if (!w.equals(me)) { target = w; break; }
        }
        if (target == null) {
            sendText(groupId, "❌ 请 @ 你要操作的对象");
            return;
        }
        // v1.19.0: 带理由的警告直接走带 reason 的 doWarn (静默豁免对象); 无理由"警告"仍走 dispatchAction 原路径。
        if (lastTok.equals("警告") && warnReason != null) { doWarn(groupId, sender, target, warnReason); return; }
        dispatchAction(groupId, sender, target, lastTok, actDays);
        return;
    }

    // ===== 无 @ 分支: 按 isText 二分 (W2 修复) =====
    if (isTextMsg) {
        // 普通文本消息: 无 @ 无引用 → 末尾动作词无 target, 维持原行为直接 return。
        // (热路径零额外开销: 不调 getQuoteMsg、不解析 XML; recordSpeak 内存入队已在更早完成。)
        // v1.16.1: 这是普通消息(非命令)主出口 → 在此一次性累加 seg1..seg5 (全程 try/catch, 失败跳过)。
        if (_segOn) {
            try {
                long _segT5 = System.nanoTime();
                long _s1 = _segT1 - _segT0;
                long _s2 = _segT2 - _segT1;
                long _s3 = _segT3 - _segT2;
                long _s4 = _segT4 - _segT3;
                long _s5 = _segT5 - _segT4;
                PERF_SEG_N++;
                PERF_SEG1_SUM_NS += _s1; if (_s1 > PERF_SEG1_MAX_NS) PERF_SEG1_MAX_NS = _s1;
                PERF_SEG2_SUM_NS += _s2; if (_s2 > PERF_SEG2_MAX_NS) PERF_SEG2_MAX_NS = _s2;
                PERF_SEG3_SUM_NS += _s3; if (_s3 > PERF_SEG3_MAX_NS) PERF_SEG3_MAX_NS = _s3;
                PERF_SEG4_SUM_NS += _s4; if (_s4 > PERF_SEG4_MAX_NS) PERF_SEG4_MAX_NS = _s4;
                PERF_SEG5_SUM_NS += _s5; if (_s5 > PERF_SEG5_MAX_NS) PERF_SEG5_MAX_NS = _s5;
            } catch (Throwable _segErr) { /* 埋点出错绝不影响消息 */ }
        }
        return;
    }

    // 非文本 appmsg (含引用回复): getContent() 的末尾 token 是 XML 碎片 (不是动作词),
    // 真正的回复文本/动作词在 <title> 里, 被引用者 = getQuoteMsg().getSendTalker()。C-PERF-01: getQuoteMsg 只在此 (!isText) 调用。
    Object quote = null;
    try { quote = msg.getQuoteMsg(); } catch (Throwable t) { quote = null; }
    if (quote == null) return;   // 非引用的其它 appmsg (链接/文件/图片) → 忽略, 不进会误判的路径

    // 从 <title> 提取回复文本 → 取末尾 token 作动作词 (与 @ 路径同一套 split: 空白 / U+2005 / U+00A0)。
    String actionTok = "";
    int qDays = 0;   // v1.17.0: 引用路径"管理员"动作尾随天数
    try {
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("<title>(.*?)</title>", java.util.regex.Pattern.DOTALL)
            .matcher(content);
        if (m.find()) {
            String title = m.group(1);
            if (title != null) {
                String[] tt = title.trim().split("[\\s\\u2005\\u00A0]+");
                if (tt.length > 0) actionTok = tt[tt.length - 1].trim();
                // 仅"管理员"动作识别尾随天数 (`引用 管理员 7`); 其它动作不变。
                if (!isActionTok(actionTok) && tt.length >= 2) {
                    String prev = tt[tt.length - 2].trim();
                    if (prev.equals("管理员") && _isPosIntTok(actionTok)) {
                        try { qDays = Integer.parseInt(actionTok); } catch (Throwable t2) { qDays = 0; }
                        if (qDays > 0) actionTok = "管理员";
                    }
                }
            }
        }
    } catch (Throwable t) { actionTok = ""; }
    if (!isActionTok(actionTok)) return;   // 提取不到 title / 回复词不是动作 → 忽略, 不打扰

    String qTarget = null;
    try { qTarget = quote.getSendTalker(); } catch (Throwable t) { qTarget = null; }
    if (qTarget == null || qTarget.isEmpty()) return;

    // T1 埋点: 引用动作是命令, 回拨到 cmd。
    if (!_perfCmd) { try { PERF_NORMAL_N--; PERF_CMD_N++; _perfCmd = true; } catch (Throwable t) {} }

    // ★ 权限/保护与 @ 路径完全相同: 先 isAuthorized 静默闸门, 再走 dispatchAction (内含 ownerOnly + canActOn)。
    if (!isAuthorized(groupId, sender)) return;       // 非授权者引用发动作 → 静默拒绝 (防探测)
    dispatchAction(groupId, sender, qTarget, actionTok, qDays);
}

// v1.17.0: 判断 token 是否为纯正整数 (用于"管理员"尾随天数解析)。纯字符串判定, 无 IO。
boolean _isPosIntTok(String s) {
    if (s == null || s.isEmpty()) return false;
    for (int i = 0; i < s.length(); i++) {
        char c = s.charAt(i);
        if (c < '0' || c > '9') return false;
    }
    return true;
}

// v1.17.2: 判断 @列表项是否为 @所有人 (WeChat 系统标记 notify@all, 含 @all; 兼容字面"所有人")。纯字符串判定。
boolean _isAtAll(String w) {
    if (w == null) return false;
    String s = w.trim();
    if (s.isEmpty()) return false;
    return s.equals("notify@all") || s.indexOf("@all") >= 0 || s.indexOf("所有人") >= 0;
}

// 动作关键词集 (与 @ / 引用路径共用)。
boolean isActionTok(String lastTok) {
    return lastTok.equals("踢") || lastTok.equals("请") ||
           lastTok.equals("拉黑") || lastTok.equals("黑名单") ||
           lastTok.equals("警告") || lastTok.equals("清零") ||
           lastTok.equals("警告-1") || lastTok.equals("警告减1") ||
           lastTok.equals("警告详情") ||
           lastTok.equals("管理员") || lastTok.equals("移除管理") ||
           lastTok.equals("群主") || lastTok.equals("移除群主") ||
           lastTok.equals("保护") || lastTok.equals("取消保护") ||
           lastTok.equals("白名单") || lastTok.equals("移除白名单") ||
           lastTok.equals("微信群主") || lastTok.equals("移除微信群主");
}

// ownerOnly 关键词集。
boolean isOwnerOnlyTok(String lastTok) {
    return lastTok.equals("管理员") || lastTok.equals("移除管理") ||
           lastTok.equals("群主")   || lastTok.equals("移除群主") ||
           lastTok.equals("微信群主") || lastTok.equals("移除微信群主");
}

// 统一动作分发 (目标可来自 @ 或 引用回复)。
// 调用前提: caller 已通过 isAuthorized(groupId,sender) 闸门。
// 各 do* 内部仍各自做 canActOn(保护名单/原生群主/层级)校验 → 破坏性动作权限不被任何入口绕过。
void dispatchAction(String groupId, String sender, String target, String lastTok) {
    dispatchAction(groupId, sender, target, lastTok, 0);
}
// v1.17.0: days 仅对"管理员"动作有意义 (永久=0 / N天), 其它动作忽略。
void dispatchAction(String groupId, String sender, String target, String lastTok, int days) {
    // 群主专属命令: 与 @ 路径一致的 ownerOnly 校验。
    if (isOwnerOnlyTok(lastTok) && !isOwner(sender)) {
        sendText(groupId, "❌ 此命令仅群主可用");
        return;
    }
    if (lastTok.equals("踢") || lastTok.equals("请"))           { doKick(groupId, sender, target); }
    // v1.16: @TA 拉黑 / @TA 黑名单 过于简单易误触, 已改为引导到显式命令 (不再直接拉黑)
    else if (lastTok.equals("拉黑") || lastTok.equals("黑名单")) {
        sendText(groupId, "ℹ️ 拉黑改用显式命令\n请发: 添加黑名单 @对方\n去除: 去除黑名单 @对方 / 去除黑名单【序号】");
    }
    else if (lastTok.equals("警告"))                            { doWarn(groupId, sender, target); }
    else if (lastTok.equals("清零"))                            { doWarnClear(groupId, sender, target); }
    else if (lastTok.equals("警告-1") || lastTok.equals("警告减1")) { doWarnDec(groupId, sender, target); }
    else if (lastTok.equals("警告详情"))                        { doWarnDetail(groupId, target); }
    else if (lastTok.equals("管理员"))                          { doAddAdmin(groupId, target, days); }
    else if (lastTok.equals("移除管理"))                        { doDelAdmin(groupId, target); }
    else if (lastTok.equals("保护"))                            { doProtect(groupId, sender, target); }
    else if (lastTok.equals("取消保护"))                        { doUnprotect(groupId, sender, target); }
    else if (lastTok.equals("白名单"))                          { doWhitelist(groupId, sender, target); }
    else if (lastTok.equals("移除白名单"))                      { doUnwhitelist(groupId, sender, target); }
    else if (lastTok.equals("群主"))                            { doAddOwner(groupId, target); }
    else if (lastTok.equals("移除群主"))                        { doDelOwner(groupId, target); }
    // v1.13: 微信原生角色登记
    else if (lastTok.equals("微信群主"))                        { doRegisterNativeOwner(groupId, target); }
    else if (lastTok.equals("移除微信群主"))                    { doRemoveNativeOwner(groupId, target); }
}

// ========== 入群拦截 ==========
void onMemberChange(String type, String groupWxid, String userWxid, String userName) {
    if (!"join".equals(type)) return;
    if (!isGroupEnabled(groupWxid)) return;       // 没启用就不管

    // 新成员入群即记录 first_seen, 自动获得豁免期
    recordFirstSeen(groupWxid, userWxid);

    // v1.12: 白名单(本群+全局)免进群检测
    if (isWhitelistAny(groupWxid, userWxid)) return;
    // v1.13: 微信原生群主免进群黑名单检测
    if (isNativeOwner(groupWxid, userWxid)) return;

    // v1.12: 黑名单合并视图(本群 OR 全局)
    final boolean fromGlobal = isGlobalBlacklist(userWxid);
    boolean inLocal = getBlacklist(groupWxid).contains(userWxid);
    if (!fromGlobal && !inLocal) return;

    delay(2000, new Runnable() {
        public void run() {
            String tag = fromGlobal ? "🌐 全局黑名单" : "黑名单";
            if (botPrivState(groupWxid).equals("none")) {
                sendText(groupWxid, "⚠️ " + userName + " 在" + tag + "，但机器人无群管权限，未能自动踢出");
                return;
            }
            if (tryKickSilent(groupWxid, userWxid)) {
                sendText(groupWxid, "🚫 " + userName + " 在" + tag + ", 自动移除");
            } else if (botPrivState(groupWxid).equals("none")) {
                sendText(groupWxid, "⚠️ " + userName + " 在" + tag + "，机器人刚刚发现自己无群管权限");
            }
        }
    });
}

// ========== 命令实现 ==========
void doKick(String groupId, String sender, String target) {
    if (!canActOn(groupId, sender, target)) { sendText(groupId, "❌ 权限不足或不能操作该对象"); return; }
    if (botPrivState(groupId).equals("none")) { warnNoBotPriv(groupId); return; }
    if (tryKickSilent(groupId, target)) {
        sendText(groupId, "👢 已踢出 " + lookupName(target, groupId));
    } else if (botPrivState(groupId).equals("none")) {
        warnNoBotPriv(groupId);
    } else {
        sendText(groupId, "❌ 踢出失败 (非权限原因，可能成员已离群或网络问题)");
    }
}

void doBan(String groupId, String sender, String target) {
    if (!canActOn(groupId, sender, target)) { sendText(groupId, "❌ 权限不足或不能操作该对象"); return; }
    // v1.12: 在白名单的人不能直接拉黑 (需先用 @TA 移除白名单)
    if (isWhitelistAny(groupId, target)) {
        sendText(groupId, "🛡 " + lookupName(target, groupId) + " 在白名单, 不能拉黑\n如需拉黑请先 @TA 移除白名单");
        return;
    }
    List bl = getBlacklist(groupId);
    if (!bl.contains(target)) { bl.add(target); setBlacklist(groupId, bl); }
    String name = lookupName(target, groupId);
    if (botPrivState(groupId).equals("none")) {
        sendText(groupId, "🚫 已加入黑名单: " + name + "\n⚠️ 机器人无群管权限，本次未踢出。再入群时也无法自动踢。");
        return;
    }
    if (tryKickSilent(groupId, target)) {
        sendText(groupId, "🚫 已拉黑 " + name + " (再入群自动踢)");
    } else if (botPrivState(groupId).equals("none")) {
        sendText(groupId, "🚫 已加入黑名单: " + name + "\n⚠️ 但机器人无群管权限，未能踢出");
    } else {
        sendText(groupId, "🚫 已加入黑名单: " + name + "\n⚠️ 踢出失败 (非权限原因)");
    }
}

void doUnban(String groupId, String target) {
    List bl = getBlacklist(groupId);
    if (bl.remove(target)) {
        setBlacklist(groupId, bl);
        sendText(groupId, "✅ 已解除黑名单: " + lookupName(target, groupId));
    } else {
        sendText(groupId, "❌ 该用户不在黑名单");
    }
}

// v1.16: 显式命令 "添加黑名单 @XX [@YY...]" — 对每个被 @ 的人执行加黑+踢 (复用 doBan, 含 canActOn/白名单/权限校验)。
// 调用前提: caller 已过 isAuthorized 闸门。bot 自己被 @ 时跳过。
void doAddBlacklistByAt(String groupId, String sender, List atList) {
    if (atList == null || atList.isEmpty()) {
        sendText(groupId, "❌ 请 @ 要加入黑名单的人\n例: 添加黑名单 @张三 @李四");
        return;
    }
    String me = getLoginWxid();
    int acted = 0;
    for (int i = 0; i < atList.size(); i++) {
        String w = (String) atList.get(i);
        if (w == null || w.isEmpty()) continue;
        if (w.equals(me)) continue;               // 跳过 @ 到机器人自己
        doBan(groupId, sender, w);                // 各自内部 canActOn + 白名单 + 权限校验, 逐条回执
        acted++;
    }
    if (acted == 0) sendText(groupId, "❌ 没有有效的 @ 对象");
}

// v1.16: 显式命令 "去除黑名单 @XX [@YY...]"(@路径) 或 "去除黑名单【N】[【M】...]"(序号路径)。
// 序号 N = 当前 getBlacklist() 列表 1-based 位置 (与 "黑名单" 查询输出一致)。
// 仅删名单项 (复用 doUnban 移除逻辑), 不做 un-kick。
void doRemoveBlacklist(String groupId, String sender, String content, List atList) {
    boolean hasAt = atList != null && !atList.isEmpty();
    if (hasAt) {
        String me = getLoginWxid();
        int acted = 0;
        for (int i = 0; i < atList.size(); i++) {
            String w = (String) atList.get(i);
            if (w == null || w.isEmpty()) continue;
            if (w.equals(me)) continue;
            doUnban(groupId, w);
            acted++;
        }
        if (acted == 0) sendText(groupId, "❌ 没有有效的 @ 对象");
        return;
    }
    // 序号路径: 解析 content 中所有【N】/【1】/[01] 容错形式
    List idxs = parseBlacklistIndexes(content);
    if (idxs.isEmpty()) {
        sendText(groupId, "❌ 用法: 去除黑名单 @对方\n或: 去除黑名单【序号】(序号见 \"黑名单\" 列表, 如 去除黑名单【01】)");
        return;
    }
    List bl = getBlacklist(groupId);
    int n = bl.size();
    if (n == 0) { sendText(groupId, "📋 本群黑名单空"); return; }
    // 先收集要删的 wxid (按当前列表快照定位), 再统一移除, 避免删一个后序号错位。
    List toRemove = new java.util.ArrayList();
    StringBuilder bad = new StringBuilder();
    for (int i = 0; i < idxs.size(); i++) {
        int k = ((Integer) idxs.get(i)).intValue();   // 1-based
        if (k < 1 || k > n) { bad.append("【").append(k < 10 ? "0" + k : "" + k).append("】"); continue; }
        String w = (String) bl.get(k - 1);
        if (!toRemove.contains(w)) toRemove.add(w);
    }
    if (toRemove.isEmpty()) {
        sendText(groupId, "❌ 序号无效" + (bad.length() > 0 ? (": " + bad) : "") + " (当前黑名单共 " + n + " 人)");
        return;
    }
    StringBuilder done = new StringBuilder();
    for (int i = 0; i < toRemove.size(); i++) {
        String w = (String) toRemove.get(i);
        if (bl.remove(w)) done.append("· ").append(lookupName(w, groupId)).append("\n");
    }
    setBlacklist(groupId, bl);
    StringBuilder out = new StringBuilder();
    out.append("✅ 已从黑名单移除:\n").append(done);
    if (bad.length() > 0) out.append("⚠️ 无效序号: ").append(bad);
    sendText(groupId, out.toString().trim());
}

// 解析 "去除黑名单【01】【3】[02]" 中的序号 (1-based)。容错: 全角【】/半角[], 前导 0。返回 Integer 列表。
List parseBlacklistIndexes(String content) {
    List out = new java.util.ArrayList();
    try {
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("[\\[【]\\s*(\\d+)\\s*[\\]】]")
            .matcher(content);
        while (m.find()) {
            try { out.add(Integer.valueOf(Integer.parseInt(m.group(1)))); } catch (Throwable t) {}
        }
    } catch (Throwable t) {}
    return out;
}

void doWarn(String groupId, String sender, String target) { doWarn(groupId, sender, target, null); }
// v1.19.0: reason 非空 = 自动来源(带理由) → 对豁免对象(权限/白名单)静默跳过, 不发报错; 通知文案末尾拼 " · 理由"
void doWarn(String groupId, String sender, String target, String reason) {
    boolean auto = (reason != null && !reason.trim().isEmpty());
    // v1.19.1: 自动来源(伸手党等)不警告管理员/群主/原生群主 (canActOn 对 owner→admin 不挡, 须显式豁免, 防误踢管理)。手动警告(auto=false)不受影响, 仍可警告管理员。
    if (auto && (isAdminInGroup(groupId, target) || isOwner(target) || isNativeOwner(groupId, target))) return;
    if (!canActOn(groupId, sender, target)) { if (!auto) sendText(groupId, "❌ 权限不足或不能操作该对象"); return; }
    // v1.12: 白名单(本群+全局)免警告
    if (isWhitelistAny(groupId, target)) {
        if (!auto) sendText(groupId, "🛡 " + lookupName(target, groupId) + " 在白名单, 不警告");
        return;
    }
    int n = getWarn(groupId, target) + 1;
    setWarn(groupId, target, n);
    addWarnList(groupId, target);
    // v1.12: 记录最近警告时间 + 操作人
    putString("wnt_" + groupId + "_" + target, String.valueOf(System.currentTimeMillis()));
    putString("wnf_" + groupId + "_" + target, sender);

    String name = lookupName(target, groupId);
    String byName = lookupName(sender, groupId);
    int kick = getWarnKick(groupId);   // W1: 本群上限 (默认 10, 可经 wk_<group> 覆盖)
    int remain = kick - n;
    String why = auto ? (" · " + reason.trim()) : "";

    StringBuilder sb = new StringBuilder();
    sb.append("[AtWx=").append(target).append("]\n");   // @ 受罚者, 让 TA 收到通知

    if (n >= kick) {
        sb.append("🚨 ").append(name).append(" 累计 ").append(n).append("/").append(kick).append(" 自动移除").append(why);
        sendText(groupId, sb.toString());
        if (botPrivState(groupId).equals("none")) {
            sendText(groupId, "⚠️ 机器人无群管权限, 未能踢出");   // 无权限: 不清零, 保留计数等下次
        } else {
            boolean kicked = tryKickSilent(groupId, target);
            if (kicked) {
                // 踢成功才清零: 重新进群从 0 重新累计
                setWarn(groupId, target, 0);
                delWarnList(groupId, target);
                putString("wnt_" + groupId + "_" + target, "");
                putString("wnf_" + groupId + "_" + target, "");
            } else {
                // 踢失败(含权限丢失, tryKickSilent 内已 setBotPriv): 不清零, 保留计数
                sendText(groupId, "⚠️ 机器人无群管权限, 未能踢出");
            }
        }
    } else if (n == 1) {
        sb.append("⚠️ 警告 ").append(n).append("/").append(kick).append(" — ").append(name).append(" 还剩 ").append(remain).append(" 次").append(why);
        sendText(groupId, sb.toString());
    } else {
        sb.append("⚠️⚠️ 警告 ").append(n).append("/").append(kick).append(" — ").append(name).append(" 仅剩 ").append(remain).append(" 次, 下次自动踢").append(why);
        sendText(groupId, sb.toString());
    }
}

void doWarnDec(String groupId, String sender, String target) {
    if (!canActOn(groupId, sender, target)) { sendText(groupId, "❌ 权限不足"); return; }
    int n = getWarn(groupId, target) - 1;
    if (n < 0) n = 0;
    setWarn(groupId, target, n);
    if (n == 0) delWarnList(groupId, target);
    sendText(groupId, "⚠️ " + lookupName(target, groupId) + " 警告 " + n + "/" + getWarnKick(groupId));
}

void doWarnClear(String groupId, String sender, String target) {
    if (!canActOn(groupId, sender, target)) { sendText(groupId, "❌ 权限不足"); return; }
    setWarn(groupId, target, 0);
    delWarnList(groupId, target);
    sendText(groupId, "✅ " + lookupName(target, groupId) + " 警告已清零");
}

// W1: `警告上限 N` 设本群警告自动踢上限 (调用前已过 isAuthorized)。N 夹取 1~99。
void doSetWarnKick(String groupId, String content) {
    String arg = content.substring("警告上限".length()).trim();
    if (arg.isEmpty()) {
        sendText(groupId, "ℹ️ 本群警告上限: " + getWarnKick(groupId) + "\n用法: 警告上限 N (N=1~99)");
        return;
    }
    int n;
    try { n = Integer.parseInt(arg); }
    catch (Exception e) { sendText(groupId, "❌ 请输入有效数字 (1~99)"); return; }
    int actual = setWarnKick(groupId, n);
    if (actual != n) sendText(groupId, "✅ 本群警告上限已设为 " + actual + " 次自动踢 (输入 " + n + " 已夹取到 1~99)");
    else sendText(groupId, "✅ 本群警告上限已设为 " + actual + " 次自动踢");
}

// 群命令路径: 保留群消息 (含时长信息)。无参/days<=0 = 永久 (向后兼容原行为)。
void doAddAdmin(String groupId, String target) { doAddAdmin(groupId, target, 0); }
void doAddAdmin(String groupId, String target, int days) {
    if (target.equals(getLoginWxid())) { sendText(groupId, "❌ 机器人账号已是群主, 不需要再加管理"); return; }
    if (isOwner(target))  { sendText(groupId, "❌ 该用户已是群主"); return; }
    List admins = getAdmins(groupId);
    if (!admins.contains(target)) {
        admins.add(target);
        setAdmins(groupId, admins);
    }
    // v1.17.0: days>0 → 设到期; days<=0 → 永久 (清除可能残留的旧时效)
    if (days > 0) setAdminExpiry(groupId, target, System.currentTimeMillis() + (long) days * 86400000L);
    else clearAdminExpiry(groupId, target);
    String suffix;
    if (days > 0) suffix = " (" + days + "天, " + adminExpiryLabel(groupId, target) + ")";
    else suffix = " (永久)";
    sendText(groupId, "🛡 已加为管理员: " + lookupName(target, groupId) + suffix);
}

void doDelAdmin(String groupId, String target) {
    List admins = getAdmins(groupId);
    if (admins.remove(target)) {
        setAdmins(groupId, admins);
        clearAdminExpiry(groupId, target);   // v1.17.0: 清理残留时效
        sendText(groupId, "✅ 已移除管理: " + lookupName(target, groupId));
    } else {
        sendText(groupId, "❌ 该用户不是管理员");
    }
}

void doAddOwner(String groupId, String target) {
    if (target.equals(getLoginWxid())) { sendText(groupId, "❌ 机器人账号已是群主"); return; }
    List owners = getOwners();
    if (!owners.contains(target)) {
        owners.add(target);
        putString("super_owners", joinCsv(owners));
        // 自动从 admin 列表里移除(避免重复)
        List admins = getAdmins(groupId);
        if (admins.remove(target)) setAdmins(groupId, admins);
    }
    sendText(groupId, "👑 已加为群主: " + lookupName(target, groupId));
}

void doDelOwner(String groupId, String target) {
    if (target.equals(getLoginWxid())) { sendText(groupId, "❌ 不能移除机器人账号"); return; }
    List owners = getOwners();
    if (owners.remove(target)) {
        putString("super_owners", joinCsv(owners));
        sendText(groupId, "✅ 已移除群主: " + lookupName(target, groupId));
    } else {
        sendText(groupId, "❌ 该用户不是群主");
    }
}

void showHelp(String groupId) {
    StringBuilder sb = new StringBuilder();
    sb.append("📋 GroupAdmin v1.16.1\n");
    sb.append("作者: 早哥\n");
    sb.append("━━━━━━━━━━━\n");
    sb.append("【日常】\n");
    sb.append("@TA 踢          移出群聊\n");
    sb.append("添加黑名单 @TA  加黑名单 + 踢 (可@多人)\n");
    sb.append("去除黑名单 @TA  解除黑名单 (可@多人)\n");
    sb.append("去除黑名单【N】 按黑名单序号解除\n");
    sb.append("@TA 警告     累计 ").append(getWarnKick(groupId)).append(" 次自动踢\n");
    sb.append("@TA 清零     警告归零\n");
    sb.append("@TA 白名单   免警告 + 免进群检测\n");
    sb.append("@TA 保护     潜水豁免\n");
    sb.append("#潜水 7      列出 7 天没说话的人\n\n");
    sb.append("【查询】\n");
    sb.append("黑名单 (带序号) / 白名单 / 管理员\n");
    sb.append("警告名单 / 保护名单\n\n");
    sb.append("💡 发 \"群管\" 弹本群配置面板\n");
    sb.append("(含全局配置 / 严格模式 / 警告详情\n");
    sb.append(" / wxid 操作 等更多高级项)");
    sendText(groupId, sb.toString());
}

void showBlacklist(String groupId) {
    List bl = getBlacklist(groupId);
    if (bl.isEmpty()) { sendText(groupId, "📋 本群黑名单空"); return; }
    StringBuilder sb = new StringBuilder();
    sb.append("🚫 黑名单 (").append(bl.size()).append("):\n");
    // v1.16: 每项前加 1-based 序号【NN】, 配合 "去除黑名单【N】" 使用
    for (int i = 0; i < bl.size(); i++) {
        int n = i + 1;
        sb.append(n < 10 ? "【0" + n + "】" : "【" + n + "】");
        sb.append(lookupName((String) bl.get(i), groupId)).append("\n");
    }
    sb.append("──────\n去除: 去除黑名单【序号】 或 去除黑名单 @对方");
    sendText(groupId, sb.toString());
}

void showAdmins(String groupId) {
    try { purgeExpiredAdmins(groupId); } catch (Throwable t) {}   // v1.17.0 惰性到期清除 (命令路径, 低频)
    StringBuilder sb = new StringBuilder();
    // 群主段
    sb.append("👑 群主:\n");
    String me = getLoginWxid();
    String meName = lookupName(me, groupId);
    if (meName.equals("未知")) {
        try { String a = getLoginAlias(); if (a != null && !a.isEmpty()) meName = a; } catch (Exception e) {}
    }
    sb.append("· [机器人] ").append(meName).append("\n");
    List owners = getOwners();
    for (int i = 0; i < owners.size(); i++) {
        sb.append("· ").append(lookupName((String) owners.get(i), groupId)).append("\n");
    }
    // 管理员段
    List admins = getAdmins(groupId);
    if (!admins.isEmpty()) {
        sb.append("\n🛡 管理员:\n");
        for (int i = 0; i < admins.size(); i++) {
            String aw = (String) admins.get(i);
            sb.append("· ").append(lookupName(aw, groupId))
              .append(" (").append(adminExpiryLabel(groupId, aw)).append(")\n");
        }
    }
    sendText(groupId, sb.toString());
}

void showWarnList(String groupId) {
    List wl = getWarnList(groupId);
    if (wl.isEmpty()) { sendText(groupId, "📋 本群无警告记录"); return; }
    StringBuilder sb = new StringBuilder();
    sb.append("⚠️ 警告名单 (").append(wl.size()).append("):\n");
    for (int i = 0; i < wl.size(); i++) {
        String w = (String) wl.get(i);
        sb.append("· ").append(lookupName(w, groupId)).append(" - ").append(getWarn(groupId, w)).append("/").append(getWarnKick(groupId)).append("\n");
    }
    sendText(groupId, sb.toString());
}

// ============================================================
// ==== 群配置 GUI Dialog (v1.11) =============================
// 触发: bot 自己在群里输入 `群管设置` / `群管配置` (onClickSendBtn 拦截)
// 内容: 启用开关 + 状态摘要 + 黑/保护/警告 逐项操作 + 潜水控制
// ============================================================

int getDefaultInactiveDays() {
    try { return Integer.parseInt(getString("default_inactive_days", "7")); }
    catch (Exception e) { return 7; }
}

void setDefaultInactiveDays(int n) {
    if (n < 1) n = 1; if (n > 365) n = 365;
    putString("default_inactive_days", String.valueOf(n));
}

int rebuildBaselineForce(String groupId) {
    // T2′: 清空该群 first_seen (UPDATE SET first_seen=NULL, 保留 last_speak 行) → 再按当前成员重建。
    // l3ClearFirstSeen 与 l3UpsertFirstSeen 各自持 FLUSH_LOCK, 与后台 flush 互斥; 二者之间若有后台 flush
    // 插入, 至多补回个别 first_seen=该消息ts (取 min), 不影响"按当前成员重建为 now"的语义 (新 now 必更晚, min 留旧)。
    l3ClearFirstSeen(groupId);
    return initFirstSeenBaseline(groupId);
}

// ---- onClickSendBtn: bot 输入框触发 ----
boolean onClickSendBtn(String text) {
    if (text == null) return false;
    // v1.12: 加 `群管` / `#群管` 两个短触发词
    if (text.equals("群管") || text.equals("#群管") ||
        text.equals("群管设置") || text.equals("群管配置") ||
        text.equals("#群管设置") || text.equals("#群管配置")) {
        String talker = getTargetTalker();
        if (talker == null || !talker.contains("@chatroom")) {
            toast("此命令需在群聊中使用");
            return true;
        }
        _logNativeRoleProbe(talker);
        showGroupConfigDialog(talker);
        return true;
    }
    return false;
}

// ---- 通用 UI helper ----
android.widget.Button _gaBtn(Activity ctx, String text, String fillColor) {
    int dp = (int) ctx.getResources().getDisplayMetrics().density;
    android.widget.Button b = new android.widget.Button(ctx);
    b.setText(text);
    b.setTextColor(_gaC("#E8E9EC"));
    b.setBackground(_gaRound(fillColor, 24));
    b.setAllCaps(false);
    b.setTextSize(14);
    b.setPadding(20 * dp, 12 * dp, 20 * dp, 12 * dp);
    android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
    b.setLayoutParams(lp);
    return b;
}

android.widget.LinearLayout _gaRow(Activity ctx, String name, String sub, String actText, String actFill) {
    int dp = (int) ctx.getResources().getDisplayMetrics().density;
    android.widget.LinearLayout row = new android.widget.LinearLayout(ctx);
    row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
    row.setPadding(12 * dp, 10 * dp, 10 * dp, 10 * dp);
    row.setBackground(_gaRound("#1A1D24", 12));
    row.setGravity(android.view.Gravity.CENTER_VERTICAL);

    android.widget.LinearLayout left = new android.widget.LinearLayout(ctx);
    left.setOrientation(android.widget.LinearLayout.VERTICAL);
    left.setLayoutParams(new android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

    android.widget.TextView tvName = new android.widget.TextView(ctx);
    tvName.setText(name);
    tvName.setTextColor(_gaC("#E8E9EC"));
    tvName.setTextSize(14);
    left.addView(tvName);

    if (sub != null && !sub.isEmpty()) {
        android.widget.TextView tvSub = new android.widget.TextView(ctx);
        tvSub.setText(sub);
        tvSub.setTextColor(_gaC("#7C828E"));
        tvSub.setTextSize(11);
        left.addView(tvSub);
    }
    row.addView(left);

    android.widget.Button act = new android.widget.Button(ctx);
    act.setText(actText);
    act.setTextColor(_gaC("#0F1115"));
    act.setBackground(_gaRound(actFill, 16));
    act.setAllCaps(false);
    act.setTextSize(12);
    act.setPadding(14 * dp, 6 * dp, 14 * dp, 6 * dp);
    row.addView(act);
    return row;
}

void _gaSpacer(android.widget.LinearLayout root, Activity ctx, int h) {
    android.view.View v = new android.view.View(ctx);
    v.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
        android.widget.LinearLayout.LayoutParams.MATCH_PARENT, h));
    root.addView(v);
}

// ---- 主 Dialog ----
void showGroupConfigDialog(String groupId) {
    Activity ctx = getTopActivity();
    if (ctx == null) { toast("无法获取 Activity"); return; }

    boolean enabled = isGroupEnabled(groupId);
    String botPriv = botPrivState(groupId);
    int adminCount = getAdmins(groupId).size();
    int banCount = parseCsv(getString("bl_" + groupId, "")).size();
    int wlCount = parseCsv(getString("wl_" + groupId, "")).size();
    int pCount = getProtected(groupId).size();
    // T2′ (Reviewer B): flush-before-read — first_seen 可能仍在内存脏增量未落盘,
    // 启用群后 30s 内直接查 DB 会误显「基线未建立」诱导误重建。读前先 flush 提交进 DB。
    try { l3Flush(); } catch (Throwable t) {}
    boolean baselineExists = l3CountFirstSeen(groupId) > 0;
    int defDays = getDefaultInactiveDays();

    int dp = (int) ctx.getResources().getDisplayMetrics().density;

    android.widget.LinearLayout root = new android.widget.LinearLayout(ctx);
    root.setOrientation(android.widget.LinearLayout.VERTICAL);
    root.setPadding(16 * dp, 16 * dp, 16 * dp, 16 * dp);
    root.setBackground(_gaRound("#0F1115", 0));

    android.widget.TextView title = new android.widget.TextView(ctx);
    title.setText("⚙️ 本群 GroupAdmin 配置");
    title.setTextColor(_gaC("#E8E9EC"));
    title.setTextSize(18);
    title.setPadding(0, 0, 0, 4 * dp);
    root.addView(title);

    android.widget.TextView gIdView = new android.widget.TextView(ctx);
    gIdView.setText(groupId);
    gIdView.setTextColor(_gaC("#7C828E"));
    gIdView.setTextSize(11);
    gIdView.setPadding(0, 0, 0, 12 * dp);
    root.addView(gIdView);

    // 状态 card
    android.widget.LinearLayout statusCard = new android.widget.LinearLayout(ctx);
    statusCard.setOrientation(android.widget.LinearLayout.VERTICAL);
    statusCard.setPadding(12 * dp, 12 * dp, 12 * dp, 12 * dp);
    statusCard.setBackground(_gaRound("#1A1D24", 12));

    StringBuilder st = new StringBuilder();
    st.append("启用状态: ").append(enabled ? "✅ 已启用" : "🔴 未启用").append("\n");
    st.append("bot 权限: ");
    if (botPriv.equals("admin")) st.append("✅ 群管理员");
    else if (botPriv.equals("none")) st.append("❌ 非管理员 (踢人会失败)");
    else st.append("❓ 未检测 (踢一次会自动检测)");
    st.append("\n");
    st.append("潜水基线: ").append(baselineExists ? "✅ 已建立" : "🔴 未建立").append("\n");
    st.append("默认潜水天数: ").append(defDays).append(" 天\n");
    st.append("警告自动踢上限: ").append(getWarnKick(groupId)).append(" 次\n");
    st.append("本群管理员: ").append(adminCount).append(" 人\n");
    st.append("全局群主: ").append(getOwners().size()).append(" 人\n");
    st.append("黑 ").append(banCount).append("  ·  警告 ").append(wlCount).append("  ·  保护 ").append(pCount);

    android.widget.TextView statusText = new android.widget.TextView(ctx);
    statusText.setText(st.toString());
    statusText.setTextColor(_gaC("#E8E9EC"));
    statusText.setTextSize(13);
    statusText.setLineSpacing(0, 1.4f);
    statusCard.addView(statusText);
    root.addView(statusCard);

    _gaSpacer(root, ctx, 12 * dp);

    final String fGroupId = groupId;
    final boolean fEnabled = enabled;
    final AlertDialog[] dh = new AlertDialog[1];

    android.widget.Button toggleBtn = _gaBtn(ctx, enabled ? "🔕 禁用本群群管" : "✅ 启用本群群管", enabled ? "#FF6B6B" : "#5CB6FF");
    toggleBtn.setOnClickListener(new android.view.View.OnClickListener() {
        public void onClick(android.view.View v) {
            if (fEnabled) {
                disableGroup(fGroupId);
                toast("已禁用本群群管");
            } else {
                int n = enableGroupWithBaseline(fGroupId);
                toast("已启用 (基线 " + n + " 人)");
            }
            if (dh[0] != null) dh[0].dismiss();
            showGroupConfigDialog(fGroupId);
        }
    });
    root.addView(toggleBtn);

    _gaSpacer(root, ctx, 8 * dp);

    android.widget.Button adminBtn = _gaBtn(ctx, "🛡 本群管理员 (" + adminCount + ")", "#252934");
    adminBtn.setOnClickListener(new android.view.View.OnClickListener() {
        public void onClick(android.view.View v) {
            if (dh[0] != null) dh[0].dismiss();
            showAdminListDialog(fGroupId);
        }
    });
    root.addView(adminBtn);
    _gaSpacer(root, ctx, 6 * dp);

    android.widget.Button ownerBtn = _gaBtn(ctx, "👑 全局群主 (" + getOwners().size() + ")", "#252934");
    ownerBtn.setOnClickListener(new android.view.View.OnClickListener() {
        public void onClick(android.view.View v) {
            if (dh[0] != null) dh[0].dismiss();
            showOwnerListDialog(fGroupId);
        }
    });
    root.addView(ownerBtn);
    _gaSpacer(root, ctx, 6 * dp);

    android.widget.Button banBtn = _gaBtn(ctx, "🦶 黑名单 (" + banCount + ")", "#252934");
    banBtn.setOnClickListener(new android.view.View.OnClickListener() {
        public void onClick(android.view.View v) {
            if (dh[0] != null) dh[0].dismiss();
            showBanListDialog(fGroupId);
        }
    });
    root.addView(banBtn);
    _gaSpacer(root, ctx, 6 * dp);

    android.widget.Button protBtn = _gaBtn(ctx, "🛡 保护名单 (" + pCount + ")", "#252934");
    protBtn.setOnClickListener(new android.view.View.OnClickListener() {
        public void onClick(android.view.View v) {
            if (dh[0] != null) dh[0].dismiss();
            showProtectedListDialog(fGroupId);
        }
    });
    root.addView(protBtn);
    _gaSpacer(root, ctx, 6 * dp);

    // v1.12: 白名单按钮
    int wtCount = getWhitelist(groupId).size();
    android.widget.Button wtBtn = _gaBtn(ctx, "✅ 白名单 (" + wtCount + ")", "#252934");
    wtBtn.setOnClickListener(new android.view.View.OnClickListener() {
        public void onClick(android.view.View v) {
            if (dh[0] != null) dh[0].dismiss();
            showWhitelistDialog(fGroupId);
        }
    });
    root.addView(wtBtn);
    _gaSpacer(root, ctx, 6 * dp);

    android.widget.Button warnBtn = _gaBtn(ctx, "⚠️ 警告名单 (" + wlCount + ")", "#252934");
    warnBtn.setOnClickListener(new android.view.View.OnClickListener() {
        public void onClick(android.view.View v) {
            if (dh[0] != null) dh[0].dismiss();
            showWarnedListDialog(fGroupId);
        }
    });
    root.addView(warnBtn);
    _gaSpacer(root, ctx, 6 * dp);

    // W1: 本群警告自动踢上限 数字输入控件 (仿默认潜水天数控件) → 写 wk_<group>
    android.widget.TextView wkLabel = new android.widget.TextView(ctx);
    wkLabel.setText("⚠️ 警告自动踢上限 (1~99, 默认 " + WARN_AUTO_KICK + ")");
    wkLabel.setTextColor(_gaC("#E8E9EC"));
    wkLabel.setTextSize(13);
    wkLabel.setPadding(0, 0, 0, 6 * dp);
    root.addView(wkLabel);

    final android.widget.EditText wkInp = new android.widget.EditText(ctx);
    wkInp.setText(String.valueOf(getWarnKick(groupId)));
    wkInp.setTextColor(_gaC("#E8E9EC"));
    wkInp.setHintTextColor(_gaC("#7C828E"));
    wkInp.setBackground(_gaRound("#252934", 12));
    wkInp.setPadding(12 * dp, 8 * dp, 12 * dp, 8 * dp);
    wkInp.setTextSize(14);
    wkInp.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
    wkInp.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));
    root.addView(wkInp);
    _gaSpacer(root, ctx, 6 * dp);

    final String fWkGroupId = groupId;
    android.widget.Button wkSaveBtn = _gaBtn(ctx, "💾 保存警告上限", "#5CB6FF");
    wkSaveBtn.setOnClickListener(new android.view.View.OnClickListener() {
        public void onClick(android.view.View v) {
            try {
                int n = Integer.parseInt(wkInp.getText().toString().trim());
                int actual = setWarnKick(fWkGroupId, n);   // 夹取 1~99
                wkInp.setText(String.valueOf(actual));
                toast(actual != n ? ("已保存: " + actual + " (已夹取到 1~99)") : ("已保存: " + actual + " 次自动踢"));
            } catch (Exception e) { toast("请输入有效数字 (1~99)"); }
        }
    });
    root.addView(wkSaveBtn);
    _gaSpacer(root, ctx, 6 * dp);

    android.widget.Button inactBtn = _gaBtn(ctx, "💤 潜水/权限 控制", "#252934");
    inactBtn.setOnClickListener(new android.view.View.OnClickListener() {
        public void onClick(android.view.View v) {
            if (dh[0] != null) dh[0].dismiss();
            showInactiveControlDialog(fGroupId);
        }
    });
    root.addView(inactBtn);

    android.widget.ScrollView sv = new android.widget.ScrollView(ctx);
    sv.addView(root);
    sv.setBackground(_gaRound("#0F1115", 0));

    dh[0] = new AlertDialog.Builder(ctx)
        .setView(sv)
        .setNegativeButton("关闭", null)
        .create();
    dh[0].show();
}

android.widget.LinearLayout _adminRow(Activity ctx, String groupId, String wxid, AlertDialog[] dh) {
    final String fGroupId = groupId;
    final String fWxid = wxid;
    final AlertDialog[] fDh = dh;
    // v1.17.0: 副标题显示 失效日期/永久 (替代原来只显示 wxid; wxid 仍拼在前)
    String sub = fWxid + " · " + adminExpiryLabel(fGroupId, fWxid);
    android.widget.LinearLayout row = _gaRow(ctx, lookupName(fWxid, fGroupId), sub, "移除", "#FF6B6B");
    android.view.View act = row.getChildAt(1);
    act.setOnClickListener(new android.view.View.OnClickListener() {
        public void onClick(android.view.View v) {
            List l = getAdmins(fGroupId);
            l.remove(fWxid);
            setAdmins(fGroupId, l);
            clearAdminExpiry(fGroupId, fWxid);   // v1.17.0: 清理残留时效
            toast("已移除本群管理员");
            if (fDh[0] != null) fDh[0].dismiss();
            showAdminListDialog(fGroupId);
        }
    });
    return row;
}

android.widget.LinearLayout _ownerRow(Activity ctx, String returnGroupId, String wxid, AlertDialog[] dh) {
    final String fReturnGroupId = returnGroupId;
    final String fWxid = wxid;
    final AlertDialog[] fDh = dh;
    android.widget.LinearLayout row = _gaRow(ctx, lookupName(fWxid, fReturnGroupId), fWxid, "移除", "#FF6B6B");
    android.view.View act = row.getChildAt(1);
    act.setOnClickListener(new android.view.View.OnClickListener() {
        public void onClick(android.view.View v) {
            List l = getOwners();
            l.remove(fWxid);
            putString("super_owners", joinCsv(l));
            toast("已移除全局群主");
            if (fDh[0] != null) fDh[0].dismiss();
            showOwnerListDialog(fReturnGroupId);
        }
    });
    return row;
}

// ---- 本群管理员子 Dialog ----
void showAdminListDialog(String groupId) {
    Activity ctx = getTopActivity();
    if (ctx == null) return;
    try { purgeExpiredAdmins(groupId); } catch (Throwable t) {}   // v1.17.0 惰性到期清除 (Dialog 路径, 低频)
    final String fGroupId = groupId;
    final AlertDialog[] dh = new AlertDialog[1];

    List admins = getAdmins(groupId);
    int dp = (int) ctx.getResources().getDisplayMetrics().density;

    android.widget.LinearLayout root = new android.widget.LinearLayout(ctx);
    root.setOrientation(android.widget.LinearLayout.VERTICAL);
    root.setPadding(16 * dp, 16 * dp, 16 * dp, 16 * dp);
    root.setBackground(_gaRound("#0F1115", 0));

    android.widget.TextView title = new android.widget.TextView(ctx);
    title.setText("🛡 本群管理员 (" + admins.size() + " 人)");
    title.setTextColor(_gaC("#E8E9EC"));
    title.setTextSize(16);
    title.setPadding(0, 0, 0, 6 * dp);
    root.addView(title);

    android.widget.TextView hint = new android.widget.TextView(ctx);
    hint.setText("管理员权限仅对本群生效；群主请到全局群主管理里维护。");
    hint.setTextColor(_gaC("#7C828E"));
    hint.setTextSize(11);
    hint.setPadding(0, 0, 0, 12 * dp);
    root.addView(hint);

    final android.widget.EditText inp = new android.widget.EditText(ctx);
    inp.setHint("输入 wxid_... 后添加为本群管理员");
    inp.setTextColor(_gaC("#E8E9EC"));
    inp.setHintTextColor(_gaC("#7C828E"));
    inp.setBackground(_gaRound("#252934", 12));
    inp.setPadding(12 * dp, 8 * dp, 12 * dp, 8 * dp);
    inp.setTextSize(14);
    root.addView(inp);
    _gaSpacer(root, ctx, 6 * dp);

    // v1.17.0: 天数输入 (空=永久)
    final android.widget.EditText dayInp = new android.widget.EditText(ctx);
    dayInp.setHint("天数(空=永久)");
    dayInp.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
    dayInp.setTextColor(_gaC("#E8E9EC"));
    dayInp.setHintTextColor(_gaC("#7C828E"));
    dayInp.setBackground(_gaRound("#252934", 12));
    dayInp.setPadding(12 * dp, 8 * dp, 12 * dp, 8 * dp);
    dayInp.setTextSize(14);
    root.addView(dayInp);
    _gaSpacer(root, ctx, 6 * dp);

    android.widget.Button addBtn = _gaBtn(ctx, "➕ 添加本群管理员", "#5CB6FF");
    addBtn.setOnClickListener(new android.view.View.OnClickListener() {
        public void onClick(android.view.View v) {
            String w = inp.getText().toString().trim();
            if (w.isEmpty()) { toast("请输入 wxid"); return; }
            // v1.17.0: 解析天数 (空/非正整数 = 永久), Dialog 路径保持静默 (只 toast, 不发群)
            int days = 0;
            try {
                String ds = dayInp.getText().toString().trim();
                if (!ds.isEmpty() && _isPosIntTok(ds)) { int n = Integer.parseInt(ds); if (n > 0) days = n; }
            } catch (Throwable t) { days = 0; }
            List l = getAdmins(fGroupId);
            if (!l.contains(w)) { l.add(w); setAdmins(fGroupId, l); }
            if (days > 0) setAdminExpiry(fGroupId, w, System.currentTimeMillis() + (long) days * 86400000L);
            else clearAdminExpiry(fGroupId, w);
            toast(days > 0 ? ("已添加本群管理员 (" + days + "天)") : "已添加本群管理员 (永久)");
            if (dh[0] != null) dh[0].dismiss();
            showAdminListDialog(fGroupId);
        }
    });
    root.addView(addBtn);
    _gaSpacer(root, ctx, 6 * dp);

    if (admins.isEmpty()) {
        android.widget.TextView empty = new android.widget.TextView(ctx);
        empty.setText("本群暂无管理员");
        empty.setTextColor(_gaC("#7C828E"));
        empty.setTextSize(13);
        empty.setPadding(0, 12 * dp, 0, 12 * dp);
        root.addView(empty);
    } else {
        for (int i = 0; i < admins.size(); i++) {
            root.addView(_adminRow(ctx, fGroupId, (String) admins.get(i), dh));
            _gaSpacer(root, ctx, 6 * dp);
        }
    }

    _gaSpacer(root, ctx, 8 * dp);
    android.widget.Button back = _gaBtn(ctx, "← 返回", "#252934");
    back.setOnClickListener(new android.view.View.OnClickListener() {
        public void onClick(android.view.View v) {
            if (dh[0] != null) dh[0].dismiss();
            showGroupConfigDialog(fGroupId);
        }
    });
    root.addView(back);

    android.widget.ScrollView sv = new android.widget.ScrollView(ctx);
    sv.addView(root);
    sv.setBackground(_gaRound("#0F1115", 0));
    dh[0] = new AlertDialog.Builder(ctx).setView(sv).create();
    dh[0].show();
}

// ---- 全局群主子 Dialog ----
void showOwnerListDialog(String returnGroupId) {
    Activity ctx = getTopActivity();
    if (ctx == null) return;
    final String fReturnGroupId = returnGroupId;
    final AlertDialog[] dh = new AlertDialog[1];

    List owners = getOwners();
    int dp = (int) ctx.getResources().getDisplayMetrics().density;

    android.widget.LinearLayout root = new android.widget.LinearLayout(ctx);
    root.setOrientation(android.widget.LinearLayout.VERTICAL);
    root.setPadding(16 * dp, 16 * dp, 16 * dp, 16 * dp);
    root.setBackground(_gaRound("#0F1115", 0));

    android.widget.TextView title = new android.widget.TextView(ctx);
    title.setText("👑 全局群主 (" + owners.size() + " 人)");
    title.setTextColor(_gaC("#E8E9EC"));
    title.setTextSize(16);
    title.setPadding(0, 0, 0, 6 * dp);
    root.addView(title);

    android.widget.TextView hint = new android.widget.TextView(ctx);
    hint.setText("全局群主可管理所有启用群；机器人账号默认永远是群主，不在此列表中。");
    hint.setTextColor(_gaC("#7C828E"));
    hint.setTextSize(11);
    hint.setPadding(0, 0, 0, 12 * dp);
    root.addView(hint);

    final android.widget.EditText inp = new android.widget.EditText(ctx);
    inp.setHint("输入 wxid_... 后添加为全局群主");
    inp.setTextColor(_gaC("#E8E9EC"));
    inp.setHintTextColor(_gaC("#7C828E"));
    inp.setBackground(_gaRound("#252934", 12));
    inp.setPadding(12 * dp, 8 * dp, 12 * dp, 8 * dp);
    inp.setTextSize(14);
    root.addView(inp);
    _gaSpacer(root, ctx, 6 * dp);

    android.widget.Button addBtn = _gaBtn(ctx, "➕ 添加全局群主", "#5CB6FF");
    addBtn.setOnClickListener(new android.view.View.OnClickListener() {
        public void onClick(android.view.View v) {
            String w = inp.getText().toString().trim();
            if (w.isEmpty()) { toast("请输入 wxid"); return; }
            if (w.equals(getLoginWxid())) { toast("机器人账号默认已经是群主"); return; }
            List l = getOwners();
            if (!l.contains(w)) { l.add(w); putString("super_owners", joinCsv(l)); }
            toast("已添加全局群主");
            if (dh[0] != null) dh[0].dismiss();
            showOwnerListDialog(fReturnGroupId);
        }
    });
    root.addView(addBtn);
    _gaSpacer(root, ctx, 12 * dp);

    if (owners.isEmpty()) {
        android.widget.TextView empty = new android.widget.TextView(ctx);
        empty.setText("暂无额外全局群主");
        empty.setTextColor(_gaC("#7C828E"));
        empty.setTextSize(13);
        empty.setPadding(0, 12 * dp, 0, 12 * dp);
        root.addView(empty);
    } else {
        for (int i = 0; i < owners.size(); i++) {
            root.addView(_ownerRow(ctx, fReturnGroupId, (String) owners.get(i), dh));
            _gaSpacer(root, ctx, 6 * dp);
        }
    }

    _gaSpacer(root, ctx, 8 * dp);
    android.widget.Button back = _gaBtn(ctx, "← 返回", "#252934");
    back.setOnClickListener(new android.view.View.OnClickListener() {
        public void onClick(android.view.View v) {
            if (dh[0] != null) dh[0].dismiss();
            showGroupConfigDialog(fReturnGroupId);
        }
    });
    root.addView(back);

    android.widget.ScrollView sv = new android.widget.ScrollView(ctx);
    sv.addView(root);
    sv.setBackground(_gaRound("#0F1115", 0));
    dh[0] = new AlertDialog.Builder(ctx).setView(sv).create();
    dh[0].show();
}

// ---- 黑名单子 Dialog ----
void showBanListDialog(String groupId) {
    Activity ctx = getTopActivity();
    if (ctx == null) return;
    final String fGroupId = groupId;
    final AlertDialog[] dh = new AlertDialog[1];

    List bl = parseCsv(getString("bl_" + groupId, ""));
    int dp = (int) ctx.getResources().getDisplayMetrics().density;

    android.widget.LinearLayout root = new android.widget.LinearLayout(ctx);
    root.setOrientation(android.widget.LinearLayout.VERTICAL);
    root.setPadding(16 * dp, 16 * dp, 16 * dp, 16 * dp);
    root.setBackground(_gaRound("#0F1115", 0));

    android.widget.TextView title = new android.widget.TextView(ctx);
    title.setText("🦶 黑名单 (" + bl.size() + " 人)");
    title.setTextColor(_gaC("#E8E9EC"));
    title.setTextSize(16);
    title.setPadding(0, 0, 0, 12 * dp);
    root.addView(title);

    if (bl.isEmpty()) {
        android.widget.TextView empty = new android.widget.TextView(ctx);
        empty.setText("本群无黑名单");
        empty.setTextColor(_gaC("#7C828E"));
        empty.setTextSize(13);
        empty.setPadding(0, 12 * dp, 0, 12 * dp);
        root.addView(empty);
    } else {
        for (int i = 0; i < bl.size(); i++) {
            final String wxid = (String) bl.get(i);
            android.widget.LinearLayout row = _gaRow(ctx, lookupName(wxid, fGroupId), wxid, "解黑", "#FF6B6B");
            android.view.View act = row.getChildAt(1);
            act.setOnClickListener(new android.view.View.OnClickListener() {
                public void onClick(android.view.View v) {
                    doUnban(fGroupId, wxid);
                    toast("已解黑");
                    if (dh[0] != null) dh[0].dismiss();
                    showBanListDialog(fGroupId);
                }
            });
            root.addView(row);
            _gaSpacer(root, ctx, 6 * dp);
        }
    }

    _gaSpacer(root, ctx, 8 * dp);
    android.widget.Button back = _gaBtn(ctx, "← 返回", "#252934");
    back.setOnClickListener(new android.view.View.OnClickListener() {
        public void onClick(android.view.View v) {
            if (dh[0] != null) dh[0].dismiss();
            showGroupConfigDialog(fGroupId);
        }
    });
    root.addView(back);

    android.widget.ScrollView sv = new android.widget.ScrollView(ctx);
    sv.addView(root);
    sv.setBackground(_gaRound("#0F1115", 0));
    dh[0] = new AlertDialog.Builder(ctx).setView(sv).create();
    dh[0].show();
}

// ---- 保护名单子 Dialog ----
void showProtectedListDialog(String groupId) {
    Activity ctx = getTopActivity();
    if (ctx == null) return;
    final String fGroupId = groupId;
    final AlertDialog[] dh = new AlertDialog[1];

    List pl = getProtected(groupId);
    int dp = (int) ctx.getResources().getDisplayMetrics().density;

    android.widget.LinearLayout root = new android.widget.LinearLayout(ctx);
    root.setOrientation(android.widget.LinearLayout.VERTICAL);
    root.setPadding(16 * dp, 16 * dp, 16 * dp, 16 * dp);
    root.setBackground(_gaRound("#0F1115", 0));

    android.widget.TextView title = new android.widget.TextView(ctx);
    title.setText("🛡 保护名单 (" + pl.size() + " 人)");
    title.setTextColor(_gaC("#E8E9EC"));
    title.setTextSize(16);
    title.setPadding(0, 0, 0, 12 * dp);
    root.addView(title);

    if (pl.isEmpty()) {
        android.widget.TextView empty = new android.widget.TextView(ctx);
        empty.setText("本群无保护名单");
        empty.setTextColor(_gaC("#7C828E"));
        empty.setTextSize(13);
        empty.setPadding(0, 12 * dp, 0, 12 * dp);
        root.addView(empty);
    } else {
        for (int i = 0; i < pl.size(); i++) {
            final String wxid = (String) pl.get(i);
            android.widget.LinearLayout row = _gaRow(ctx, lookupName(wxid, fGroupId), wxid, "取消保护", "#FFD166");
            android.view.View act = row.getChildAt(1);
            act.setOnClickListener(new android.view.View.OnClickListener() {
                public void onClick(android.view.View v) {
                    List p = getProtected(fGroupId);
                    p.remove(wxid);
                    putString("protected_" + fGroupId, joinCsv(p));
                    toast("已取消保护");
                    if (dh[0] != null) dh[0].dismiss();
                    showProtectedListDialog(fGroupId);
                }
            });
            root.addView(row);
            _gaSpacer(root, ctx, 6 * dp);
        }
    }

    _gaSpacer(root, ctx, 8 * dp);
    android.widget.Button back = _gaBtn(ctx, "← 返回", "#252934");
    back.setOnClickListener(new android.view.View.OnClickListener() {
        public void onClick(android.view.View v) {
            if (dh[0] != null) dh[0].dismiss();
            showGroupConfigDialog(fGroupId);
        }
    });
    root.addView(back);

    android.widget.ScrollView sv = new android.widget.ScrollView(ctx);
    sv.addView(root);
    sv.setBackground(_gaRound("#0F1115", 0));
    dh[0] = new AlertDialog.Builder(ctx).setView(sv).create();
    dh[0].show();
}

// ---- 警告名单子 Dialog ----
void showWarnedListDialog(String groupId) {
    Activity ctx = getTopActivity();
    if (ctx == null) return;
    final String fGroupId = groupId;
    final AlertDialog[] dh = new AlertDialog[1];

    List wl = getWarnList(groupId);
    int dp = (int) ctx.getResources().getDisplayMetrics().density;

    android.widget.LinearLayout root = new android.widget.LinearLayout(ctx);
    root.setOrientation(android.widget.LinearLayout.VERTICAL);
    root.setPadding(16 * dp, 16 * dp, 16 * dp, 16 * dp);
    root.setBackground(_gaRound("#0F1115", 0));

    android.widget.TextView title = new android.widget.TextView(ctx);
    title.setText("⚠️ 警告名单 (" + wl.size() + " 人)");
    title.setTextColor(_gaC("#E8E9EC"));
    title.setTextSize(16);
    title.setPadding(0, 0, 0, 12 * dp);
    root.addView(title);

    if (wl.isEmpty()) {
        android.widget.TextView empty = new android.widget.TextView(ctx);
        empty.setText("本群无警告记录");
        empty.setTextColor(_gaC("#7C828E"));
        empty.setTextSize(13);
        empty.setPadding(0, 12 * dp, 0, 12 * dp);
        root.addView(empty);
    } else {
        for (int i = 0; i < wl.size(); i++) {
            final String wxid = (String) wl.get(i);
            int n = getWarn(groupId, wxid);
            android.widget.LinearLayout row = _gaRow(ctx, lookupName(wxid, fGroupId), "警告 " + n + "/" + getWarnKick(fGroupId), "清零", "#FFD166");
            android.view.View act = row.getChildAt(1);
            act.setOnClickListener(new android.view.View.OnClickListener() {
                public void onClick(android.view.View v) {
                    setWarn(fGroupId, wxid, 0);
                    List l = getWarnList(fGroupId);
                    l.remove(wxid);
                    putString("wl_" + fGroupId, joinCsv(l));
                    toast("已清零");
                    if (dh[0] != null) dh[0].dismiss();
                    showWarnedListDialog(fGroupId);
                }
            });
            root.addView(row);
            _gaSpacer(root, ctx, 6 * dp);
        }
    }

    _gaSpacer(root, ctx, 8 * dp);
    android.widget.Button back = _gaBtn(ctx, "← 返回", "#252934");
    back.setOnClickListener(new android.view.View.OnClickListener() {
        public void onClick(android.view.View v) {
            if (dh[0] != null) dh[0].dismiss();
            showGroupConfigDialog(fGroupId);
        }
    });
    root.addView(back);

    android.widget.ScrollView sv = new android.widget.ScrollView(ctx);
    sv.addView(root);
    sv.setBackground(_gaRound("#0F1115", 0));
    dh[0] = new AlertDialog.Builder(ctx).setView(sv).create();
    dh[0].show();
}

// ---- 潜水/权限 控制 子 Dialog ----
void showInactiveControlDialog(String groupId) {
    Activity ctx = getTopActivity();
    if (ctx == null) return;
    final String fGroupId = groupId;
    final AlertDialog[] dh = new AlertDialog[1];

    String botPriv = botPrivState(groupId);
    // T2′ (Reviewer B): flush-before-read — 同上, 避免 30s 窗口内误显「基线未建立」。
    try { l3Flush(); } catch (Throwable t) {}
    boolean baselineExists = l3CountFirstSeen(groupId) > 0;
    int defDays = getDefaultInactiveDays();
    int dp = (int) ctx.getResources().getDisplayMetrics().density;

    android.widget.LinearLayout root = new android.widget.LinearLayout(ctx);
    root.setOrientation(android.widget.LinearLayout.VERTICAL);
    root.setPadding(16 * dp, 16 * dp, 16 * dp, 16 * dp);
    root.setBackground(_gaRound("#0F1115", 0));

    android.widget.TextView title = new android.widget.TextView(ctx);
    title.setText("💤 潜水 / bot 权限 控制");
    title.setTextColor(_gaC("#E8E9EC"));
    title.setTextSize(16);
    title.setPadding(0, 0, 0, 12 * dp);
    root.addView(title);

    // bot 权限段
    String pTxt;
    if (botPriv.equals("admin")) pTxt = "bot 权限: ✅ 群管理员";
    else if (botPriv.equals("none")) pTxt = "bot 权限: ❌ 非管理员 (踢人会失败)";
    else pTxt = "bot 权限: ❓ 未检测";
    android.widget.TextView pv = new android.widget.TextView(ctx);
    pv.setText(pTxt);
    pv.setTextColor(_gaC("#E8E9EC"));
    pv.setTextSize(13);
    pv.setPadding(0, 0, 0, 6 * dp);
    root.addView(pv);

    android.widget.Button resetPrivBtn = _gaBtn(ctx, "🔄 重置 bot 权限缓存", "#252934");
    resetPrivBtn.setOnClickListener(new android.view.View.OnClickListener() {
        public void onClick(android.view.View v) {
            putString("bp_" + fGroupId, "unknown");
            toast("已重置, 下次踢人重新检测");
            if (dh[0] != null) dh[0].dismiss();
            showInactiveControlDialog(fGroupId);
        }
    });
    root.addView(resetPrivBtn);

    _gaSpacer(root, ctx, 16 * dp);

    // 潜水基线段
    android.widget.TextView bv = new android.widget.TextView(ctx);
    bv.setText("潜水基线: " + (baselineExists ? "✅ 已建立" : "🔴 未建立"));
    bv.setTextColor(_gaC("#E8E9EC"));
    bv.setTextSize(13);
    bv.setPadding(0, 0, 0, 6 * dp);
    root.addView(bv);

    android.widget.Button rebuildBtn = _gaBtn(ctx, "🔁 强制重建潜水基线 (覆盖现有)", "#FFD166");
    rebuildBtn.setOnClickListener(new android.view.View.OnClickListener() {
        public void onClick(android.view.View v) {
            int n = rebuildBaselineForce(fGroupId);
            toast("重建完成: " + n + " 人");
            if (dh[0] != null) dh[0].dismiss();
            showInactiveControlDialog(fGroupId);
        }
    });
    root.addView(rebuildBtn);

    _gaSpacer(root, ctx, 16 * dp);

    // 默认天数段
    android.widget.TextView dv = new android.widget.TextView(ctx);
    dv.setText("默认潜水天数 (当前 " + defDays + ", v1.11 仅记录, 命令仍需 #潜水 N)");
    dv.setTextColor(_gaC("#E8E9EC"));
    dv.setTextSize(13);
    dv.setPadding(0, 0, 0, 6 * dp);
    root.addView(dv);

    final android.widget.EditText inp = new android.widget.EditText(ctx);
    inp.setText(String.valueOf(defDays));
    inp.setTextColor(_gaC("#E8E9EC"));
    inp.setHintTextColor(_gaC("#7C828E"));
    inp.setBackground(_gaRound("#252934", 12));
    inp.setPadding(12 * dp, 8 * dp, 12 * dp, 8 * dp);
    inp.setTextSize(14);
    inp.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
    inp.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));
    root.addView(inp);

    _gaSpacer(root, ctx, 6 * dp);

    android.widget.Button saveBtn = _gaBtn(ctx, "💾 保存默认天数", "#5CB6FF");
    saveBtn.setOnClickListener(new android.view.View.OnClickListener() {
        public void onClick(android.view.View v) {
            try {
                int n = Integer.parseInt(inp.getText().toString().trim());
                setDefaultInactiveDays(n);
                toast("已保存: " + n + " 天");
            } catch (Exception e) { toast("请输入有效数字"); }
        }
    });
    root.addView(saveBtn);

    _gaSpacer(root, ctx, 16 * dp);

    android.widget.Button back = _gaBtn(ctx, "← 返回", "#252934");
    back.setOnClickListener(new android.view.View.OnClickListener() {
        public void onClick(android.view.View v) {
            if (dh[0] != null) dh[0].dismiss();
            showGroupConfigDialog(fGroupId);
        }
    });
    root.addView(back);

    android.widget.ScrollView sv = new android.widget.ScrollView(ctx);
    sv.addView(root);
    sv.setBackground(_gaRound("#0F1115", 0));
    dh[0] = new AlertDialog.Builder(ctx).setView(sv).create();
    dh[0].show();
}

// ============================================================
// ==== v1.12: 白名单 / 警告详情 / 全局配置 / 严格模式 ============
// ============================================================

// ---- 白名单 dispatch ----
void doWhitelist(String groupId, String sender, String target) {
    if (!canActOn(groupId, sender, target)) { sendText(groupId, "❌ 权限不足"); return; }
    List wt = getWhitelist(groupId);
    if (!wt.contains(target)) { wt.add(target); setWhitelist(groupId, wt); }
    sendText(groupId, "✅ 已加入本群白名单: " + lookupName(target, groupId) + "\n(免警告 + 免进群黑名单检测)");
}

void doUnwhitelist(String groupId, String sender, String target) {
    if (!canActOn(groupId, sender, target)) { sendText(groupId, "❌ 权限不足"); return; }
    List wt = getWhitelist(groupId);
    if (wt.remove(target)) {
        setWhitelist(groupId, wt);
        sendText(groupId, "✅ 已移除本群白名单: " + lookupName(target, groupId));
    } else {
        sendText(groupId, "❌ 该用户不在本群白名单");
    }
}

void showWhitelist(String groupId) {
    List wt = getWhitelist(groupId);
    if (wt.isEmpty()) { sendText(groupId, "📋 本群白名单为空"); return; }
    StringBuilder sb = new StringBuilder();
    sb.append("✅ 本群白名单 (").append(wt.size()).append("):\n");
    for (int i = 0; i < wt.size(); i++) {
        sb.append("· ").append(lookupName((String) wt.get(i), groupId)).append("\n");
    }
    sendText(groupId, sb.toString());
}

// ---- 警告详情 ----
void doWarnDetail(String groupId, String target) {
    int n = getWarn(groupId, target);
    String name = lookupName(target, groupId);
    StringBuilder sb = new StringBuilder();
    sb.append("⚠️ ").append(name).append(" 警告详情:\n");
    sb.append("━━━━━━\n");
    sb.append("当前警告: ").append(n).append("/").append(getWarnKick(groupId)).append("\n");
    if (n == 0) {
        sb.append("(无警告记录)");
    } else {
        String tsStr = getString("wnt_" + groupId + "_" + target, "");
        if (!tsStr.isEmpty()) {
            try {
                long ts = Long.parseLong(tsStr);
                long ago = (System.currentTimeMillis() - ts) / 60000L;
                if (ago < 60) sb.append("最近警告: ").append(ago).append(" 分钟前\n");
                else if (ago < 1440) sb.append("最近警告: ").append(ago / 60).append(" 小时前\n");
                else sb.append("最近警告: ").append(ago / 1440).append(" 天前\n");
            } catch (Exception e) {}
        }
        String fromWxid = getString("wnf_" + groupId + "_" + target, "");
        if (!fromWxid.isEmpty()) {
            sb.append("操作人: ").append(lookupName(fromWxid, groupId)).append("\n");
        }
        int remain = getWarnKick(groupId) - n;
        if (remain > 0) sb.append("还剩 ").append(remain).append(" 次机会");
        else sb.append("已达上限");
    }
    sendText(groupId, sb.toString());
}

// ---- 清除警告名单 (仅群主) ----
void doWarnClearAll(String groupId, String sender) {
    if (!isOwner(sender)) { sendText(groupId, "❌ 此命令仅群主可用"); return; }
    List wl = getWarnList(groupId);
    if (wl.isEmpty()) { sendText(groupId, "📋 本群无警告记录"); return; }
    int cleared = wl.size();
    for (int i = 0; i < wl.size(); i++) {
        String w = (String) wl.get(i);
        setWarn(groupId, w, 0);
        putString("wnt_" + groupId + "_" + w, "");
        putString("wnf_" + groupId + "_" + w, "");
    }
    putString("wl_" + groupId, "");
    sendText(groupId, "✅ 已清空本群所有警告 (共清除 " + cleared + " 人)");
}

// ---- 全局黑名单 ----
void doAddGlobalBlacklist(String groupId, String wxid) {
    List bl = getGlobalBlacklist();
    if (!bl.contains(wxid)) { bl.add(wxid); setGlobalBlacklist(bl); }
    sendText(groupId, "🌐 已加入全局黑名单: " + wxid + "\n(任何已启用群进入即踢)");
}

void doDelGlobalBlacklist(String groupId, String wxid) {
    List bl = getGlobalBlacklist();
    if (bl.remove(wxid)) { setGlobalBlacklist(bl); sendText(groupId, "✅ 已从全局黑名单移除: " + wxid); }
    else sendText(groupId, "❌ 该 wxid 不在全局黑名单");
}

void showGlobalBlacklist(String groupId) {
    List bl = getGlobalBlacklist();
    if (bl.isEmpty()) { sendText(groupId, "🌐 全局黑名单为空"); return; }
    StringBuilder sb = new StringBuilder();
    sb.append("🌐 全局黑名单 (").append(bl.size()).append("):\n");
    for (int i = 0; i < bl.size(); i++) {
        sb.append("· ").append(bl.get(i)).append("\n");
    }
    sendText(groupId, sb.toString());
}

// ---- 全局白名单 ----
void doAddGlobalWhitelist(String groupId, String wxid) {
    List wt = getGlobalWhitelist();
    if (!wt.contains(wxid)) { wt.add(wxid); setGlobalWhitelist(wt); }
    sendText(groupId, "🌐 已加入全局白名单: " + wxid + "\n(任何已启用群免警告/免进群检测)");
}

void doDelGlobalWhitelist(String groupId, String wxid) {
    List wt = getGlobalWhitelist();
    if (wt.remove(wxid)) { setGlobalWhitelist(wt); sendText(groupId, "✅ 已从全局白名单移除: " + wxid); }
    else sendText(groupId, "❌ 该 wxid 不在全局白名单");
}

void showGlobalWhitelist(String groupId) {
    List wt = getGlobalWhitelist();
    if (wt.isEmpty()) { sendText(groupId, "🌐 全局白名单为空"); return; }
    StringBuilder sb = new StringBuilder();
    sb.append("🌐 全局白名单 (").append(wt.size()).append("):\n");
    for (int i = 0; i < wt.size(); i++) {
        sb.append("· ").append(wt.get(i)).append("\n");
    }
    sendText(groupId, sb.toString());
}

// ---- 严格模式 ----
void doStrictMode(String groupId, String sender, String content) {
    if (!isOwner(sender)) { sendText(groupId, "❌ 此命令仅群主可用"); return; }
    String[] tk = content.split("[\\s\\u2005\\u00A0]+");
    if (tk.length < 2) {
        sendText(groupId, "用法:\n严格模式 开 — 仅群主能用命令\n严格模式 关 — 管理员+ 都能用\n严格模式 状态\n当前: " + (isStrictMode() ? "✅ 开启" : "🔓 关闭"));
        return;
    }
    String arg = tk[1];
    if (arg.equals("开") || arg.equals("on") || arg.equals("启用")) {
        setStrictMode(true);
        sendText(groupId, "✅ 严格模式已开启 (全局生效, 仅群主能用群管命令)");
    } else if (arg.equals("关") || arg.equals("off") || arg.equals("禁用")) {
        setStrictMode(false);
        sendText(groupId, "✅ 严格模式已关闭 (管理员+ 都能用)");
    } else if (arg.equals("状态")) {
        sendText(groupId, "严格模式: " + (isStrictMode() ? "✅ 开启 (仅群主)" : "🔓 关闭 (管理员+)"));
    } else {
        sendText(groupId, "❌ 用法: 严格模式 开/关/状态");
    }
}

// ---- 白名单 GUI 子 Dialog ----
void showWhitelistDialog(String groupId) {
    Activity ctx = getTopActivity();
    if (ctx == null) return;
    final String fGroupId = groupId;
    final AlertDialog[] dh = new AlertDialog[1];

    List wt = getWhitelist(groupId);
    int dp = (int) ctx.getResources().getDisplayMetrics().density;

    android.widget.LinearLayout root = new android.widget.LinearLayout(ctx);
    root.setOrientation(android.widget.LinearLayout.VERTICAL);
    root.setPadding(16 * dp, 16 * dp, 16 * dp, 16 * dp);
    root.setBackground(_gaRound("#0F1115", 0));

    android.widget.TextView title = new android.widget.TextView(ctx);
    title.setText("✅ 白名单 (" + wt.size() + " 人)");
    title.setTextColor(_gaC("#E8E9EC"));
    title.setTextSize(16);
    title.setPadding(0, 0, 0, 6 * dp);
    root.addView(title);

    android.widget.TextView hint = new android.widget.TextView(ctx);
    hint.setText("白名单成员免警告 + 免进群黑名单检测");
    hint.setTextColor(_gaC("#7C828E"));
    hint.setTextSize(11);
    hint.setPadding(0, 0, 0, 12 * dp);
    root.addView(hint);

    if (wt.isEmpty()) {
        android.widget.TextView empty = new android.widget.TextView(ctx);
        empty.setText("本群无白名单");
        empty.setTextColor(_gaC("#7C828E"));
        empty.setTextSize(13);
        empty.setPadding(0, 12 * dp, 0, 12 * dp);
        root.addView(empty);
    } else {
        for (int i = 0; i < wt.size(); i++) {
            final String wxid = (String) wt.get(i);
            android.widget.LinearLayout row = _gaRow(ctx, lookupName(wxid, fGroupId), wxid, "移除", "#7C828E");
            android.view.View act = row.getChildAt(1);
            act.setOnClickListener(new android.view.View.OnClickListener() {
                public void onClick(android.view.View v) {
                    List l = getWhitelist(fGroupId);
                    l.remove(wxid);
                    setWhitelist(fGroupId, l);
                    toast("已移除");
                    if (dh[0] != null) dh[0].dismiss();
                    showWhitelistDialog(fGroupId);
                }
            });
            root.addView(row);
            _gaSpacer(root, ctx, 6 * dp);
        }
    }

    _gaSpacer(root, ctx, 8 * dp);
    android.widget.Button back = _gaBtn(ctx, "← 返回", "#252934");
    back.setOnClickListener(new android.view.View.OnClickListener() {
        public void onClick(android.view.View v) {
            if (dh[0] != null) dh[0].dismiss();
            showGroupConfigDialog(fGroupId);
        }
    });
    root.addView(back);

    android.widget.ScrollView sv = new android.widget.ScrollView(ctx);
    sv.addView(root);
    sv.setBackground(_gaRound("#0F1115", 0));
    dh[0] = new AlertDialog.Builder(ctx).setView(sv).create();
    dh[0].show();
}

// ============================================================
// ==== v1.13: 微信原生群主/管理员手动登记 (保护免被踢/拉黑/警告)
// ============================================================
void doRegisterNativeOwner(String groupId, String target) {
    List l = getNativeOwners(groupId);
    if (!l.contains(target)) { l.add(target); putString("nat_owner_" + groupId, joinCsv(l)); }
    sendText(groupId, "👑 已登记为微信群主: " + lookupName(target, groupId) + "\n(任何人不能再对其执行踢/拉黑/警告)");
}

void doRemoveNativeOwner(String groupId, String target) {
    List l = getNativeOwners(groupId);
    if (l.remove(target)) {
        putString("nat_owner_" + groupId, joinCsv(l));
        sendText(groupId, "✅ 已取消微信群主登记: " + lookupName(target, groupId));
    } else sendText(groupId, "❌ 该用户未登记为微信群主");
}
