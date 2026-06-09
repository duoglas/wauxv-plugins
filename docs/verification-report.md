# VERIFICATION REPORT — RedPacketStats v1.11.4（伸手党升级缺口自愈）

日期: 2026-06-09 · 迭代: redpacket-freeloader-since-lazyinit-v1.11.4 · 风险: medium(破坏性路径, 但本修复偏保守)

## 根因(系统化调试 Phase 1, 日志实证)
伸手党 v1.11.2 测试时已 `伸手党 开`(10:00 rp.log 真发警告 warned=4/2)。v1.11.3 引入「开关打开时刻 since」闸门, since 仅在 OFF→ON 写。升级到 v1.11.3 时开关带「已开」状态、从未经历 OFF→ON → `since=0` → 判定段 `since<=0` 安全网整包跳过 → 伸手党静默失效。rp.log 实证:`12:52/12:54/13:08 freeloader skip: no since anchor`。`normGroupId=trim` 无 key 错配(已查 line 958)。**不是逻辑 bug, 是升级状态缺口。**

## 修复
判定线程 `since<=0` 分支:由「直接跳过」改为「**lazy-init 补写 `since=now` 再跳过**」——自愈, 免手动重切, 下一个包即按宽限/判定生效, 仍保守(补 since 后不当轮判定)。

## QA 结果

| 层 | 项 | 结果 | 证据 |
|---|---|---|---|
| L2 | bsh 解析 | PASS | `bsh.Parser` 去 final EXIT=0(两次:初版 + Santa 修复版) |
| L2 | 部署 | PASS | info.prop=1.11.4；设备 main.java 275335B；留底 main.java.bak-before-v1114-20260609 |
| L2 | 加载 | PASS | 重启后 rp.log `v1.11.4 loaded`(13:28)；无 eval/parse/TargetError |
| L2 | Security | PASS | 仅 putString 一次 since(毫秒数字)，无密钥/敏感日志新增 |
| L2 | Diff | PASS | 仅 RedPacketStats main.java/info.prop/SPEC.md |
| L4 Santa | A ∧ B | FIX→NICE | A(逻辑) FAIL→修;B(规格)PASS。共同指出 since 写 key 用裸 groupId、读用 normGroupId → 三处 since 写点(lazy-init/命令/Dialog)统一套 normGroupId, 写=读无条件一致, SPEC 声明改准 → 复验 PARSE_OK + 干净加载 |

### Santa 修复明细
A 置信 88 指出:`putString(K_FREELOADER_SINCE_PREFIX + 裸groupId)` vs `cfgFreeloaderSince` 读 `normGroupId(=trim)`。实际群 ID 无空格不触发(B 据此 PASS),但属结构性不一致 + SPEC 声明不准。修:三处 since 写点(main.java:1502/2700/3608)显式套 `normGroupId()`。

## 行为实测(T1 lazy-init)状态: PENDING-下一个真红包
lazy-init 需真红包触发 `hbDetailExtract`，adb 模拟不了。代码经 bsh 解析 + Santa 双审(A 确认:补 since 后本轮 `return` 跳过、绝不当轮 sendText；自愈链路 since>0→宽限→判定 正确；fTalker final)。**部署后那个测试群(开关仍开)的下一个拼手气包会自动 lazy-init**(rp.log 应出现 `no since anchor → lazy-init now ... skip this round`)，之后按宽限/判定生效。

### 快速测试配方(给用户)
1. 测试群发 `伸手党窗口 1`(窗口=1 分钟 → 宽限也=1 分钟，免等 30 分钟)。
2. 发/抢一个拼手气红包 → 这个包触发 lazy-init(写 since=now，本轮跳过)。
3. 等 ~1 分钟过宽限，再发一个拼手气包 → 抢前 1 分钟没发言的人 → 被警告。
4. 测完 `伸手党窗口 30` 调回。
（或先 `伸手党 关`→`伸手党 开` 直接写 since，再走 2-4。）

## Overall: READY(代码层)/ lazy-init 行为 PENDING-下一个真红包
回滚: 设备留底 `main.java.bak-before-v1114-20260609`。
