# Frontend Module — Software Design Description

## Document Control

| Property        | Value                                         |
|-----------------|-----------------------------------------------|
| **Version**     | 1.0                                           |
| **Status**      | Draft — initial definition for implementation |
| **Last update** | 2026-07-01                                    |
| **System SDD**  | `docs/SDD.md`                                 |

---

## 1. Introduction

### 1.1 Purpose

This document describes the detailed design of the Frontend module. It covers component hierarchy, routing, state
management, API client design, page layouts, UI patterns, configuration, and testing strategy.

It is the implementation guide for the `front/` module. It assumes familiarity with the system-level design documented
in `docs/SDD.md`.

### 1.2 Scope

This document covers:

- Module architecture and directory structure
- Route definitions and navigation flow
- Component tree (pages, layouts, shared components)
- State management approach
- API client and data fetching strategy
- Page-by-page layout and behaviour
- UI patterns (search, pagination, validation, empty states)
- Configuration and environment variables
- Testing approach

It does **not** cover:

- System architecture principles → see `docs/SDD.md`
- REST API contracts → see `docs/api/SDD.md`
- Filesystem monitoring → see `docs/agent/SDD.md`

---

## 2. Module Architecture

### 2.1 High-Level Component Tree

```
<App>
├── <Layout>                         ← header, nav, main area
│   ├── <SearchBar />                ← global search input
│   ├── <NavBar />                   ← navigation links
│   └── <Outlet />                   ← routed page content
│       ├── <SourceListPage>         ← /sources
│       │   ├── <FilterBar />        ← author, tag, format filters
│       │   ├── <SourceTable />      ← paginated source rows
│       │   └── <Pagination />
│       ├── <SourceDetailPage>       ← /sources/:id
│       │   ├── <SourceMetadata />   ← editable fields
│       │   ├── <TagEditor />        ← add/remove tags
│       │   └── <AuthorBadge />
│       ├── <AuthorListPage>         ← /authors
│       ├── <TagListPage>            ← /tags
│       └── <ReconciliationPage>     ← /reconcile
└── <ApiProvider>                    ← context: API client config
```

### 2.2 Directory Structure

```
src/
├── main.tsx                         ← ReactDOM.createRoot
├── App.tsx                          ← Router + providers
├── index.css                        ← Global styles
├── api/
│   ├── client.ts                    ← HTTP client (fetch wrapper)
│   ├── sources.ts                   ← source endpoints
│   ├── authors.ts                   ← author endpoints
│   ├── tags.ts                      ← tag endpoints
│   └── types.ts                     ← shared API types
├── components/
│   ├── layout/
│   │   ├── Layout.tsx               ← shell: header + nav + content
│   │   ├── NavBar.tsx
│   │   └── SearchBar.tsx
│   ├── source/
│   │   ├── SourceTable.tsx
│   │   ├── SourceRow.tsx
│   │   ├── SourceMetadata.tsx
│   │   ├── SourceMetadataForm.tsx
│   │   └── SourceDetail.tsx
│   ├── tag/
│   │   ├── TagBadge.tsx
│   │   ├── TagList.tsx
│   │   └── TagEditor.tsx
│   ├── author/
│   │   └── AuthorBadge.tsx
│   └── shared/
│       ├── FilterBar.tsx
│       ├── Pagination.tsx
│       ├── LoadingSpinner.tsx
│       ├── EmptyState.tsx
│       ├── ErrorMessage.tsx
│       └── ConfirmDialog.tsx
├── hooks/
│   ├── useSources.ts                ← data fetching + state
│   ├── useAuthors.ts
│   ├── useTags.ts
│   └── useDebounce.ts               ← debounce for search input
├── pages/
│   ├── SourceListPage.tsx
│   ├── SourceDetailPage.tsx
│   ├── AuthorListPage.tsx
│   ├── TagListPage.tsx
│   └── ReconciliationPage.tsx
├── context/
│   └── ApiContext.tsx               ← shared API base URL
└── utils/
    └── formatters.ts                ← date, path, filename formatting
```

### 2.3 Dependencies

The current `package.json` needs the following additions:

| Package                 | Version         | Purpose                 |
|-------------------------|-----------------|-------------------------|
| `react-router-dom`      | ^7.x            | Client-side routing     |
| `@tanstack/react-query` | ^5.x (optional) | Server state management |

**Optional — react-query**:

