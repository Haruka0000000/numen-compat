$ErrorActionPreference = 'Continue'
$modDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$project = Split-Path -Parent (Split-Path -Parent $modDir)
$srv = Join-Path $project 'Server'
$jdk = 'C:\Program Files\Java\jdk-17\bin'
$srg = Join-Path $srv 'libraries\net\minecraft\server\1.20.1-20230612.114412\server-1.20.1-20230612.114412-srg.jar'
$modsDir = Join-Path $srv 'mods'
# The plugin compiles against numen's nested api jar (the jar-in-jar dependency inside the "all" jar),
# which is the exact artifact a third-party plugin links against at runtime.
$numenApi = Join-Path $project '_patch\nested_numen_api-forge-1.20.1-0.1.4.1.jar'
$lso = (Get-ChildItem -LiteralPath $modsDir -Filter 'legendarysurvivaloverhaul-*.jar' -ErrorAction SilentlyContinue | Select-Object -First 1).FullName
$curios = (Get-ChildItem -LiteralPath $modsDir -Filter 'curios-forge-*.jar' -ErrorAction SilentlyContinue | Select-Object -First 1).FullName
$libs = Get-ChildItem -LiteralPath (Join-Path $srv 'libraries') -Recurse -Filter *.jar -ErrorAction SilentlyContinue | ForEach-Object { $_.FullName }
$cp = (@($srg, $numenApi, $lso, $curios) + $libs) -join ';'
$src = Join-Path $modDir 'src'
$res = Join-Path $src 'main\resources'
$classes = Join-Path $modDir 'build\classes'
if (Test-Path -LiteralPath $classes) { Remove-Item -LiteralPath $classes -Recurse -Force }
New-Item -ItemType Directory -Path $classes -Force | Out-Null
$files = Get-ChildItem -LiteralPath $src -Recurse -Filter *.java | ForEach-Object { $_.FullName }
Write-Host "modDir=$modDir"
Write-Host "numenApi=$numenApi"
Write-Host "lso=$lso"
Write-Host "curios=$curios"
Write-Host "jars=$((@($srg, $numenApi, $lso, $curios) + $libs | Measure-Object).Count) sources=$($files.Count)"
$env:JAVA_TOOL_OPTIONS = "-Duser.language=en -Duser.country=US"; & "$jdk\javac.exe" -encoding UTF-8 -nowarn -cp $cp -d $classes @files 2>&1 | Select-Object -First 100
Write-Host "javac exit: $LASTEXITCODE"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
Copy-Item -Path (Join-Path $res '*') -Destination $classes -Recurse -Force
$jarOut = Join-Path $modDir 'build\numen_lso-1.0.0.jar'
if (Test-Path -LiteralPath $jarOut) { Remove-Item -LiteralPath $jarOut -Force }
& "$jdk\jar.exe" --create --file $jarOut -C $classes .
Write-Host "jar exit: $LASTEXITCODE -> $jarOut"
if (Test-Path -LiteralPath $jarOut) { (Get-Item -LiteralPath $jarOut).Length }
