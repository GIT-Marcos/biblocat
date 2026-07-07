# Issue: Prefijo `\\?\` en Windows para paths largos

**Estado: ❌ No resuelto**
**Severidad: 🟩 1**

**Nota:** Pendiente de decidir entre NIO puro (sin `\\?\`) o soporte con APIs legacy. Diseño bifurcado en EC5.

## Contexto

La documentación actual (EC5) indica que para manejar paths que exceden el límite de 260 caracteres (MAX_PATH) en
Windows, se debe prefijar el path con `\\?\`.

Sin embargo, el uso de `\\?\` tiene implicaciones técnicas importantes que no están documentadas.

## Problemas identificados

### 1. `\\?\` desactiva la normalización de paths

Cuando un path comienza con `\\?\`, Windows API:

- NO resuelve `.` y `..` (relative segments).
- NO convierte `/` a `\` (forward slashes no se normalizan).
- NO elimina trailing spaces ni dots al final de nombres.
- Requiere que el path sea **absoluto** (los paths relativos no funcionan).

### 2. Interacción con `toRealPath()` (EC1)

El Agent ejecuta `rootDir.toRealPath()` para resolver symlinks, y la documentación dice "luego prefijar con `\\?\`". El
orden correcto debe ser:

1. `toRealPath()` — obtiene el path absoluto real sin componentes relativos.
2. Verificar que el path no sea UNC (requiere `\\?\UNC\...`).
3. Prefijar con `\\?\` — construir `Path` con `Path.of("\\\\?\\" + realPath)`.

Si se invierte el orden (prefijar antes de `toRealPath()`), Java puede fallar al resolver el path.

### 3. UNC paths

Si el directorio raíz es un recurso de red (`\\server\share\biblioteca`), el prefijo correcto es
`\\?\UNC\server\share\biblioteca`, no `\\?\` + path normal.

```
Incorrecto: \\?\ \\server\share\biblioteca
Correcto:   \\?\UNC\server\share\biblioteca
```

### 4. `Files.walk()` con paths largos en Java 21

Java NIO soporta paths de más de 260 caracteres desde Java 8 update 101 sin necesidad de `\\?\` cuando se usa
exclusivamente NIO (`Path`, `Files`, etc.). El prefijo es necesario solo cuando se mezcla NIO con APIs legacy.

**Pregunta clave:** ¿El Agent usa alguna API legacy de Windows que requiera `\\?\`, o usa exclusivamente NIO?

Si usa solo NIO (`Files.walk()`, `Files.size()`, `DigestInputStream` sobre `FileChannel`), el prefijo `\\?\` **no es
necesario** y agregarlo puede causar problemas de compatibilidad.

### 5. Construcción de paths hijos

Durante un `Files.walk()` con un root prefijado con `\\?\`, Java debe construir paths hijos correctamente. Por ejemplo:

```
Root: \\?\C:\biblioteca
Archivo hijo: \\?\C:\biblioteca\autor\libro.pdf
```

Al hacer `root.resolve("autor/libro.pdf")`, Java NIO genera correctamente el path completo con el prefijo. Pero hay que
verificar que `Files.walk()` propague correctamente el prefijo a los hijos — debe funcionar, pero necesita prueba.

## Preguntas a resolver

1. ¿El Agent usa exclusivamente NIO o mezcla con APIs legacy?
2. Si usa solo NIO → no se necesita `\\?\`, simplifica mucho.
3. Si usa APIs legacy → documentar el orden exacto de operaciones y el manejo de UNC.
4. ¿Se soportan rutas UNC como directorio raíz?

## Recomendación

- Si el Agent usa exclusivamente NIO (recomendado): eliminar la referencia a `\\?\` de la documentación y confiar en el
  soporte nativo de Java 21 para paths largos.
- Si se necesita soporte UNC: documentar el formato `\\?\UNC\...`.
- En cualquier caso: especificar el orden exacto de `toRealPath()` + prefijo.

## Impacto

- Si se usa NIO puro: sin cambios en implementación, solo documentación.
- Si se necesitan APIs legacy: agregar lógica de normalización de paths y pruebas.
