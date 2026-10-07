[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$LitematicaJar,
    [Parameter(Mandatory = $true)][string]$MalilibJar,
    [string]$GradleUserHome = $env:GRADLE_USER_HOME,
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$AsmVersion = '9.10.1',
    [string]$OutputDirectory
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$moduleDirectory = Split-Path -Parent $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($GradleUserHome)) {
    $GradleUserHome = Join-Path $env:USERPROFILE '.gradle'
}
if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $OutputDirectory = Join-Path $moduleDirectory 'libs/named'
}
if ([string]::IsNullOrWhiteSpace($JavaHome)) {
    $javaExecutable = (Get-Command java -ErrorAction Stop).Source
} else {
    $javaExecutable = Join-Path $JavaHome 'bin/java.exe'
    if (!(Test-Path -LiteralPath $javaExecutable -PathType Leaf)) { throw "Java not found: $javaExecutable" }
}

function Resolve-CachedJar([string]$Group, [string]$Artifact, [string]$Version) {
    $directory = Join-Path $GradleUserHome "caches/modules-2/files-2.1/$Group/$Artifact/$Version"
    $fileName = "$Artifact-$Version.jar"
    $cachedCandidates = @(Get-ChildItem -LiteralPath $directory -Filter $fileName -File -Recurse -ErrorAction Stop)
    if ($cachedCandidates.Count -ne 1) { throw "Expected one cached $fileName in $directory, found $($cachedCandidates.Count)." }
    return $cachedCandidates[0].FullName
}

function Resolve-VerifiedInput([string]$Path, [string]$ExpectedSha256, [string]$ExpectedSha512) {
    $resolved = (Resolve-Path -LiteralPath $Path -ErrorAction Stop).ProviderPath
    if ((Get-FileHash -LiteralPath $resolved -Algorithm SHA256).Hash -ine $ExpectedSha256) {
        throw "SHA256 mismatch: $resolved. Use the exact official artifact listed in libs/README.md."
    }
    if ((Get-FileHash -LiteralPath $resolved -Algorithm SHA512).Hash -ine $ExpectedSha512) {
        throw "SHA512 mismatch: $resolved. Use the exact official artifact listed in libs/README.md."
    }
    return $resolved
}

$verifiedLitematica = Resolve-VerifiedInput $LitematicaJar `
    '69df83a3d229a1fe77ddbeb54071a5ac07c2284ed4d623ea075d04e5f0a6d328' `
    'c7d8e4ee3a1a4eacb825b19d389a06e17f6c4b78c3074fd929eb1bb1f5eabdbd77bd09545229b042e50f47aa78deac4005809a4c4bf554537e816e40e2885005'
$verifiedMalilib = Resolve-VerifiedInput $MalilibJar `
    '48461c24a560c68afc4042545b654d8d5ea3796a9339681485aed76a326f6ef3' `
    'c6a0cbc407962b43316e52b15d928aa2137efa86963cdcea29699f43efa6442519206bdbfac23e4c6c36237931006ecd12b9c6450a4efd1a9a20689e50b5622a'

$mappingId = '1.21.11-net.fabricmc.yarn.1_21_11.1.21.11+build.3-v2'
$mappings = Join-Path $GradleUserHome 'caches/fabric-loom/1.21.11/net.fabricmc.yarn.1_21_11.1.21.11+build.3-v2/mappings.tiny'
$minecraft = Join-Path $GradleUserHome "caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged-intermediary/$mappingId/minecraft-merged-intermediary-$mappingId.jar"
foreach ($required in @($mappings, $minecraft)) {
    if (!(Test-Path -LiteralPath $required -PathType Leaf)) { throw "Missing Loom 1.21.11/Yarn build.3 cache: $required" }
}
$toolClasspath = @(
    (Resolve-CachedJar 'net.fabricmc' 'tiny-remapper' '0.12.2'),
    (Resolve-CachedJar 'net.fabricmc' 'mapping-io' '0.8.0')
)
foreach ($artifact in @('asm', 'asm-commons', 'asm-tree', 'asm-analysis')) {
    $toolClasspath += Resolve-CachedJar 'org.ow2.asm' $artifact $AsmVersion
}

# A unique work directory avoids reusing partially written remap output. The
# source release jars are only read, and are never rewritten or repackaged.
$workDirectory = Join-Path $moduleDirectory ('build/remap-runtime/' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $workDirectory -Force | Out-Null
$javaArguments = @(
    '-Xmx1G', '-cp', ($toolClasspath -join [IO.Path]::PathSeparator), '--source', '21',
    (Join-Path $PSScriptRoot 'RemapRealMods.java'), $workDirectory, $mappings, $minecraft,
    $verifiedLitematica, $verifiedMalilib
)
& $javaExecutable @javaArguments
if ($LASTEXITCODE -ne 0) { throw "TinyRemapper failed with exit code $LASTEXITCODE. See $workDirectory." }

New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$sourceNames = @($verifiedLitematica, $verifiedMalilib)
$outputNames = @('litematica-fabric-1.21.11-0.26.16-named.jar', 'malilib-fabric-1.21.11-0.27.20-named.jar')
for ($index = 0; $index -lt $sourceNames.Count; $index++) {
    $generatedName = [IO.Path]::GetFileName($sourceNames[$index]).Replace('.jar', '-named.jar')
    $generated = Join-Path $workDirectory "named/$generatedName"
    $destination = Join-Path $OutputDirectory $outputNames[$index]
    Copy-Item -LiteralPath $generated -Destination $destination -Force
    Get-FileHash -LiteralPath $destination -Algorithm SHA256 | Format-List Algorithm, Hash, Path
}
