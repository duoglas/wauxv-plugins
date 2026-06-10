---
name: waux-plugin-root-deploy-pitfalls
description: 用 adb/root 部署新 WAuxiliary 插件(新建 Plugin 子目录)的三个坑：SELinux 类别 / FUSE 缓存 / 命名空间重载
metadata: 
  node_type: memory
  type: reference
  originSessionId: d18bfa18-f91c-4706-9795-3c0962e5d144
---

用 `adb`+`su` 给真机新建一个 WAuxiliary 插件目录(`WAuxiliary/Plugin/<新名>/`)部署时,2026-06-11 灰度 GroupAdminPlus 连踩三坑,每个都只在真机暴露:

1. **root `mkdir` 的目录缺 app 的 SELinux 类别 → app 建 db 报 `SQLITE_CANTOPEN ... check directory permissions`**。
   `Android/media/com.tencent.mm/...` 下的文件需带微信沙箱类别 `u:object_r:media_rw_data_file:s0:c24,c257,c512,c768`;
   root 建出来的是裸 `...:s0`(无类别)。DAC 权限(属主/770)对了也没用,是 MAC(MLS)拦的。
   **修:`chcon -R <兄弟插件目录的完整 context> <新目录>`** 对齐(取一个 app 自建的老插件如 GroupAdmin 的 `ls -Zd`)。

2. **root 经 `/data/media/0/...` 删/改文件会污染 FUSE/MediaProvider 对该路径的 dentry 缓存 → app 经 `/storage/emulated/0/...` 重建同名文件失败**(即便 context 对了)。
   现象:第一次 app 自建 db 成功;root 删掉后 app 再也建不出同名 db。
   **规避:别用 root 删 app 要自建的文件。若已污染,预置一个合法空 sqlite(`sqlite3 x.db "VACUUM;"` 造出 "SQLite format 3" 头)放到位 + chown u0_a280:media_rw + chmod 660 + chcon 对齐 → openOrCreateDatabase 走"打开已存在"而非"创建"路径**(打开已存在不受该坑影响)。

3. **WAuxiliary 插件的 BeanShell 命名空间只在微信进程被杀后才重新解析 main.java**。
   插件列表里 toggle 开关、甚至"重启微信"(非强制停)都不重新解析旧命名空间 → 改了 main.java 不生效。
   **判据:看 plugin.log 里 `BlockNameSpace<N>` 的数字——数字没变=没重载。**
   **强制重载:`adb shell su -c 'am force-stop com.tencent.mm'` 杀进程,再手动打开微信** → 全新命名空间、onLoad 重跑。

部署落位范式(对齐兄弟插件): push 到 /sdcard 中转 → `su cp` 到目标 → `chown u0_a280:media_rw` + `chmod 660`(文件)/`2770`(目录,带 setgid) + `chcon <app context>`。
插件目录最小需要 `main.java` + `info.prop`(name/author/version/updateTime);config.prop/db/log 运行时自建。

关联:[[beanshell-parser-stricter-than-javac]] [[concat-port-needs-callee-closure-scan]] —— 同属"解析门+单测双盲区、只真机暴露"的逃逸类(装配/部署侧)。C-ARCH-05/VH-05 记录了配套的代码侧装配 bug(端口 global. 作用域 + 建表索引顺序 + 丢 import)。
