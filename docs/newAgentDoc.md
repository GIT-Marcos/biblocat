# newAgentDoc

## 1. Stack detallado de tecnologías y dependencias

## 2. Diagrama de componentes

## 3. Sincronización

### 3.1. Visión general

La reconciliación es el proceso mediante el cual el Agent sincroniza el estado del filesystem con el estado registrado
en la base de datos. Se ejecuta en tres escenarios:

- **Al iniciar** el Agent (*full scan*).
- **Periódicamente** cada 5 minutos (o según intervalo configurado).
- **A petición** del usuario desde el frontend (reconciliación manual).

La API es la fuente de verdad del estado registrado. El FS es la fuente de verdad del estado actual. El Agent actúa como
intermediario: computa el delta entre ambos y envía las operaciones resultantes a la API.

El Agent **no mantiene ningún archivo local** de estado. Cada reconciliación comienza consultando a la API el estado
conocido y recorriendo el FS desde cero.

### 3.2. Flujo completo del proceso

```mermaid
flowchart TD
    A[Inicio de reconciliación] --> B[GET /api/sources/paths]
    B --> C[Files.walk del directorio raíz]
    C --> D[Clasificar cada archivo vs estado API]
    D --> E{"¿Requiere hash?"}
    E -->|No| F[Agrupar en batch]
    E -->|Sí| G[Computar SHA-256 con timeout]
    G --> F
    F --> H[POST /api/sources/reconcile]
    H --> I[API procesa y persiste]
```

El proceso completo consta de 8 pasos:

1. Agent solicita el estado actual de la API (`GET /api/sources/paths`).
2. Agent recorre el directorio raíz con `Files.walk()`, respetando la profundidad máxima configurada.
3. Agent filtra archivos por extensión: solo `.pdf`, `.epub`, `.mhtml`.
4. Agent clasifica cada archivo contra el estado conocido según la tabla de clasificación (§3.5).
5. Para los archivos que lo requieren, Agent computa SHA-256 con timeout configurable (§3.6).
6. Agent agrupa las operaciones resultantes en batches de N elementos (default: 50).
7. Agent envía cada batch a la API (`POST /api/sources/reconcile`).
8. API procesa cada operación, persiste los cambios y responde con resumen.

### 3.3. Consulta del estado conocido (GET /api/sources/paths)

El Agent inicia cada reconciliación obteniendo el estado actual de la base de datos desde la API.

**Endpoint:** `GET /api/sources/paths`

**Response (200 OK):**

```json
[
  {
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "path": "biblioteca/autor/libro.pdf",
    "contentHash": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
    "pathLower": "biblioteca/autor/libro.pdf",
    "deletedAt": null
  }
]
```

**Retry:** Los reintentos ante fallo de conexión son configurables (default: 3 intentos con backoff de 2s, 4s, 8s). Si
se agotan, la reconciliación se aborta y no se envía ningún cambio.

### 3.4. Escaneo del filesystem

El Agent recorre el directorio raíz de biblioteca usando la API estándar de Java NIO.

**Tecnología:** `java.nio.file.Files.walk(path, maxDepth, options)`

**Reglas de escaneo:**

- Profundidad máxima configurable (default: 10 niveles).
- No se siguen symlinks ni junctions (`FOLLOW_LINKS` no se establece).
- Se filtran únicamente archivos con extensiones `.pdf`, `.epub`, `.mhtml` (comparación case-insensitive).
- Archivos con otras extensiones se registran en log DEBUG y se omiten.
- Archivos bloqueados por otro proceso se saltan con log WARN.
- Archivos que desaparecen durante el escaneo se manejan como si nunca hubieran existido.
- El directorio raíz se resuelve con `rootDir.toRealPath()` al iniciar, para eliminar dependencia de symlinks o
  junctions. El path real se usa durante toda la sesión.
- Antes de cada walk se verifica que el directorio raíz exista. Si no existe, se aborta la reconciliación.

**Normalización de paths:**

