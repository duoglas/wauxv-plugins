# 计划: AI 群聊点评 / 锐评 (内容向群聊总结) — 2026-06-14

## 需求
用户要类似友商的「AI 点评」: 大模型读群聊**内容**生成一段有梗的群聊总结/锐评(能点名说出谁刷屏/谁撕逼/谁发了啥), 要比友商更精彩。

## 核心认知 (决定架构)
- 友商样例能点名说**具体内容/原话** → **必须抓群聊文本内容**喂大模型。当前 `msg_event` **不存 content**(设计刻意, 隐私+体积)。
- 故需**新增短保留内容缓冲** `chat_recent(grp,wxid,ts,content)`(只存文本, 滚动保留如 48h, 自动清理) —— 即 spec §2.7 预留的"短保留原始表", 现在用上。
- **我们更精彩的点**: 内容锐评 + **现成排行榜数据**(话痨Top/水王/红包王…)结合 → 数据有据的点评(样例结尾也带了个 mini 榜)。

## 关键决策 (需用户确认)
1. **抓内容的隐私**: 群聊文本会发给外部 LLM(LLM_HOST_REDACTED), 比之前(昵称+数字)更进一步。你已接受昵称+数字, 内容是更大一步 —— 确认接受? 短保留(默认48h)+只文本+可关。
2. **风格/人设**: 锐评语气用**可配置 system prompt**(键 `ga_llm_recap_system`, 从兄弟插件 seed 或自定义, 同自动回复 persona)。**默认给"机智毒舌吐槽"人设(不点名人身攻击/不黄)**; 你想要样例那种更辣的, 自己改 prompt 即可(你的群你的 LLM)。
3. **触发**: 命令「群聊总结」/「锐评」(管理员) + 可选每日定时(如每晚) ; 出榜发本群。
4. **体量/成本**: 转录截断(最近 N 条 / 字符预算, 如最近300条或~6k字), 避免超大 LLM 调用; 长群只取窗口尾部。

## 任务拆解 (每个 ≤15min)
- **T1 domain/chat_recent**: 建表 `chat_recent(grp,wxid,ts,content)` + 索引(grp,ts); insert/batch; 查窗口最近N条(ORDER BY ts DESC LIMIT N 再正序); prune(DELETE WHERE ts < cutoff)。L1/L2 测(查询窗口/截断/prune)。
- **T2 内容采集(热路径)**: 仿 rank_collect —— 文本消息且(rank启用 && 点评开关 `ga_recap_on`)时, 把 (grp,sender,ts,content) 入内存缓冲 → 批量 flush 到 chat_recent; flush 时顺带 prune 过期(>保留窗口)。**热路径仍 O(1) 内存 append**(content 已在手), 整段 try/catch。**默认关**(灰度+隐私)。L1 测缓冲/flush/gate。
- **T3 aiRecap 生成(rank_render 旁/新 app/ai_recap.bsh)**: 取 chat_recent 窗口转录(wxid→昵称 + content, 截断到预算) + 拼排行榜摘要 → `llmChat(recapSystem, 转录+榜摘要)` → 失败回退"今日数据太少/生成失败"。全后台线程。可测: 转录构建(纯)+回退(桩 llmChat)。
- **T4 命令+配置+seed**: 命令「群聊总结」/「锐评」(管理员 _cmdAuthorized) → 后台 aiRecap 发本群; showRankConfig 加「AI点评 开/关」(ga_recap_on)+提示; ga_llm_recap_system 默认人设(onLoad seed, 已配不覆盖)。
- **T5 构建准出**: check.sh GREEN + 闭合 + **Security 0(key/content 不入源码/日志; 转录不写 plugin.log)**。
- **T6 独立 review (C-HARNESS-03) + Santa**: 内容采集挂热路径=medium → Santa 双审(A 热路径O(1)+隐私不泄漏+降级 / B SQL/prune/转录截断/回退/seed)。
- **T7 真机**: 部署→测试群开「AI点评」→发「群聊总结」→看锐评(内容+榜结合)+ 降级(无key/断网回退)+ chat_recent 滚动保留(prune 生效)+ 热路径不回归。

## 风险 / 红线
- **隐私**: 群聊内容外发 + 短暂落库。仅文本、短保留、可关、默认关、灰度。content/转录**绝不写 plugin.log**。key 仍只设备。
- **热路径**: 内容采集 O(1) 内存; 重活(转录/LLM)全后台。
- **体积**: chat_recent 滚动 prune(默认48h), flush 时清理; 看门狗后续。
- **内容风格**: 默认人设机智吐槽不人身攻击/不黄; 更辣由用户自配 prompt(其群其 LLM)。

## 验收
- check.sh GREEN + Security 0(无 key/content 泄漏)。
- 真机: 开点评→群聊总结出有据锐评(引用真实内容+榜) + 降级回退 + prune 生效 + 热路径不回归。
- 独立 review + Santa NICE。

## 新增需求 (2026-06-14 一并纳入)
- **N1 子配置菜单返回上级**: showRankConfig 等子页加「← 返回」按钮回上级(showMergedGroupConfig), 不只「关闭」。范围=合并 UI 新建的子页(showRankConfig 本轮先加; 排查其它新子页是否缺)。小改, 折进本 AI 点评的 T4 dialog 工作一并做。
- **N2 顶层菜单支持大模型修改配置**: showMergedHome 加「🤖 AI 配置助手」入口 → 用户自然语言描述要改的设置 → LLM 解析成**结构化意图(改哪个键=什么值)** → **对照白名单校验**(只允许改预定义可改项: 群管/红包/排行榜/点评开关 + 阈值等, 绝不让 LLM 改任意键/执行任意动作) → 预览 + 确认后写入。**单独较大功能**, AI 点评之后单列迭代(需: 可改配置白名单 + LLM 结构化输出 schema + 解析校验 + 确认 UI + 独立 review+Santa, 防 LLM 误改/注入)。

## 后续(用户确认也要做, 本计划后)
拍一拍/转账/熬夜冠军 metric + rollup物化 + 体积看门狗 + 文档同步。
