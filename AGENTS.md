# Repository Guidelines

## Project Structure & Module Organization

- `src/main/java` and `src/main/resources` hold the primary Spring Boot backend; tests live in `src/test/java`.
- `frontend/src` contains the Vue 3 + TypeScript app, with `pages/`, `components/`, `api/`, `stores/`, and `utils/`.
- `go-backend/` and `python-backend/` are alternative backend ports; keep work scoped to the implementation you are changing.
- `sql/` stores schema and migration-style scripts. Root Docker files support full-stack local startup.

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

## Testing Guidelines

- Add or update JUnit tests beside the Spring Boot code you change; name test classes `*Tests.java`.
- No committed frontend, Go, or Python test suite exists yet, so include focused manual verification in your PR for those areas.
- Prioritize controller/service paths and SQL or API contract changes affecting article creation, auth, or payments.

## Commit & Pull Request Guidelines

- Follow the history style: short, prefixed commits such as `docs: update setup notes` or `chore: add example config`.
- Keep commits scoped by module when possible: `frontend`, `go-backend`, `python-backend`, or Spring Boot root.
- PRs should include a brief summary, affected paths, config or migration notes, linked issues, and screenshots for visible UI changes.

## Configuration & Secrets

- Copy `.env.example`, `go-backend/.env.example`, `python-backend/.env.example`, or `src/main/resources/application-local.yml.example` before local setup.
- Never commit real API keys, Stripe secrets, COS credentials, or populated local config files.
