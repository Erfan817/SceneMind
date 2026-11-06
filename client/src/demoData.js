export const DEMO_ITEM = {
  id: 1001,
  filename: '数据结构课程 · 二叉树遍历.mp4',
  status: 'COMPLETED',
  uploadTime: '2026-07-10T14:30:00',
  transcriptText: '本节课介绍二叉树前序、中序和后序遍历，并对比递归与迭代实现。'
}

export const DEMO_PLAN = {
  understoodGoal: '理解视频核心内容，提炼关键结论，并给出带时间戳的证据和可执行建议',
  tasks: ['定位视频章节与核心主题', '融合 ASR 与关键帧 OCR 证据', '提炼结论并进行 Critic 校验']
}

export const DEMO_TRACE = {
  stageDurationMs: {
    VIDEO_CONTEXT: 12840,
    RETRIEVAL: 860,
    PLANNER: 1150,
    EXECUTOR: 3420,
    CRITIC: 1260
  }
}

export const DEMO_EVALUATION = {
  structuredValid: true,
  evidenceSupportRate: 0.92,
  criticPassed: true,
  // 新增评估信号：冗余率/覆盖度为 0~1 数值，冲突标记为布尔。
  evidenceRedundancyRate: 0.18,
  temporalSpread: 0.76,
  conflictFlagged: true
}

// 音画冲突：语音与画面在同一时间点给出不一致的发布日期。
export const DEMO_CONFLICTS = [
  {
    startMs: 522000,
    asrValue: '8月1日正式发布',
    ocrValue: '上线时间：8月3日',
    detail: '语音与课件对发布日期的表述不一致，建议以画面公告为准并复核。'
  }
]

// 证据核验摘要（与 TaskStatus.evidenceAudit 同构）。
export const DEMO_EVIDENCE_AUDIT = {
  total: 11,
  accepted: 8,
  retryableRejections: 2,
  hallucinationRejections: 1,
  notes: [
    '2 条证据缺少可核对时间戳，已退回重试',
    '1 条 OCR 文本与画面不符，疑似幻觉已剔除'
  ]
}

// 检索诊断（与 agent-trace 的 diagnostics 同构）：
// retrieval 给前端渲染预算/命中明细，evidence 作为 evidenceAudit 的兜底来源。
export const DEMO_DIAGNOSTICS = {
  retrieval: {
    topK: 8,
    dynamicTopK: 6,
    duplicatesSuppressed: 3,
    windowsCovered: 5,
    channelsUsed: ['ASR', 'OCR'],
    notes: [
      '跨通道融合后按 fusedScore 择优',
      '相邻时间窗的重复片段已合并'
    ],
    items: [
      { id: 'w04-asr-02', fusedScore: 0.91, selectionPass: 1, channelRanks: { ASR: 1, OCR: 2 }, matchedChannels: ['ASR', 'OCR'] },
      { id: 'w04-ocr-01', fusedScore: 0.87, selectionPass: 1, channelRanks: { ASR: 3, OCR: 1 }, matchedChannels: ['OCR'] },
      { id: 'w09-asr-01', fusedScore: 0.74, selectionPass: 2, channelRanks: { ASR: 2 }, matchedChannels: ['ASR'] }
    ]
  },
  evidence: {
    total: 11,
    accepted: 8,
    retryableRejections: 2,
    hallucinationRejections: 1,
    notes: ['证据审计与 TaskStatus.evidenceAudit 一致']
  },
  frames: {
    mode: 'dense',
    candidates: 70,
    kept: 22,
    suppressed: 48,
    maxFrames: 60,
    denseIntervalSeconds: 8
  },
  generatedAt: '2026-07-10T14:32:00'
}

export const DEMO_RESULT = `## 二叉树遍历课程分析

## 核心结论
- 前序遍历遵循“根节点、左子树、右子树”，适合构建树结构副本。
- 中序遍历二叉搜索树时可得到有序序列。
- 递归实现更直观，迭代实现通过栈显式保存访问状态。

## 视频证据
- [02:05] ASR：老师开始讲解前序遍历的访问顺序。
- [02:08] OCR：课件显示“根节点 → 左子树 → 右子树”。
- [08:42] ASR + OCR：对比递归调用栈与显式栈实现。

## 学习建议
- 用同一棵树手动推演三种遍历顺序。
- 分别实现递归版与迭代版，重点理解栈中保存的状态。`
