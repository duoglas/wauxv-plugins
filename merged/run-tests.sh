#!/usr/bin/env bash
# 本地测试 runner：跑 tests/*.bsh（bsh + sqlite-jdbc），查输出哨兵判定。
# bsh.Interpreter 脚本异常时仍 exit 0 → 不能只看退出码，靠 TEST_OK 哨兵 + 无 FAIL/异常。
set -u
cd "$(dirname "$0")"
CP="tools/bsh-2.0b6.jar:tools/sqlite-jdbc.jar"
fails=0; total=0
for t in tests/*.bsh; do
  base="$(basename "$t")"
  [ "$base" = "_harness.bsh" ] && continue
  total=$((total + 1))
  out="$(java -cp "$CP" bsh.Interpreter "$t" 2>&1)"
  if echo "$out" | grep -q "TEST_OK" && ! echo "$out" | grep -qE "TEST_FAIL|Script threw|Target exception|^FAIL "; then
    echo "[PASS] $base"
  else
    echo "[FAIL] $base"
    echo "$out" | sed 's/^/    /'
    fails=$((fails + 1))
  fi
done
echo "----"
echo "tests: $((total - fails))/$total passed"
[ "$fails" -eq 0 ]