- Separadores `\` se convierten a `/`.
- Se preserva el casing original del archivo para mostrar al usuario.
- Para comparación se genera un campo `pathLower` (lowercase + `/`), enviado a la API para detección de duplicados.
- Los paths se normalizan a Unicode NFC (`Normalizer.normalize(path, Normalizer.Form.NFC)`) para consistencia con la
  normalización NFC nativa de Windows.

### 3.5. Clasificación de archivos

Por cada archivo en el FS, el Agent determina su relación con el estado conocido aplicando la siguiente tabla de
decisión:

| # | Archivo en FS | Existe en API (path) | Hash coincide       | `deletedAt` en API | Clasificación                    |
|---|---------------|----------------------|---------------------|--------------------|----------------------------------|
| A | Sí            | Sí                   | Sí                  | `null`             | Sin cambios — skip               |
| B | Sí            | Sí                   | Sí                  | ≠ `null`           | **REACTIVATE**                   |
| C | Sí            | Sí                   | No                  | `null`             | **UPDATE** (safe-save)           |
| D | Sí            | No                   | Sí (en otro source) | cualquiera         | **RENAME**                       |
| E | Sí            | No                   | No                  | —                  | **CREATE**                       |
| F | No            | Sí                   | —                   | `null`             | **DELETE** (soft-delete)         |
| G | No            | Sí                   | —                   | ≠ `null`           | Sigue siendo orphan — skip       |
| H | Sí            | Sí                   | No                  | ≠ `null`           | **CREATE** (orphan sigue orphan) |

**Notas sobre la clasificación:**

- Los casos **D** y **E** requieren computar el hash para determinar si el archivo es un renombre o es realmente nuevo.
- El caso **B** requiere computar hash para confirmar que el contenido coincide (seguridad ante falsas reactivaciones).
- El caso **H** captura archivos que aparecen donde antes había un source soft-deleteado, pero con contenido diferente.
  No se reactiva — se crea un nuevo source y el orphan sigue huérfano.
- **Caso D (RENAME):** Si el source origen del RENAME está soft-deleteado (`deletedAt ≠ null`), la API lo reactiva
  (limpia `deletedAt`) al procesar el RENAME. Si múltiples sources tienen el mismo hash que el archivo en FS, se
  prioriza: (1) activos sobre soft-deleteados, (2) path alfabéticamente menor.
- Para el caso **A**, el hash se computa siempre (no hay optimización activa). Ver `docs/Optimization01.md` para
  estrategias futuras.

### 3.6. Cómputo de hash SHA-256

**Algoritmo:** `java.security.MessageDigest.getInstance("SHA-256")` combinado con `DigestInputStream`.

**Modalidad:** Secuencial (un archivo a la vez). El cuello de botella suele ser I/O de disco, no CPU.

**Protecciones:**

- Timeout configurable por archivo (default: 30s). Si expira, se salta y se reintenta en el próximo escaneo.
- Tamaño máximo configurable (default: 500 MB). Archivos mayores se saltan con log WARN.
- Si el archivo se trunca o desaparece durante la lectura, se captura la `IOException`, se loguea y se continúa.
- **Write race:** se verifica `Files.size()` antes y después del cómputo de hash. Si el tamaño cambió durante la
  lectura, se descarta el hash y se incrementa un contador de reintentos consecutivos para ese archivo. Se
  salta con log WARN si el contador es ≤ `biblocat.agent.hash.max-retries`, o log ERROR si lo supera. El
  archivo se reintenta en cada escaneo, pero después de los archivos sin reintentos previos. El contador se
  resetea al hashear exitosamente. El contador es volátil (memoria, se pierde al reiniciar el Agent).

**Optimización:** No implementada. El Agent computa SHA-256 siempre que la tabla de clasificación lo requiere. Ver
`docs/Optimization01.md` para el diseño de una optimización futura basada en timestamps del filesystem.

### 3.7. Inferencia de autor

El Agent extrae el nombre del autor desde la carpeta padre inmediata dentro del directorio raíz.

**Reglas:**

1. Se calcula el path relativo del archivo respecto al directorio raíz.
2. El primer segmento del path relativo es el nombre de la carpeta del autor.
3. Si el archivo está directamente en la raíz (sin subdirectorios), `authorName = null`.
4. El nombre se normaliza: strip (eliminar espacios al inicio y final). Se preserva el casing original del nombre de la
   carpeta.
5. El Agent envía `authorName` como string; la API se encarga de buscar o crear la entidad Author.

**Ejemplos:**

| Path en FS                                                   | authorName inferido      |
|--------------------------------------------------------------|--------------------------|
| `biblioteca/Gabriel García Márquez/Cien años de soledad.pdf` | `Gabriel García Márquez` |
| `biblioteca/Anónimo/poema.pdf`                               | `Anónimo`                |
| `biblioteca/libro.pdf`                                       | `null`                   |

**RENAME:** El Agent envía `authorName` de la misma forma que en CREATE. El autor se re-infere del nuevo path usando las
reglas de inferencia (§3.7) y se incluye en la operación RENAME. La API nunca infiere autor; solo persiste el valor
recibido.

### 3.8. Edge cases

Los edge cases se organizan por la fase del proceso de reconciliación en la que ocurren.

#### A. Previo al escaneo — validaciones antes de iniciar la reconciliación

| # | Caso                                                    | Comportamiento                                                                                                                                                                                                                                                                                              |
|---|---------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| 1 | Root-dir es symlink / junction                          | Resolver con `rootDir.toRealPath()` al iniciar. Log INFO con el path real. Usar el path resuelto durante toda la sesión.                                                                                                                                                                                    |
| 2 | Root-dir no existe al iniciar                           | Validar con `Files.exists()` y `Files.isDirectory()`. Si no existe: log ERROR, abortar con código de salida ≠ 0. Esperar intervención del usuario.                                                                                                                                                          |
| 3 | Network drive se desconecta durante el walk             | Capturar `AccessDeniedException`. Verificar que root siga respondiendo antes de enviar soft-deletes masivos. Si root no responde: abortar reconciliación, log ERROR.                                                                                                                                        |
| 4 | Root-dir se renombra o elimina mientras el agente corre | Antes de cada walk verificar que root exista. Si no: abortar, log ERROR, registrar health check. No reintentar automáticamente.                                                                                                                                                                             |
| 5 | Path excede el límite de longitud del SO                | Limitación conocida de MAX_PATH (260 chars) en Windows. Si el Agent usa exclusivamente NIO (Java 21), confiar en el soporte nativo de paths largos sin `\\?\`. Si se usan APIs legacy, prefijar con `\\?\` después de `toRealPath()`, manejando el caso UNC con `\\?\UNC\...`. Ver `docs/IssueLongPath.md`. |

#### B. Durante el escaneo del árbol

| #  | Caso                                                            | Comportamiento                                                         |
|----|-----------------------------------------------------------------|------------------------------------------------------------------------|
| 6  | Archivos ocultos                                                | Se procesan (atributo Hidden). Log DEBUG.                              |
| 7  | Nombres reservados de Windows (`CON`, `NUL`, `COM1`, `aux.txt`) | Capturar `IOException`. Log WARN con el nombre del archivo. Continuar. |
| 8  | Profundidad de directorios excedida                             | Ignorar archivos más allá del límite configurado, log DEBUG.           |
| 9  | Extensión no soportada (`.txt`, `.doc`, etc.)                   | Saltar, log DEBUG.                                                     |
| 10 | Carpetas sin archivos compatibles                               | Ignoradas. No generan ninguna operación.                               |

#### C. Durante el cómputo de hash

| #  | Caso                                                      | Comportamiento                                                                                                                                                 |
|----|-----------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------|
| 11 | Archivo bloqueado / siendo escrito por otro proceso       | Saltar, log WARN. Se reintenta en el próximo escaneo.                                                                                                          |
| 12 | Archivo sin permisos de lectura (`AccessDeniedException`) | Saltar, log WARN.                                                                                                                                              |
| 13 | Archivo de 0 bytes                                        | Procesar normalmente. SHA-256 del contenido vacío es un valor conocido y válido.                                                                               |
| 14 | Archivo mayor al tamaño máximo configurable               | Saltar, log WARN. No se computa hash ni se cataloga.                                                                                                           |
| 15 | Timeout de hash excedido                                  | Saltar, log WARN. Se reintenta en el próximo escaneo.                                                                                                          |
| 16 | Archivo desaparece durante el hash (`IOException`)        | Saltar, log DEBUG.                                                                                                                                             |
| 17 | **Write race** — archivo siendo escrito durante el hash   | Verificar `Files.size()` antes y después del cómputo. Si el tamaño cambió: descartar el hash, saltar el archivo, log WARN. Se reintenta en el próximo escaneo. |
| 18 | Archivo se trunca durante la lectura                      | Caso tolerado. `DigestInputStream` captura la `IOException`. Log WARN, continuar con el siguiente archivo.                                                     |

#### D. Clasificación

| #  | Caso                                                      | Comportamiento                                                                                                                                                                                                   |
|----|-----------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| 19 | Carpeta de autor renombrada en el FS                      | Cada archivo se clasifica como RENAME. La API actualiza el author en cada source.                                                                                                                                |
| 20 | Hash duplicado con path diferente                         | Clasificar como RENAME.                                                                                                                                                                                          |
| 21 | Archivo en raíz del directorio de biblioteca              | `authorName = null`. `author_id` queda NULL en DB.                                                                                                                                                               |
| 22 | Múltiples archivos con el mismo contenido (hash idéntico) | Agrupar por hash. Si hay más de un CREATE con el mismo hash, usar orden alfabético de path como tiebreaker para garantizar comportamiento determinista.                                                          |
| 23 | Dos archivos que difieren solo en casing                  | No aplica. Windows tiene FS case-insensitive, `Files.walk()` nunca puede encontrar dos archivos que difieran solo en casing. El índice único en `pathLower` en la DB se mantiene como restricción de integridad. |
| 24 | Unicode NFC en nombres de archivo                         | Normalizar con `Normalizer.normalize(path, Normalizer.Form.NFC)` al leer del FS y al computar `pathLower`. Consistencia con la normalización NFC nativa de Windows.                                              |

#### E. Comunicación con la API

| #  | Caso                                                | Comportamiento                                                                                                                                                                                                                                                                                                                                                                                               |
|----|-----------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| 25 | Path duplicado en la respuesta de la API            | Ignorar el duplicado, usar el primero y log WARN                                                                                                                                                                                                                                                                                                                                                             |
| 26 | GET /api/sources/paths falla tras agotar reintentos | Abortar la reconciliación. No se envían cambios.                                                                                                                                                                                                                                                                                                                                                             |
| 27 | POST /api/sources/reconcile falla a mitad           | Modelo de éxito parcial: la API responde siempre 200 con un array `errors` para operaciones individuales fallidas. El Agent verifica `response.errors`: log WARN y continúa — las fallidas se reintentarán en el próximo escaneo. En 5xx + `IOException` de conexión: reintentar con backoff (configurable). `4xx` (excluyendo 409) → no reintentar, log ERROR, abandonar batch. El endpoint es idempotente. |
| 28 | API devuelve 409 Conflict                           | Log WARN, reintentar 1 vez. Si vuelve a fallar: abandonar batch, log ERROR.                                                                                                                                                                                                                                                                                                                                  |
| 29 | Archivo cambia entre GET /paths y POST /reconcile   | Ventana pequeña (minutos). La API procesa operaciones duplicadas como no-op. Si un CREATE falla porque el path ya no existe, la API lo maneja internamente. Caso de bajo impacto.                                                                                                                                                                                                                            |

#### F. Post-procesamiento y solapamiento

| #  | Caso                                                                                                                       | Comportamiento                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                              |
|----|----------------------------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| 30 | Orphan reactivado con hash distinto al almacenado                                                                          | Evaluar `deletedAt` primero. Si `deletedAt ≠ null` y hash coincide → REACTIVATE. Si `deletedAt ≠ null` y hash **no** coincide → CREATE (nuevo source) y el orphan sigue huérfano. Esto ya está reflejado en la tabla de clasificación (§3.5) con el nuevo caso H.                                                                                                                                                                                                                                                                                           |
| 31 | Move cross-filesystem (antes "Safe-save que cruza FS")                                                                     | El archivo se mueve entre volúmenes distintos (ej: C:\ → D:\). Windows implementa el move cross-filesystem como COPY+DELETE, no como rename atómico. El Agent lo detecta como CREATE + DELETE con hashes distintos. Los metadatos originales se preservan en el soft-delete del source original. Ver `docs/IssueSafeSaveCrossFS.md` para análisis detallado y soluciones de transferencia de metadatos.                                                                                                                                                     |
| 32 | Archivo de 0 bytes que luego se escribe con contenido                                                                      | CREATE con hash vacío, luego UPDATE en el siguiente escaneo. El source existe brevemente con metadatos vacíos, comportamiento correcto.                                                                                                                                                                                                                                                                                                                                                                                                                     |
| 33 | Cambio de hora (DST / ajuste de NTP)                                                                                       | Usar `ScheduledExecutorService.scheduleWithFixedRate()` que opera sobre el reloj monotónico e ignora cambios de hora real. No usar `Instant.now()` para calcular el próximo intervalo.                                                                                                                                                                                                                                                                                                                                                                      |
| 34 | Dos reconciliaciones superpuestas                                                                                          | Usar `AtomicBoolean` como lock. Si hay una reconciliación en curso: la periódica entrante se salta (log DEBUG), la manual entrante se encola (máximo 1 pendiente).                                                                                                                                                                                                                                                                                                                                                                                          |
| 35 | Reconciliación periódica tarda más que el intervalo                                                                        | Caso tolerado. Cubierto por el caso 34: la periódica salta si hay una en curso. Agregar métrica de duración para detectar escaneos lentos.                                                                                                                                                                                                                                                                                                                                                                                                                  |
| 36 | Archivos duplicados con el mismo contenido (hash idéntico)                                                                 | El caso D clasifica el archivo como RENAME cuando su hash coincide con un source existente, incluso si el usuario solo duplicó el archivo (no lo renombró). El source con metadatos "sigue" al nuevo path; el path original se recrea como CREATE en el próximo escaneo con metadatos vacíos. Comportamiento determinista gracias al tiebreaker alfabético (§3.5). Impacto bajo: la pérdida de metadatos es solo en el path original, no hay pérdida de datos irreversible.                                                                                 |
| 37 | **Safe-save en el mismo FS** — aplicaciones que usan el patrón DELETE+CREATE (ej: editores de texto, navegadores, Acrobat) | Entre el DELETE y el CREATE hay una ventana (ms) donde el archivo no existe en el FS. Si el escaneo ocurre en esa ventana, el source se clasifica como DELETE (caso F). En el próximo escaneo, el archivo reaparece con hash distinto → CREATE (caso E/H). Los metadatos se preservan en el orphan. Si la API implementa transferencia por hash (ver `docs/IssueSafeSaveCrossFS.md`), se recuperan automáticamente en el CREATE. Probabilidad: baja. Impacto: medio (pérdida temporal de metadatos hasta la transferencia por hash o re-asignación manual). |

### 3.9. Contrato de comunicación con la API

#### GET /api/sources/paths

Devuelve todos los sources registrados con los campos mínimos necesarios para la clasificación.

**Response (200):**

```json
[
  {
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "path": "biblioteca/autor/libro.pdf",
    "contentHash": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
    "pathLower": "biblioteca/autor/libro.pdf",
    "deletedAt": null
  }
]
```

#### POST /api/sources/reconcile

Envía un batch de operaciones para su persistencia. Cada operación es idempotente.

**Request:**

```json
{
  "operations": [
    {
      "type": "CREATE",
      "name": "Cien años de soledad.pdf",
      "path": "biblioteca/Gabriel García Márquez/Cien años de soledad.pdf",
      "pathLower": "biblioteca/gabriel garcía márquez/cien años de soledad.pdf",
      "contentHash": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
      "fileFormat": "PDF",
      "authorName": "Gabriel García Márquez"
    },
    {
      "type": "RENAME",
      "sourceId": "550e8400-e29b-41d4-a716-446655440000",
      "name": "Cien años de soledad",
      "path": "biblioteca/Gabriel García Márquez/Cien años de soledad.pdf",
      "pathLower": "biblioteca/gabriel garcía márquez/cien años de soledad.pdf",
      "authorName": "Gabriel García Márquez"
    },
    {
      "type": "UPDATE",
      "sourceId": "550e8400-e29b-41d4-a716-446655440002",
      "contentHash": "01ba4719c80b6fe911b091a7c05124b64eeece964e09c058ef8f9805daca546b"
    },
    {
      "type": "DELETE",
      "path": "biblioteca/eliminado.pdf"
    },
    {
      "type": "REACTIVATE",
      "path": "biblioteca/reactivado.pdf",
      "contentHash": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    }
  ]
}
```

**Response (200):**

```json
{
  "processed": 5,
  "created": 1,
  "renamed": 1,
  "updated": 1,
  "deleted": 1,
  "reactivated": 1,
  "errors": [
    {
      "type": "CREATE",
      "path": "biblioteca/error.pdf",
      "error": "UNSUPPORTED_FORMAT"
    }
  ]
}
```

**Campos requeridos por tipo de operación:**

| Tipo       | `type` | `sourceId` | `name` | `path` | `pathLower` | `contentHash` | `fileFormat` | `authorName` |
|------------|--------|------------|--------|--------|-------------|---------------|--------------|--------------|
| CREATE     | ✓      | —          | ✓      | ✓      | ✓           | ✓             | ✓            | opcional     |
| RENAME     | ✓      | ✓          | ✓      | ✓      | ✓           | —             | —            | opcional     |
| UPDATE     | ✓      | ✓          | —      | —      | —           | ✓             | —            | —            |
| DELETE     | ✓      | —          | —      | ✓      | —           | —             | —            | —            |
| REACTIVATE | ✓      | —          | —      | ✓      | —           | ✓             | —            | —            |

**Tamaño de batch:** Configurable (default: 50 operaciones por request). Si hay más operaciones que el límite, el Agent
las divide en múltiples requests secuenciales.

## 4. Inicio y ciclo de vida

### 4.1. Secuencia de inicio

### 4.2. Secuencia de cierre

### 4.3. Otros eventos

## 5. Procesos del agente

## 6. Configuraciones y propiedades

### 6.1. Propiedades del proceso de reconciliación

| Propiedad                              | Tipo   | Default     | Descripción                                                           |
|----------------------------------------|--------|-------------|-----------------------------------------------------------------------|
| `biblocat.agent.scan.root-dir`         | String | (requerido) | Ruta absoluta al directorio raíz de la biblioteca                     |
| `biblocat.agent.scan.period-seconds`   | int    | 300         | Intervalo entre reconciliaciones periódicas (segundos)                |
| `biblocat.agent.scan.max-depth`        | int    | 10          | Profundidad máxima de subdirectorios para escanear                    |
| `biblocat.agent.hash.timeout-seconds`  | int    | 30          | Timeout máximo para el cómputo de hash por archivo                    |
| `biblocat.agent.hash.max-file-size-mb` | int    | 500         | Tamaño máximo de archivo para hashear (MB). 0 = sin límite            |
| `biblocat.agent.hash.max-retries`      | int    | 3           | Reintentos consecutivos de hash antes de loguear ERROR por write-race |
| `biblocat.agent.batch.size`            | int    | 50          | Máximo de operaciones por request a la API                            |
| `biblocat.agent.retry.max-attempts`    | int    | 3           | Reintentos máximos ante fallo de conexión con la API                  |
| `biblocat.agent.retry.backoff-seconds` | int    | 2           | Backoff inicial entre reintentos (se duplica en cada intento)         |

## 7. Testing
