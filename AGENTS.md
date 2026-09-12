# Repository Guidelines

## Project Structure & Module Organization

The application root is `agv-device-monitor-plan/`. Its Java 17 Maven modules are `backend/` and `simulator/`, each using `src/main/java`, `src/test/java`, and `src/main/resources`. Future static assets belong under backend `src/main/resources/static/`; migrations belong under `db/migration/`.

Read `START_HERE.md`, `README.md`, and `docs/06-tasks.md` inside the application root. The task handoff defines actual progress; business collection, persistence, UI, and Compose remain planned.

## Build, Test, and Development Commands

Run from `agv-device-monitor-plan/`:

- `./mvnw -version`: check the project Maven distribution and JDK.
- `timeout 60s ./mvnw test`: run context and dependency initialization tests.
- `timeout 60s ./mvnw verify`: include HTTP integration tests and package executable JARs.
- `java -jar backend/target/backend.jar`: start backend HTTP on loopback port 8080.
- `java -jar simulator/target/simulator.jar`: start simulator HTTP on loopback port 8081.

Compose commands are future targets. Healthy HTTP responses do not prove Modbus or database readiness.

## Coding Style & Naming Conventions

Use four-space Java indentation, UTF-8, LF, PascalCase classes, camelCase members, and packages under `com.example.agv`. No formatter or linter is configured. Keep changes focused; use parameterized SQL and pinned dependencies. Explain complex logic with a short English comment followed by Chinese clarification. Use Chinese for learning notes and user-facing explanations.

## Testing Guidelines

Use JUnit Jupiter, Surefire `*Test`, and Failsafe `*IT`. Keep backend checks bounded to 60 seconds. Add regression cases for bugs. Future protocol/database checks require real TCP and isolated MySQL. Map acceptance evidence to `docs/07-test-plan.md`; record unavailable checks as `NOT_RUN` or `BLOCKED`. No coverage percentage is mandated.

## Commit & Pull Request Guidelines

Git metadata is unavailable; no historical convention is verified. Prefer focused messages such as `docs(learning): clarify protocol boundaries`. Obtain explicit commit/push approval; Codex commits require detailed Chinese messages and `Generated-by: Codex`. PRs should identify tasks, behavior changes, validation, limitations, and screenshots for UI changes. Do not use `git restore`, `stash`, `checkout`, or `worktree`.

## Scope, Safety & Learning

This is a local, read-only simulation: no device control, public deployment, or committed credentials. Obtain approval before deletion, bulk edits, schema changes, environment/CI changes, or global installations.

The learner has seven years of Java experience. Follow `docs/05-development-plan.md`: teach industrial context, ask one scenario question at a time, and separate engineering completion from learning assessment. Keep full industrial-system discussion distinct from implemented monitoring features.
