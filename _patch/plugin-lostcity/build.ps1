$ErrorActionPreference = 'Continue'
$modDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$project = Split-Path -Parent (Split-Path -Parent $modDir)
$srv = Join-Path $project 'Server'
$jdk = 'C:\Program Files\Java\jdk-17\bin'
$srg = Join-Path $srv 'libraries\net\minecraft\server\1.20.1-20230612.114412\server-1.20.1-20230612.114412-srg.jar'
$modsDir = Join-Path $srv 'mods'
$numenApi = Join-Path $project '_patch\nested_numen_api-forge-1.20.1-0.1.4.1.jar'
$lostcities = (Get-ChildItem -LiteralPath $modsDir -Filter 'lostcities-*.jar' -ErrorAction SilentlyContinue | Select-Object -First 1).FullName
$libs = Get-ChildItem -LiteralPath (Join-Path $srv 'libraries') -Recurse -Filter *.jar -ErrorAction SilentlyContinue | ForEach-Object { $_.FullName }
$cp = (@($srg, $numenApi, $lostcities) + $libs) -join ';'
$src = Join-Path $modDir 'src'
$res = Join-Path $src 'main\resources'
$classes = Join-Path $modDir 'build\classes'
if (Test-Path -LiteralPath $classes) { Remove-Item -LiteralPath $classes -Recurse -Force }
New-Item -ItemType Directory -Path $classes -Force | Out-Null
$files = Get-ChildItem -LiteralPath $src -Recurse -Filter *.java | ForEach-Object { $_.FullName }
Write-Host "numenApi=$numenApi"
Write-Host "lostcities=$lostcities"

$env:JAVA_TOOL_OPTIONS = "-Duser.language=en -Duser.country=US"
& (Join-Path $jdk 'javac.exe') -encoding UTF-8 -nowarn -cp $cp -d $classes @files
Write-Host "javac exit: $LASTEXITCODE"
if ($LASTEXITCODE -ne 0) { exit 1 }

Copy-Item -Path (Join-Path $res '*') -Destination $classes -Recurse -Force
$out = Join-Path $modDir 'build\numen_lostcity-1.0.0.jar'
if (Test-Path -LiteralPath $out) { Remove-Item -LiteralPath $out -Force }
& (Join-Path $jdk 'jar.exe') --create --file $out -C $classes '.'
Write-Host "jar exit: $LASTEXITCODE"
if ($LASTEXITCODE -eq 0) { Write-Host ("built " + $out + " (" + (Get-Item -LiteralPath $out).Length + " B)") }
