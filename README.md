# DevContext

中文 | [English](./README.en.md) | [日本語](./README.ja-JP.md)

DevContext 是一个面向个人开发者的本地 AI 研发助手。你把一个代码仓库导入进来，它会生成 AI 可读的项目上下文，建立可检索的本地知识库，帮你审查 Git 改动，并把代码审查、工程决策和 AI 运行过程沉淀成可复用资产。

它解决的不是“再做一个聊天框”，而是一个更实际的问题：

> 每次让 AI 帮你看项目、查代码、做 Review、解释历史方案时，都不想重新贴一堆背景、路径、diff 和文档。

## 适合谁用

DevContext 适合下面这些场景：

- 你经常用 AI 辅助开发，但每次都要重复解释项目结构和业务背景。
- 你希望把一个新项目快速整理成 AI 能理解的上下文。
- 你想在提交前让 AI 检查当前改动、最近提交或两个分支之间的 diff。
- 你有本地设计文档、测试记录、排查笔记，希望能像问知识库一样查询。
- 你希望把重要技术决策记录下来，以后遇到相似问题时能被召回。

DevContext 目前更适合作为个人本地工具使用，还不是团队级权限系统或企业审计平台。

## 你可以用它做什么

### 1. 导入项目并生成 AI 上下文

选择一个本地项目后，DevContext 会扫描代码、配置、文档和 Git 信息，生成一组 AI 友好的上下文资产：

- `AGENTS.md`
- `.ai/AI_README.md`
- `.ai/code-map.json`
- `.ai/generated/project-structure.md`
- `.ai/generated/tech-architecture.md`
- `.ai/generated/dev-guide.md`
- `.ai/generated/core-flows.md`

这些文件的目标是让 AI 先理解“这个项目是什么、怎么运行、有哪些核心模块、代码大概在哪里”，减少你后续提问时的解释成本。

### 2. 查询本地知识库

你可以把项目文档、`.ai` 资产、SQL、配置、测试记录、监控记录等内容加入知识库，然后直接问：

- “缓存具体是怎么做的？”
- “SQL 里用了哪些索引？”
- “秒杀链路怎么监控？”
- “部署配置在哪里？”
- “这个项目的核心流程是什么？”

DevContext 会返回带来源引用的答案，并展示命中的文档片段，方便你判断答案是否可信。

### 3. 做 AI Code Review

DevContext 支持几种常见审查入口：

- 审查当前工作区改动，包括 staged / unstaged。
- 审查最近一次提交。
- 审查两个分支之间的 diff。
- 手动粘贴 diff。

审查完成后，你可以看到结构化的问题列表，包括：

- 严重级别
- 文件路径和行号
- 问题说明
- 影响
- 修改建议
- 置信度
- 状态反馈

你可以把问题标记为“采纳”“误报”“已修复”或“拒绝”，这些反馈会被保存下来，后续用于复盘和质量改进。

### 4. 记录工程决策

当你做了一个重要技术选择，比如分页策略、缓存方案、幂等方案、消息补偿方案，可以把它保存成 Decision Card：

- 场景是什么
- 有哪些备选方案
- 最终选了什么
- 为什么这么选
- 有哪些权衡
- 什么情况下适用
- 什么情况下不适用
- 证据来自哪里

以后遇到类似问题时，DevContext 可以召回历史决策，并让 AI 判断“能不能复用、哪些部分要调整、风险在哪里”。

### 5. 查看 AI 行为追踪

每次 AI 任务都会记录 AgentRun 和 AgentEvent。你可以看到一次结果背后发生了什么：

- 读取了哪个 diff
- 加载了哪些上下文
- 构造了多大的 prompt
- 调用了哪个模型
- 模型输出是否成功解析
- 最终保存了哪些结果

这让 AI 输出不再是一个黑盒。

## 快速开始

推荐先用 Docker Compose 启动完整本地环境：

```bash
docker compose up -d --build
```

启动后访问：

- 前端：http://localhost:5173
- 后端：http://localhost:18080
- Qdrant：http://localhost:6333

Docker 模式下，后端运行在容器内，只能访问挂载进容器的项目目录。默认项目挂载目录是：

```text
./workspace/projects -> /workspace/projects
```

因此第一次体验时，可以把要分析的项目放到仓库下的 `workspace/projects/`，然后在 DevContext 中填写容器内路径，例如：

```text
/workspace/projects/my-project
```

如果你的项目在其他目录，可以通过环境变量指定挂载位置：

