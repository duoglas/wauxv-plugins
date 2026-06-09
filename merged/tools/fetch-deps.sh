#!/usr/bin/env bash
# 拉取本地测试/解析依赖(不进 git，跨机器靠 Dropbox 同步或此脚本)。
set -eu
cd "$(dirname "$0")"

# BeanShell 2.0b6：优先复用本机 gradle 自带，否则 maven 拉。
GRADLE_BSH="$HOME/.gradle/wrapper/dists/gradle-5.4.1-bin/e75iq110yv9r9wt1a6619x2xm/gradle-5.4.1/lib/plugins/bsh-2.0b6.jar"
if [ ! -f bsh-2.0b6.jar ]; then
  if [ -f "$GRADLE_BSH" ]; then cp "$GRADLE_BSH" bsh-2.0b6.jar
  else curl -fsSL -o bsh-2.0b6.jar "https://repo1.maven.org/maven2/org/apache-extras/beanshell/bsh/2.0b6/bsh-2.0b6.jar"; fi
fi

# sqlite-jdbc 3.36.0.3：自包含(3.43+ 硬依赖 slf4j，勿升)。
if [ ! -f sqlite-jdbc.jar ]; then
  curl -fsSL -o sqlite-jdbc.jar "https://repo1.maven.org/maven2/org/xerial/sqlite-jdbc/3.36.0.3/sqlite-jdbc-3.36.0.3.jar"
fi

echo "deps ready:"
ls -la bsh-2.0b6.jar sqlite-jdbc.jar
