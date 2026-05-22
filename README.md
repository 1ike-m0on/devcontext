# DevContext

DevContext is a personal AI-native development context system.

It helps a developer turn project facts, engineering decisions, review history,
and development knowledge into reusable context for AI-assisted coding.

## Current Status

The project has completed the MVP0 foundation branch.

Implemented:

- Spring Boot REST API on port `18080`
- Health check API: `GET /api/health`
- SQLite local storage under `data/devcontext.sqlite`
- Project registration and query APIs
- Pluggable `ContextProvider` / `ContextItem` foundation
- `LlmClient` port with a mock adapter
- Traceable `AgentRun` / `AgentEvent` execution flow
- Full planning documents under `docs/`

## Documentation

Start here:

- [Documentation Index](docs/README.md)
- [Master Plan](docs/00-master-plan.md)
- [Architecture Principles](docs/01-architecture-principles.md)
- [Git Workflow](docs/02-git-workflow.md)
- [Development Guide](docs/03-dev-guide.md)

## Local Development

```bash
mvn clean package
mvn spring-boot:run
```

Health check:

```http
GET http://localhost:18080/api/health
```

MVP0 smoke flow:

```http
POST http://localhost:18080/api/projects
GET  http://localhost:18080/api/projects
GET  http://localhost:18080/api/projects/{projectId}/context-items
POST http://localhost:18080/api/agent-runs/mock-llm
GET  http://localhost:18080/api/agent-runs/{runId}/events
```
