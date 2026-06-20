# DevContext

中文 | [English](./README.en.md) | [日本語](./README.ja-JP.md)

DevContext 是一个面向本地代码仓库的 AI 研发工作台。它帮助个人开发者把项目文件、源码证据、知识库问答、代码审查、工程决策和 AI 运行追踪组织成一套可复用、可验证的上下文系统。

它的重点不是再做一个聊天框，而是解决代码项目里更常见的问题：

> AI 回答代码问题时，应该知道该看哪些源文件、哪些证据不能用、答案引用来自哪里，以及证据不足时应该拒答。

## 适合谁

DevContext 适合这些场景：

- 你经常用 AI 理解项目，但不想每次重新解释目录、模块、配置和历史决策。
- 你希望把一个本地仓库整理成可索引、可提问的知识工作台。
- 你想让 AI 基于真实源码证据回答项目问题，而不是只根据泛泛文档推断。
- 你想在提交前审查当前改动、最近提交或分支差异。
- 你想保存工程决策、Review 反馈和 AI 运行过程，之后可以继续复盘。

当前版本更适合作为个人本地开发工具，不是企业级权限系统、团队审计平台或自动修复机器人。

## 核心能力

### 1. 项目导入与知识库索引

在首页选择或导入一个本地项目后，DevContext 会围绕这个项目建立知识库索引。索引会读取项目中的代码、配置、SQL、测试、文档和生成的 `.ai` 资产，让后续问答可以检索项目事实。

索引是项目级操作。项目内容发生变化后，需要刷新索引。

### 2. Source-Grounded Knowledge RAG

用户提问时，DevContext 会自动完成本次问题的上下文选择：

1. 理解问题意图。
2. 判断需要源码、测试、配置、SQL、部署、运行记录还是其他证据。
3. 从知识库和项目结构中选择 primary evidence。
4. 过滤 generated docs、无关文档、测试噪声或 forbidden source。
5. 基于 selected evidence 生成回答。
6. 返回 Markdown 答案、source paths、citations 和可展开 trace。

也就是说，用户不需要先手动“生成上下文”。上下文是每次提问时自动生成的。

### 3. 代码审查

DevContext 支持几种常见 Review 入口：

- 当前工作区改动。
- 最近一次提交。
- 当前分支相对默认分支的差异。
- 手动粘贴 diff。

审查结果会以结构化 Issue 展示，包括严重级别、文件路径、说明、影响、修改建议和置信度。你可以对问题做 accepted、false positive、fixed、rejected 等反馈，后续用于复盘和质量改进。

### 4. 工程决策记忆

你可以把重要技术选择保存成 Decision Card，例如缓存策略、幂等方案、分页策略、消息补偿方案等。每条决策可以记录场景、候选方案、最终选择、理由、权衡、适用条件和证据。

后续遇到类似问题时，DevContext 可以检索相关决策，帮助判断哪些经验可以复用，哪些地方需要调整。

### 5. 运行追踪与可解释性

DevContext 会记录 AI 任务的 AgentRun、AgentEvent、RetrievalRecord 和 evidence evaluation。你可以查看一次回答或审查背后使用了哪些证据、检索到哪些 source paths、调用了哪个模型，以及是否触发了 no-answer guard。

## 推荐使用流程

1. 打开 DevContext 前端。
2. 创建或选择项目。
3. 选择项目路径。
4. 建立或刷新知识库索引。
5. 在知识库页面提问。
6. 阅读 Markdown 答案、source paths 和引用。
7. 如需更多细节，展开 trace / evidence details。

上下文生成发生在第 5 步提问过程中，不需要作为单独准备步骤执行。

## 快速启动

推荐用 Docker Compose 启动完整本地环境：

```bash
docker compose up -d --build
```

启动后访问：

- 前端：http://localhost:5173
- 后端 API：http://localhost:18080
- Qdrant：http://localhost:6333

