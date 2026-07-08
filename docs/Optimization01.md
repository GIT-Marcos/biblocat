# Optimization 01: Omitir hash en escaneos subsecuentes

## Contexto

Durante una reconciliación, el Agent computa SHA-256 para los archivos clasificados en los casos B, C, D, E y H de la
tabla de clasificación (ver `newAgentDoc.md` §2.5). En directorios con cientos o miles de archivos, el cómputo de hash
es la operación más costosa en términos de I/O de disco.

Para el **caso A** (path existe en API, hash coincide, `deletedAt = null`), el archivo no ha cambiado. En teoría, si
pudiéramos confirmar que el archivo no se modificó desde el último escaneo, podríamos evitar el hash.

## Problema

El Agent **no mantiene estado local** entre escaneos (`newAgentDoc.md` §2.1). La API es la fuente de verdad del estado
registrado. Para determinar si un archivo cambió sin hashear, el Agent necesita:

1. Saber el `lastModified` timestamp y el `size` del archivo en el momento del último escaneo.
2. Poder compararlos contra los valores actuales del FS.

La API actualmente no expone timestamps del filesystem en `GET /api/sources/paths` — solo expone `id`, `path`,
`contentHash`, `pathLower` y `deletedAt`. La entidad Source hereda `createdAt` y `updatedAt` (auditoría JPA), pero
estos registran cuándo se modificó el **registro en DB**, no cuándo se modificó el **archivo en disco**.

### ¿Por qué no sirven `createdAt` / `updatedAt`?

- `createdAt`: se setea una vez al crear el source. No cambia.
- `updatedAt`: se actualiza con cada operación que modifica el source (RENAME, UPDATE, etc.), **no** cuando el archivo
  en disco cambia.
- Un RENAME actualiza `updatedAt` pero el archivo en disco no se modificó → falso negativo (haría hashear de más, pero
  no de menos — seguro pero ineficiente).
- Un safe-save (UPDATE) actualiza `updatedAt` y el archivo SÍ cambió → sería correcto pero por accidente.

Conclusión: `updatedAt` **no es una señal confiable** para determinar si el archivo en disco cambió.

## Estrategias evaluadas

### A: API expone `fileLastModified` + `fileSize` en GET /paths

Agregar dos campos nuevos a la entidad Source: `fileLastModified` (Instant) y `fileSize` (long). Se setean en CREATE y
UPDATE. La API los incluye en la respuesta de `GET /api/sources/paths`:

```json
{
  "id": "550e8400-...",
  "path": "biblioteca/libro.pdf",
  "contentHash": "e3b0c44...",
  "pathLower": "biblioteca/libro.pdf",
  "deletedAt": null,
  "fileLastModified": "2026-07-04T10:30:00Z",
  "fileSize": 2048576
}
```

En cada escaneo, antes de hashear el caso A, el Agent compara:

```
Files.getLastModifiedTime(path) == source.fileLastModified
&& Files.size(path) == source.fileSize
```

Si ambos coinciden → skip hash (el archivo no cambió).

**Ventajas:**

- Sin estado local (la API es el estado).
- Persiste entre reinicios del Agent.
- La API ya tiene la info (se envía en CREATE y UPDATE).

**Desventajas:**

- Aumenta el payload del GET /paths (~50 bytes adicionales por source).
- Requiere migración Flyway para agregar columnas.
- El Agent debe convertir `fileLastModified` al zone GMT y normalizar precisión (segundos) para comparación correcta.

### B: Caché volátil en memoria (Map\<Path, FileTime\>)

El Agent mantiene en memoria un `Map<Path, FileTime>` que persiste durante su ciclo de vida. Se descarta al reiniciar.

```java
Map<Path, FileTime> lastScanTimestamps = new HashMap<>();
```

Al inicio de cada escaneo, para cada archivo en caso A:

1. Obtener `Files.getLastModifiedTime(path)`.
2. Si `lastScanTimestamps.get(path)` coincide → omitir hash.
3. Si no está en el mapa o es distinto → hashear y actualizar el mapa.

**Ventajas:**

- Sin cambios en la API.
- Fácil de implementar.
- La pérdida de estado al reiniciar es aceptable (un escaneo completo extra).

**Desventajas:**

- Consume memoria proporcional a la cantidad de archivos.
- No sobrevive a reinicios.
- Contradice parcialmente "sin estado local" (aunque es volátil en RAM, no en disco).

### C (elegida): Sin optimización — siempre hashear

No implementar ninguna optimización. El Agent computa SHA-256 para todos los archivos que la tabla de clasificación
requiera, sin atajo basado en timestamps.

**Ventajas:**

- Simplicidad máxima.
- Sin estado local (consistente con el diseño).
- Sin cambios en API ni en entidad Source.
- Sin riesgos de falsos negativos (omitir hash cuando el archivo sí cambió).

**Desventajas:**

- Mayor I/O de disco en cada escaneo.
- Más tiempo de CPU para archivos grandes sin cambios.

### D: Usar `updatedAt` de la entidad (descartada)

Usar el campo `updatedAt` (herencia de base class) como proxy de cambio.

**Desventaja fatal:**

- `updatedAt` se actualiza por operaciones que no cambian el archivo (RENAME, cambio de metadatos).
- Falsos negativos frecuentes: haría hashear archivos que no cambiaron, desperdiciando I/O.
- No aporta beneficio real sobre siempre hashear.

## Decisión

Se elige **Opción C: Sin optimización — siempre hashear**.

Motivos:

1. Consistencia con el principio de "sin estado local" (`newAgentDoc.md` §2.1).
2. Simplicidad de implementación.
3. Sin riesgo de falsos negativos.
4. El cuello de botella no es CPU sino I/O, y en la práctica los directorios de biblioteca personal
   rara vez superan unos pocos miles de archivos.

## Para retomar en el futuro

Si el rendimiento en directorios grandes (>10,000 archivos) lo requiere, implementar **Estrategia A**:

1. Agregar `file_last_modified TIMESTAMP` y `file_size BIGINT` a la tabla `sources` (migración Flyway).
2. Setear ambos campos en CREATE y en cada UPDATE (el Agent envía `contentHash` pero no timestamps; la API debe
   recibirlos o el Agent debe enviarlos en el contrato).
3. Incluir `fileLastModified` y `fileSize` en la respuesta de `GET /api/sources/paths`.
4. En el Agent, antes de hashear caso A: comparar timestamps + size.
5. Si coinciden → skip hash. Si no → hashear y el UPDATE enviará los nuevos valores.

**Nota:** El contrato de POST `/reconcile` necesitaría extenderse para que CREATE y UPDATE incluyan
`fileLastModified` y `fileSize`. Alternativamente, la API puede leerlos del FS, pero eso viola el principio
de "API no accede al filesystem".
