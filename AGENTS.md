# AGENTS.md

## Modules

| Module   | Dir      | Entrypoint                        | Stack                                  |
|----------|----------|-----------------------------------|----------------------------------------|
| API      | `api/`   | `com.biblocat.api.ApiApplication` | Spring Boot 4.1, Java 21, Maven        |
| Agent    | `agent/` | `com.biblocat.App`                | Java 21, Maven                         |
| Frontend | `front/` | `src/main.tsx`                    | React 19, TypeScript 6, Vite 8, ESLint |

## Documentation

The authoritative documentation for development is in the new set of files under `docs/`:

- `docs/newDoc.md` — system architecture, stack, flows
- `docs/newApiDoc.md` — API, data model, database
- `docs/newAgentDoc.md` — filesystem monitoring, sync
- `docs/newFrontDoc.md` — UI, routing, components

The old files (`docs/SDD.md`, `docs/api/SDD.md`, `docs/agent/SDD.md`, `docs/front/SDD.md`) are **DEPRECATED** and should
only be used as historical reference. Any development decisions must be based on the new documents.

## Backend

- No root POM. Each Java module has its own Maven wrapper at `api/mvnw` and `agent/mvnw`.
- Package naming differs: `com.biblocat.api` (api) vs `com.biblocat` (agent).
- `api/pom.xml` inherits Spring Boot 4.1 parent (Java 21, JUnit 5).
- `docs/newDoc.md` is the system design document (loaded as OpenCode instruction).
- `docs/SDD.md` (DEPRECATED) — historical reference only.

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

- Architecture & domain model: `docs/newDoc.md`
- API design: `docs/newApiDoc.md`
- Agent design: `docs/newAgentDoc.md`
- Frontend design: `docs/newFrontDoc.md`
- Backend sub-agent: `.opencode/agents/backend.md`
- Frontend sub-agent: `.opencode/agents/frontend.md`
- Available commands: `.opencode/commands/`
- Legacy docs (DEPRECATED): `docs/SDD.md`, `docs/api/SDD.md`, `docs/agent/SDD.md`, `docs/front/SDD.md`
