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
    '19fbf46ddc0776c2ff2a1be4cac627b3a236cc4682ce93d01c858e1437e93d4d' `
    'a4931c649fb21d52be1f12abd1a31950b63429e0e083974347b2e7bab3da8edb19930906790de70ef3b2803ea17059d2ef188c5e0ac13dfdb8272f1a1bb16f4d'
$verifiedMalilib = Resolve-VerifiedInput $MalilibJar `
    '07e2c7569378e37059b35c0cf7a6530554f756737c8e341e1a8c2312dc2d4548' `
    '03d0a554a2a6a069e9a1b624d9bb72c7e1f17a1e29f2c62e5c29284912829ba9a476cc34dec1a7ee8409ca08b46deb60527363659b4fa16d8a71d8110b67a18b'

$mappingId = '1.21.4-net.fabricmc.yarn.1_21_4.1.21.4+build.8-v2'
$mappings = Join-Path $GradleUserHome 'caches/fabric-loom/1.21.4/net.fabricmc.yarn.1_21_4.1.21.4+build.8-v2/mappings.tiny'
$minecraft = Join-Path $GradleUserHome "caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged-intermediary/$mappingId/minecraft-merged-intermediary-$mappingId.jar"
foreach ($required in @($mappings, $minecraft)) {
    if (!(Test-Path -LiteralPath $required -PathType Leaf)) { throw "Missing Loom 1.21.4/Yarn build.8 cache: $required" }
}
$toolClasspath = @(
    (Resolve-CachedJar 'net.fabricmc' 'tiny-remapper' '0.12.2'),
    (Resolve-CachedJar 'net.fabricmc' 'mapping-io' '0.8.0'),
    (Resolve-CachedJar 'com.google.code.gson' 'gson' '2.11.0')
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
$outputNames = @('litematica-fabric-1.21.4-0.21.7-named.jar', 'malilib-fabric-1.21.4-0.23.5-named.jar')
for ($index = 0; $index -lt $sourceNames.Count; $index++) {
    $generatedName = [IO.Path]::GetFileName($sourceNames[$index]).Replace('.jar', '-named.jar')
    $generated = Join-Path $workDirectory "named/$generatedName"
    $destination = Join-Path $OutputDirectory $outputNames[$index]
    Copy-Item -LiteralPath $generated -Destination $destination -Force
    Get-FileHash -LiteralPath $destination -Algorithm SHA256 | Format-List Algorithm, Hash, Path
}
