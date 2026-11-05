/**
 * demoData.js — built-in synthetic SceneMind data for SCENEMIND_DEMO=1.
 *
 * This module NEVER touches the network. It mirrors the shapes returned by the
 * real REST endpoints (Result envelope payloads / DTOs), so every tool and
 * resource can be exercised offline. It also contains one audio-visual
 * conflict example ("8月1日" / "8月3日") consistent with
 * docs/benchmark-report.md.
 *
 * NOTE: the structured `conflicts` / `evidenceAudit` fields here are
 * illustrative synthetic data showing the shape the platform MAY expose.
 * The current Java backend surfaces these as aggregate signals through
 * /analysis/agent-evaluation (conflictFlagged, evidenceSupportRate, ...),
 * not as a dedicated per-item audit object.
 */

export const DEMO_MEDIA_ID = 1001;

/** Backend default goal (AnalysisController /analysis/ai default). */
export const DEFAULT_GOAL = '理解视频核心内容并生成结构化分析报告';

const DEMO_REPORT = [
  '## 发布计划音画冲突核查',
  '',
  '## 核心结论',
  '- 视频口播称新功能于「8月1日」上线，但画面 PPT 标注为「8月3日」，存在音画不一致。',
  '- 课程主体讲解了迭代遍历与显式栈的使用，以及前序遍历顺序。',
  '- 建议以书面材料为准，并在对外发布前核实确切的上线日期。',
  '',
  '## 视频证据',
  '- [08:42] ASR：新功能将于八月一日正式上线，敬请期待。',
  '- [08:45] OCR：上线时间：8月3日',
  '- [10:27] ASR+OCR：迭代遍历使用显式栈保存待访问节点，画面展示了前序遍历顺序。',
  '- [23:10] OCR：前序遍历：根节点、左子树、右子树',
  '',
  '## 建议',
  '- 针对 8月1日 / 8月3日 的冲突，向发布方确认最终日期后再对外引用。',
  '- 所有结论均附带时间戳与来源，可回到原片对应位置复核。',
  '',
  '## 待核验事项',
  '- 音画冲突：语音「8月1日」/ 画面「8月3日」，平台已显式标注而非替用户猜测。',
].join('\n');

function mediaSummary(url) {
  const name = typeof url === 'string' && url.length
    ? url.split(/[?#]/)[0].split('/').filter(Boolean).pop() || 'demo-video.mp4'
    : 'demo-video.mp4';
  return {
    id: DEMO_MEDIA_ID,
    filename: name,
    status: 'READY',
    coverUrl: '',
    uploadTime: '2026-01-01T00:00:00',
  };
}

/** POST /media/upload-url -> Result<MediaSummary>.data */
export function submitUrl(url) {
  return mediaSummary(url);
}

/** POST /analysis/ai -> 202 Accepted (Result<Void>). */
export function analyze() {
  return { status: 202, accepted: true, alreadyCompleted: false };
}

/** GET /analysis/analysis-status -> Result<TaskStatus>.data (always completed). */
export function status() {
  return {
    state: 'COMPLETED',
    result: DEMO_REPORT,
    message: '任务完成（demo 合成数据）',
  };
}

/** GET /analysis/agent-evaluation -> Result<Map>.data (aggregate signals). */
export function evaluation() {
  return {
    structuredValid: true,
    timestampCoverageRate: 1.0,
    evidenceSupportRate: 0.92,
    claimEvidenceSupportRate: 0.9,
    criticPassed: false, // a conflict was flagged, so Critic did not fully pass
    evidenceRedundancyRate: 0.0,
    temporalSpread: 0.75,
    conflictFlagged: true,
    userAcceptanceRate: 0.0,
    feedbackSamples: 0,
    // Illustrative structured additions (see module header).
    conflicts: [
      {
        startMs: 522000,
        asrValue: '8月1日',
        ocrValue: '8月3日',
        note: '口播「8月1日上线」与画面「8月3日」不一致',
      },
    ],
    evidenceAudit: {
      source: 'SceneMind EvidenceAuditor (server-side, demo synthetic)',
      summary: { total: 4, verified: 3, flagged: 1 },
      items: [
        { timestampMs: 522000, source: 'ASR', verdict: 'SUPPORTED', reason: 'NONE' },
        {
          timestampMs: 525000,
          source: 'OCR',
          verdict: 'FLAGGED',
          reason: 'AUDIO_VISUAL_CONFLICT',
          detail: '语音「8月1日」/ 画面「8月3日」',
        },
        { timestampMs: 627000, source: 'ASR+OCR', verdict: 'SUPPORTED', reason: 'NONE' },
        { timestampMs: 1390000, source: 'OCR', verdict: 'SUPPORTED', reason: 'NONE' },
      ],
    },
  };
}

/** GET /analysis/agent-trace -> Result<Map>.data (retrieval diagnostics). */
export function trace() {
  return {
    traceId: 'demo-trace-0001',
    taskId: DEMO_MEDIA_ID,
    goalDigest: 'demo',
    startedAt: '2026-01-01T00:00:00Z',
    stageDurationMs: { retrieval: 120, planner: 240, executor: 980, critic: 210 },
    counters: { modelCalls: 4, inputTokensEstimated: 3200, outputTokensEstimated: 1100, suppressedNearDuplicates: 4 },
    values: { rrfK: 60, coveredWindows: 8 },
    estimatedCost: 0.0042,
  };
}

/** GET /analysis/evidence-search -> Result<List<VideoEvidenceHit>>.data */
export function evidenceSearch(query) {
  return [
    {
      startMs: 522000,
      endMs: 582000,
      source: 'ASR+OCR',
      snippet: `与「${query}」相关：口播称 8月1日上线，画面 PPT 却标注 8月3日。`,
      transcript: '新功能将于八月一日正式上线，敬请期待。',
      ocrTexts: ['上线时间：8月3日'],
    },
    {
      startMs: 627000,
      endMs: 687000,
      source: 'ASR+OCR',
      snippet: '迭代遍历使用显式栈保存待访问节点，画面展示了前序遍历顺序。',
      transcript: '迭代遍历使用显式栈保存待访问节点。',
      ocrTexts: ['前序遍历：根节点、左子树、右子树'],
    },
  ];
}

/** POST /analysis/follow-up -> Result<String>.data */
export function followUp(question) {
  return [
    `关于「${question}」：根据带时间戳的证据，口播提到「8月1日上线」，`,
    '但 [08:45] 画面 OCR 明确标注「上线时间：8月3日」，两者存在音画冲突。',
    '平台不做猜测，建议以书面/OCR 材料为准并人工核实。',
    '相关证据时间戳：08:42（ASR）、08:45（OCR）。',
  ].join('');
}
