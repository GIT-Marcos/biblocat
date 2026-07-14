---
description: Actualiza la versión de un módulo y sincroniza todas las referencias en el repositorio
---

# /version

Actualiza la versión de uno o todos los módulos del proyecto. Busca y reemplaza
automáticamente todas las referencias a la versión en los archivos fuente,
documentación y scripts de deploy.

## Restricciones

El agente debe ceñirse estrictamente a este plan. No puede:

- Agregar ni deducir archivos o patrones fuera de la tabla en §1
- Ejecutar comandos que no sean los listados explícitamente (grep, git log, edit)
- Sugerir, preguntar o ejecutar acciones fuera del alcance: commit, tag, build, changelog, CI/CD, refactors, formateo de
  código
- Usar regex o patrones no listados para detectar versiones
- Modificar archivos que no estén en la tabla de §6
- En dry-run: modificar archivos (ni siquiera como "efecto secundario")

## Argumentos

| Flag        | Efecto                                               |
|-------------|------------------------------------------------------|
| (sin args)  | Modo interactivo completo                            |
| `--dry-run` | Solo muestra los cambios que haría; no modifica nada |

## Flujo

### 1. Escaneo automático

Busca en estos archivos con grep:

| Módulo | Patrón                                            | Archivos a escanear                                         |
|--------|---------------------------------------------------|-------------------------------------------------------------|
| agent  | `<version>`, `<finalName>`, `agent-<version>.jar` | `agent/pom.xml`, `agent/install-agent.ps1`, `docs/agent.md` |
| api    | `<version>`                                       | `api/pom.xml`                                               |
| front  | `"version"`                                       | `front/package.json`                                        |

### 2. Tabla resumen

Muestra al usuario el estado actual:

```
╔══════════════════════════════════╗
║  Versiones actuales              ║
╠════════╤══════════╤══════════════╣
║ Módulo │ Versión  │ Archivos     ║
╟────────┼──────────┼──────────────╢
║ agent  │ 0.1.0    │ pom.xml, ... ║
║ api    │ 0.0.1-SNAPSHOT │ pom.xml   ║
║ front  │ 0.0.0    │ package.json ║
╚════════╧══════════╧══════════════╝
```

### 3. Dry-run

Si se pasó `--dry-run`, muestra la tabla + el detalle de cada reemplazo
que se haría pero sin modificar archivos. Termina después del reporte.

### 4. Selección de módulo

Usa `question` para preguntar: **"¿Qué módulo quieres versionar?"**

Opciones: `[agent] [api] [front] [todos]`

### 5. Recomendación basada en commits

El agente infiere el bump SemVer analizando `git log` desde el último tag
del módulo hasta HEAD. Usa el tag que matchee el patrón `agent-v*`, `api-v*`
o `front-v*` según el módulo seleccionado.

**Si no hay tag previo:** recomienda `0.1.0`.

**Si hay tag:**

1. Ejecuta: `git log --oneline <último-tag>..HEAD`
2. Clasifica cada línea de commit por su tipo Conventional Commit:
    - `fix:` o `perf:` → patch
    - `feat:` → minor
    - `BREAKING CHANGE` o `feat!:`, `fix!:`, etc → major
    - `docs:`, `style:`, `refactor:`, `test:`, `chore:`, `build:`, `ci:` → no bump
3. Usa el bump más alto encontrado (major > minor > patch)
4. Muestra al usuario: `📊 3 feat, 2 fix, 5 otros desde agent-v0.1.0`

**Reglas:**

- Si no hay commits desde el último tag, informa "No hay cambios nuevos desde
  el último tag" y pregunta si igual quiere versionar.
- Si hay commits sin tipo Conventional (sin `tipo:`), los ignora para el bump
  pero los lista como "sin clasificar".
- La recomendación es informativa. El usuario siempre elige la versión final.
- Si `git log` falla (no hay repo, no hay commits), fallback a recomendación
  numérica simple basada en la versión actual.

Usa `question` para preguntar la versión, mostrando la recomendación como
default: **"¿Qué versión quieres? (recomendado: X.X.X)"**

### 6. Aplicar cambios

Por cada archivo del módulo seleccionado, usa `edit` (con `replaceAll=true`
cuando aplique) para reemplazar la versión anterior por la nueva.

**Reglas por archivo:**

| Archivo                   | Reemplazo                                                                   |
|---------------------------|-----------------------------------------------------------------------------|
| `agent/pom.xml`           | `<version>vieja</version>` → `<version>nueva</version>`                     |
|                           | `<finalName>agent-vieja</finalName>` → `<finalName>agent-nueva</finalName>` |
| `agent/install-agent.ps1` | `agent-vieja.jar` → `agent-nueva.jar`                                       |
| `docs/agent.md`           | `agent-vieja.jar` → `agent-nueva.jar`                                       |
| `api/pom.xml`             | `<version>vieja</version>` → `<version>nueva</version>`                     |
| `front/package.json`      | `"version": "vieja"` → `"version": "nueva"`                                 |

Para `todos`, aplicar la misma versión a los 3 módulos.

### 7. Validación post-cambio

Ejecuta un segundo grep con la **versión anterior** sobre los archivos
modificados. Si encuentra ocurrencias residuales:

```
⚠ Quedaron 2 referencias sin actualizar en:
  - docs/agent.md:835
  - docs/agent.md:800
Revisar manualmente.
```

Si no hay residuales:

```
✔ No quedaron referencias obsoletas.
```

El comando **no falla** si hay residuales; solo advierte.

### 8. Resumen final

Muestra:

```
✔ agent actualizado: 0.1.0 → 0.2.0
   Archivos modificados: agent/pom.xml, agent/install-agent.ps1, docs/agent.md
   Residuales: 0
```

## Ejemplos

```
/version                           # interactivo
/version --dry-run                  # vista previa sin modificar
```

## Edge cases

- Si un archivo esperado no existe, lo informa como WARN y continúa
- Si la versión actual no se encuentra en ningún archivo, el comando falla con mensaje claro
- Para `api`, si la versión contenía `-SNAPSHOT` y la nueva no, pregunta "¿Agregar `-SNAPSHOT` a la nueva versión?"
- Si no hay tag previo y el módulo tiene cambios sin versionar, recomienda `0.1.0`
- Si `git log` falla (no hay repo, no hay commits), fallback a recomendación numérica simple (patch/minor/major según
  versión actual)
