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
if ([string]::IsNullOrWhiteSpace($PrinterJar)) { $PrinterJar = Join-Path $moduleDirectory 'build/libs/litematica-printer-3.4.2-mc1.21.4.jar' }

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
$originalLitematica = Verified-Jar $LitematicaJar '19fbf46ddc0776c2ff2a1be4cac627b3a236cc4682ce93d01c858e1437e93d4d'
$originalMalilib = Verified-Jar $MalilibJar '07e2c7569378e37059b35c0cf7a6530554f756737c8e341e1a8c2312dc2d4548'
$artifact = (Resolve-Path -LiteralPath $PrinterJar).ProviderPath
$loader = Cached-Jar 'net.fabricmc:fabric-loader:0.18.2'
$mappingId = '1.21.4-net.fabricmc.yarn.1_21_4.1.21.4+build.8-v2'
$minecraft = Join-Path $GradleUserHome "caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged-intermediary/$mappingId/minecraft-merged-intermediary-$mappingId.jar"
$mappings = Join-Path $GradleUserHome 'caches/fabric-loom/1.21.4/net.fabricmc.yarn.1_21_4.1.21.4+build.8-v2/mappings.tiny'
$versionInfo = Get-Content -Raw -LiteralPath (Join-Path $GradleUserHome 'caches/fabric-loom/1.21.4/mojang_minecraft_info.json') | ConvertFrom-Json
$classpath = @($loader, $minecraft)
foreach ($library in $versionInfo.libraries) {
    # Native bundles/macOS bridge are unused here. Fabric Loader selects its
    # own ASM version below; do not also add Minecraft 1.21.4's older ASM.
    if ($library.name -match ':natives-|^io\.netty:netty-transport-native-(epoll|kqueue):' -or
            $library.name -like 'ca.weblite:*' -or $library.name -like 'org.ow2.asm:*') { continue }
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
