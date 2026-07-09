# AGENTS.md

## Modules

| Module   | Dir      | Entrypoint                        | Stack                                  |
|----------|----------|-----------------------------------|----------------------------------------|
| API      | `api/`   | `com.biblocat.api.ApiApplication` | Spring Boot 4.1, Java 21, Maven        |
| Agent    | `agent/` | `com.biblocat.App`                | Java 21, Maven                         |
| Frontend | `front/` | `src/main.tsx`                    | React 19, TypeScript 6, Vite 8, ESLint |

## Documentation

The authoritative documentation for development is in the new set of files under `docs/`:

- `docs/architecture.md` — system architecture, stack, flows
- `docs/api.md` — API, data model, database
- `docs/agent.md` — filesystem monitoring, sync
- `docs/front.md` — UI, routing, components

## Backend

- No root POM. Each Java module has its own Maven wrapper at `api/mvnw` and `agent/mvnw`.
- Package naming differs: `com.biblocat.api` (api) vs `com.biblocat` (agent).
- `api/pom.xml` inherits Spring Boot 4.1 parent (Java 21, JUnit 5).
- `docs/architecture.md` is the system design document (loaded as OpenCode instruction).

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

- Architecture & domain model: `docs/architecture.md`
- API design: `docs/api.md`
- Agent design: `docs/agent.md`
- Frontend design: `docs/front.md`
- Backend sub-agent: `.opencode/agents/backend.md`
- Frontend sub-agent: `.opencode/agents/frontend.md`
- Available commands: `.opencode/commands/`

## Problem tracking

Functional issues are recorded as Markdown files in `docs/issues/`. Each issue is numbered sequentially:
`docs/issues/ISSUE-NN-Name.md`. Use the template at `docs/issues/template.md`.
Format:

```
**Estado: ❌ No resuelto | 🟡 Parcialmente resuelto | ✅ Resuelto (doc) | ✅ Resuelto (impl)**
**Severidad: 🟩 1** (mejora) | 🟧 2 (importante) | 🟥 3 (bloqueante)**
**Nota:** <summary>

## Solución tomada  ← only if at least partially resolved
```

When an issue is fully resolved, move it to `docs/issues/resolved/` (preserving its ISSUE-NN number).

## Issues → ADR rules (MANDATORY)

These rules govern the relationship between issues and ADRs. Follow them exactly. Do not infer, extend, or override
them.

1. Every problem is documented as an issue in `docs/issues/` using the template at `docs/issues/template.md`.
2. The solution is recorded inline in `## Solución tomada` **within the same issue file**.
3. **Do NOT create an ADR unless** the decision meets ALL of:
    - Schema change (new table, column, migration).
    - Multiple alternatives evaluated with documented trade-offs.
    - Cross-module impact (touches at least two of: API, Agent, Frontend).
4. If an ADR is created, place it at `docs/issues/decisions/ADR-NNNN-titulo.md`.
5. Cross-references are mandatory in BOTH directions:
    - The issue MUST list `**Decisión:** docs/issues/decisions/ADR-NNNN-titulo.md`.
    - The ADR MUST list `**Issue:** docs/issues/ISSUE-NN-Name.md`.
6. An ADR MAY exist without an issue (pure design decision). An issue MAY resolve without an ADR (inline solution).
   The relationship is NOT 1:1. Do not force an ADR where one is not needed, and do not skip an ADR where one is needed.

**Restriction: if you are unsure whether a change requires an ADR, ask the user. Do not decide on your own.**