- Recommended if the API interaction grows beyond basic CRUD.
- Provides caching, refetching, loading states out of the box.
- V1 can start without it (use simple `useState` + `useEffect`) and add it later.

---

## 3. Routing & Navigation

### 3.1 Route Structure

```
/                     → redirect to /sources
/sources              → SourceListPage (default)
/sources/:id          → SourceDetailPage
/authors              → AuthorListPage
/tags                 → TagListPage
/reconcile            → ReconciliationPage
```

Implemented with `react-router-dom` v7:

```typescript
import {createBrowserRouter, Navigate} from 'react-router-dom';

const router = createBrowserRouter([
    {
        path: '/',
        element: <Layout / >,
        children: [
            {index: true, element: <Navigate to = "/sources" replace / >
    },
    {path: 'sources', element: <SourceListPage / >},
    {path: 'sources/:id', element: <SourceDetailPage / >},
    {path: 'authors', element: <AuthorListPage / >},
    {path: 'tags', element: <TagListPage / >},
    {path: 'reconcile', element: <ReconciliationPage / >},
],
},
])
;
```

### 3.2 Navigation Flow

```
SourceListPage
  ├─ click source row → /sources/{id}
  └─ click "Authors" in nav → /authors

SourceDetailPage
  └─ back button → /sources

AuthorListPage / TagListPage
  └─ simple list, no detail pages in V1

ReconciliationPage
  └─ trigger button → POST /api/reconcile
```

---

## 4. State Management

### 4.1 Approach

**V1**: React built-in state only (no external library):

| Concern               | Mechanism                                                       |
|-----------------------|-----------------------------------------------------------------|
| API base URL          | `React.createContext` (ApiContext)                              |
| Current search/filter | Local `useState` in SourceListPage                              |
| Data fetching         | Custom hooks (`useSources`, etc.) with `useState` + `useEffect` |
| Form state            | Local `useState`                                                |
| URL params            | `useParams`, `useSearchParams` from react-router                |

**If complexity grows**: Add `@tanstack/react-query` for:

- Automatic cache invalidation
- Background refetching
- Loading / error state management
- Pagination handling

### 4.2 Data Fetching Strategy

```typescript
// hooks/useSources.ts
function useSources(params: SourceSearchParams) {
    const [data, setData] = useState<Page<SourceResponse> | null>(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<Error | null>(null);

    useEffect(() => {
        const controller = new AbortController();
        setLoading(true);

        api.sources.list(params, {signal: controller.signal})
            .then(setData)
            .catch(err => {
                if (err.name !== 'AbortError') setError(err);
            })
            .finally(() => setLoading(false));

        return () => controller.abort();
    }, [params.q, params.page, params.size, params.authorId, params.tagId, params.formatId]);

    return {data, loading, error};
}
```

Key patterns:

- `AbortController` cancels in-flight requests when params change.
- Debounced search (300ms) prevents excessive API calls.
- Error state is handled per-component (not global).

---

## 5. API Client

### 5.1 Client Design

```typescript
// api/client.ts
const BASE_URL = import.meta.env.VITE_API_URL ?? 'http://localhost:8080';

async function request<T>(
    method: string,
    path: string,
    options?: { body?: unknown; params?: Record<string, string>; signal?: AbortSignal },
): Promise<T> {
    const url = new URL(`${BASE_URL}${path}`);
    if (options?.params) {
        Object.entries(options.params).forEach(([k, v]) => url.searchParams.set(k, v));
    }

    const response = await fetch(url, {
        method,
        headers: {'Content-Type': 'application/json'},
        body: options?.body ? JSON.stringify(options.body) : undefined,
        signal: options?.signal,
    });

    if (!response.ok) {
        const error = await response.json().catch(() => ({}));
        throw new ApiError(response.status, error.message ?? response.statusText);
    }

    if (response.status === 204) return undefined as T;
    return response.json();
}
```

```typescript
// api/types.ts
interface SourceResponse {
    id: number;
    path: string;
    name: string;
    author: { id: number; name: string } | null;
    format: { id: number; name: string };
    tags: { id: number; name: string }[];
    year: number | null;
    edition: string | null;
    url: string | null;
    createdAt: string;
    updatedAt: string;
}

interface Page<T> {
    content: T[];
    page: number;
    size: number;
    totalElements: number;
    totalPages: number;
}

interface SyncResult {
    created: number;
    deleted: number;
    skipped: number;
}

class ApiError extends Error {
    constructor(
        readonly status: number,
        message: string,
    ) {
        super(message);
        this.name = 'ApiError';
    }
}
```

