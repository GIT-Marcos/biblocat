<#
.SYNOPSIS
  Instala o desinstala el Agent de BiblioCat como servicio Windows.

.DESCRIPTION
  Automatiza la configuración del Agent: verifica Java 21+, descarga NSSM si es
  necesario, genera agent.properties, instala el servicio BiblioCatAgent con
  NSSM y lo inicia.

  Ejecutar como Administrador (clic derecho → "Ejecutar como administrador").

.PARAMETER RootDir
  Ruta absoluta al directorio raíz de la biblioteca. Si se omite, se pide
  interactivamente.

.PARAMETER ApiBaseUrl
  URL base de la API REST. Si se omite, se pide interactivamente
  (default: http://localhost:8080).

.PARAMETER JarPath
  Ruta al JAR del Agent. Default: .\agent-0.1.0.jar (junto al script).

.PARAMETER ReadmePath
  Ruta al archivo README.txt. Default: .\README.txt (junto al script).
  Si no existe, se omite con un aviso (no fatal).

.PARAMETER Uninstall
  Detiene y remueve el servicio BiblioCatAgent. No elimina config ni logs.

.EXAMPLE
  .\install-agent.ps1                                        # interactivo
  .\install-agent.ps1 -RootDir "D:\Biblioteca"               # semi-automatizado
  .\install-agent.ps1 -RootDir "D:\Biblioteca" -ApiBaseUrl "http://api:8080"  # automatizado
  .\install-agent.ps1 -Uninstall                             # desinstalar
#>

param(
    [string]$RootDir,
    [string]$ApiBaseUrl,
    [string]$JarPath = "$PSScriptRoot\agent-0.1.0.jar",
    [string]$ReadmePath = "$PSScriptRoot\README.txt",
    [switch]$Uninstall
)

$ErrorActionPreference = "Stop"

# ─── Constantes ───────────────────────────────────────────────────────────────

$ServiceName = "BiblioCatAgent"
$ProgramFilesDir = "$env:ProgramFiles\BiblioCat\Agent"
$ProgramDataDir = "$env:ProgramData\BiblioCat\agent"
$LogsDir = "$ProgramDataDir\logs"
$NssmDir = "$env:ProgramData\BiblioCat\nssm"
$NssmExe = "$NssmDir\nssm.exe"
$NssmUrl = "https://nssm.cc/release/nssm-2.24.zip"
$NssmZip = "$env:TEMP\nssm-2.24.zip"
$PropertiesFile = "$ProgramDataDir\agent.properties"

# ─── Validación de administrador ──────────────────────────────────────────────

function Assert-Administrator
{
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = [Security.Principal.WindowsPrincipal]$identity
    if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator))
    {
        Write-Host "╔══════════════════════════════════════════╗" -ForegroundColor Red
        Write-Host "║  Este script debe ejecutarse como        ║" -ForegroundColor Red
        Write-Host "║  Administrador.                          ║" -ForegroundColor Red
        Write-Host "╚══════════════════════════════════════════╝" -ForegroundColor Red
        Write-Host ""
        Write-Host "Hacé clic derecho → 'Ejecutar como administrador'" -ForegroundColor Yellow
        throw "Permisos de Administrador requeridos"
    }
}

# ─── Pausa final ──────────────────────────────────────────────────────────────

function Stop-And-Exit($exitCode)
{
    Write-Host ""
    Write-Host "Presione Enter para cerrar esta ventana..." -ForegroundColor Gray
    $null = Read-Host
    exit $exitCode
}

# ─── Funciones auxiliares ─────────────────────────────────────────────────────

function Read-Parameters
{
    if (-not $RootDir)
    {
        $RootDir = Read-Host "📁 Ruta absoluta al directorio raíz de la biblioteca"
        $RootDir = $RootDir.Trim()
        if (-not $RootDir)
        {
            Write-Host "✘ El directorio raíz es obligatorio." -ForegroundColor Red
            throw "El directorio raíz es obligatorio."
        }
    }
    if (-not (Test-Path -LiteralPath $RootDir -PathType Container))
    {
        Write-Host "✘ El directorio '$RootDir' no existe. Verificá la ruta." -ForegroundColor Red
        throw "El directorio '$RootDir' no existe."
    }
    $script:RootDir = $RootDir

    if (-not $ApiBaseUrl)
    {
        $userInput = Read-Host "🔗 URL base de la API REST (Enter para default: http://localhost:8080)"
        $script:ApiBaseUrl = if ( [string]::IsNullOrWhiteSpace($userInput))
        {
            "http://localhost:8080"
        }
        else
        {
            $userInput.Trim()
        }
    }
    else
    {
        $script:ApiBaseUrl = $ApiBaseUrl
    }
}

