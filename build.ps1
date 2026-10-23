param(
    [string]$IdePath,
    [string[]]$Tasks = @('check', 'buildPlugin', 'verifyPluginStructure')
)

$ErrorActionPreference = 'Stop'

if (-not $IdePath) {
    $installRoot = Join-Path $env:ProgramFiles 'JetBrains'
    $candidate = Get-ChildItem -LiteralPath $installRoot -Directory -Filter 'PyCharm 2026.2*' -ErrorAction SilentlyContinue |
        Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'product-info.json') } |
        Sort-Object Name -Descending | Select-Object -First 1
    if ($candidate) { $IdePath = $candidate.FullName }
}

if (-not $IdePath -or -not (Test-Path -LiteralPath (Join-Path $IdePath 'product-info.json'))) {
    throw 'Pass -IdePath with a PyCharm 2026.2 installation directory.'
}

$resolvedIde = (Resolve-Path -LiteralPath $IdePath).Path
$runtimePath = Join-Path $resolvedIde 'jbr'
if (-not (Test-Path -LiteralPath (Join-Path $runtimePath 'bin\java.exe'))) {
    throw "Bundled Java runtime not found in $resolvedIde"
}

$previousJavaHome = $env:JAVA_HOME
try {
    $env:JAVA_HOME = $runtimePath
    Push-Location -LiteralPath $PSScriptRoot
    try {
        & .\gradlew.bat "-PlocalIdePath=$resolvedIde" @Tasks '--console=plain'
        if ($LASTEXITCODE -ne 0) { throw "Gradle failed with exit code $LASTEXITCODE" }
    } finally {
        Pop-Location
    }
} finally {
    $env:JAVA_HOME = $previousJavaHome
}
