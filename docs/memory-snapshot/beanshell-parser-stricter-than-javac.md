---
name: beanshell-parser-stricter-than-javac
description: "WAuxiliary 的 BeanShell 解析器比 javac 严; 部署前用 bsh-2.0b6 离线全文解析(去 final)验语法, 别只靠 javac harness"
metadata: 
  node_type: memory
  type: feedback
  originSessionId: 3cbb9098-fbe3-4e37-8615-e1be36f085db
---

WAuxiliary 插件用 BeanShell 解释执行。BeanShell(bsh-2.0b6) 解析器比 javac **更严**：密集单行嵌套 `if (...) { if (...) {...} x++; continue; }` javac 能编过，但真机 bsh 报 `Encountered: 0 at line ...` 直接 load Failed。v1.6.1 第一次部署就因此挂掉。

**Why:** javac harness 通过 ≠ bsh 能解析。更要命的后果(2026-06-06 实测): **解析失败会被 WAuxiliary 自动「停用」该插件**——load Failed 后插件开关被关，之后即使把修好的文件重部署，插件仍是关闭状态不加载(启用开关独立于文件，修文件不会自动重新打开)。表现为 rp.log/logcat 自此零记录、发红包也不检测，极易误判成「懒加载/没触发」。**修复: 部署失败后必须去 WAuxiliary 插件列表手动把开关切回 ON。** 所以需要一个不依赖真机的语法预检，避免把坏文件推上去触发停用。

**How to apply:**
1. 写 BeanShell 时避免密集单行嵌套块, 拆多行 + 抽 helper。
2. 部署前离线全文验证: 下载 `org.apache-extras.beanshell:bsh:2.0b6`(maven central 路径 `org/apache-extras/beanshell/bsh/2.0b6/bsh-2.0b6.jar`; 注意 `org/beanshell/...` 那个路径是 404), 用 `bsh.Parser` 循环 `Line()` 解析整文件。
3. **坑**: bsh-2.0b6 独立 `Parser.Line()` 不认方法参数上的 `final`(报 `Encountered "final"`), 但真机 WAuxiliary 加载不受影响 → 这是 harness 误报。验证前先 `sed 's/(final /(/g; s/, final /, /g'` 去掉 final 再解析。PARSE OK 即语法对 bsh 合法。
4. 用"已知设备能加载的旧版本"做差分: 旧版同样 PARSE OK/同样在 final 处报错 → 证明 harness 行为基准, 排除误判。

相关 [[device-spike-anr-safety]](真机不可逆动作的谨慎)、[[redpacket-build-review-before-deploy]]。
