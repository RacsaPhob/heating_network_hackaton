param([switch]$Rebuild, [switch]$BuildOnly)
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { throw 'Docker CLI not found. Install Docker Desktop and start Docker Engine.' }
& docker info --format '{{.ServerVersion}}' | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Docker Engine is not running.' }
$composeArgs = @('compose', '-p', 'heatnet-hackagecrew')
if ($BuildOnly) {
    & docker @composeArgs build app
} elseif ($Rebuild) {
    & docker @composeArgs up -d --build
} else {
    & docker @composeArgs up -d
}
if ($LASTEXITCODE -ne 0) { throw 'Docker Compose failed.' }
if (-not $BuildOnly) { Write-Host 'Application: http://127.0.0.1:8080/' }