Docker 模式下，后端运行在容器内，只能访问挂载进容器的项目目录。默认挂载是：

```text
./workspace/projects -> /workspace/projects
```

第一次体验时，可以把要分析的项目放到仓库下的 `workspace/projects/`，然后在 DevContext 中选择容器内路径：

```text
/workspace/projects/my-project
```

如果要使用其他宿主机目录，可以指定：

```powershell
$env:DEVCONTEXT_PROJECTS_DIR="C:\Users\you\Documents\projects"
docker compose up -d --build
```

然后在 DevContext 中选择：

```text
/workspace/projects/your-project
```

## 模型配置

默认 Docker 配置使用 `mock` 模型，适合先验证项目导入、索引、前端流程和基础功能。

如果要使用真实模型，可以复制 `.env.example`：

```powershell
Copy-Item .env.example .env
```

然后配置 provider 和 API Key。

DeepSeek 示例：

```properties
DEVCONTEXT_LLM_PROVIDER=deepseek
DEEPSEEK_API_KEY=<your-api-key>
DEEPSEEK_MODEL=deepseek-chat
DEEPSEEK_TIMEOUT=120s
```

Gemini 示例：

```properties
DEVCONTEXT_LLM_PROVIDER=gemini
GEMINI_API_KEY=<your-api-key>
GEMINI_MODEL=gemini-2.0-flash
```

修改 `.env` 后通常只需要重启后端：

```bash
docker compose up -d backend
```

如果修改了 `Dockerfile`、`pom.xml` 或前端依赖，再执行：

```bash
docker compose up -d --build
```

API Key、本地数据库、运行日志和生成的私有上下文不会提交到 Git。

## 向量存储

Docker Compose 默认使用 Qdrant：

```properties
DEVCONTEXT_VECTOR_PROVIDER=qdrant
QDRANT_BASE_URL=http://qdrant:6333
```

本地开发也可以使用 JDBC 向量存储：

```properties
DEVCONTEXT_VECTOR_PROVIDER=jdbc
```

当前版本仍保留自研 embedding/vector 适配，Spring AI 的 embedding、vector store、tool calling 和 observability 接入属于后续演进方向。

## 常用命令

后端测试：

```bash
mvn test
```

前端构建：

```bash
npm.cmd run frontend:build
```

上下文质量门禁：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\devcontext-context-benchmark.ps1 -Suite all
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\devcontext-context-benchmark.ps1 -Suite evidence-pack
```

知识库验收：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\devcontext-knowledge-rag-benchmark.ps1
```

代码审查验收：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\devcontext-code-review-benchmark.ps1 -TimeoutSeconds 150 -RetryCount 1
```

## 当前可验收状态

当前版本已经具备一条可用的本地闭环：

- 项目创建、编辑、删除和路径选择。
- 知识库索引和刷新状态展示。
- Source-grounded Knowledge RAG 问答。
- Markdown 答案渲染、source paths、citations 和 trace 展示。
- AI Code Review 和 Review feedback。
- Decision Card 和基础决策召回。
- AgentRun / RetrievalRecord 追踪。
- React 前端覆盖主要工作流。

上下文和知识库质量已经有自动化门禁覆盖，包括 no-LLM context benchmark、evidence-pack、HTTP benchmark 和真实 LLM smoke/sample。最新合并基线中，核心 context gates 和 Knowledge RAG acceptance 已通过。

## 边界与限制

请不要把 DevContext 理解为：

- 自动修改代码的 Agent。
- 自动提交或自动开 PR 的工具。
- 完整 IDE 级语义分析器。
- 完整 ReAct Agent 框架。
- 企业级权限、审计和多用户协作平台。
- 对任意问题都必须回答的聊天机器人。

当证据不足时，DevContext 应该倾向于说明证据不足，而不是编造答案。它的定位是：

> 帮个人开发者把项目事实、源码证据、知识库、代码审查和历史决策组织成 AI 可以持续使用的本地上下文工作台。
