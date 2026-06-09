#!/usr/bin/env bash
# 本地准出门禁：build → bsh 离线解析(装配体) → 本地单测+集成测试。全绿才进真机 VERIFY。
set -u
cd "$(dirname "$0")"

echo "== [1/3] build (拼接源模块 → out/main.java) =="
./build.sh || { echo "BUILD FAILED"; exit 1; }

echo "== [2/3] bsh 离线解析 (装配体去 final 全文解析) =="
CHK="$(mktemp /tmp/merged_chk.XXXXXX.java)"
perl -pe 's/\bfinal\b//g' out/main.java > "$CHK"
java -cp tools/bsh-2.0b6.jar bsh.Parser "$CHK"; PARSE=$?
rm -f "$CHK"
if [ "$PARSE" -ne 0 ]; then echo "PARSE FAILED (EXIT=$PARSE)"; exit 1; fi
echo "parse OK (EXIT=0)"

echo "== [3/3] 本地测试 (L1 单测 + L2 集成) =="
./run-tests.sh || { echo "TESTS FAILED"; exit 1; }

echo ""
echo "== check.sh GREEN =="
