# PLAN — P1-a：membership 领域进骨架（GA+RP 合并工程）

> 接 P0（commit 8c3e89c）。本次做 P1 第一刀：membership（权限/管理员/群主/原生群主）领域。纯本地、不动线上插件 → 零真机风险。

## 线上源码对照（已核实）
- `canActOn` @ plugins/group-admin/main.java:1851 — 依赖 getLoginWxid()/isOwner/isAdminInGroup/isNativeOwner
- `isOwner` @1729 → getLoginWxid()+getOwners()
- `isAdminInGroup` @1739 → isOwner+getAdmins(gid)(`admins_<gid>`)
- `isNativeOwner` @785 → getNativeOwners(gid)+_detectNativeOwner(gid)(反射, L4-only)
- 语义保真：canActOn 不查 admin_exp 时效（线上靠命令入口惰性清, 热路径不碰）→ 骨架照搬不引入。

## 任务（每个 ≤15min）
- T1 HostPort 测试 fake（内存 KV + 可编程 getLoginWxid + 原生群主 detectNativeOwner）。
- T2 `merged/src/domain/membership.bsh`（getOwners/getAdmins/getNativeOwners + isOwner/isAdminInGroup/isNativeOwner/canActOn，只依赖 HOST 端口，键名保真）+ `merged/src/core/host_android.bsh` 生产骨架。
- T3 L1 单测 `merged/tests/membership_unit.bsh`：canActOn 真值表 9 例。
- T4 加入 manifest.prod.txt + `./check.sh` 全绿。

## 验收
本地 check.sh 全绿（build+parse+L1+L2）；无真机动作。
