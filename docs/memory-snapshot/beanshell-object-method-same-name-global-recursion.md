---
name: beanshell-object-method-same-name-global-recursion
description: "BeanShell 对象方法内裸调同名全局函数会解析到自身→无限自递归 StackOverflow, 须 global. 限定"
metadata: 
  node_type: memory
  type: reference
  originSessionId: d18bfa18-f91c-4706-9795-3c0962e5d144
---

BeanShell 里 `Object foo() { void bar(){...} return this; }` 这种对象作用域内定义的方法, **若方法体里裸调一个与本方法同名的 col-0 全局函数, 会优先解析到本对象的同名方法 → 无限自递归 → StackOverflow**(真机崩, 不是解析门能抓的: 解析门只查语法 EXIT=0)。

典型场景: 端口/适配器桥(makeAndroidLeaf 把 LEAF.showHelp/groupStatus/disableGroup 桥到同名全局 showHelp/groupStatus/disableGroup)。

**修复**: 转发调用一律加 `global.` 限定强制解析到全局命名空间的 col-0 函数: `global.showHelp(groupId)`。非同名转发(如 `kick→global.doKick`)加 global. 无害, 统一加最稳。

**漏一个的后果方向**: 该命令真机一调即崩(拒绝服务), 但不会错误执行其它动作 — 对踢人桥而言是"崩而不误踢", 方向安全, 但仍是必须修的 bug。

发现于 P3-b T8 W6 makeAndroidLeaf 收口(用 bsh-2.0b6 解释器复现), Santa 红线审专列一项逐个核 global. 齐全。关联 [[beanshell-parser-stricter-than-javac]](解析门只查语法不查此类运行时递归)、[[project-status]]。
