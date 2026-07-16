================================================================================
  BiblioCat Agent -- Asistente de sincronización
================================================================================

El Agent sincroniza automáticamente los archivos de la biblioteca (PDF, EPUB,
MHTML) con el sistema BiblioCat. Se ejecuta como servicio de Windows.

================================================================================
  SERVICIO
================================================================================

El servicio se llama "BiblioCatAgent". Adminístrelo con NSSM:

  nssm start   BiblioCatAgent    Iniciar el servicio
  nssm stop    BiblioCatAgent    Detener el servicio
  nssm restart BiblioCatAgent    Reiniciar el servicio
  nssm status  BiblioCatAgent    Ver estado del servicio

También desde PowerShell:

  Get-Service BiblioCatAgent

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
================================================================================

1. Detenga el servicio:      nssm stop BiblioCatAgent
2. Reemplace el JAR en:      %ProgramFiles%\BiblioCat\Agent\
3. Inicie el servicio:       nssm start BiblioCatAgent

No necesita modificar la configuración ni los logs.

================================================================================
  DESINSTALACIÓN
================================================================================

Ejecute como Administrador:

  powershell.exe -File install-agent.ps1 -Uninstall

Esto detiene y remueve el servicio. Los archivos de configuración y logs se
conservan.

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
