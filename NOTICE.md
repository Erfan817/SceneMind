# Notice

## 依赖与来源

SceneMind 的基础设施层（分片上传、RocketMQ 异步链路、MySQL/Redis/MinIO 存储、鉴权、
Vue 3 工作台、浏览器扩展）基于一个 MIT 许可的开源长视频分析实现构建，原版权声明保留在
[LICENSE](LICENSE) 中。

在此之上，本项目重新设计并实现了以下部分（`server/src/main/java/com/scenemind/core/`、
相关 Spring 适配层、评测脚本、CI 门禁及 `docs/` 下的设计与评测文档）：

- 多通道 RRF 融合排序、时间窗覆盖与近重复抑制、自适应召回预算；
- 结构化证据校验、拒绝原因枚举、ASR/OCR 通道隔离、音画冲突检测；
- 确定性离线评测、指标体系、自检断言与回归门禁；
- 包名规范化、策略配置化、单元测试与架构决策记录。

## 第三方组件

运行时依赖的第三方组件（Spring Boot、Vue、RocketMQ、Qdrant、MinIO、FFmpeg、Tesseract、
LangChain4j 等）各自遵循其原有许可证，本项目未修改其源码。

## 评测数据说明

`docs/benchmark-report.md` 中的指标来自**可复现的合成压力数据集**，用于策略回归，
不代表线上真实流量或业务效果。生成方式与复现命令见该报告末尾。
