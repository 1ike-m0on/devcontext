# DevContext

[中文](./README.md) | English | [日本語](./README.ja-JP.md)

DevContext is a local AI development workspace for code repositories. It helps individual developers organize project files, source evidence, knowledge-base answers, code reviews, engineering decisions, and AI traces into reusable and verifiable project context.

It is not just another chat box. DevContext is built for a more practical problem:

> When AI answers a codebase question, it should know which source files matter, which evidence must not be used, where citations come from, and when the available evidence is not enough.

## Who It Is For

DevContext is useful if:

- You often use AI to understand projects, but repeatedly explain the same modules, paths, configuration, and decisions.
- You want to turn a local repository into an indexed, queryable project workspace.
- You want AI answers to be grounded in source evidence instead of broad documentation guesses.
- You want an AI review before committing current changes, the latest commit, or a branch diff.
- You want to preserve engineering decisions, review feedback, and AI execution traces for later reuse.

The current version is designed as a personal local tool. It is not an enterprise permission system, team audit platform, or automated code-fixing robot.

## Core Capabilities

### 1. Project Import And Knowledge Indexing

After you create or select a local project, DevContext builds a knowledge index for that project. The index reads code, configuration, SQL, tests, documents, and generated `.ai` assets so later questions can retrieve project facts.

Indexing is project-scoped. Refresh the index when the project changes.

### 2. Source-Grounded Knowledge RAG

When you ask a question, DevContext automatically prepares the context for that specific query:

1. Understand the query intent.
2. Decide whether the answer needs source code, tests, configuration, SQL, deployment files, runtime records, or other evidence.
3. Select primary evidence from the project structure and knowledge index.
4. Filter generated docs, unrelated documents, noisy tests, and forbidden sources.
5. Generate an answer from the selected evidence.
6. Return a Markdown answer, source paths, citations, and expandable trace details.

You do not need to click a separate "generate context" button before asking. Context generation happens during each question.

### 3. AI Code Review

DevContext can review:

- current working-tree changes.
- the latest commit.
- the current branch compared with the default branch.
- a manually pasted diff.

Review output is stored as structured issues with severity, file path, description, impact, suggested fix, and confidence. You can mark issues as accepted, false positive, fixed, or rejected. Feedback is saved for later review and quality improvement.

### 4. Engineering Decision Memory

You can save important technical choices as Decision Cards, such as caching strategy, idempotency design, pagination, or message compensation. A card can include the scenario, options, final decision, reasons, trade-offs, applicable conditions, and supporting evidence.

When a similar problem appears later, DevContext can retrieve related decisions and help decide what can be reused and what must be adapted.

### 5. Traces And Explainability

DevContext records AgentRun, AgentEvent, RetrievalRecord, and evidence evaluation data. You can inspect which evidence was used, which source paths were retrieved, which model was called, and whether the no-answer guard was triggered.

## Recommended Workflow

1. Open the DevContext frontend.
2. Create or select a project.
3. Select the project path.
4. Build or refresh the knowledge index.
5. Ask a question in Knowledge Q&A.
6. Read the Markdown answer, source paths, and citations.
7. Expand trace / evidence details when you need more context.

Context generation happens in step 5 as part of answering the question.

## Quick Start

The easiest way to run the full local stack is Docker Compose:

```bash
docker compose up -d --build
```

Then open:

- Frontend: http://localhost:5173
- Backend API: http://localhost:18080
- Qdrant: http://localhost:6333

In Docker mode, the backend runs inside a container and can only access project directories mounted into that container. The default mount is:

```text
./workspace/projects -> /workspace/projects
```

For a first run, place the project you want to analyze under `workspace/projects/`, then import it with a container path:

```text
/workspace/projects/my-project
```

To use another host directory:

```powershell
$env:DEVCONTEXT_PROJECTS_DIR="C:\Users\you\Documents\projects"
docker compose up -d --build
```

Then import projects with:

```text
/workspace/projects/your-project
```

## Model Configuration

The default Docker setup uses the `mock` model, which is useful for verifying project import, indexing, frontend flow, and baseline features.

To use a real model, copy `.env.example`:

```powershell
Copy-Item .env.example .env
```

Then configure a provider and API key.

DeepSeek example:

```properties
DEVCONTEXT_LLM_PROVIDER=deepseek
DEEPSEEK_API_KEY=<your-api-key>
DEEPSEEK_MODEL=deepseek-chat
DEEPSEEK_TIMEOUT=120s
```

Gemini example:

```properties
DEVCONTEXT_LLM_PROVIDER=gemini
GEMINI_API_KEY=<your-api-key>
GEMINI_MODEL=gemini-2.0-flash
```

After changing `.env`, restarting the backend is usually enough:

```bash
docker compose up -d backend
```

If you changed `Dockerfile`, `pom.xml`, or frontend dependencies, rebuild:

```bash
docker compose up -d --build
```

API keys, local databases, runtime logs, and private generated context files are not committed to Git.

## Vector Store

Docker Compose uses Qdrant by default:

```properties
DEVCONTEXT_VECTOR_PROVIDER=qdrant
QDRANT_BASE_URL=http://qdrant:6333
```

For local development, you can also use the JDBC vector store:

```properties
DEVCONTEXT_VECTOR_PROVIDER=jdbc
```

The current version still keeps custom embedding/vector adapters. Spring AI integration for embedding, vector store, tool calling, and observability is planned as future infrastructure work.

## Useful Commands

Backend tests:

```bash
mvn test
```

Frontend build:

```bash
npm.cmd run frontend:build
```

Context quality gates:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\devcontext-context-benchmark.ps1 -Suite all
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\devcontext-context-benchmark.ps1 -Suite evidence-pack
```

Knowledge RAG acceptance:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\devcontext-knowledge-rag-benchmark.ps1
```

Code Review acceptance:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\devcontext-code-review-benchmark.ps1 -TimeoutSeconds 150 -RetryCount 1
```

## Current Acceptance Status

The current version has a usable local loop:

- project create, edit, delete, and path selection.
- knowledge indexing with visible status.
- source-grounded Knowledge RAG answers.
- Markdown answer rendering, source paths, citations, and trace details.
- AI Code Review and review feedback.
- Decision Cards and basic decision recall.
- AgentRun / RetrievalRecord tracing.
- React frontend for the main workflows.

Context and Knowledge RAG quality are covered by automated gates, including deterministic no-LLM context benchmarks, evidence-pack checks, HTTP benchmarks, and real LLM smoke/sample runs. The latest merged baseline passes the core context gates and Knowledge RAG acceptance.

## Boundaries And Limitations

DevContext is not:

- an autonomous code-editing agent.
- a tool that commits to Git or opens PRs automatically.
- a complete IDE-level semantic analyzer.
- a full ReAct agent framework.
- an enterprise permission, audit, or multi-user collaboration platform.
- a chatbot that must answer every question.

When evidence is insufficient, DevContext should say so instead of inventing an answer. Its purpose is:

> Help individual developers organize project facts, source evidence, local knowledge, code review results, and engineering decisions into an AI-ready local context workspace.