function Test-Java21($javaExe)
{
    if (-not $javaExe)
    {
        Write-Host "✘ No se pudo ubicar java.exe en PATH ni en ubicaciones comunes." -ForegroundColor Red
        Write-Host "  Descargalo desde https://adoptium.net/" -ForegroundColor Yellow
        return $false
    }

    try
    {
        $version = & $javaExe -version 2>&1
        $versionStr = $version -join ' '
        if ($versionStr -match '"(?:1\.)?(\d+)')
        {
            $major = [int]$Matches[1]
            if ($major -ge 21)
            {
                Write-Host "✔ Java $major+ detectado en $javaExe" -ForegroundColor Green
                return $true
            }
        }
        Write-Host "✘ Se requiere Java 21+. Versión detectada: $versionStr" -ForegroundColor Red
        Write-Host "  en $javaExe" -ForegroundColor Red
        Write-Host "  Descargalo desde https://adoptium.net/" -ForegroundColor Yellow
        return $false
    }
    catch
    {
        Write-Host "✘ Error al ejecutar java.exe: $( $_.Exception.Message )" -ForegroundColor Red
        Write-Host "  Descargalo desde https://adoptium.net/" -ForegroundColor Yellow
        return $false
    }
}

function Resolve-JavaExe
{
    $candidates = @(
        "$env:ProgramFiles\Java\jdk-21\bin\java.exe",
        "$env:ProgramFiles\Eclipse Adoptium\jdk-*-hotspot\bin\java.exe",
        "$env:ProgramFiles\Amazon Corretto\jdk1.*\bin\java.exe",
        "${env:ProgramFiles(x86)}\Java\jdk-21\bin\java.exe"
    )
    foreach ($pattern in $candidates)
    {
        $match = Resolve-Path $pattern -ErrorAction SilentlyContinue
        if ($match)
        {
            return $match.Path
        }
    }

    return (Get-Command "java" -ErrorAction SilentlyContinue).Source
}

function Assert-NssmExists
{
    if (Test-Path -LiteralPath $NssmExe -PathType Leaf)
    {
        Write-Host "✔ NSSM disponible en $NssmExe" -ForegroundColor Green
        return
    }

    Write-Host "⬇ Descargando NSSM desde nssm.cc..." -ForegroundColor Yellow
    try
    {
        Invoke-WebRequest -Uri $NssmUrl -OutFile $NssmZip -UseBasicParsing
        $null = New-Item -ItemType Directory -Path $NssmDir -Force

        Add-Type -AssemblyName System.IO.Compression.FileSystem
        [System.IO.Compression.ZipFile]::ExtractToDirectory($NssmZip, $env:TEMP)
        $extractedNssm = Get-ChildItem -Path "$env:TEMP\nssm-*\win64\nssm.exe" | Select-Object -First 1
        if (-not $extractedNssm)
        {
            throw "No se encontró nssm.exe en el ZIP descargado."
        }
        Copy-Item -LiteralPath $extractedNssm.FullName -Destination $NssmExe -Force
        Remove-Item -Path $NssmZip -Force -ErrorAction SilentlyContinue
        Write-Host "✔ NSSM instalado en $NssmExe" -ForegroundColor Green
    }
    catch
    {
        Write-Host "✘ Error al descargar/instalar NSSM: $_" -ForegroundColor Red
        Write-Host ""

        if (Test-Path -LiteralPath $NssmExe -PathType Leaf)
        {
            Write-Host "✔ NSSM ya está presente en $NssmExe — se continúa" -ForegroundColor Green
            return
        }

        Write-Host "  Descargalo manualmente:" -ForegroundColor Yellow
        Write-Host "    1. Abrí $NssmUrl en un navegador" -ForegroundColor Yellow
        Write-Host "    2. Extraé nssm.exe de nssm-2.24\win64\ a $NssmDir" -ForegroundColor Yellow
        Write-Host "    3. Ejecutá el script de nuevo" -ForegroundColor Yellow
        throw "Error al descargar/instalar NSSM: $_"
    }
}

