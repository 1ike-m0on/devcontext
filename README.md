# DevContext

DevContext is a personal AI-native development context system.

It helps a developer turn project facts, engineering decisions, review history,
and development knowledge into reusable context for AI-assisted coding.

## Current Status

The project has completed the MVP0-MVP3 core loops.

Implemented:

- Spring Boot REST API on port `18080`
- Health check API: `GET /api/health`
- SQLite local storage under `data/devcontext.sqlite`
- Project registration and query APIs
- Pluggable `ContextProvider` / `ContextItem` foundation
- Project scanner and generated AI context assets
- Generated `AGENTS.md`, `.ai/AI_README.md`, `.ai/generated/*`, and `.ai/code-map.json`
- Manual `.ai/manual/*` templates that are preserved by default
- AI Code Review from Git diff or provided diff text
- Structured review report parsing and `.ai/reviews/review-{id}.md` output
- Review issue lifecycle tracking: `pending`, `accepted`, `rejected`, `false_positive`, `fixed`, `ignored`
- Decision Card creation and retrieval
- Decision Memory retrieval with tags and lightweight keyword matching
- Decision reuse advice generation through the configured LLM adapter
- Decision reuse records persisted with traceable run events
- `LlmClient` port with a mock adapter
- Traceable `AgentRun` / `AgentEvent` execution flow

## Core Loops

### 1. Project Context Assets

DevContext scans a local project and generates AI-friendly context files:

- `AGENTS.md`
- `.ai/AI_README.md`
- `.ai/code-map.json`
- `.ai/generated/*`
- `.ai/manual/*`

### 2. AI Code Review

DevContext can review a Git diff or provided `diffText`, inject project context,
call the configured LLM, parse the response, and persist review issues.

### 3. Decision Memory

DevContext stores engineering trade-offs as Decision Cards and recalls them when
a similar problem appears later. This is currently implemented with tags and
keyword matching; semantic vector retrieval is planned as a post-MVP enhancement.

## Local Development

```bash
mvn clean package
mvn spring-boot:run
```

Health check:

```http
GET http://localhost:18080/api/health
```

MVP smoke flow:

```http
POST http://localhost:18080/api/projects
GET  http://localhost:18080/api/projects
POST http://localhost:18080/api/projects/{projectId}/context/generate
GET  http://localhost:18080/api/projects/{projectId}/context
GET  http://localhost:18080/api/projects/{projectId}/context-items
POST http://localhost:18080/api/agent-runs/mock-llm
POST http://localhost:18080/api/projects/{projectId}/reviews
GET  http://localhost:18080/api/reviews/{reviewId}
PATCH http://localhost:18080/api/review-issues/{issueId}
GET  http://localhost:18080/api/reviews/{reviewId}/events
POST http://localhost:18080/api/decisions
GET  http://localhost:18080/api/decisions/{decisionId}
POST http://localhost:18080/api/decisions/search
POST http://localhost:18080/api/decisions/reuse-advice
GET  http://localhost:18080/api/agent-runs/{runId}/events
```

## LLM Configuration

The default provider is `mock`, which is enough to verify API wiring and event
tracing.

To call Gemini, start the backend with environment variables:

```powershell
$env:DEVCONTEXT_LLM_PROVIDER="gemini"
$env:GEMINI_API_KEY="<your-api-key>"
$env:GEMINI_MODEL="gemini-2.0-flash"
mvn spring-boot:run
```

API keys and local runtime data are intentionally ignored by Git.
