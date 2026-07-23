<#
.SYNOPSIS
  Copia el Agent de BiblioCat al directorio de instalación y genera la configuración.

.DESCRIPTION
  Valida Java 21+, genera agent.properties, copia el JAR y README a
  %ProgramFiles%\BiblioCat\Agent, y muestra cómo ejecutar el Agent.

  El Agent se ejecuta como proceso independiente (no como servicio Windows).

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

.EXAMPLE
  .\setup-agent.psl                                        # interactivo
  .\setup-agent.psl -RootDir "D:\Biblioteca"               # semi-automatizado
  .\setup-agent.psl -RootDir "D:\Biblioteca" -ApiBaseUrl "http://api:8080"  # automatizado
#>

param(
    [string]$RootDir,
    [string]$ApiBaseUrl,
    [string]$JarPath = "$PSScriptRoot\agent-0.1.0.jar",
    [string]$ReadmePath = "$PSScriptRoot\README.txt"
)

$ErrorActionPreference = "Stop"

# ─── Constantes ───────────────────────────────────────────────────────────────

$ProgramFilesDir = "$env:ProgramFiles\BiblioCat\Agent"
$ProgramDataDir = "$env:ProgramData\BiblioCat\agent"
$LogsDir = "$ProgramDataDir\logs"
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

function Show-Summary
{
    Write-Host ""
    Write-Host "═══════════════════════════════════════════" -ForegroundColor Cyan
    Write-Host "  BiblioCat Agent — instalación completada" -ForegroundColor Cyan
    Write-Host "═══════════════════════════════════════════" -ForegroundColor Cyan
    Write-Host "  JAR:        $ProgramFilesDir\agent-0.1.0.jar"
    Write-Host "  README:     $ProgramFilesDir\README.txt"
    Write-Host "  Config:     $PropertiesFile"
    Write-Host "  Logs:       $LogsDir"
    Write-Host "  Root dir:   $RootDir"
    Write-Host "  API URL:    $ApiBaseUrl"
    Write-Host ""
    Write-Host "  Ejecutar:" -ForegroundColor Cyan
    Write-Host "    java -jar `"$ProgramFilesDir\agent-0.1.0.jar`""
    Write-Host ""
    Write-Host "  Detener:" -ForegroundColor Cyan
    Write-Host "    Ctrl+C en la terminal donde se ejecuta el proceso"
    Write-Host "═══════════════════════════════════════════" -ForegroundColor Cyan
}

# ─── Main ──────────────────────────────────────────────────────────────────────

function Main
{
    Assert-Administrator

    Write-Host "╔══════════════════════════════════════════╗" -ForegroundColor Cyan
    Write-Host "║    BiblioCat Agent — Setup               ║" -ForegroundColor Cyan
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

    Test-JarExists

    # 3. Instalación
    Write-Host "  Instalando Agent..." -ForegroundColor Yellow
    New-AgentDirectories
    New-AgentProperties
    Copy-Jar | Out-Null
    Copy-Readme

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
