# NPC Translator (SkyBlock 中文增强) 一键编译脚本
$ErrorActionPreference = "Stop"
Set-Location -Path $PSScriptRoot
$ProgressPreference = "SilentlyContinue"

function Get-JavaMajor($javaExe) {
    # 先读 JDK 目录里的 release 文件（最可靠，不用运行 java）
    $release = Join-Path (Split-Path (Split-Path $javaExe -Parent) -Parent) "release"
    if (Test-Path $release) {
        $text = Get-Content $release -Raw
        if ($text -match 'JAVA_VERSION="(\d+)') { return [int]$Matches[1] }
    }
    # 退而求其次：运行 java -version（它输出到 stderr，必须临时放宽错误处理）
    $old = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $out = (& $javaExe -version 2>&1 | Out-String)
        if ($out -match 'version "(\d+)') { return [int]$Matches[1] }
    } catch {
    } finally {
        $ErrorActionPreference = $old
    }
    return 0
}

function Test-Jdk($dir) {
    if (-not $dir) { return $false }
    $java = Join-Path $dir "bin\java.exe"
    $javac = Join-Path $dir "bin\javac.exe"
    if (-not (Test-Path $java) -or -not (Test-Path $javac)) { return $false }
    return ((Get-JavaMajor $java) -ge 25)
}

Write-Host "==> 查找 JDK 25 ..." -ForegroundColor Cyan
$jdkHome = $null
$candidates = @()
if ($env:JAVA_HOME) { $candidates += $env:JAVA_HOME }
$candidates += Get-ChildItem -Directory -Path ".\jdk" -ErrorAction SilentlyContinue | ForEach-Object { $_.FullName }
foreach ($root in @("C:\Program Files\Java", "C:\Program Files\Eclipse Adoptium", "C:\Program Files\Microsoft", "C:\Program Files\Zulu", "$env:USERPROFILE\.jdks")) {
    $candidates += Get-ChildItem -Directory -Path $root -ErrorAction SilentlyContinue | ForEach-Object { $_.FullName }
}
foreach ($c in $candidates) { if (Test-Jdk $c) { $jdkHome = $c; break } }

if (-not $jdkHome) {
    Write-Host "==> 没找到 JDK 25，开始下载 Temurin JDK 25 到 .\jdk（约 200MB）..." -ForegroundColor Yellow
    New-Item -ItemType Directory -Force -Path ".\jdk" | Out-Null
    $zip = ".\jdk\jdk25.zip"
    Invoke-WebRequest -UseBasicParsing -Uri "https://api.adoptium.net/v3/binary/latest/25/ga/windows/x64/jdk/hotspot/normal/eclipse" -OutFile $zip
    Expand-Archive -Path $zip -DestinationPath ".\jdk" -Force
    Remove-Item $zip
    foreach ($c in (Get-ChildItem -Directory -Path ".\jdk" | ForEach-Object { $_.FullName })) { if (Test-Jdk $c) { $jdkHome = $c; break } }
    if (-not $jdkHome) { throw "JDK 25 下载或解压失败" }
}
Write-Host "    使用 JDK: $jdkHome" -ForegroundColor Green
$env:JAVA_HOME = $jdkHome
$env:Path = "$jdkHome\bin;$env:Path"

# 读取 Windows 系统代理（Clash 等开了“系统代理”时生效），传给 Gradle
$gradleArgs = @("build", "--no-daemon", "--console=plain")
try {
    $inet = Get-ItemProperty "HKCU:\Software\Microsoft\Windows\CurrentVersion\Internet Settings"
    if ($inet.ProxyEnable -eq 1 -and $inet.ProxyServer) {
        $p = $inet.ProxyServer
        if ($p -match "https=([^;]+)") { $p = $Matches[1] } elseif ($p -match "http=([^;]+)") { $p = $Matches[1] }
        $p = $p -replace "^https?://", ""
        $hostPart, $portPart = $p.Split(":")
        if ($hostPart -and $portPart) {
            Write-Host "    检测到系统代理 $hostPart`:$portPart，Gradle 将通过它下载依赖" -ForegroundColor Green
            $proxyOpts = "-Dhttp.proxyHost=$hostPart -Dhttp.proxyPort=$portPart -Dhttps.proxyHost=$hostPart -Dhttps.proxyPort=$portPart"
            $env:GRADLE_OPTS = $proxyOpts
            $gradleArgs += $proxyOpts.Split(" ")
        }
    }
} catch {}

$targets = @("26.2", "26.3")
$built = @()
foreach ($t in $targets) {
    Write-Host ""
    Write-Host "==> 编译 Minecraft $t 版（第一次要下载 Gradle、Minecraft 和 Fabric 依赖，可能需要 5~15 分钟）..." -ForegroundColor Cyan
    & .\gradlew.bat clean @gradleArgs "-PmcTarget=$t"
    if ($LASTEXITCODE -ne 0) { throw "Minecraft $t 版编译失败，请把上面的报错发给 Claude" }
    $jar = Get-ChildItem ".\build\libs\*.jar" | Where-Object { $_.Name -notlike "*-sources.jar" } | Select-Object -First 1
    Copy-Item $jar.FullName -Destination (Join-Path $PSScriptRoot "..") -Force
    $built += $jar.Name
}

Write-Host ""
Write-Host "==> 完成！以下 jar 已复制到上一级文件夹：" -ForegroundColor Green
$built | ForEach-Object { Write-Host "    $_" -ForegroundColor Green }
Write-Host "    文件名里的 mc26.2 / mc26.3 对应游戏版本，只装和你游戏版本一致的那个。" -ForegroundColor Green
