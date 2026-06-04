#!/usr/bin/env node
'use strict';

/**
 * Safety Guard Hook — 拦截危险命令
 * @version 0.8.1
 * 触发: PreToolUse:Bash
 *
 * 根据项目需要添加/修改 BLOCKED 规则。
 */

const MAX_STDIN = 1024 * 1024;
let raw = '';
process.stdin.setEncoding('utf8');
process.stdin.on('data', chunk => {
  if (raw.length < MAX_STDIN) raw += chunk.substring(0, MAX_STDIN - raw.length);
});

process.stdin.on('end', () => {
  try {
    const input = JSON.parse(raw);
    const cmd = String(input.tool_input?.command || '');

    // === 根据项目定制：添加或修改拦截规则 ===
    const BLOCKED = [
      { pattern: /rm\s+-rf\s+[\/~]/, msg: '禁止删除根目录或 home 目录' },
      { pattern: /git\s+push\s+--force(?!-with-lease)\b/, msg: '禁止 force push，使用 --force-with-lease' },
      { pattern: /git\s+reset\s+--hard/, msg: '禁止 hard reset，请确认后手动执行' },
      { pattern: /--no-verify/, msg: '禁止跳过 git hooks' },
      { pattern: /DROP\s+(TABLE|DATABASE)/i, msg: '禁止直接 DROP，需要人工确认' },
      { pattern: /chmod\s+777/, msg: '禁止 chmod 777，使用最小权限' },
    ];

    for (const rule of BLOCKED) {
      if (rule.pattern.test(cmd)) {
        process.stderr.write(`[Safety Guard] ${rule.msg}\n命令: ${cmd}\n`);
        process.exit(2);
      }
    }
  } catch {}
  // stdout 保持为空：Codex 0.118.0 要求 PreToolUse hook stdout 为合法响应 schema 或空；
  // 原 passthrough (写回请求 JSON) 在 Codex 下会报 "invalid pre-tool-use JSON output"。
  // Claude Code 对空 stdout 同样视为 allow-unchanged，行为一致。
});
