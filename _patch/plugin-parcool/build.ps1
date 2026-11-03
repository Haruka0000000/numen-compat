$ErrorActionPreference = 'Continue'
$modDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$project = Split-Path -Parent (Split-Path -Parent $modDir)
$srv = Join-Path $project 'Server'
$jdk = 'C:\Program Files\Java\jdk-17\bin'
$srg = Join-Path $srv 'libraries\net\minecraft\server\1.20.1-20230612.114412\server-1.20.1-20230612.114412-srg.jar'
$numenApi = Join-Path $project '_patch\nested_numen_api-forge-1.20.1-0.1.4.1.jar'
$parcool = (Get-ChildItem -LiteralPath (Join-Path $srv 'mods') -Filter 'ParCool-*.jar' -ErrorAction SilentlyContinue | Select-Object -First 1).FullName
$libs = Get-ChildItem -LiteralPath (Join-Path $srv 'libraries') -Recurse -Filter *.jar -ErrorAction SilentlyContinue | ForEach-Object { $_.FullName }
$all = @($srg, $numenApi, $parcool) + $libs
# javac on Windows decodes argv as ANSI and this workspace path is Chinese, so every path
# handed to javac is relative to the plugin directory (same trick as build_plugin.py).
function Rel([string] $full) {
    if ($full -and $full.StartsWith($modDir, [System.StringComparison]::OrdinalIgnoreCase)) {
        return $full.Substring($modDir.Length + 1)
    }
    if ($full -and $full.StartsWith($project, [System.StringComparison]::OrdinalIgnoreCase)) {
        return '..\..\' + $full.Substring($project.Length + 1)
    }
    return $full
}
$rel = @()
foreach ($p in $all) { if ($p) { $rel += (Rel $p) } }
$cp = $rel -join ';'
$src = Join-Path $modDir 'src'
$res = Join-Path $src 'main\resources'
$classes = Join-Path $modDir 'build\classes'
Set-Location -LiteralPath $modDir
if (Test-Path -LiteralPath $classes) { Remove-Item -LiteralPath $classes -Recurse -Force }
New-Item -ItemType Directory -Path $classes -Force | Out-Null
$files = @()
Get-ChildItem -LiteralPath $src -Recurse -Filter *.java | ForEach-Object {
    $files += ((Rel $_.FullName) -replace '\\', '/')
}
Set-Content -LiteralPath 'build\sources.txt' -Encoding ascii -Value $files
Write-Host "modDir=$modDir"
Write-Host "parcool=$parcool"
Write-Host "jars=$($rel.Count) sources=$($files.Count)"
$env:JAVA_TOOL_OPTIONS = "-Duser.language=en -Duser.country=US"
& "$jdk\javac.exe" -encoding UTF-8 -nowarn -cp $cp -d 'build/classes' '@build/sources.txt' 2>&1 | Select-Object -First 120
Write-Host "javac exit: $LASTEXITCODE"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
Copy-Item -Path (Join-Path $res '*') -Destination $classes -Recurse -Force
$jarOut = Join-Path $modDir 'build\numen_parcool-1.0.0.jar'
if (Test-Path -LiteralPath $jarOut) { Remove-Item -LiteralPath $jarOut -Force }
& "$jdk\jar.exe" --create --file 'build\numen_parcool-1.0.0.jar' -C 'build/classes' .
Write-Host "jar exit: $LASTEXITCODE -> $jarOut"
if (Test-Path -LiteralPath $jarOut) { (Get-Item -LiteralPath $jarOut).Length }
