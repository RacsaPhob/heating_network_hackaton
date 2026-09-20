param([switch]$Rebuild, [switch]$BuildOnly, [int]$Port = 8080)
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
$jdkDirectory = Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot '.tools') -Directory -Filter 'jdk-11*' -ErrorAction SilentlyContinue | Select-Object -First 1
if ($jdkDirectory) { $env:JAVA_HOME = $jdkDirectory.FullName }
if ($env:JAVA_HOME) { $javaExecutable = Join-Path $env:JAVA_HOME 'bin/java.exe' } else { $javaExecutable = (Get-Command java -ErrorAction Stop).Source }
$jarPath = Join-Path $PSScriptRoot 'backend/target/heatnet.jar'
if ($Rebuild -or $BuildOnly -or -not (Test-Path -LiteralPath $jarPath)) {
    $mavenPath = Join-Path $PSScriptRoot '.tools/apache-maven-3.9.9/bin/mvn.cmd'
    if (-not (Test-Path -LiteralPath $mavenPath)) { $mavenPath = (Get-Command mvn -ErrorAction Stop).Source }
    $repositoryPath = Join-Path $PSScriptRoot '.tools/m2'
    & $mavenPath -B "-Dmaven.repo.local=$repositoryPath" -f backend/pom.xml verify
    if ($LASTEXITCODE -ne 0) { throw 'Build or tests failed.' }
}
if ($BuildOnly) { exit 0 }
Write-Host "Heatnet: http://localhost:$Port"
Write-Host 'Press Ctrl+C to stop the server.'
& $javaExecutable '-Dfile.encoding=UTF-8' '-Xmx2g' -jar $jarPath "--server.port=$Port" '--server.address=127.0.0.1'
exit $LASTEXITCODE
