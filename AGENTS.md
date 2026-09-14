# Repository Guidelines

## Project Structure & Module Organization

Use `agv-device-monitor-plan/`, the Java 17 Maven application root:

- `backend/`: Spring Boot, JDBC, Flyway, protocol, collector, monitoring, API, SSE, metrics, and history retention.
- `simulator/`: three-device Modbus TCP simulator and HTTP scenario controls.
- Both modules use `src/main/java`, `src/test/java`, and `src/main/resources`.
- Database migrations: `backend/src/main/resources/db/migration/`. UI assets: `backend/src/main/resources/static/`; browser checks: `scripts/browser-check.js`. Compose files provide local container deployment; see `docs/08-deployment.md`.
- `docs/06-tasks.md`: progress; `docs/04-api-design.md`: API contract; `docs/10-modbus-register-map.md`: protocol.

## Build, Test, and Development Commands

Run from the application root:

- `./mvnw -version`: verify Maven Wrapper and JDK.
- `timeout 60s ./mvnw test`: run unit tests.
- `timeout 60s ./mvnw -pl simulator -am verify`: test and package the simulator.
- `timeout 60s ./mvnw -pl backend -am -Dit.test=MonitoringApiIT -Dfailsafe.failIfNoSpecifiedTests=false verify`: run one backend integration batch; requires Docker.
- `java -jar simulator/target/simulator.jar`: start HTTP 8081 and Modbus 1502 on loopback.
- `java -jar backend/target/backend.jar`: start HTTP 8080; supply datasource configuration. The `demo` profile initializes missing devices.

Split oversized suites into bounded batches listed in `README.md`; report unexecuted checks. `scripts/system-check.py` runs a separate 30-minute Compose test with isolated resources.

## Coding Style & Naming Conventions

Use UTF-8, LF, four-space indentation, `PascalCase` classes, `camelCase` members, and lowercase packages under `com.example.agv`. No formatter or linter is configured. Keep changes minimal, SQL parameterized, and complex comments bilingual: simple English followed by Chinese explanation. Never swallow exceptions or fabricate successful results.

## Testing Guidelines

Use JUnit Jupiter, Surefire `*Test`, and Failsafe `*IT`. Add regression cases for bugs, independent protocol fixtures, and controllable clocks. Use real TCP and isolated MySQL/Testcontainers for integration. Map evidence to `docs/07-test-plan.md`; mark unavailable checks `NOT_RUN` or `BLOCKED`. No coverage percentage is required.

## Commit & Pull Request Guidelines

History uses `[codex] type(scope): 中文摘要`. Include a detailed Chinese body and `Generated-by: Codex`. PRs explain task IDs, behavior, validation, limitations, and UI screenshots when applicable. Stage named paths and preserve unrelated changes. Obtain explicit commit and push authorization; never use `git restore`, `stash`, `checkout`, or `worktree`. History rewriting and force-pushing require explicit approval.

## Security & Agent Workflow

Keep this demo local and device access read-only. Keep credentials out of Git; preserve deployed migrations. Confirm deletion, bulk edits, schema/environment/CI changes, and global dependency changes unless already authorized.

Respond in Simplified Chinese. Scan `~/.codex/skills/`, read applicable skills, and use `codex-git-commit-guard` after edits. Read `START_HERE.md` and `docs/05-development-plan.md`: the approved interview sprint prioritizes continuous implementation, batch validation, and later review. Preserve learning answers; code and passing tests do not establish learner mastery.
