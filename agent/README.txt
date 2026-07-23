================================================================================
  BiblioCat Agent -- Asistente de sincronización
================================================================================

El Agent sincroniza automáticamente los archivos de la biblioteca (PDF, EPUB,
MHTML) con el sistema BiblioCat. Se ejecuta como proceso independiente (no como
servicio Windows).

===============================================================================
  EJECUCIÓN
===============================================================================

Ejecutar como Administrador (una sola vez) para copiar el JAR y generar la
configuración:

  powershell.exe -File setup-agent.ps1

Luego iniciar el Agent:

  java -jar "%ProgramFiles%\BiblioCat\Agent\agent-0.1.0.jar"

Detener con Ctrl+C en la terminal donde se ejecuta el proceso.

================================================================================
  CONFIGURACIÓN
================================================================================

Archivo: %ProgramData%\BiblioCat\agent\agent.properties

Parámetros principales:
  biblocat.agent.scan.root-dir    Ruta a la carpeta de la biblioteca
  biblocat.agent.api.base-url     URL de la API REST de BiblioCat
  biblocat.agent.scan.period-seconds  Intervalo entre sincronizaciones (def: 300)

Edite el archivo con cualquier editor de texto y reinicie el servicio.

===============================================================================
   LOGS
===============================================================================

Los registros están en:
  %ProgramData%\BiblioCat\agent\logs\
    agent.log          Bitácora principal (formato JSON)
    agent-error.log    Solo advertencias y errores (formato texto)

Cada archivo rota al llegar a 10 MB o diariamente.
  - agent.log:    se conservan hasta 7 archivos rotados
  - agent-error.log: se conservan hasta 30 archivos rotados

===============================================================================
   ACTUALIZACIÓN
===============================================================================

1. Detenga el proceso con Ctrl+C
2. Reemplace el JAR en:      %ProgramFiles%\BiblioCat\Agent\
3. Ejecute de nuevo:         java -jar "%ProgramFiles%\BiblioCat\Agent\agent-0.1.0.jar"

No necesita modificar la configuración ni los logs.

===============================================================================
   DESINSTALACIÓN
===============================================================================

Detenga el proceso con Ctrl+C y elimine los directorios:

  %ProgramFiles%\BiblioCat\Agent\
  %ProgramData%\BiblioCat\agent\

Los logs y configuración se conservan hasta que los borre manualmente.

================================================================================
  SOLUCIÓN DE PROBLEMAS
================================================================================

Problema: El servicio no inicia
  -> Revise los logs en %ProgramData%\BiblioCat\agent\logs\
  -> Verifique que Java 21+ este instalado: java -version

Problema: No se sincronizan archivos
  -> Verifique biblocat.agent.scan.root-dir en agent.properties
  -> Verifique que la API de BiblioCat este corriendo

Problema: Error de permisos
  -> Ejecute el script de instalación como Administrador
  -> Verifique permisos de lectura en root-dir

================================================================================
  MAS INFORMACIÓN
================================================================================

Documentación técnica: https://github.com/anomalyco/biblocat
Reportar errores:     https://github.com/anomalyco/biblocat/issues
================================================================================
