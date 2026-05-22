# DevContext

DevContext is a personal AI-native development context system.

It helps a developer turn project facts, engineering decisions, review history,
and development knowledge into reusable context for AI-assisted coding.

## Current Status

The project is at the initial engineering setup stage.

Implemented:

- Spring Boot application skeleton
- Health check API: `GET /api/health`
- Basic package boundaries for domain, application, ports, adapters, run, and LLM integrations
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