```powershell
$env:DEVCONTEXT_PROJECTS_DIR="C:\Users\you\Documents\projects"
docker compose up -d --build
```

然后在 DevContext 中使用：

```text
/workspace/projects/your-project
```

默认 Docker 配置使用 `mock` 模型，可以先验证导入项目、生成上下文、知识库索引和界面流程。

### Docker 模式切换 LLM

Docker Compose 会读取项目根目录的 `.env`。如果要切换模型，不需要本地启动后端，只需要修改 `.env`，然后重启后端容器。

例如切到 DeepSeek：

```properties
DEVCONTEXT_LLM_PROVIDER=deepseek
DEEPSEEK_API_KEY=<your-api-key>
DEEPSEEK_MODEL=deepseek-chat
```

然后执行：

```bash
docker compose up -d backend
```

如果你修改的是镜像构建相关文件，例如 `Dockerfile`、`pom.xml`、前端依赖，再执行：

```bash
docker compose up -d --build
```

只修改 `.env` 里的模型、API Key、超时时间、向量库 provider 时，通常不需要重新 build。

## 第一次使用

1. 导入一个本地项目。
2. 在项目工作台点击“全量刷新生成资产”。
3. 进入知识库，把生成的 `.ai` 资产加入知识源并索引。
4. 在知识库问一个项目问题。
5. 进入代码审查，选择“审查当前改动”或“审查最近提交”。

## 健康检查

```http
GET http://localhost:18080/api/health
```

LLM provider/model/key 状态和最近错误类型也可以单独查看：

```http
GET http://localhost:18080/api/settings/llm
```

这些状态接口只返回 API Key 是否已配置，不会返回明文 API Key。

## 使用真实模型

推荐把 `.env.example` 复制为 `.env`，然后只改 `.env`：

```powershell
Copy-Item .env.example .env
```

Docker Compose 会读取这份配置。切换模型后执行 `docker compose up -d backend` 重启后端容器即可。

本地 Maven 运行也可以使用被 Git 忽略的 `devcontext.local.yml` 或 `config/devcontext.local.yml` 覆盖模型配置，避免通过 PowerShell 临时环境变量切换。

### Gemini

```properties
DEVCONTEXT_LLM_PROVIDER=gemini
GEMINI_API_KEY=<your-api-key>
GEMINI_MODEL=gemini-2.0-flash
```

### DeepSeek

```properties
DEVCONTEXT_LLM_PROVIDER=deepseek
DEEPSEEK_API_KEY=<your-api-key>
DEEPSEEK_MODEL=deepseek-chat
DEEPSEEK_TIMEOUT=120s
```

API Key、本地数据库、运行日志和生成的项目私有上下文不会提交到 Git。

## 向量库

默认使用本地 JDBC 向量存储，开箱即可运行。也可以在 `.env` 中切换：

```properties
DEVCONTEXT_VECTOR_PROVIDER=jdbc
```

如果你想使用 Qdrant：

```properties
DEVCONTEXT_VECTOR_PROVIDER=qdrant
QDRANT_BASE_URL=http://localhost:6333
```

## 常用命令

后端测试：

```bash
mvn test
```

前端构建：

```bash
npm.cmd run frontend:build
```

Code Review 评估：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\devcontext-code-review-benchmark.ps1 -TimeoutSeconds 150 -RetryCount 1
```

知识库评估：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\devcontext-knowledge-rag-benchmark.ps1
```

决策召回评估：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\devcontext-recall-quality-benchmark.ps1
```

## 当前状态

DevContext 当前已经具备可用的本地闭环：

- 项目导入和上下文生成可用。
- 知识库检索和带引用问答可用。
- AI Code Review 主流程可用。
- Review Issue 状态反馈可用。
- Decision Card 和决策召回基础闭环可用。
- AgentRun 事件追踪可用。
- React 前端已经覆盖主要入口，但仍处于 Alpha 阶段。

仍在继续改进的部分：

- 前端交互和历史记录体验。
- 更稳定的上下文可信度评估。
- 更细粒度的代码定位和按需回溯。
- 更强的 no-answer 边界，避免知识不足时过度推断。
- 更大规模的真实项目评估。

## 不适合期待什么

DevContext 目前不会自动修改你的代码、自动提交 Git，也不宣称拥有 IDE 级完整语义理解。它的定位是：

> 帮个人开发者把项目事实、代码变更、知识库和历史决策组织成 AI 可以持续使用的上下文工作台。
