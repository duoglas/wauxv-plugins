#!/usr/bin/env node
'use strict';

/**
 * Session End Hook — session 结束时的收尾动作
 * @version 0.8.1
 * 触发: SessionEnd lifecycle event
 *
 * 职责:
 * 1. 归档当前 observations.jsonl 到 .harness/observations.archive/observations-<sid8>-<ts>.jsonl
 *    —— 不等 10MB 阈值，session 结束就归档，避免跨 session 混淆
 * 2. 在 session-log.md 写入结束标记（含 source: clear/resume/logout/prompt_input_exit/other）
 * 3. 透传 stdin 到 stdout，不阻止退出
 *
 * 不做:
 * - 自动触发 harness-learn 分析（可能慢，阻塞退出；改由 --periodic 或手动触发）
 *
 * observability-only, 不能 exit 2。
 * 设计目标: <100ms 执行时间，零 fork。
 */

const fs = require('fs');
const path = require('path');
const findRoot = require('./find-root');
const ROOT = findRoot();

const LOG_FILE = path.join(ROOT, '.harness/session-log.md');
const OBS_FILE = path.join(ROOT, '.harness/observations.jsonl');
const ARCHIVE_DIR = path.join(ROOT, '.harness/observations.archive');
const MAX_STDIN = 1024 * 1024;

let raw = '';
process.stdin.setEncoding('utf8');
process.stdin.on('data', chunk => {
  if (raw.length < MAX_STDIN) raw += chunk.substring(0, MAX_STDIN - raw.length);
});

process.stdin.on('end', () => {
  // 完全关闭
  if (process.env.HARNESS_LOG === 'off') {
    return;
  }

  try {
    const input = JSON.parse(raw);

    // 防御：只处理 SessionEnd 事件（避免被其他调用误触发）
    if (input.hook_event_name !== 'SessionEnd') {
      return;
    }

    const source = input.source || 'other';
    const sessionId = input.session_id || 'unknown';
    const now = new Date();
    const timeStr = now.toLocaleTimeString('zh-CN', { hour12: false, hour: '2-digit', minute: '2-digit' });

    // ── 1. 归档 observations.jsonl ──
    // 无视大小，session 结束就归档，避免跨 session 混淆
    if (fs.existsSync(OBS_FILE)) {
      try {
        if (!fs.existsSync(ARCHIVE_DIR)) fs.mkdirSync(ARCHIVE_DIR, { recursive: true });
        const sidShort = String(sessionId).substring(0, 8);
        const ts = now.toISOString().replace(/[:.]/g, '-');
        const archivePath = path.join(ARCHIVE_DIR, `observations-${sidShort}-${ts}.jsonl`);
        fs.renameSync(OBS_FILE, archivePath);
      } catch {}
    }

    // ── 2. 写 session-log 结束标记 ──
    try {
      const dir = path.dirname(LOG_FILE);
      if (!fs.existsSync(dir)) fs.mkdirSync(dir, { recursive: true });
      const entry = `### [${timeStr}] Session 结束\n- **source**: ${source}\n- **session**: ${sessionId}\n\n`;
      if (!fs.existsSync(LOG_FILE)) {
        fs.writeFileSync(LOG_FILE,
          '# Harness Session Log\n\n## 元信息\n' +
          `- 开始时间: ${now.toISOString().slice(0, 16)}\n` +
          '- 工具: Claude Code\n\n---\n\n## 事件记录\n\n');
      }
      fs.appendFileSync(LOG_FILE, entry);
    } catch {}
  } catch {}
  // stdout 保持为空（Codex 0.118.0 兼容，见 VH-13）
});
