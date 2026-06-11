# 计划: 群管+红包 设置界面合并 + 全局开关 (UI 重建) — 2026-06-11

## 需求 (用户原话浓缩)
1. 群管 + 红包统计两个独立设置界面 → 合并成一个;输入「群管」开启。
2. 重新设计插件列表「设置」按钮的入口菜单,参考进退群版的「群列表 / 人员列表读取」。
3. 群管设置界面: 先选一个群 → 配置该群的两个功能(群管 + 红包)。
4. 最外层有统一功能开关,优先于单群能力生效。

## 已确认决策 (2026-06-11)
- 全局开关语义 = **一票否决**: 全局关→该功能所有群停用; 全局开→回到按单群判定。
- 全局开关粒度 = **群管、红包各一个** (`ga_global_on` / `rp_global_on`)。
- 群内打「群管」→ **直接进当前群合并配置** (跳过选群); 插件列表设置按钮 → 先弹选群。
- 升级安全: 两个全局键**空=开 (默认 ON)**, 避免老用户升级后所有群静默停用 ([[config-key-written-on-transition-upgrade-gap]])。

## 现状锚点
- 入口: `openSettings()`(设置按钮)→ 只开红包全局面板 `showConfigDialog(null)`; 群管仅群内打「群管」→ `showGroupConfigDialog(talker)`。
- 热路径闸门: GA=`isGroupEnabled()`(enabled_groups + ENABLED_CACHE 60s, enable_state.bsh); RP=`rpIsGroupEnabled()`(rp_enabled_groups + RP_ENABLED_CACHE 60s, redpacket_detect.bsh)。
- 群列表读取范式现成: `rpPickTarget`(getGroupList + sCachedGroupList 后台加载 → setItems 单选, dialogs.bsh:1623) = 「进退群版群列表读取」可复用。

## 设计原则
- **不重写两棵现有菜单树内部** (逐字保真保留): showGroupConfigDialog(GA 菜单) 与 showConfigDialog(RP 菜单) 原样留作各自「功能子页」, 只在其上加合并父页 + 选群 + 全局开关。
- 本任务是**有意偏离 P3 逐字保真** 的新功能 (UI 合并 + 全局门); 两个闸门函数的「红线逐字」注释改为标注"经审查的全局前置门"。
- 热路径全局门必须 **O(1) 走缓存** (C-PERF-01), 不得每条消息读盘。

## 任务拆解 (每个 ≤15min 可独立验证)
- **T1 GA 全局门**: enable_state.bsh 加 `gaGlobalOn()`(缓存读, 空=ON)+`setGaGlobal(bool)`(写+刷缓存); `isGroupEnabled` 顶部加 `if(!gaGlobalOn()) return false`。done: 离线解析过 + 缓存 O(1)。
- **T2 RP 全局门**: redpacket_detect.bsh 对称加 `rpGlobalOn()`/`setRpGlobal()`; `rpIsGroupEnabled` 顶部同款前置门。done: 同上。
- **T3 选群入口**: dialogs.bsh 加 `pickGroupForConfig()` (仿 rpPickTarget 群分支, 后台 getGroupList → 单选 → `showMergedGroupConfig(roomId)`)。done: 解析过。
- **T4 合并群配置页**: dialogs.bsh 加 `showMergedGroupConfig(groupId)`: 顶部显示该群 群管/红包 启用态 + 两个本群 enable 开关 + 两按钮 → 进各自现有菜单(showGroupConfigDialog / showConfigDialog)。done: 解析过。
- **T5 顶层 home + 入口重接**: dialogs.bsh 加 `showMergedHome()`(两个全局开关 toggle + 「选群配置」按钮→pickGroupForConfig + 「红包全局默认」按钮→showConfigDialog(null)); `openSettings()`→showMergedHome; `gaOnClickSendBtn` 的「群管」→showMergedGroupConfig(talker)。done: 解析过。
- **T6 构建+准出**: build.sh → check.sh GREEN (build + bsh 离线解析 + L1/L2 23/23 + 新增门若需单测)。
- **T7 真机准出 + Santa 双审 (热路径必做)**: 部署→force-stop 重载→实测: (a)全局关→该群 GA/RP 都不触发(热路径一票否决, 看 logcat/plugin.log/rp.log); (b)全局开→单群设置恢复; (c)设置按钮→选群→合并页两功能可达; (d)群内打「群管」直进当前群; (e)升级安全: 键空时默认走开。双独立 Reviewer。

## 风险 / 不可逆动作 (PLAN 列明)
- **热路径改动 (medium+)**: isGroupEnabled/rpIsGroupEnabled 是每条消息总闸门 → 必须保 O(1) 缓存, 必须 Santa 双审 + 真机实测 (qa-standards Layer 4)。
- **全局「一票否决」误关**: 关全局开关会让该功能所有群静默停 → 测试与交付说明须强调。
- **真机重载**: force-stop com.tencent.mm (标准动作, 已确认范式)。
- **逐字保真偏离**: 需在 enable_state/redpacket_detect 注释 + 视情况 constraints 记录这是经审查的新功能门, 非回归。

## 验收标准
- check.sh GREEN。
- 真机: 全局一票否决在热路径生效 + 选群入口 + 合并页两功能可达 + 群内直进 + 键空默认开 (升级安全) 全部实测 PASS。
- Santa 双审 NICE。
