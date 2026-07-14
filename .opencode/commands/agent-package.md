---
description: Build del agent, empaquetado en zip listo para release
---

# /agent-package

Empaqueta el módulo agent en un zip distribuible con el JAR y el script de
instalación. El zip se crea en `dist/biblocat-agent-<version>.zip`.

## Restricciones estrictas (MANDATORY)

Seguir estas reglas sin excepción. Cualquier desviación debe ser autorizada
explícitamente por el usuario.

1. **No modificar archivos fuera del plan.** Solo se tocan:
    - `agent/pom.xml` (si el usuario lo pide)
    - `agent/install-agent.ps1` (si el usuario lo pide)
    - `.opencode/commands/agent-package.md` (este archivo)
    - `.gitignore` (si el usuario lo pide)
      En esta ejecución: **no se modifican archivos, solo se lee y se generan
      artefactos (JAR, zip).**

2. **Usar exactamente los comandos especificados.** No cambiar flags, rutas,
   ni herramientas. Ejemplos de lo que NO está permitido:
    - Usar `jar` en lugar de `mvnw package`
    - Usar `7-Zip` en lugar de `Compress-Archive`
    - Cambiar la ruta del zip a otro directorio

3. **No agregar ni omitir pasos.** El flujo tiene 7 pasos fijos. No saltar
   la verificación del zip, no agregar pasos extra (como subir a GitHub,
   pushear tags, etc.). Ejecutar en orden.

4. **Extraer la versión del POM. No hardcodear.** Si la versión no se puede
   leer del XML, abortar con error.

5. **Si un paso falla, abortar.** No continuar ni hacer suposiciones sobre
   por qué falló. Mostrar el error y detenerse.

6. **No generar output ni archivos extra.** El único archivo nuevo que debe
   existir al terminar es `dist/biblocat-agent-<version>.zip`.

7. **No preguntar al usuario durante la ejecución.** El comando es
   determinista. Si algo no está claro, revisar la documentación antes de
   preguntar.

## Prerrequisitos

- Java 21+ instalado
- Maven wrapper en `agent/mvnw`
- Los tests deben pasar (se ejecutan automáticamente)

## Flujo

### 1. Ejecutar tests del agent

Usa el comando `agent-test` (definido en `.opencode/commands/agent-test.md`). Si
falla algún test, abortar con mensaje de error y no continuar.

### 2. Build del JAR

```
cd agent && .\mvnw package -DskipTests
```

Esto genera `agent/target/agent-<version>.jar` (shaded uber-jar).

### 3. Leer versión del POM

Extraer la versión de `agent/pom.xml` con PowerShell:

```powershell
$version = Select-Xml -Path agent/pom.xml -XPath "//project/version" | Select-Object -ExpandProperty Node | Select-Object -ExpandProperty InnerText
```

La versión debe leerse automáticamente. No hardcodear.

### 4. Crear directorio `dist/`

```powershell
New-Item -ItemType Directory -Path dist -Force
```

### 5. Empaquetar zip

```powershell
$zipPath = "dist/biblocat-agent-$version.zip"
$null = New-Item -ItemType Directory -Path dist -Force
Compress-Archive -Path "agent/target/agent-$version.jar", "agent/install-agent.ps1", "agent/README.txt" -DestinationPath $zipPath -Force
```

### 6. Verificar contenido del zip

Abrir el zip usando `System.IO.Compression.ZipFile::OpenRead` y listar las
entradas. Verificar que contenga exactamente 3 archivos:

- `agent-<version>.jar`
- `install-agent.ps1`
- `README.txt`

Si faltan archivos, mostrar WARN.

### 7. Mostrar resumen

```
═══════════════════════════════════════════
  Release agent-<version> listo para subir
═══════════════════════════════════════════

✔ Tests pasados
✔ Build exitoso: agent-<version>.jar
✔ Zip verificado: dist\biblocat-agent-<version>.zip
  ├── agent-<version>.jar
  ├── install-agent.ps1
  └── README.txt

Próximos pasos para release manual:
  1. git tag agent-v<version>
  2. git push origin agent-v<version>
  3. GitHub → Releases → Create new release
     - Tag: agent-v<version>
     - Title: Agent v<version>
     - Upload: dist\biblocat-agent-<version>.zip
```

## Archivos generados

| Archivo              | Ruta                                                                       |
|----------------------|----------------------------------------------------------------------------|
| Zip listo para subir | `dist/biblocat-agent-<version>.zip` (JAR + install-agent.ps1 + README.txt) |

El directorio `dist/` ya está en `.gitignore`, los zips no se commitean.

## Post-ejecución: verificación obligatoria

Antes de dar el comando por terminado, confirmar:

- [ ] `dist/biblocat-agent-<version>.zip` existe
- [ ] El zip contiene exactamente 3 archivos: `agent-<version>.jar` + `install-agent.ps1` + `README.txt`
- [ ] No hay archivos nuevos fuera de `dist/`
- [ ] No se modificó ningún archivo fuente
