# 真实标注集评测

> 当前 `docs/benchmark-report.md` 的结论建立在**合成压力数据集**上：它证明了 RRF 对量纲变化的鲁棒性与去冗余收益，但 **Recall@8 与时间覆盖率持平（1.000）**，没有证明召回上限提升。要回答「检索策略在真实内容上是否更好」，需要一份小的真实标注集。

## 1. 最小可行数据集

不需要大规模标注。建议先做：

| 项 | 建议 |
| --- | --- |
| 视频数 | 1–2 个（公开课 / 会议 / 操作录屏均可，总时长 30 分钟–2 小时） |
| query 数 | 20–30 条，每条对应一个能在视频里定位到时间点的问题 |
| 标注粒度 | query → 相关时间戳区间（`startMs`–`endMs`），一条 query 允许多个区间 |
| 数据来源 | 自己录的、或公开可引用视频；**不要把版权受限内容提交到公开仓库** |

## 2. 数据格式

文件放到 `server/src/main/resources/evaluation/real-annotations.json`，结构见
[`real-annotations.example.json`](../server/src/main/resources/evaluation/real-annotations.example.json)：

```json
{
  "version": 1,
  "sets": [
    {
      "name": "公开课第 3 讲",
      "source": "https://example.com/lecture-3",
      "goal": "总结本节算法步骤",
      "segments": [
        {
          "startMs": 120000,
          "endMs": 180000,
          "transcript": "前序遍历先访问根节点，然后递归访问左子树，最后访问右子树。",
          "ocrTexts": ["前序遍历：根节点、左子树、右子树"]
        }
      ],
      "queries": [
        {
          "id": "q1",
          "text": "前序遍历的访问顺序是什么",
          "relevant": [{ "startMs": 120000, "endMs": 180000 }]
        }
      ]
    }
  ]
}
```

字段说明：

| 字段 | 含义 |
| --- | --- |
| `segments` | 视频的 ASR/OCR 片段，与系统内部 `VideoContext.VideoSegment` 对齐；`ocrTexts` 可为空（纯语音视频） |
| `queries[].text` | 用户会问的自然语言问题 |
| `queries[].relevant` | 人工标注的相关时间戳区间；允许多个 |

`segments` 有两条采集路径：

1. **手工整理**：从视频字幕 / 转写 / 自己的笔记整理成 JSON，OCR 可留空；
2. **从系统导出**：分析任务完成后，`VideoContext` 已存在 Checkpoint 里。用管理员账号调用
   `GET /admin/media/{id}/context`（需要 `role=ADMIN` 的用户 token）即可拿到完整的
   `source / userGoal / segments` JSON；把 `segments` 复制进标注文件，再补 `queries` 即可。
   接口返回的 `evidenceFrames` 等额外字段会被解析器忽略，不需要手工删。

## 3. 运行评测

```bash
# 1) 把标注文件放到 resources 下（不要提交到公开仓库）
cp server/src/main/resources/evaluation/real-annotations.example.json \
   server/src/main/resources/evaluation/real-annotations.json
# 2) 编辑成你的真实数据
# 3) 跑评测（文件不存在时测试自动跳过，CI 不受影响）
cd server && bash ./mvnw -Dtest=RealAnnotationEvaluationTest test
```

输出是一张按 query 展开的表：RRF Recall@8、线性基线 Recall@8、时间覆盖率、冗余率，以及汇总平均值。

## 4. 评测边界（必须如实说明）

- **语义通道是代理**：没有在线 embedding 服务时，用字符 bigram Jaccard 近似语义相似度；词法/视觉通道用 query bigram 覆盖率近似。它验证的是「同一份真实文本上两种排序策略的差异」，**不是**线上 Qdrant + Embedding 的真实召回率。
- **样本小**：20–30 条 query 只能给出方向性信号，不能当作统计显著的业务收益。
- **标注主观**：不同人对「相关」的边界判断不同，建议标注时写下判断依据，必要时双人标注。
- 要验证线上效果，需要用真实后端跑 `scripts/smoke-test.sh` 或在线 A/B，而不是这份离线评测。

## 5. 数据与隐私

- `real-annotations.json` 默认不进入公开仓库（见 `.gitignore`），避免版权与隐私问题；
- 示例文件 `real-annotations.example.json` 是合成的，只用于演示格式；
- 如果数据来自公开视频，建议在 `source` 里保留可追溯链接，并在报告里注明来源与许可。
