# ISSUE-05: Soft Delete and Surrogate ID for Metadata Preservation

**Estado:** ✅ Cerrado
**Severidad:** 🟧 2

**Nota:** Se implementó soft delete con ID sustituto (UUID) para preservar metadatos tras eliminación de archivos del
filesystem. Incluye reactivación automática y purge manual.

## Contexto

El sistema está diseñado con el principio de que **el filesystem es la fuente de verdad** — la base de datos es un
reflejo de lo que existe en disco. Originalmente, cuando un archivo se eliminaba del filesystem, el registro en DB se
borraba físicamente. Esto significaba que el catálogo **no tenía memoria** de lo que alguna vez estuvo en la biblioteca.

El problema: si los archivos se pierden por eliminación accidental, corrupción de disco o ransomware, todos los
metadatos (tags, URL, año, edición, autor) se pierden permanentemente. El sistema no puede funcionar como inventario
ni "póliza de seguro" de la biblioteca del usuario.

Adicionalmente, el PK natural basado en `path` genera problemas con:

- **Codificación URL**: caracteres especiales en paths deben codificarse en REST.
- **Cambios de path**: un rename cambia el identificador natural, requiriendo cascada en FKs.
- **Conflictos con soft delete**: si un path tiene un registro soft-deleteado y llega un nuevo archivo al mismo path,
  hay conflicto de PK.

## Solución

### 1. Surrogate ID + Soft Delete en `sources`

| Cambio                    | Detalle                                                                                 |
|---------------------------|-----------------------------------------------------------------------------------------|
| **Nuevo `id`**            | `id UUID PRIMARY KEY DEFAULT gen_random_uuid()`                                         |
| **Nuevo `deleted_at`**    | `deleted_at TIMESTAMP NULL` — se setea al eliminar archivo del FS                       |
| **Índice único parcial**  | `CREATE UNIQUE INDEX idx_sources_active_path ON sources(path) WHERE deleted_at IS NULL` |
| **Limpieza de metadatos** | Al transferir metadatos en un rename, se limpian los campos del registro viejo          |

### 2. Migración de `source_tags`

| Cambio        | Detalle                                                            |
|---------------|--------------------------------------------------------------------|
| **FK cambia** | `source_path VARCHAR` → `source_id UUID`                           |
| **Nueva FK**  | `FOREIGN KEY (source_id) REFERENCES sources(id) ON DELETE CASCADE` |
| **Nuevo PK**  | `PRIMARY KEY (source_id, tag_id)`                                  |

`ON DELETE CASCADE` solo se dispara en **purge** (borrado físico). Soft delete (`UPDATE deleted_at`) no cascada.

### 3. Reglas de comportamiento

| Operación                                  | Comportamiento                                                                                   |
|--------------------------------------------|--------------------------------------------------------------------------------------------------|
| **Soft delete** (archivo eliminado del FS) | `UPDATE sources SET deleted_at = now()` — metadatos preservados                                  |
| **Reactivación** (archivo reaparece)       | Requiere path **y** hash iguales. Si coinciden: `UPDATE deleted_at = NULL`. Si no: nuevo source. |
| **Rename** (hash igual, path distinto)     | Crear nuevo source con metadatos transferidos, limpiar metadatos viejos, soft-deletear el viejo  |
| **Purge** (acción del usuario)             | `DELETE /api/sources/{id}` — solo si `deleted_at IS NOT NULL`. Borrado físico con CASCADE.       |
| **No soft-delete desde UI**                | No hay acción de "eliminar source activo". Solo se soft-deletea removiendo el archivo del FS.    |

### 4. REST API

| Endpoint                   | Cambio                                    |
|----------------------------|-------------------------------------------|
| `GET /api/sources`         | Query param `?includeDeleted=false`       |
| `GET /api/sources/{id}`    | Usa UUID en vez de path                   |
| `PATCH /api/sources/{id}`  | Usa UUID                                  |
| `DELETE /api/sources/{id}` | Purge-only. 409 si el source está activo. |

### 5. Flyway Migration

Ver `api/src/main/resources/db/migration/V202607020002__add_soft_delete_and_surrogate_id.sql` para la migración
completa.

## Alternativas consideradas

### Alternativa 1: Tabla de historial separada

Mover registros eliminados a una tabla `source_deletions` con snapshot de metadatos.
**Rechazada**: consultas entre activos y eliminados requieren UNIONs. Reactivar requiere copiar datos. Complejidad
extra.

### Alternativa 2: Soft delete con path como PK

Mantener `path` como PK natural. Setear `deleted_at` al eliminar.
**Rechazada**: rename detection necesita crear un registro en un path que puede tener un soft-deleteado. El ID sustituto
lo evita por completo.

### Alternativa 3: Borrado físico (sin historial)

Eliminar registros permanentemente al remover archivos del FS.
**Rechazada**: el sistema no puede funcionar como inventario. La pérdida de metadatos ante fallas del FS es inaceptable.

## Consecuencias

### Positivas

- Metadatos preservados ante eliminaciones accidentales, corrupción de disco y ransomware.
- Reactivación automática restaura tags, autor y otros metadatos cuando un archivo reaparece.
- Purge da control explícito al usuario sobre la eliminación permanente.
- ID sustituto elimina problemas de codificación URL, conflictos de path con soft-delete y simplifica el diseño REST.
- Consistente con detección de renombres por content hash.

### Negativas

- Todas las lecturas deben filtrar por `deleted_at IS NULL` (mitigado con `@Where(clause = "deleted_at IS NULL")` en
  Hibernate).
- La tabla crece con registros soft-deleteados (mitigado por uso personal: miles de registros es despreciable).
- Migración Flyway multi-paso con backfill de datos.
- REST API usa UUID en vez de path.

### Limitaciones

- No hay undo para purge.
- Tags de sources purgados se borran por CASCADE (los tags en sí se preservan).
- Reactivación solo vía CREATE durante reconciliación.

## Referencias

- `docs/architecture.md §6.1` — Full Scan & Reconciliation flow
- `docs/architecture.md §6.1.3` — File Deletion flow (soft delete)
- `docs/api.md §4.2` — Entity model (sources table)
- `docs/api.md §2.1` — REST endpoints (Sources)
- Migrado desde ADR-0001 el 2026-07-09
