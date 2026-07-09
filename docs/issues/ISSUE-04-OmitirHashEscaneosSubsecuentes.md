# Issue: Omisión de hash en escaneos subsecuentes

**Estado: ❌ No resuelto**
**Severidad: 🟩 1**
**Tipo:** ✨ Mejora

**Nota:** El cómputo SHA-256 en cada escaneo es la operación más costosa en I/O de disco. Se evaluaron 4 estrategias; la
decisión actual es no optimizar (Opción C: siempre hashear), pero queda pendiente implementar la Estrategia A si el
rendimiento en directorios grandes (>10,000 archivos) lo requiere.

## Contexto

Durante una reconciliación, el Agent computa SHA-256 para los archivos clasificados en los casos B, C, D, E y H de la
tabla de clasificación. En directorios con cientos o miles de archivos, el hash es la operación dominante en costo de
I/O.

Para el **caso A** (path existe en API, hash coincide, `deletedAt = null`), el archivo no cambió. Si pudiéramos
confirmar que no se modificó desde el último escaneo, evitaríamos el hash.

El Agent **no mantiene estado local** entre escaneos. La API no expone timestamps del filesystem (`lastModified`,
`size`) en `GET /api/sources/paths`, solo expone `contentHash`. La entidad `Source` hereda `createdAt`/`updatedAt` (
auditoría JPA), que reflejan cambios en el registro de DB, no en el archivo en disco, por lo que no son una señal
confiable.

## Estrategias evaluadas

### A: API expone `fileLastModified` + `fileSize` en GET /paths

Agregar dos campos nuevos a la entidad `Source`. En cada escaneo, antes de hashear el caso A, el Agent compara
`Files.getLastModifiedTime(path) == source.fileLastModified && Files.size(path) == source.fileSize`. Si coinciden → skip
hash.

- **Ventaja:** Sin estado local (la API es el estado), persiste entre reinicios.
- **Desventaja:** Aumenta payload (~50 bytes extra por source), requiere migración Flyway, normalización de zona
  horaria.
- **Recomendada** como próximo paso si el rendimiento lo exige.

### B: Caché volátil en memoria (`Map<Path, FileTime>`)

El Agent mantiene en RAM un mapa de timestamps por path durante su ciclo de vida. Se descarta al reiniciar.

- **Ventaja:** Sin cambios en API, fácil de implementar.
- **Desventaja:** No sobrevive a reinicios, consume memoria, contradice parcialmente "sin estado local".

### C: Sin optimización — siempre hashear (actual)

El Agent computa SHA-256 para todos los archivos que la tabla requiera, sin atajo basado en timestamps.

- **Ventaja:** Simplicidad máxima, sin estado local, sin cambios en API/entidad, sin riesgo de falsos negativos.
- **Desventaja:** Mayor I/O de disco, más tiempo de CPU para archivos grandes sin cambios.

### D: Usar `updatedAt` de la entidad (descartada)

Usar `updatedAt` como proxy de cambio. Se descarta porque `updatedAt` se actualiza por operaciones que no cambian el
archivo (RENAME, metadatos), produciendo falsos negativos frecuentes sin beneficio real.

## Situación actual

Se ejecuta la **Opción C** (siempre hashear). Es consistente con el principio de "sin estado local" del Agent y minimiza
riesgos de implementación. En la práctica, los directorios de biblioteca personal rara vez superan unos pocos miles de
archivos, por lo que el impacto es aceptable.

## Próximo paso recomendado

Si el rendimiento en directorios grandes (>10,000 archivos) lo requiere, implementar la **Estrategia A**:

1. Agregar `file_last_modified TIMESTAMP` y `file_size BIGINT` a la tabla `sources` (migración Flyway).
2. Setear ambos campos en CREATE y en cada UPDATE (el Agent debe enviarlos o la API leerlos del FS — preferir que el
   Agent los envíe para no violar "API no accede al filesystem").
3. Incluir `fileLastModified` y `fileSize` en la respuesta de `GET /api/sources/paths`.
4. En el Agent, antes de hashear caso A: comparar timestamps + size.
5. Si coinciden → skip hash. Si no → hashear y el UPDATE enviará los nuevos valores.

## Impacto

- **Actual (Opción C):** I/O de disco completo en cada escaneo. Sin cambios en API, Agent ni DB.
- **Futuro (Opción A):** Migración Flyway, extensión del contrato en `POST /reconcile` y `GET /paths`, lógica de
  comparación en Agent. Beneficio: omisión de hash en archivos no modificados.

## Referencias
