# Repository Guidelines

## Project Structure & Module Organization

- `src/main/java` and `src/main/resources` contain the main Spring Boot backend; tests live in `src/test/java`.
- `frontend/src` holds the Vue 3 + TypeScript app, organized into `pages/`, `components/`, `api/`, `stores/`, and `utils/`.
- `go-backend/` and `python-backend/` are alternative backend ports; keep changes scoped to the stack you are editing.
- `sql/` stores schema and migration-style scripts. Root Docker files support full-stack local startup.
- `项目优化计划/` stores staged upgrade documents. Keep numbered Chinese files such as `00-总体升级方案.md`, `01-版本管理方案.md`, `02-第一阶段实施方案.md`, `03-第二阶段实施方案.md`, `04-第二阶段完成总结.md`, and `05-第三阶段实施方案.md` in sync with delivery progress.

## Build, Test, and Development Commands

- `mvn spring-boot:run` - start the main backend.
- `mvn test` - run the Java test suite.
- `cd frontend && npm install && npm run dev` - start the Vite frontend locally.
- `cd frontend && npm run build` - type-check and build the frontend.
- `cd frontend && npm run lint` / `npm run format` - apply ESLint and Prettier.
- `docker compose up -d --build` - build and boot the default full stack.

## Coding Style & Naming Conventions

- Java uses 4-space indentation, `PascalCase` classes, `camelCase` methods, and suffixes like `*Controller`, `*Service`, and `*Mapper`.
- Vue and TypeScript follow ESLint + Prettier defaults; keep page and component names in `PascalCase`, for example `ArticleCreatePage.vue`.
- Python modules use `snake_case`; Go packages stay lowercase and should be formatted with `gofmt`.
- For upgrade work, prefer Chinese branch, tag, commit, and document names that match `项目优化计划/01-版本管理方案.md`.

## Testing Guidelines

- Add or update JUnit tests beside the Spring Boot code you change; name test classes `*Tests.java`.
- Frontend, Go, and Python do not yet have committed automated suites, so include focused manual verification for those areas.
- Prioritize article creation, auth, payment, and protocol changes. When finishing an upgrade stage, record acceptance and validation notes in the corresponding file under `项目优化计划/`.

## Commit & Pull Request Guidelines

- Keep commits short and scoped, for example `重构：统一文章生成编排入口` or `文档：更新第二阶段实施方案`.
- Use one feature branch per upgrade stage and tag each completed stage as described in `项目优化计划/01-版本管理方案.md`.
- PRs should include a brief summary, affected paths, config or migration notes, linked issues, and screenshots for visible UI changes.

## Configuration & Secrets

- Copy `.env.example`, `go-backend/.env.example`, `python-backend/.env.example`, or `src/main/resources/application-local.yml.example` before local setup.
- Never commit real API keys, Stripe secrets, COS credentials, or populated local config files.