### 5.2 Endpoint Mapping

```typescript
// api/sources.ts
export const sources = {
    list: (params: SourceSearchParams, opts?: RequestOptions) =>
        request<Page<SourceResponse>>('GET', '/api/sources', {params, signal: opts?.signal}),

    get: (id: number) =>
        request<SourceResponse>('GET', `/api/sources/${id}`),

    update: (id: number, body: UpdateSourceRequest) =>
        request<SourceResponse>('PATCH', `/api/sources/${id}`, {body}),

    // Not exposed in the Frontend:
    // create → Agent only
    // delete → Agent only
};
```

```typescript
// api/authors.ts
export const authors = {
    list: (q?: string) =>
        request<AuthorResponse[]>('GET', '/api/authors', {params: q ? {q} : undefined}),
};
```

```typescript
// api/tags.ts
export const tags = {
    list: (q?: string) =>
        request<TagResponse[]>('GET', '/api/tags', {params: q ? {q} : undefined}),
};
```

```typescript
// api/sync.ts
export const sync = {
    trigger: () => request<{ message: string; statusUrl: string }>('POST', '/api/reconcile'),
};
```

### 5.3 Error Handling

All API errors are caught at the component level via the `ApiError` class:

```typescript
// In a component:
if (error instanceof ApiError && error.status === 404) {
    // show "not found" state
}
```

A global error boundary (`ErrorBoundary`) catches unhandled exceptions and displays a fallback UI.

---

## 6. Pages / Views

### 6.1 SourceListPage

The default landing page. Displays a paginated, filterable list of all sources.

**Layout:**

```
┌──────────────────────────────────────────────┐
│  [SearchBar _________________________]       │
│  [Author ▼] [Format ▼] [Tag ▼]  [+ New]     │
├──────────────────────────────────────────────┤
│  Name          Author    Format    Tags      │
│  ──────────────────────────────────────────── │
│  Critica.pdf   Kant      PDF       filosofia │
│  Fenomenologia Hegel     PDF       filosofia │
│  ...                                         │
├──────────────────────────────────────────────┤
│  « 1 2 3 ... 10 »             20 per page   │
└──────────────────────────────────────────────┘
```

**Behaviour:**

- Search input updates `q` param (debounced 300ms).
- Filters are dropdown selects populated from `/api/authors`, `/api/tags`, `/api/formats`.
- URL search params sync with filters (bookmarkable URL).
- Empty state: "No sources found. Add files to your library directory."

### 6.2 SourceDetailPage

Shows full metadata for a single source and allows editing.

**Layout:**

```
┌──────────────────────────────────────────────┐
│  ← Back to sources                            │
├──────────────────────────────────────────────┤
│  Name:      Critica.pdf                       │
│  Path:      /home/.../Critica.pdf             │
│  Author:    Kant                    [Edit]    │
│  Format:    PDF                               │
│  Year:      1781                    [Edit]    │
│  Edition:   2nd                     [Edit]    │
│  URL:       https://...             [Edit]    │
│  Tags:      filosofia  aleman       [+ Add]  │
│  Created:   2026-07-01 12:00                  │
│  Updated:   2026-07-01 14:30                  │
└──────────────────────────────────────────────┘
```

**Behaviour:**

- Editable fields use inline edit (click to edit, blur or Enter to save).
- Tag editor: typeahead component that searches `/api/tags`. Existing tags shown as badges with remove button.
- No "save" button — each field auto-saves via `PATCH /api/sources/{id}`.
- The `id` parameter is a number — no URL encoding needed.

### 6.3 AuthorListPage

Simple list of all authors with source count.

**Layout:**

```
┌──────────────────────────────────────────────┐
│  Authors              Sources                 │
│  ──────────────────────────────────────────── │
│  Kant                 12                      │
│  Hegel                5                       │
│  Unassigned           3                       │
└──────────────────────────────────────────────┘
```

Read-only in V1. Clicking an author filters the source list by that author.

### 6.4 TagListPage

Simple list of all tags with source count.

**Layout:**

```
┌──────────────────────────────────────────────┐
│  Tags                Sources                  │
│  ──────────────────────────────────────────── │
│  filosofia           17                       │
│  aleman              5                        │
│  ensayo              3                        │
└──────────────────────────────────────────────┘
```

