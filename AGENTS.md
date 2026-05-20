# Repository Guidelines

## Project Structure & Module Organization

- `src/main/java` and `src/main/resources` hold the primary Spring Boot backend; tests live in `src/test/java`.
- `frontend/src` contains the Vue 3 + TypeScript app, with `pages/`, `components/`, `api/`, `stores/`, and `utils/`.
- `go-backend/` and `python-backend/` are alternative backend ports; keep work scoped to the implementation you are changing.
- `sql/` stores schema and migration-style scripts. Root Docker files support full-stack local startup.
- `项目优化计划/` stores upgrade planning documents; keep numbered Chinese docs such as `00-总体升级方案.md`, `01-版本管理方案.md`, and `02-第一阶段实施方案.md` aligned with the current delivery stage.

## Build, Test, and Development Commands

- `mvn spring-boot:run` - start the main backend.
- `mvn test` - run the Java test suite.
- `cd frontend && npm install && npm run dev` - start the Vite frontend locally.
- `cd frontend && npm run build` - type-check and build the frontend.
- `cd frontend && npm run lint` / `npm run format` - apply ESLint and Prettier.
- `docker compose up -d --build` - boot the default full stack.
- Optional alternates: `cd go-backend && go run ./cmd/server`; `cd python-backend && uv sync && uv run uvicorn app.main:app --reload --host 0.0.0.0 --port 8123`.

## Coding Style & Naming Conventions

- Java uses 4-space indentation, `PascalCase` classes, `camelCase` methods, and suffixes like `*Controller`, `*Service`, and `*Mapper`.
- Vue and TypeScript follow Prettier + ESLint defaults; keep pages/components in `PascalCase` such as `HomePage.vue`.
- Python modules use `snake_case`; Go packages stay lowercase and should be formatted with `gofmt`.
- Reuse existing domain names like `article`, `payment`, `statistics`, and `user` across routes and services.
- For upgrade work, prefer Chinese branch, tag, and document names that match `项目优化计划/01-版本管理方案.md`.

## Testing Guidelines

- Add or update JUnit tests beside the Spring Boot code you change; name test classes `*Tests.java`.
- No committed frontend, Go, or Python test suite exists yet, so include focused manual verification in your PR for those areas.
- Prioritize controller/service paths and SQL or API contract changes affecting article creation, auth, or payments.
- When finishing an upgrade stage, record the stage acceptance checklist and validation notes in the corresponding file under `项目优化计划/`.

## Commit & Pull Request Guidelines

- Follow the history style: short, prefixed commits such as `docs: update setup notes` or `chore: add example config`.
- Keep commits scoped by module when possible: `frontend`, `go-backend`, `python-backend`, or Spring Boot root.
- PRs should include a brief summary, affected paths, config or migration notes, linked issues, and screenshots for visible UI changes.
- For staged upgrade work, use one feature branch per stage, keep commits small, and tag each completed stage as described in `项目优化计划/01-版本管理方案.md`.

## Configuration & Secrets

- Copy `.env.example`, `go-backend/.env.example`, `python-backend/.env.example`, or `src/main/resources/application-local.yml.example` before local setup.
- Never commit real API keys, Stripe secrets, COS credentials, or populated local config files.

<!-- 初始化项目 -->
