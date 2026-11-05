# SceneMind Chrome 扩展

把浏览器里的视频页一键提交给 SceneMind Video Agent 分析，在侧边栏看进度与结论。后端能力全部复用现有 Spring Boot 服务，扩展只是瘦客户端。

## 功能范围

- 登录（账号请在网页端 `http://localhost:5173` 注册）
- 一键抓取当前标签页的视频链接（B 站视频页、B 站短链 `b23.tv`、YouTube `watch`/`shorts`/`live`/`embed`、`youtu.be`），识别不了时手动粘贴兜底（任何 http/https 链接，后端 yt-dlp 决定是否支持）
- **本地视频分片上传**：5MB 分片 + 进度条，支持 MP4 / MOV / MKV / AVI / WEBM / M4V
- 分析目标 + 模式（`AUTO` 先调 `/analysis/route` 让 AI 识别意图，失败回退通用模式）
- SSE 实时阶段进度（解析语音与画面 → 检索证据 → 拆解任务 → 生成结果 → 核验），阶段以时间线呈现四态
- 结果结构化渲染：结论/证据/建议等章节按卡片排版，「结果提示」单独做成警示条；**支持表格与嵌套列表**
- **结果追问**：分析完成后可以基于同一视频继续提问，答案追加在结果下方
- 结果里的 `[00:30]` 时间戳可点击，跳转当前标签页到视频对应位置（支持 B 站完整视频页与 YouTube；`b23.tv` 短链会先尝试展开，展开失败时只提交分析、不跳转）
- 401 自动回到登录态；SSE 断线指数退避重连最多 3 次

已知限制：本地文件分析没有可跳转的视频页面（时间戳按钮会禁用）；`b23.tv` 展开依赖网络可达性。

## 加载扩展

1. 使用 Chrome 114 或更高版本，打开 `chrome://extensions`，右上角开启「开发者模式」
2. 点「加载已解压的扩展程序」，选择本 `extension/` 目录
3. 确保后端已启动（`./scripts/dev-up.sh` 后 `server/` 里 `./mvnw spring-boot:run`，健康检查 `curl http://localhost:9090/health`）
4. 在 B 站/YouTube 视频页点工具栏里的 SceneMind 图标，侧边栏会自动预填链接

## 配置

| 需求 | 改哪里 |
| :--- | :--- |
| 后端地址 | `lib/config.js` 的 `API_BASE`（默认 `http://127.0.0.1:9090`，改完在 `chrome://extensions` 点「重新加载」） |
| 新增站点识别 | `lib/urlParser.js` |

`host_permissions` 已放行 `http://127.0.0.1:9090/*`，扩展页面请求直接绕过 CORS，后端无需改动。后端换端口/域名时同步更新 manifest.json 的 `host_permissions`。

## 目录结构

```
extension/
├── manifest.json       # MV3：tabs/sidePanel/storage 权限，无 content script
├── background.js       # 仅注册「点图标打开侧边栏」
├── sidepanel.html/css  # 侧边栏 UI（登录 / 提交 / 进度三态）
├── sidepanel.js        # 主逻辑（api 信封移植、提交链路、SSE 消费）
├── lib/
│   ├── config.js       # API_BASE 常量
│   ├── urlParser.js    # 站点识别（纯函数）
│   ├── sseParser.js    # SSE 增量分帧解析（纯函数）
│   ├── stageProgress.js # 后端 TaskStage 到五阶段时间线的映射
│   ├── markdown.js     # 安全 Markdown 解析（纯函数）
│   └── markdownView.js # 使用 DOM API 渲染结果
├── tests/              # node:test 单测
└── package.json        # {"type":"module"}，仅为让 node --test 跑 ESM
```

## 测试

```bash
cd extension
node --test
```

需要 Node 22+（与仓库前端要求一致）。

## 手动验收清单

- [ ] `node --test` 全绿（59 个用例）
- [ ] 未登录打开侧边栏 → 显示登录表单；错误密码 → 提示后端 message
- [ ] 登录成功 → 自动预填当前视频页链接；在非视频页点「抓取」→ 提示手动粘贴
- [ ] 提交后看到五个阶段依次点亮，COMPLETED 后展示结果文本，「返回」可再次提交
- [ ] 选择本地视频 → 上传进度按分片更新 → 上传完成后自动进入分析
- [ ] 分析完成后输入追问 → 答案追加在结果下方
- [ ] 结果中出现表格 / 嵌套列表 → 按结构渲染，不丢层级
- [ ] b23.tv 短链提交 → 能展开为完整视频页；展开失败时仍可提交
- [ ] 同一视频 + 同一目标重复提交 → 不报错，直接接管已有进度（409 路径）
- [ ] 停掉后端再提交 → 提示「无法连接后端服务」；重新启动后恢复
- [ ] 登录态过期（后端清 token）→ 自动回到登录表单