Read-only in V1. Clicking a tag filters the source list by that tag.

### 6.5 ReconciliationPage

Allows the user to trigger a full filesystem scan.

**Layout:**

```
┌──────────────────────────────────────────────┐
│  Reconciliation                               │
│                                               │
│  The system will scan your library directory  │
│  and sync the database with the current       │
│  filesystem state.                            │
│                                               │
│  [⟳ Trigger Reconciliation]                   │
│                                               │
│  ┌────────────────────────────────────────┐   │
│  │  Last scan: 2026-07-01 12:00           │   │
│  │  Created: 5  Deleted: 2  Skipped: 10  │   │
│  └────────────────────────────────────────┘   │
└──────────────────────────────────────────────┘
```

**Behaviour:**

- Button triggers `POST /api/reconcile`.
- Shows loading state while reconciling.
- Displays the last `SyncResult` once complete.
- Disabled state: "A reconciliation is already in progress."

---

## 7. Shared Components

### 7.1 SearchBar

```typescript
interface SearchBarProps {
    value: string;
    onChange: (value: string) => void;
    placeholder?: string;
}
```

- Controlled input with debounced `onChange` (300ms).
- Clear button when input has text.
- Renders inside the Layout header for global scope.

### 7.2 FilterBar

```typescript
interface FilterBarProps {
    authorId?: number;
    tagId?: number;
    formatId?: number;
    onChange: (filters: FilterState) => void;
}
```

- Dropdown selects populated from `/api/authors`, `/api/tags`, `/api/formats`.
- "All" option clears the filter.
- Filters are independent (can combine author + tag + format).

### 7.3 Pagination

```typescript
interface PaginationProps {
    page: number;
    totalPages: number;
    onPageChange: (page: number) => void;
}
```

- Previous / Next buttons.
- First / Last page shortcuts.
- Page number indicator ("Page 3 of 10").

### 7.4 LoadingSpinner

Simple CSS spinner shown during data fetching. Used as fallback content while `loading` is true.

### 7.5 EmptyState

```typescript
interface EmptyStateProps {
    title: string;
    description: string;
    action?: { label: string; onClick: () => void };
}
```

Displayed when a list has no items. Contextual message depending on the view:

- Source list: "No sources match your filters."
- Source list (no filters): "Your library is empty. Add files to start cataloging."
- Reconciliation: "No data yet. Run a reconciliation to get started."

---

## 8. UI Patterns

### 8.1 TypeScript Constraints

The project uses TypeScript 6 with strict settings. The following rules affect code style:

```typescript
// ✅ Correct: type-only imports use 'import type'
import type {SourceResponse} from '../api/types';
import {useEffect} from 'react';  // value import

// ✅ Correct: no 'enum', no 'namespace', no parameter properties
type Format = 'PDF' | 'EPUB' | 'MHTML';  // use union types instead of enum

// ❌ Incorrect: would fail with erasableSyntaxOnly
// enum Format { PDF, EPUB, MHTML }
```

### 8.2 Source Identification

Sources are identified by their numeric `id` in all API routes and frontend navigation:

- `/api/sources/{id}` — stable identifier that does not change when a file is renamed or moved.
- `/sources/{id}` — the frontend route uses the same numeric id.
- No URL encoding is needed for numeric identifiers.

### 8.3 Form Validation

All form validation is client-side before sending to the API:

| Field        | Validation                              |
|--------------|-----------------------------------------|
| `year`       | Number between 1 and 2099               |
| `url`        | Must start with `http://` or `https://` |
| `authorName` | Non-empty string, max 255 chars         |

Errors are shown inline below the field.

### 8.4 Optimistic UI (Future)

Not implemented in V1. For now, all mutations wait for the API response before updating the UI. Loading states indicate
in-progress operations.

---

## 9. Configuration

### 9.1 Environment Variables

| Variable         | Default                 | Description          |
|------------------|-------------------------|----------------------|
| `VITE_API_URL`   | `http://localhost:8080` | Base URL for the API |
| `VITE_APP_TITLE` | `BiblioCat`             | Browser tab title    |

Variables are accessed via `import.meta.env.VITE_*`.

### 9.2 Vite Proxy (Development)

For local development, configure Vite to proxy API requests to avoid CORS issues:

```typescript
// vite.config.ts
export default defineConfig({
    plugins: [react()],
    server: {
        proxy: {
            '/api': {
                target: 'http://localhost:8080',
                changeOrigin: true,
            },
        },
    },
});
```

