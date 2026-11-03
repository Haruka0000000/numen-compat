$ErrorActionPreference = 'Continue'
$modDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$project = Split-Path -Parent (Split-Path -Parent $modDir)
$srv = Join-Path $project 'Server'
$jdk = 'C:\Program Files\Java\jdk-17\bin'
$srg = Join-Path $srv 'libraries\net\minecraft\server\1.20.1-20230612.114412\server-1.20.1-20230612.114412-srg.jar'
$mixin = Join-Path $srv 'libraries\org\spongepowered\mixin\0.8.5\mixin-0.8.5.jar'
$modsDir = Join-Path $srv 'mods'
$numenApi = Join-Path $project '_patch\nested_numen_api-forge-1.20.1-0.1.4.1.jar'
$ping = (Get-ChildItem -LiteralPath $modsDir -Filter 'Ping-Wheel-*.jar' -ErrorAction SilentlyContinue | Select-Object -First 1).FullName
$libs = Get-ChildItem -LiteralPath (Join-Path $srv 'libraries') -Recurse -Filter *.jar -ErrorAction SilentlyContinue | ForEach-Object { $_.FullName }
$cp = (@($srg, $mixin, $numenApi, $ping) + $libs) -join ';'
$src = Join-Path $modDir 'src'
$res = Join-Path $src 'main\resources'
$classes = Join-Path $modDir 'build\classes'
if (Test-Path -LiteralPath $classes) { Remove-Item -LiteralPath $classes -Recurse -Force }
New-Item -ItemType Directory -Path $classes -Force | Out-Null
$files = Get-ChildItem -LiteralPath $src -Recurse -Filter *.java | ForEach-Object { $_.FullName }
Write-Host "numenApi=$numenApi"
Write-Host "ping=$ping"
Write-Host "jars=$((@($srg, $mixin, $numenApi, $ping) + $libs | Measure-Object).Count) sources=$($files.Count)"
$env:JAVA_TOOL_OPTIONS = "-Duser.language=en -Duser.country=US"; & "$jdk\javac.exe" -encoding UTF-8 -nowarn -cp $cp -d $classes @files 2>&1 | Select-Object -First 120
$rc = $LASTEXITCODE
Write-Host "javac exit: $rc"
if ($rc -ne 0) { exit $rc }
Copy-Item -Path (Join-Path $res '*') -Destination $classes -Recurse -Force
$mf = Join-Path $modDir 'build\MANIFEST.MF'
$mfText = "Manifest-Version: 1.0`r`nMixinConfigs: numen_pingwheel.mixins.json`r`n`r`n"
[System.IO.File]::WriteAllText($mf, $mfText, (New-Object System.Text.ASCIIEncoding))
$jarOut = Join-Path $modDir 'build\numen_pingwheel-1.0.0.jar'
if (Test-Path -LiteralPath $jarOut) { Remove-Item -LiteralPath $jarOut -Force }
& "$jdk\jar.exe" --create --file $jarOut --manifest $mf -C $classes .
Write-Host "jar exit: $LASTEXITCODE -> $jarOut"
if (Test-Path -LiteralPath $jarOut) { (Get-Item -LiteralPath $jarOut).Length }