function Test-JarExists
{
    if (-not (Test-Path -LiteralPath $JarPath -PathType Leaf))
    {
        Write-Host "✘ No se encuentra el JAR en '$JarPath'." -ForegroundColor Red
        Write-Host "  Construilo primero con: .\mvnw package" -ForegroundColor Yellow
        Write-Host "  O pasá la ruta correcta con -JarPath" -ForegroundColor Yellow
        throw "No se encuentra el JAR en '$JarPath'."
    }
    Write-Host "✔ JAR encontrado: $JarPath" -ForegroundColor Green
}

function New-AgentDirectories
{
    $null = New-Item -ItemType Directory -Path $ProgramFilesDir -Force
    $null = New-Item -ItemType Directory -Path $ProgramDataDir -Force
    $null = New-Item -ItemType Directory -Path $LogsDir -Force
}

function New-AgentProperties
{
    $escapedRootDir = $RootDir -replace '\\', '/'
    $content = @"
biblocat.agent.scan.root-dir=$escapedRootDir
biblocat.agent.api.base-url=$ApiBaseUrl
biblocat.agent.scan.period-seconds=300
biblocat.agent.scan.max-depth=10
biblocat.agent.poll.interval-seconds=30
biblocat.agent.hash.timeout-seconds=30
biblocat.agent.hash.max-file-size-mb=500
biblocat.agent.hash.max-retries=3
biblocat.agent.batch.size=50
biblocat.agent.retry.max-attempts=3
biblocat.agent.retry.backoff-seconds=2
biblocat.agent.shutdown.grace-period-seconds=5
"@
    Set-Content -Path $PropertiesFile -Value $content -Encoding ASCII
    Write-Host "✔ Configuración generada en $PropertiesFile" -ForegroundColor Green
}

function Copy-Jar
{
    $destJar = "$ProgramFilesDir\agent-0.1.0.jar"
    Copy-Item -LiteralPath $JarPath -Destination $destJar -Force
    Write-Host "✔ JAR copiado a $destJar" -ForegroundColor Green
    return $destJar
}

function Copy-Readme
{
    $destReadme = "$ProgramFilesDir\README.txt"
    if (-not (Test-Path -LiteralPath $ReadmePath -PathType Leaf))
    {
        Write-Host "⚠  README.txt no encontrado en '$ReadmePath' — se omite" -ForegroundColor Yellow
        return
    }
    Copy-Item -LiteralPath $ReadmePath -Destination $destReadme -Force
    Write-Host "✔ README copiado a $destReadme" -ForegroundColor Green
}

