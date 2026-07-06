# Issue: Safe-save que cruza filesystems — pérdida de metadatos

## Contexto

Cuando un archivo se mueve entre filesystems (ej: de `C:\` a `D:\`), el Agent puede clasificar la operación como
CREATE + DELETE en lugar de RENAME, dependiendo del estado del content hash.

## Análisis

### Escenario donde funciona correctamente

Si el archivo se mueve con el contenido intacto (mismo SHA-256):

- Path nuevo: no existe en API.
- Hash: coincide con el source en el path viejo.

→ **Clasificación:** RENAME (#D). La API transfiere metadatos. Correcto.

### Escenario donde hay pérdida

Si el archivo se modifica **durante o después** del cruce de filesystem (hash cambia):

- Path nuevo: no existe en API.
- Hash: NO coincide con ningún source.

→ **Clasificación:** CREATE (#E).

- Path viejo: no existe en FS.

→ **Clasificación:** DELETE (#F).

**Resultado:**

- Se crea un nuevo source en el nuevo path con **metadatos vacíos** (sin tags, año, URL).
- El source viejo queda soft-deleteado con todos sus metadatos.

El usuario ve un source nuevo sin metadatos y un orphan con los metadatos originales. Aunque los metadatos no se pierden
irreversiblemente (están en el orphan), están **desconectados** del source activo.

### Escenarios de ejemplo

1. Usuario copia `libro.pdf` a un pendrive, lo modifica, y lo vuelve a copiar a `D:\`.
2. Safe-save que cruza filesystems: la app escribe un temp en el mismo FS pero el archivo destino está en otro FS.
3. Sincronización con cloud (OneDrive, Google Drive) que descarga el archivo modificado en una ubicación diferente.

## Soluciones propuestas

### Opción A: Aceptar como limitación documentada

Los metadatos se preservan en el orphan source. El usuario puede verlos y re-asignarlos manualmente. Documentar como
limitación conocida.

Costo: 0. Usabilidad: baja.

### Opción B: La API transfiere metadatos por hash en el CREATE

Cuando la API recibe un CREATE, busca en sources soft-deleteados con el mismo `contentHash`:

```sql
SELECT *
FROM sources
WHERE content_hash = :hash
  AND deleted_at IS NOT NULL
```

- Si hay exactamente 1 → transferir metadatos, purgar el orphan.
- Si hay 0 o >1 → no transferir (evitar ambigüedad).

Costo: bajo (una query adicional en CREATE). Cubre ciclos separados (DELETE en escaneo anterior, CREATE ahora).

### Opción C: Detección intra-batch en la API

Si en el mismo batch hay un DELETE (con hash H) y un CREATE (con hash H), la API los trata como RENAME: transfiere
metadatos, soft-deletea el viejo, crea el nuevo.

Costo: medio (lógica adicional en el procesador de batch). Cubre el mismo ciclo de reconciliación.

## Recomendación

Opción B + Opción C. Son complementarias: B cubre ciclos distintos, C cubre el mismo ciclo. Ambas son de implementación
acotada y cierran el hueco de metadata loss.

## Impacto

- API: lógica adicional en SourceService.create() y en el procesador de batch.
- Agent: sin cambios (no necesita saber si la API hará la transferencia).
- Contrato: sin cambios (todo es interno de la API).
