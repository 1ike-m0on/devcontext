# DevContext

DevContext is a personal AI-native development context system.

It helps a developer turn project facts, engineering decisions, review history,
and development knowledge into reusable context for AI-assisted coding.

## Current Status

The project has completed the MVP2 AI Code Review branch.

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
- `LlmClient` port with a mock adapter
- Traceable `AgentRun` / `AgentEvent` execution flow

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
GET  http://localhost:18080/api/agent-runs/{runId}/events
```
