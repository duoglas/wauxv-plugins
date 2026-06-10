---
name: concat-port-needs-callee-closure-scan
description: "拼接式/逐字移植后必跑全量\"调用→col-0定义\"闭合扫描; 解析门(只查语法)+单测(无UI覆盖)抓不到漏移植孤儿"
metadata: 
  node_type: memory
  type: feedback
  originSessionId: d18bfa18-f91c-4706-9795-3c0962e5d144
---

逐字移植/模块拼接(merged 工程)里,**被调用但 col-0 无定义的函数会双重逃逸到运行期**:bsh 解析门 `check.sh` 只查语法(EXIT=0 不解析符号引用),单测又往往不覆盖 UI/dialog 路径 → 漏移植的 helper(如 GA dialog 的 `_gaC`/`_gaRound`,被调 37/25 处却全模块无定义)一路绿灯到真机崩(NoSuchMethod)。

**Why**: 两道本地门禁都对"未定义符号调用"盲。语法过 ≠ 符号闭合。dialog/UI 类代码尤其无测试覆盖。

**How to apply**: 移植/收口后,除解析门+单测外,**必跑全量 callee 闭合扫描**——把装配体里所有函数调用名 grep 出来,逐一确认要么 col-0 有定义、要么是明确标注的真机 seam(占位+P2/P3-c注释)。意外孤儿(本应已移植却漏)= CRITICAL。佐证手法:看对称的另一半是否已定义(RP 的 `_rpC`/`_rpRound` 在、GA 独缺 → 系遗漏非有意 seam)。这正是 T8 W8"整合体最终审"必须独立做的事(发现于 2026-06-10)。

关联 [[beanshell-parser-stricter-than-javac]](解析门只查语法)、[[beanshell-object-method-same-name-global-recursion]](另一类解析门抓不到的运行期坑)、[[project-status]]。
