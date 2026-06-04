#!/usr/bin/env node
'use strict';

/**
 * stage-since-autofill.js — PostToolUse:Write hook
 * @version 0.8.7
 *
 * 当 AI 写入 `.harness/current-stage.json` 且 `since === "auto"` | `"now"` 时，
 * 立即用真实墙钟 ISO 覆写。这样 AI 不需要在 Write tool_input 里手抄 `date -u`
 * 的输出（VH-14 Option A 兑现）。
 *
 * 设计要点:
 *   - C-HOOK-01: 必须 < 50ms（就一次读 + 可能一次写）
 *   - C-HOOK-02: 不修改 stdout（守门人不干预，只副作用覆写文件）
 *   - 任何 non-match 路径快速 exit 0 静默放行（file_path 不是 current-stage.json / JSON 解析失败 / since 不是 sentinel 等）
 *   - 失败容错: 写文件失败也 exit 0，不阻塞下一个工具调用；stage-guard 下次读到 sentinel 会 reject，届时 AI 自行修复
 *
 * 约束: C-HOOK-09
 */

const fs = require('fs');
const path = require('path');
const findRoot = require('./find-root');

const SENTINELS = new Set(['auto', 'now']);

function readInput() {
  try {
    const raw = fs.readFileSync(0, 'utf8');
    return raw ? JSON.parse(raw) : null;
  } catch {
    return null;
  }
}

function main() {
  const input = readInput();
  if (!input) return;

  // 只处理 Write/Edit 事件（autofill 只针对 Write 目标；Edit 理论上也可能改 since，
  // 但最小必要实现先覆盖 Write）
  const toolName = input.tool_name;
  if (toolName !== 'Write') return;

  const filePath = input.tool_input && input.tool_input.file_path;
  if (!filePath) return;

  // 必须是 .harness/current-stage.json（允许绝对或相对路径）
  const basename = path.basename(filePath);
  const parentName = path.basename(path.dirname(filePath));
  if (basename !== 'current-stage.json') return;
  if (parentName !== '.harness') return;

  // 解析目标文件当前内容
  const root = findRoot();
  const absPath = path.isAbsolute(filePath) ? filePath : path.join(root, filePath);
  if (!fs.existsSync(absPath)) return;

  let content;
  try {
    content = fs.readFileSync(absPath, 'utf8');
  } catch {
    return;
  }

  let data;
  try {
    data = JSON.parse(content);
  } catch {
    return;
  }

  if (!data || !SENTINELS.has(data.since)) return;

  // 覆写 since 为真实 ISO
  const nowIso = new Date().toISOString();
  data.since = nowIso;

  try {
    fs.writeFileSync(absPath, JSON.stringify(data, null, 2) + '\n');
    process.stderr.write(
      `[Stage Since Autofill] 已将 since sentinel "${content.match(/"since"\s*:\s*"([^"]+)"/)?.[1] || ''}" 覆写为 ${nowIso} (C-HOOK-09)\n`
    );
  } catch {
    // 写失败就算了，下一个 PreToolUse 的 validateSince 会拒 sentinel 让 AI 重试
  }
}

main();
