# AGENTS.md

## Modules

| Module   | Dir      | Entrypoint                        | Stack                                  |
|----------|----------|-----------------------------------|----------------------------------------|
| API      | `api/`   | `com.biblocat.api.ApiApplication` | Spring Boot 4.1, Java 21, Maven        |
| Agent    | `agent/` | `com.biblocat.App`                | Java 21, Maven                         |
| Frontend | `front/` | `src/main.tsx`                    | React 19, TypeScript 6, Vite 8, ESLint |

## Documentation

All documentation in `docs/` is in **active draft** state. Design decisions are still being refined and may change. If you find contradictions or ambiguities, please ask before implementing.

## Backend

- No root POM. Each Java module has its own Maven wrapper at `api/mvnw` and `agent/mvnw`.
- Package naming differs: `com.biblocat.api` (api) vs `com.biblocat` (agent).
- `api/pom.xml` inherits Spring Boot 4.1 parent (Java 21, JUnit 5).
- `docs/SDD.md` is the system design document (loaded as OpenCode instruction).

## Frontend

- `npm run build` runs `tsc -b && vite build` (typecheck is part of build).
- `npm run lint` runs ESLint.
- A separate typecheck command exists: `npx tsc --noEmit`.
- TypeScript 6 flags that affect code style:
    - `verbatimModuleSyntax: true` → use `import type` for type-only imports.
    - `erasableSyntaxOnly: true` → no `enum`, no `namespace`, no constructor parameter properties.
    - `noUnusedLocals`, `noUnusedParameters` → errors on unused declarations.
    - `target: es2023`.

## Environment

- `CONTEXT7_API_KEY` required at Windows user level for Context7 MCP.
- `GITHUB_MCP_TOKEN` required for GitHub MCP integration.

## References

- Architecture & domain model: `docs/SDD.md`
- API design: `docs/api/SDD.md`
- Agent design: `docs/agent/SDD.md`
- Frontend design: `docs/front/SDD.md`
- Backend sub-agent: `.opencode/agents/backend.md`
- Frontend sub-agent: `.opencode/agents/frontend.md`
- Available commands: `.opencode/commands/`
