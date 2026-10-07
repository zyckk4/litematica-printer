[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$LitematicaJar,
    [Parameter(Mandatory = $true)][string]$MalilibJar,
    [string]$PrinterJar,
    [string]$GradleUserHome = $env:GRADLE_USER_HOME,
    [string]$JavaHome = $env:JAVA_HOME
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$moduleDirectory = Split-Path -Parent $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($GradleUserHome)) { $GradleUserHome = Join-Path $env:USERPROFILE '.gradle' }
if ([string]::IsNullOrWhiteSpace($JavaHome)) { throw 'Set JAVA_HOME or pass -JavaHome with a JDK 21 directory.' }
if ([string]::IsNullOrWhiteSpace($PrinterJar)) { $PrinterJar = Join-Path $moduleDirectory 'build/libs/litematica-printer-3.4.2-mc1.21.11.jar' }

function Cached-Jar([string]$Coordinate) {
    $pieces = $Coordinate.Split(':')
    $fileName = "$($pieces[1])-$($pieces[2])"
    if ($pieces.Length -gt 3) { $fileName += "-$($pieces[3])" }
    $fileName += '.jar'
    $directory = Join-Path $GradleUserHome "caches/modules-2/files-2.1/$($pieces[0])/$($pieces[1])/$($pieces[2])"
    $found = @(Get-ChildItem -LiteralPath $directory -File -Filter $fileName -Recurse -ErrorAction Stop)
    if ($found.Count -ne 1) { throw "Expected one cached $Coordinate, found $($found.Count)." }
    return $found[0].FullName
}
function Verified-Jar([string]$Path, [string]$Sha256) {
    $resolved = (Resolve-Path -LiteralPath $Path).ProviderPath
    if ((Get-FileHash -LiteralPath $resolved -Algorithm SHA256).Hash -ine $Sha256) { throw "Unexpected release hash: $resolved" }
    return $resolved
}
$originalLitematica = Verified-Jar $LitematicaJar '69df83a3d229a1fe77ddbeb54071a5ac07c2284ed4d623ea075d04e5f0a6d328'
$originalMalilib = Verified-Jar $MalilibJar '48461c24a560c68afc4042545b654d8d5ea3796a9339681485aed76a326f6ef3'
$artifact = (Resolve-Path -LiteralPath $PrinterJar).ProviderPath
$loader = Cached-Jar 'net.fabricmc:fabric-loader:0.18.2'
$mappingId = '1.21.11-net.fabricmc.yarn.1_21_11.1.21.11+build.3-v2'
$minecraft = Join-Path $GradleUserHome "caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged-intermediary/$mappingId/minecraft-merged-intermediary-$mappingId.jar"
$mappings = Join-Path $GradleUserHome 'caches/fabric-loom/1.21.11/net.fabricmc.yarn.1_21_11.1.21.11+build.3-v2/mappings.tiny'
$versionInfo = Get-Content -Raw -LiteralPath (Join-Path $GradleUserHome 'caches/fabric-loom/1.21.11/mojang_minecraft_info.json') | ConvertFrom-Json
$classpath = @($loader, $minecraft)
foreach ($library in $versionInfo.libraries) {
    # Platform native bundles and macOS bridge are unused by a class-load check.
    if ($library.name -match ':natives-|^io\.netty:netty-transport-native-(epoll|kqueue):' -or
            $library.name -like 'ca.weblite:*') { continue }
    $classpath += Cached-Jar $library.name
}
foreach ($asm in @('asm', 'asm-analysis', 'asm-commons', 'asm-tree', 'asm-util')) { $classpath += Cached-Jar "org.ow2.asm:${asm}:9.9" }
$classpath += Cached-Jar 'net.fabricmc:sponge-mixin:0.16.5+mixin.0.8.7'

$workDirectory = Join-Path $moduleDirectory ('build/production-smoke/' + [Guid]::NewGuid().ToString('N'))
$classes = Join-Path $workDirectory 'classes'
$mods = Join-Path $workDirectory 'mods'
New-Item -ItemType Directory -Path $classes,$mods -Force | Out-Null
Copy-Item -LiteralPath $originalLitematica,$originalMalilib,$artifact -Destination $mods
$java = Join-Path $JavaHome 'bin/java.exe'
$javac = Join-Path $JavaHome 'bin/javac.exe'
& $javac -cp $loader -d $classes (Join-Path $PSScriptRoot 'ProductionMixinSmoke.java')
if ($LASTEXITCODE -ne 0) { throw 'Compiling smoke launcher failed.' }
$classpath = @($classes) + $classpath
$arguments = @(
    '-Xmx1G', '-Dfabric.development=false', '-Dfabric.unitTest=true',
    '-Dfabric.gameMappingNamespace=intermediary', '-Dfabric.runtimeMappingNamespace=intermediary',
    "-Dfabric.gameJarPath.client=$minecraft", "-Dfabric.mappingPath=$mappings",
    '-cp', ($classpath -join [IO.Path]::PathSeparator), 'ProductionMixinSmoke', $mappings
)
$logFile = Join-Path $workDirectory 'smoke.log'
Get-FileHash -LiteralPath $artifact -Algorithm SHA256 | Format-List | Out-String | Set-Content -LiteralPath $logFile
Push-Location -LiteralPath $workDirectory
try {
    & $java @arguments 2>&1 | Tee-Object -FilePath $logFile -Append
    if ($LASTEXITCODE -ne 0) { throw "Production smoke failed. See $logFile" }
} finally { Pop-Location }
Write-Output "Production smoke log: $logFile"
