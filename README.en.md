# DevContext

[中文](./README.md) | English | [日本語](./README.ja-JP.md)

DevContext is a local AI development assistant for individual developers. Import a codebase, and DevContext helps generate AI-readable project context, build a searchable local knowledge base, review Git changes, and preserve review feedback, engineering decisions, and agent traces as reusable development context.

It is not just another chat box. It is built for a practical pain point:

> When you ask AI to understand a project, review code, explain a design, or reuse an old decision, you should not have to paste the same background, paths, diffs, and notes every time.

## Who It Is For

DevContext is useful if:

- You often use AI while coding, but repeatedly explain the same project context.
- You want to turn a new local repository into AI-friendly documentation.
- You want an AI review before committing current changes, the latest commit, or a branch diff.
- You have local design docs, testing notes, troubleshooting records, and project assets that should be queryable.
- You want important engineering decisions to be remembered and recalled later.

DevContext is currently designed as a personal local tool. It is not yet a team permission system or enterprise audit platform.

## What You Can Do

### 1. Import A Project And Generate AI Context

After you select a local project, DevContext scans code, configuration, documents, and Git information, then generates AI-friendly context assets:

- `AGENTS.md`
- `.ai/AI_README.md`
- `.ai/code-map.json`
- `.ai/generated/project-structure.md`
- `.ai/generated/tech-architecture.md`
- `.ai/generated/dev-guide.md`
- `.ai/generated/core-flows.md`

These files help AI understand what the project is, how it runs, where important modules live, and which files matter before you ask detailed questions.

### 2. Ask The Local Knowledge Base

You can index project docs, `.ai` assets, SQL files, configuration, testing notes, observability records, and other local engineering evidence. Then ask questions such as:

- “How is caching implemented?”
- “Which SQL indexes are used?”
- “How is the flash-sale flow monitored?”
- “Where is the deployment configuration?”
- “What is the core business flow of this project?”

DevContext answers with source citations and retrieved context snippets so you can judge whether the answer is grounded.

### 3. Run AI Code Review

DevContext can review:

- current working-tree changes, including staged and unstaged files.
- the latest commit.
- a branch comparison.
- a manually pasted diff.

The result is a structured issue list with:

- severity
- file path and line number
- problem description
- impact
- suggested fix
- confidence
- feedback status

You can mark issues as accepted, false positive, fixed, or rejected. Feedback is saved for later review and quality improvement.

### 4. Save Engineering Decisions

When you make an important technical choice, such as pagination, caching, idempotency, or message compensation, you can save it as a Decision Card:

- scenario
- options
- decision
- reasons
- trade-offs
- applicable conditions
- non-applicable conditions
- evidence

When a similar problem appears later, DevContext can recall related decisions and ask AI to judge what can be reused, what must be adapted, and what risks remain.

### 5. Inspect Agent Traces

Every AI task is recorded as an AgentRun with AgentEvents. You can inspect:

- which diff was collected.
- which context was loaded.
- how large the prompt was.
- which model was called.
- whether the response was parsed successfully.
- which results were saved.

This makes AI output easier to debug and explain.

## Quick Start

The easiest way to try DevContext is Docker Compose:

```bash
docker compose up -d --build
```

Then open:

- Frontend: http://localhost:5173
- Backend: http://localhost:18080
- Qdrant: http://localhost:6333

In Docker mode, the backend runs inside a container and can only access project directories mounted into that container. The default mount is:

```text
./workspace/projects -> /workspace/projects
```

For a first run, put the project you want to analyze under `workspace/projects/`, then import it with a container path such as:

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

The default Docker setup uses the `mock` model, so you can verify project import, context generation, knowledge indexing, and the UI flow before configuring a real LLM.

### Switch LLM In Docker Mode

Docker Compose reads the `.env` file at the repository root. To switch models, you do not need to start the backend locally. Edit `.env`, then restart the backend container.

For DeepSeek:

```properties
DEVCONTEXT_LLM_PROVIDER=deepseek
DEEPSEEK_API_KEY=<your-api-key>
DEEPSEEK_MODEL=deepseek-chat
```

Then run:

```bash
docker compose up -d backend
```

If you changed image build inputs such as `Dockerfile`, `pom.xml`, or frontend dependencies, run:

```bash
docker compose up -d --build
```

For `.env` changes such as model provider, API key, timeout, or vector provider, a rebuild is usually not required.

## First Run

1. Import a local project.
2. Open the project workspace and refresh context assets.
3. Add generated `.ai` assets to the knowledge base and index them.
4. Ask a project question in Knowledge Q&A.
5. Open Code Review and review current changes or the latest commit.

## Health Check

```http
GET http://localhost:18080/api/health
```

## Use A Real Model

The recommended setup is to copy `.env.example` to `.env` and edit that file:

```powershell
Copy-Item .env.example .env
```

Docker Compose reads this configuration. After changing the model provider, run `docker compose up -d backend` to restart the backend container.

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

API keys, local databases, runtime logs, and private generated context files are not committed to Git.

## Vector Store

The default local JDBC vector store works out of the box. You can also switch it in `.env`:

```properties
DEVCONTEXT_VECTOR_PROVIDER=jdbc
```

To use Qdrant:

```properties
DEVCONTEXT_VECTOR_PROVIDER=qdrant
QDRANT_BASE_URL=http://localhost:6333
```

## Useful Commands

Backend tests:

```bash
mvn test
```

Frontend build:

```bash
npm.cmd run frontend:build
```

Code Review benchmark:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\devcontext-code-review-benchmark.ps1 -TimeoutSeconds 150 -RetryCount 1
```

Knowledge benchmark:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\devcontext-knowledge-rag-benchmark.ps1
```

Decision recall benchmark:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\devcontext-recall-quality-benchmark.ps1
```

## Current Status

DevContext currently has a usable local loop:

- project import and context generation.
- local knowledge retrieval and cited answers.
- AI Code Review.
- Review Issue feedback.
- Decision Cards and basic decision recall.
- AgentRun tracing.
- React frontend for the main workflows, currently in Alpha.

Still improving:

- frontend history and interaction details.
- context confidence scoring.
- finer-grained code localization and on-demand lookup.
- stronger no-answer behavior when evidence is missing.
- larger real-project evaluations.

## What Not To Expect Yet

DevContext does not automatically edit your code, commit to Git, or claim complete IDE-level semantic understanding. Its purpose is:

> Help individual developers organize project facts, code changes, local knowledge, and engineering decisions into an AI-ready context workspace.
