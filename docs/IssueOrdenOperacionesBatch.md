# Issue: Orden de operaciones dentro del batch de reconciliación

## Contexto

Durante una reconciliación, el Agent genera múltiples operaciones (CREATE, RENAME, UPDATE, DELETE, REACTIVATE) que
agrupa en batches y envía a `POST /api/sources/reconcile`. El orden de procesamiento de estas operaciones dentro del
batch **no está especificado** en la documentación actual.

## Problema

Cuando el Agent clasifica archivos de una carpeta renombrada, genera simultáneamente operaciones RENAME (path nuevo) y
DELETE (path viejo) para los mismos sources. Sin un orden definido, el procesamiento en la API puede producir resultados
inconsistentes.

### Ejemplo concreto

Carpeta de autor renombrada: `Autor A/` → `Autor B/`.

El Agent genera 4 operaciones en el mismo batch:

- RENAME (sourceId: 1, path: `Autor B/libro1.pdf`)
- RENAME (sourceId: 2, path: `Autor B/libro2.pdf`)
- DELETE (path: `Autor A/libro1.pdf`)
- DELETE (path: `Autor A/libro2.pdf`)

#### Caso A (RENAME primero, DELETE después)

1. RENAME libro1: cambia path de `Autor A/libro1.pdf` a `Autor B/libro1.pdf`.
2. DELETE `Autor A/libro1.pdf`: busca por path — **no lo encuentra** porque el RENAME ya lo movió. El DELETE falla
   silenciosamente.

Resultado: los sources existen en `Autor B/` pero nunca se registró que su path anterior fue removido (no hay
soft-delete del path viejo).

#### Caso B (DELETE primero, RENAME después)

1. DELETE `Autor A/libro1.pdf`: soft-deletea el source (deleted_at = now).
2. RENAME (sourceId: 1): busca el source por ID, lo encuentra pero está soft-deleteado. Comportamiento indefinido — la
   tabla de clasificación no cubre RENAME con deletedAt ≠ null.

## Causa raíz

La operación DELETE usa `path` como identificador, mientras que RENAME usa `sourceId`. Esto crea un coupling implícito
entre operaciones cuando un mismo source aparece en ambas listas. El orden de procesamiento determina el resultado.

## Soluciones propuestas

### Opción 1: DELETE por sourceId

Cambiar la operación DELETE para que use `sourceId` en lugar de (o además de) `path`. La API busca el source por ID,
verifica que su path ya no exista en FS, y soft-deletea.

| Tipo             | sourceId | path     |
|------------------|----------|----------|
| DELETE actual    | —        | ✓        |
| DELETE propuesto | ✓        | opcional |

### Opción 2: Orden garantizado dentro del batch

El Agent envía primero todos los RENAME, luego todos los DELETE. La API procesa en orden de recepción. Los DELETE contra
paths ya renombrados se convierten en no-op.

### Opción 3: Lógica de resolución en la API

La API detecta que un mismo source aparece como RENAME y DELETE en el mismo batch y lo resuelve atómicamente: (1)
transfiere metadatos, (2) actualiza path, (3) soft-deletea el path viejo — todo en una transacción.

## Recomendación

Opción 1 + Opción 2. DELETE por sourceId elimina la ambigüedad, y ordenar RENAME antes que DELETE es una salvaguarda
simple. Son complementarias y de bajo costo de implementación.

## Impacto

- Contrato de POST /reconcile: campo `sourceId` pasa de opcional a requerido en DELETE.
- Lógica del Agent: al clasificar, emitir primero los RENAME, luego los DELETE.
- Lógica de la API: procesar en orden de llegada; DELETE busca por sourceId.