function Install-Service
{
    $javaExe = Resolve-JavaExe
    if (-not $javaExe)
    {
        Write-Host "✘ No se pudo ubicar java.exe." -ForegroundColor Red
        throw "No se pudo ubicar java.exe."
    }

    $destJar = "$ProgramFilesDir\agent-0.1.0.jar"
    $appParameters = "-jar `"$destJar`""

    $null = & $NssmExe status $ServiceName 2>&1
    if ($LASTEXITCODE -eq 0)
    {
        Write-Host "⚠  El servicio '$ServiceName' ya existe — removiendo..." -ForegroundColor Yellow
        & $NssmExe stop $ServiceName 2>&1 | Out-Null
        & $NssmExe remove $ServiceName confirm 2>&1 | Out-Null
        Write-Host "✔ Servicio previo removido" -ForegroundColor Green
    }

    & $NssmExe install $ServiceName "`"$javaExe`"" $appParameters 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0)
    {
        Write-Host "✘ Error al instalar el servicio con NSSM (exit code: $LASTEXITCODE)" -ForegroundColor Red
        throw "Error al instalar el servicio con NSSM (exit code: $LASTEXITCODE)"
    }

    $nssmOps = @(
        @("set", $ServiceName, "AppDirectory", "`"$ProgramFilesDir`""),
        @("set", $ServiceName, "AppEnvironmentExtra", "BIBLOCAT_AGENT_CONFIG=$PropertiesFile"),
        @("set", $ServiceName, "AppExit", "Default", "Restart")
    )

    foreach ($op in $nssmOps)
    {
        & $NssmExe $op 2>&1 | Out-Null
        if ($LASTEXITCODE -ne 0)
        {
            Write-Host "⚠  Falló nssm $( $op -join ' ' ) (exit code: $LASTEXITCODE)" -ForegroundColor Yellow
        }
    }

    Write-Host "✔ Servicio '$ServiceName' instalado" -ForegroundColor Green
}

function Start-Service
{
    & $NssmExe start $ServiceName 2>&1 | Out-Null
    $status = & $NssmExe status $ServiceName 2>&1
    if ($LASTEXITCODE -eq 0 -and $status -match "SERVICE_RUNNING")
    {
        Write-Host "✔ Servicio '$ServiceName' iniciado" -ForegroundColor Green
    }
    else
    {
        Write-Host "⚠  El servicio se instaló pero no se pudo iniciar: $status" -ForegroundColor Yellow
        Write-Host "  Revisá los logs en $LogsDir" -ForegroundColor Yellow
    }
}

function Show-Summary
{
    $status = & $NssmExe status $ServiceName 2>&1
    Write-Host ""
    Write-Host "═══════════════════════════════════════════" -ForegroundColor Cyan
    Write-Host "  BiblioCat Agent — instalación completada" -ForegroundColor Cyan
    Write-Host "═══════════════════════════════════════════" -ForegroundColor Cyan
    Write-Host "  Servicio:   $ServiceName"
    Write-Host "  Estado:     $status"
    Write-Host "  JAR:        $ProgramFilesDir\agent-0.1.0.jar"
    Write-Host "  README:     $ProgramFilesDir\README.txt"
    Write-Host "  Config:     $PropertiesFile"
    Write-Host "  Logs:       $LogsDir"
    Write-Host "  Root dir:   $RootDir"
    Write-Host "  API URL:    $ApiBaseUrl"
    Write-Host ""
    Write-Host "  Comandos útiles:" -ForegroundColor Cyan
    Write-Host "    nssm start      $ServiceName"
    Write-Host "    nssm stop       $ServiceName"
    Write-Host "    nssm restart    $ServiceName"
    Write-Host "    nssm status     $ServiceName"
    Write-Host "    nssm edit       $ServiceName"
    Write-Host "    Get-Service     $ServiceName"
    Write-Host "═══════════════════════════════════════════" -ForegroundColor Cyan
}

function Stop-Service
{
    $status = & $NssmExe status $ServiceName 2>&1
    if ($status -match "SERVICE_RUNNING|SERVICE_PAUSED")
    {
        Write-Host "⏹ Deteniendo servicio '$ServiceName'..." -ForegroundColor Yellow
        & $NssmExe stop $ServiceName 2>&1 | Out-Null
        Start-Sleep -Seconds 2
        Write-Host "✔ Servicio detenido" -ForegroundColor Green
    }
}

function Uninstall-Service
{
    Stop-Service
    & $NssmExe remove $ServiceName confirm 2>&1 | Out-Null
    if ($LASTEXITCODE -eq 0)
    {
        Write-Host "✔ Servicio '$ServiceName' removido" -ForegroundColor Green
        Write-Host "  (no se eliminaron config ni logs en $ProgramDataDir)" -ForegroundColor Yellow
    }
    else
    {
        Write-Host "⚠  Error al remover el servicio (exit code: $LASTEXITCODE)" -ForegroundColor Yellow
    }
}

# ─── Main ──────────────────────────────────────────────────────────────────────

function Main
{
    Assert-Administrator

    if ($Uninstall)
    {
        Uninstall-Service
        return
    }

    Write-Host "╔══════════════════════════════════════════╗" -ForegroundColor Cyan
    Write-Host "║    BiblioCat Agent — Instalación         ║" -ForegroundColor Cyan
    Write-Host "╚══════════════════════════════════════════╝" -ForegroundColor Cyan
    Write-Host ""

    # 1. Parámetros
    Read-Parameters

    # 2. Prerrequisitos
    $javaExe = Resolve-JavaExe
    $ok = Test-Java21 $javaExe
    if (-not $ok)
    {
        throw "Java 21+ no encontrado."
    }

    Assert-NssmExists
    Test-JarExists

    # 3. Instalación
    Write-Host "  Instalando Agent..." -ForegroundColor Yellow
    New-AgentDirectories
    New-AgentProperties
    Copy-Jar | Out-Null
    Copy-Readme
    Install-Service
    Start-Service

    # 4. Resumen
    Show-Summary
}

try
{
    Main
    Stop-And-Exit 0
}
catch
{
    Write-Host ""
    Write-Host "✘ Error: $( $_.Exception.Message )" -ForegroundColor Red
    Stop-And-Exit 1
}
