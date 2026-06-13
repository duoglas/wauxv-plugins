# 计划: LLM 对接(OpenAI 兼容) + 排行榜润色 — 2026-06-14

## 需求(用户确认)
- 给 GroupAdminPlus 加**可配置 LLM 客户端**(OpenAI 兼容: url/path/key/model/system)。
- 用 LLM **每次润色排行榜**(默认开, 有 key 就用)。
- 后续(不在本迭代): 群管智能回复。

## 已确认决策
- **默认配置 auto-seed**: 从兄弟插件「自动回复配置版」config.prop 读 `zhilia_ai_api_url/api_path/api_key/model_name` → 写进 GroupAdminPlus 自己的 `ga_llm_*`(仅设备运行时, key 留设备)。
- **润色默认开**(有 key 即用), LLM 失败/超时 → 回退朴素榜(永不卡出榜)。
- **隐私接受**(真昵称发外部 API, 与自动回复同源)。

## 安全红线 (C-SEC)
- **API key 绝不进 main.java / 不进 git / 不进任何日志**。只存设备 config.prop, 从兄弟插件 seed。Security grep 必须 0。

## 现状锚点
- 自动回复配置: url=`https://LLM_HOST_REDACTED/v1` path=`/chat/completions` model=`gpt-5.5`, 标准 OpenAI Bearer + messages, org.json 解析。
- org.json 是 Android 类(**bsh-2.0b6 测试 classpath 无**) → llm.bsh 的 HTTP+JSON 属设备缝(L4 真机验, 仿反射缝); 可测的是"润色失败回退"逻辑。

## 任务拆解 (每个 ≤15min)
- **T1 llm.bsh 客户端(设备缝)**: 配置键 `ga_llm_url/path/key/model/system/rank_on`; `llmEnabled()`(key 非空 + rank_on); `_llmHttpPost(url,key,body)`(HttpURLConnection POST, Bearer, 超时10s, 全 try/catch 返 null) ; `llmChat(system,user)`(org.json 建 messages + 解析 choices[0].message.content, 失败 null); `llmSeedDefaults()`(GA ga_llm_url 空时读兄弟 config.prop 拷键)。done: 解析过(去 final)。
- **T2 排行榜润色接线(rank_render)**: `rankShowLeaderboard` 后台线程内 build 朴素榜 → `llmEnabled()` 则 `rankLlmPolish(board)`(llmChat 润色, 返 null 回退朴素) → sendText。**润色逻辑(失败回退/禁用直发)抽成可测纯函数 + 假 llmChat 单测**。done: check.sh 含新测 GREEN。
- **T3 onLoad seed + manifest + 命令**: onLoad 调 llmSeedDefaults(独立 try/catch); manifest 登记 llm.bsh(rank_render 前); 命令 `榜单润色 开/关`(owner) 写 ga_llm_rank_on。done: 解析 + check.sh GREEN + callee 闭合。
- **T4 构建准出**: check.sh GREEN + 闭合扫描 + **Security grep 0(尤其 key/Bearer 不在源码)**。
- **T5 独立 code review (C-HARNESS-03, 不可跳过)**: 独立 reviewer 审 —— key 不入源码/不入日志、HTTP 超时+全 try/catch 不卡线程、润色失败回退、JSON 健壮、seed 跨插件读安全。网络外部依赖属 medium → 叠 Santa 双审(A 安全/降级 / B JSON/边界/回退)。done: 独立 review PASS + Santa NICE。
- **T6 真机准出**: 部署→seed 验证(GA config.prop 出现 ga_llm_* 非空)→测试群发「群龙榜」→看 LLM 润色版出榜 + 关 key/断网 → 回退朴素榜(降级实测)+ logcat 无异常 + 热路径不回归(润色全在后台)。done: 实测 PASS(含降级)。

## 风险 / 不可逆
- 外部网络调用(从真机插件)：超时/失败必须不卡出榜、不碰消息热路径。
- key 处理：seed 到设备 config.prop(可逆), 绝不入 git。
- 真机 force-stop 重载(标准)。

## 验收标准
- check.sh GREEN + 闭合 + **Security 0(key 不在源码/日志)**。
- 真机: seed 成功 + 群龙榜 LLM 润色出榜 + 降级(无 key/断网)回退朴素 + 热路径不回归。
- 独立 code review PASS + Santa 双审 NICE。