With this proxy, the frontend dev server on port 5173 forwards `/api/*` to the backend on port 8080.

---

## 10. Testing Strategy

### 10.1 Component Tests

Use Vitest (bundled with Vite) + React Testing Library:

```bash
npm install -D vitest @testing-library/react @testing-library/jest-dom @testing-library/user-event jsdom
```

```typescript
// Example: SourceRow renders correctly
import {render, screen} from '@testing-library/react';
import {SourceRow} from './SourceRow';

test('renders source name and author', () => {
    render(<SourceRow source = {mockSource}
    />);
    expect(screen.getByText('Critica.pdf')).toBeInTheDocument();
    expect(screen.getByText('Kant')).toBeInTheDocument();
});
```

| What to test        | Approach                                                  |
|---------------------|-----------------------------------------------------------|
| Component rendering | Render with mock data, assert DOM elements                |
| User interactions   | `userEvent.click()`, `userEvent.type()`, assert callbacks |
| Loading states      | Render with `loading=true`, assert spinner                |
| Empty states        | Render with empty data, assert empty message              |
| Error states        | Render with error, assert error message                   |

### 10.2 Integration Tests

Test data fetching and routing together:

```typescript
// Example: SourceListPage fetches and displays sources
import {render, screen, waitFor} from '@testing-library/react';
import {MemoryRouter} from 'react-router-dom';
import {SourceListPage} from './SourceListPage';

test('displays sources from API', async () => {
    // Mock the API call
    vi.spyOn(api.sources, 'list').mockResolvedValue(mockPage);

    render(
        <MemoryRouter>
            <SourceListPage / >
        </MemoryRouter>,
    );

    await waitFor(() => {
        expect(screen.getByText('Critica.pdf')).toBeInTheDocument();
    });
});
```

### 10.3 Test Configuration

Add to `vite.config.ts`:

```typescript
/// <reference types="vitest/config" />
export default defineConfig({
    plugins: [react()],
    test: {
        globals: true,
        environment: 'jsdom',
        setupFiles: './src/test/setup.ts',
    },
});
```

---

## 11. Frontend-specific Design Decisions

### 11.1 No External State Management Library (V1)

|               | Decision                                                                                                                   |
|---------------|----------------------------------------------------------------------------------------------------------------------------|
| **Chosen**    | React built-in state (useState + useEffect + Context).                                                                     |
| **Rationale** | The app has few pages and simple data flows. A library like Redux or Zustand adds complexity without proportional benefit. |
| **Future**    | If cross-page caching or background refetching becomes necessary, add `@tanstack/react-query`.                             |

### 11.2 Inline Edit with Auto-Save

|               | Decision                                                                                                         |
|---------------|------------------------------------------------------------------------------------------------------------------|
| **Chosen**    | Each editable field saves individually via `PATCH` on blur/Enter.                                                |
| **Rationale** | No "save" button means fewer clicks, immediate feedback. `PATCH` semantics allow sending only the changed field. |
| **Trade-off** | Each field edit produces a separate HTTP request. Acceptable for single-user editing at human speed.             |

### 11.3 URL-Based Filter State

|                    | Decision                                                                             |
|--------------------|--------------------------------------------------------------------------------------|
| **Chosen**         | Search and filter parameters are stored in URL search params (`?q=kant&formatId=1`). |
| **Rationale**      | Bookmarkable URLs, browser back/forward works naturally, easy to share.              |
| **Implementation** | `useSearchParams` from react-router.                                                 |

### 11.4 ID as URL Parameter

|               | Decision                                                                                                                                                                                                                                                                                                                                        |
|---------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Chosen**    | The source `id` (BIGSERIAL) is used in the route: `/sources/{id}`.                                                                                                                                                                                                                                                                              |
| **Rationale** | `id` is a stable identifier that never changes, even if the file is renamed or moved. It avoids URL encoding issues (path separators, spaces, special characters) and enables file path coexistence after soft-delete (multiple records with the same path but different `id`). Consistent with the API, where all source endpoints use `{id}`. |

### 11.5 No Direct Agent Communication

|               | Decision                                                                                                                                                                 |
|---------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Chosen**    | The Frontend never communicates with the Agent directly.                                                                                                                 |
| **Rationale** | Enforced by the architecture (section 2.3 of system SDD). All communication goes through the API. Even reconciliation triggering goes API → Agent, not Frontend → Agent. |
