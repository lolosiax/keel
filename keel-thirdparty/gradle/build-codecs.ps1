#Requires -Version 5.1
<#
.SYNOPSIS
    一次性构建 thirdparty/ 下的第三方原生静态库（zlib / libpng / libwebp / libjpeg-turbo / giflib / msgpack-c）。

.DESCRIPTION
    只在需要新增或升级库时手动跑一次；产物（.a + 公共头文件）落在 build/thirdparty/out/
    （不入库），日常 Gradle 构建只做 cinterop，不再碰 C 工具链。

    编译器直接用 Kotlin/Native 自带的那一套，不依赖本机 MSYS2/Visual Studio：
      - 解析 gradle/libs.versions.toml 里的 kotlin 版本，找到 ~/.konan/kotlin-native-prebuilt-*-<ver>；
      - clang 与 llvm-ar 取自 ~/.konan/dependencies/llvm-*-windows-essentials-*/bin；
      - mingwX64   sysroot = ~/.konan/dependencies/msys2-mingw-w64-x86_64-*
      - linuxArm64 sysroot = ~/.konan/dependencies/aarch64-unknown-linux-gnu-gcc-*/aarch64-unknown-linux-gnu/sysroot
      - linuxX64   sysroot = ~/.konan/dependencies/x86_64-unknown-linux-gnu-gcc-*/x86_64-unknown-linux-gnu/sysroot
    这些 sysroot 与 Kotlin/Native 链接期的完全一致，因此产物的 ABI/glibc 版本天然对齐。

    zlib / libpng / libwebp / libjpeg-turbo 用各自上游的 CMake 构建（避免手工维护 config.h 与
    SIMD 源文件清单）；giflib 只有 autotools，按其 Makefile 的 SOURCES 直接编译归档；
    msgpack-c 的 C 库也只编译 src/*.c，其 cmake 模板生成的 sysdep.h / pack_template.h 就地替换字节序宏后产出。

.PARAMETER Targets
    要构建的目标，默认三个都构建。

.PARAMETER WorkDir
    源码下载/解压/CMake 构建目录，默认 <repo>/build/thirdparty（已被 .gitignore 忽略）。

.PARAMETER Force
    清掉构建目录与已下载的源码包，全部重来。

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File thirdparty/gradle/build-codecs.ps1
#>
[CmdletBinding()]
param(
    [ValidateSet('mingwX64', 'linuxArm64', 'linuxX64')]
    [string[]]$Targets = @('mingwX64', 'linuxArm64', 'linuxX64'),
    [string]$WorkDir,
    [switch]$Force
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'toolchain.ps1')

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
if (-not $WorkDir) { $WorkDir = Join-Path $repoRoot 'build/thirdparty' }
$srcDir = Join-Path $WorkDir 'src'
$treeDir = Join-Path $WorkDir 'tree'
$cmakeDir = Join-Path $WorkDir 'cmake'
$incOut = Join-Path $WorkDir 'out/include'

function Info([string]$m) { Write-Host "==> $m" -ForegroundColor Cyan }
function Warn([string]$m) { Write-Host "!!  $m" -ForegroundColor Yellow }
function Fail([string]$m) { throw $m }

# ---------------------------------------------------------------- 源码清单
# giflib 官方只在 SourceForge 放 tarball（直连返回 HTML 说明页），走 Debian pool 的原始包。
$sources = @(
    @{ Name = 'zlib';          Version = '1.3.1';  File = 'zlib-1.3.1.tar.gz';          Url = 'https://github.com/madler/zlib/releases/download/v1.3.1/zlib-1.3.1.tar.gz' }
    @{ Name = 'libpng';        Version = '1.6.44'; File = 'libpng-1.6.44.tar.gz';        Url = 'https://github.com/pnggroup/libpng/archive/refs/tags/v1.6.44.tar.gz' }
    @{ Name = 'libwebp';       Version = '1.6.0';  File = 'libwebp-1.6.0.tar.gz';        Url = 'https://storage.googleapis.com/downloads.webmproject.org/releases/webp/libwebp-1.6.0.tar.gz' }
    @{ Name = 'libjpeg-turbo'; Version = '3.1.0';  File = 'libjpeg-turbo-3.1.0.tar.gz';  Url = 'https://github.com/libjpeg-turbo/libjpeg-turbo/releases/download/3.1.0/libjpeg-turbo-3.1.0.tar.gz' }
    @{ Name = 'giflib';        Version = '5.2.2';  File = 'giflib-5.2.2.tar.gz';         Url = 'https://deb.debian.org/debian/pool/main/g/giflib/giflib_5.2.2.orig.tar.gz' }
    # msgpack-c 的 C 库（tag 前缀 c-）；sysdep.h / pack_template.h 由 cmake 模板生成，见下文手工替换
    @{ Name = 'msgpack-c';     Version = '7.0.2';  File = 'msgpack-c-7.0.2.tar.gz';      Url = 'https://github.com/msgpack/msgpack-c/releases/download/c-7.0.2/msgpack-c-7.0.2.tar.gz' }
)

# ------------------------------------------------ 1. 定位 Kotlin/Native 工具链
Info '定位 Kotlin/Native 自带交叉工具链'

$versionsToml = Join-Path $repoRoot 'gradle/libs.versions.toml'
if (-not (Test-Path $versionsToml)) {
    # 独立构建不在 keel 根目录下：版本目录仍以 keel/gradle 的唯一来源为准
    $versionsToml = Join-Path (Resolve-Path (Join-Path $repoRoot '../gradle')).Path 'libs.versions.toml'
}
$kotlinVersion = (Select-String -Path $versionsToml -Pattern '^\s*kotlin\s*=\s*"([^"]+)"').Matches[0].Groups[1].Value
$konanRoot = Join-Path $env:USERPROFILE '.konan'
$deps = Join-Path $konanRoot 'dependencies'

if (-not (Test-Path (Join-Path $konanRoot "kotlin-native-prebuilt-windows-x86_64-$kotlinVersion"))) {
    Warn "未找到 kotlin-native-prebuilt-windows-x86_64-$kotlinVersion（先跑一次 gradle 构建让它下载）"
}
if (-not (Test-Path $deps)) { Fail "找不到 $deps，请先执行一次 Gradle 构建以下载 Kotlin/Native 依赖" }

$llvmHome = Get-ChildItem $deps -Directory -Filter 'llvm-*-windows-essentials-*' |
    Sort-Object Name -Descending | Select-Object -First 1
$msysHome = Get-ChildItem $deps -Directory -Filter 'msys2-mingw-w64-x86_64-*' | Select-Object -First 1
$aaHome = Get-ChildItem $deps -Directory -Filter 'aarch64-unknown-linux-gnu-gcc-*' | Select-Object -First 1
$x64Home = Get-ChildItem $deps -Directory -Filter 'x86_64-unknown-linux-gnu-gcc-*' | Select-Object -First 1
foreach ($d in @($llvmHome, $msysHome, $aaHome, $x64Home)) {
    if (-not $d) { Fail "Kotlin/Native 依赖不完整（llvm / msys2-mingw / aarch64-gcc / x86_64-gcc 至少缺一个），请先执行一次 Gradle 构建" }
}

$clang = Join-Path $llvmHome.FullName 'bin/clang.exe'
$llvmAr = Join-Path $llvmHome.FullName 'bin/llvm-ar.exe'
$msysSysroot = $msysHome.FullName
$aaSysroot = Join-Path $aaHome.FullName 'aarch64-unknown-linux-gnu/sysroot'
$x64Sysroot = Join-Path $x64Home.FullName 'x86_64-unknown-linux-gnu/sysroot'
foreach ($p in @($clang, $llvmAr, $msysSysroot, $aaSysroot, $x64Sysroot)) {
    if (-not (Test-Path $p)) { Fail "缺文件：$p" }
}
Write-Host "    clang   : $clang"
Write-Host "    mingw   : $msysSysroot"
Write-Host "    aarch64 : $aaSysroot"
Write-Host "    x86_64  : $x64Sysroot"

$targetDefs = @{
    mingwX64   = @{ SystemName = 'Windows'; Processor = 'x86_64'; Triple = 'x86_64-w64-mingw32'; Sysroot = $msysSysroot }
    linuxArm64 = @{ SystemName = 'Linux'; Processor = 'aarch64'; Triple = 'aarch64-linux-gnu'; Sysroot = $aaSysroot }
    linuxX64   = @{ SystemName = 'Linux'; Processor = 'x86_64'; Triple = 'x86_64-linux-gnu'; Sysroot = $x64Sysroot }
}

# ---------------------------------------------------------------- 2. 下载解压
if ($Force) {
    Info '清理旧的构建目录'
    foreach ($p in @($cmakeDir, $treeDir, $srcDir)) {
        if (Test-Path $p) { Remove-Item $p -Recurse -Force }
    }
}
New-Item -ItemType Directory -Force -Path $srcDir, $treeDir, $cmakeDir | Out-Null

$artifactRepo = Get-LolosiaArtifactRepo
foreach ($s in $sources) {
    $tarball = Join-Path $srcDir $s.File
    if ((Test-Path $tarball) -and -not $Force) {
        Write-Host "    已存在 $($s.File)"
        continue
    }

    # 先问自建仓库的 /sources（配置了 local.properties 时），取不到再走上游
    $urls = @()
    if ($artifactRepo) { $urls += "$($artifactRepo.Sources)/$($s.Name)/$($s.Version)/$($s.File)" }
    $urls += $s.Url

    $downloaded = $false
    foreach ($url in $urls) {
        try {
            Info "下载 $($s.Name) $($s.Version) <- $url"
            $headers = @{}
            if ($artifactRepo -and $url.StartsWith($artifactRepo.Sources) -and $artifactRepo.Username) {
                $pair = "$($artifactRepo.Username):$($artifactRepo.Password)"
                $headers['Authorization'] = 'Basic ' + [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($pair))
            }
            Invoke-WebRequest -Uri $url -OutFile $tarball -Headers $headers -UseBasicParsing -TimeoutSec 300
            $magic = [System.IO.File]::ReadAllBytes($tarball)[0..1]
            if ($magic[0] -eq 0x3C -and $magic[1] -eq 0x21) { throw '下载到的是 HTML 而非 tarball' }
            $downloaded = $true
            break
        } catch {
            Warn "$($s.Name)：$($_.Exception.Message)"
            if (Test-Path $tarball) { Remove-Item $tarball -Force }
        }
    }
    if (-not $downloaded) { Fail "$($s.Name)：源码下载失败（已尝试：$($urls -join ' | ')）" }
}

foreach ($s in $sources) {
    $tarball = Join-Path $srcDir $s.File
    $dirName = "$($s.Name)-$($s.Version)"
    # libpng 的 GitHub 归档解出来是 libpng-1.6.44，与本地命名一致；逐个确认
    if (Test-Path (Join-Path $treeDir $dirName)) { continue }
    Info "解压 $($s.Name) $($s.Version)"
    & tar -xzf $tarball -C $treeDir
    if ($LASTEXITCODE -ne 0) { Fail "解压失败：$tarball" }
}

function Get-Tree([string]$name) {
    $p = Join-Path $treeDir $name
    if (-not (Test-Path $p)) { Fail "源码目录不存在：$p" }
    return $p
}

# ---------------------------------------------------------------- 3. 构建工具函数
function Invoke-CMakeBuild {
    param(
        [string]$Label,
        [string]$SourceDir,
        [string]$BuildDir,
        [string]$Target,
        [string[]]$Options,
        [string[]]$BuildTarget
    )
    $d = $targetDefs[$Target]
    Info "cmake configure  $Label [$Target]"
    $cfg = @(
        '-S', $SourceDir, '-B', $BuildDir, '-G', 'MinGW Makefiles',
        "-DCMAKE_SYSTEM_NAME=$($d.SystemName)",
        "-DCMAKE_SYSTEM_PROCESSOR=$($d.Processor)",
        # 交叉编译不做 try_run，试编译产物直接当静态库，省掉链接器依赖
        '-DCMAKE_TRY_COMPILE_TARGET_TYPE=STATIC_LIBRARY',
        "-DCMAKE_C_COMPILER=$clang",
        "-DCMAKE_C_COMPILER_TARGET=$($d.Triple)",
        "-DCMAKE_SYSROOT=$($d.Sysroot)",
        '-DCMAKE_BUILD_TYPE=Release',
        '-DBUILD_SHARED_LIBS=OFF'
    ) + $Options
    & cmake @cfg
    if ($LASTEXITCODE -ne 0) { Fail "cmake configure 失败：$Label [$Target]" }

    Info "cmake build      $Label [$Target]"
    $bld = @('--build', $BuildDir, '--parallel')
    if ($BuildTarget) { $bld += @('--target') + $BuildTarget }
    & cmake @bld
    if ($LASTEXITCODE -ne 0) { Fail "cmake build 失败：$Label [$Target]" }
}

function Invoke-CCompile {
    param(
        [string]$Label,
        [string]$Target,
        [string]$OutDir,
        [string[]]$SourceFiles,
        [string[]]$IncludeDirs = @()
    )
    $d = $targetDefs[$Target]
    Info "clang 编译        $Label [$Target]"
    New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
    $incArgs = @()
    foreach ($inc in $IncludeDirs) { $incArgs += "-I$inc" }
    $objects = @()
    foreach ($src in $SourceFiles) {
        $obj = Join-Path $OutDir ((Split-Path $src -Leaf) -replace '\.c$', '.o')
        & $clang "--target=$($d.Triple)" "--sysroot=$($d.Sysroot)" `
            -O2 -fPIC -fno-strict-aliasing -std=gnu99 -Wall @incArgs -c $src -o $obj
        if ($LASTEXITCODE -ne 0) { Fail "编译失败：$src [$Target]" }
        $objects += $obj
    }
    return $objects
}

function Copy-Artifact {
    param([string]$From, [string]$ToDir, [string]$AsName)
    if (-not (Test-Path $From)) { Fail "产物不存在：$From" }
    New-Item -ItemType Directory -Force -Path $ToDir | Out-Null
    $dest = Join-Path $ToDir $AsName
    Copy-Item $From $dest -Force
    $size = [math]::Round((Get-Item $dest).Length / 1KB)
    Write-Host "    -> $AsName (${size} KB)"
}

# ---------------------------------------------------------------- 4. 构建
$zlibTree = Get-Tree "zlib-1.3.1"
$pngTree = Get-Tree "libpng-1.6.44"
$webpTree = Get-Tree "libwebp-1.6.0"
$jpegTree = Get-Tree "libjpeg-turbo-3.1.0"
$gifTree = Get-Tree "giflib-5.2.2"
$msgpackTree = Get-Tree "msgpack-c-7.0.2"

# msgpack-c 的 sysdep.h / pack_template.h 由 cmake 的 configure_file 生成，只有字节序宏要替换；
# 三个目标都是小端，直接写死 LITTLE=1 / BIG=0（等价于 TEST_BIG_ENDIAN 的结果），省掉 cmake。
$msgpackInclude = Join-Path $msgpackTree 'include'
foreach ($tpl in @(
    @{ In = 'sysdep.h.in';        Out = 'sysdep.h' },
    @{ In = 'pack_template.h.in'; Out = 'pack_template.h' }
)) {
    $content = Get-Content (Join-Path $msgpackTree "cmake/$($tpl.In)") -Raw
    $content = $content.Replace('@MSGPACK_ENDIAN_BIG_BYTE@', '0').Replace('@MSGPACK_ENDIAN_LITTLE_BYTE@', '1')
    Set-Content -Path (Join-Path $msgpackInclude "msgpack/$($tpl.Out)") -Value $content -NoNewline -Encoding ascii
}

foreach ($target in $Targets) {
    Write-Host ''
    Info "================ 目标 $target ================"
    $outDir = Join-Path $WorkDir "out/$target"

    # ---- zlib ----
    $zlibBuild = Join-Path $cmakeDir "$target/zlib"
    Invoke-CMakeBuild -Label 'zlib' -SourceDir $zlibTree -BuildDir $zlibBuild -Target $target `
        -Options @('-DZLIB_BUILD_EXAMPLES=OFF') -BuildTarget 'zlibstatic'
    $zlibLib = Get-ChildItem $zlibBuild -Filter '*.a' -Recurse |
        Where-Object { $_.Name -notlike '*.dll.a' -and $_.Name -notlike 'objects.a' } |
        Sort-Object Length -Descending | Select-Object -First 1
    Copy-Artifact $zlibLib.FullName $outDir 'libz.a'

    # libpng 只认 find_package(ZLIB)（PNG_BUILD_ZLIB 已废弃），按其约定摆一个 prefix 出来
    # zconf.h 由 zlib 的 CMake 生成在构建目录（源码树里的那份会被改名成 zconf.h.included）
    $zlibPrefix = Join-Path $WorkDir "deps/$target/zlib"
    New-Item -ItemType Directory -Force -Path "$zlibPrefix/include", "$zlibPrefix/lib" | Out-Null
    Copy-Item "$zlibTree/zlib.h", "$zlibBuild/zconf.h" "$zlibPrefix/include" -Force
    Copy-Item $zlibLib.FullName "$zlibPrefix/lib/libz.a" -Force

    # ---- libpng（静态库输出名各平台不同，统一改名为 libpng.a）----
    $pngBuild = Join-Path $cmakeDir "$target/libpng"
    Invoke-CMakeBuild -Label 'libpng' -SourceDir $pngTree -BuildDir $pngBuild -Target $target -Options @(
        '-DPNG_SHARED=OFF',
        '-DPNG_STATIC=ON',
        '-DPNG_TESTS=OFF',
        '-DPNG_TOOLS=OFF',
        '-DPNG_HARDWARE_OPTIMIZATIONS=OFF',
        "-DZLIB_ROOT=$zlibPrefix"
    ) -BuildTarget 'png_static'
    $pngLib = Get-ChildItem $pngBuild -Filter '*png*.a' -Recurse |
        Where-Object { $_.Name -notlike 'objects.a' } |
        Sort-Object Length -Descending | Select-Object -First 1
    Copy-Artifact $pngLib.FullName $outDir 'libpng.a'

    # ---- libwebp（1.6 起编码侧的 SharpYuv 拆成独立归档 libsharpyuv.a，这里合并回单个 libwebp.a）----
    # 保留两个归档时，K/N 的 ELF 链接（ld.gold）按归档逐个解析，顺序被重排后
    # libwebp.a 里对 SharpYuv* 的引用会解析不到（arm64 / x64 都会失败）；合并成一劳永逸。
    $webpBuild = Join-Path $cmakeDir "$target/libwebp"
    Invoke-CMakeBuild -Label 'libwebp' -SourceDir $webpTree -BuildDir $webpBuild -Target $target -Options @(
        '-DWEBP_BUILD_ANIM_UTILS=OFF',
        '-DWEBP_BUILD_CWEBP=OFF',
        '-DWEBP_BUILD_DWEBP=OFF',
        '-DWEBP_BUILD_GIF2WEBP=OFF',
        '-DWEBP_BUILD_IMG2WEBP=OFF',
        '-DWEBP_BUILD_VWEBP=OFF',
        '-DWEBP_BUILD_WEBPINFO=OFF',
        '-DWEBP_BUILD_WEBPMUX=OFF',
        '-DWEBP_BUILD_LIBWEBPMUX=OFF',
        '-DWEBP_BUILD_EXTRAS=OFF'
    ) -BuildTarget 'webp'
    $webpLib = Get-ChildItem $webpBuild -Filter 'libwebp.a' -Recurse | Select-Object -First 1
    $sharpLib = Get-ChildItem $webpBuild -Filter 'libsharpyuv.a' -Recurse | Select-Object -First 1
    if (-not $sharpLib) { Fail "libsharpyuv.a 未生成（libwebp 版本变化？）[$target]" }
    $webpMergeDir = Join-Path $cmakeDir "$target/webp-merge"
    if (Test-Path $webpMergeDir) { Remove-Item $webpMergeDir -Recurse -Force }
    New-Item -ItemType Directory -Force -Path $webpMergeDir | Out-Null
    Push-Location $webpMergeDir
    & $llvmAr x $webpLib.FullName
    & $llvmAr x $sharpLib.FullName
    # 成员后缀随目标而异（mingw 是 .obj，linux 是 .o），这里取解出来的全部文件
    $webpMembers = Get-ChildItem $webpMergeDir -File | Select-Object -ExpandProperty Name
    & $llvmAr rcs (Join-Path $outDir 'libwebp.a') $webpMembers
    $mergeExit = $LASTEXITCODE
    Pop-Location
    if ($mergeExit -ne 0) { Fail "libwebp.a 合并归档失败 [$target]" }
    Write-Host "    -> libwebp.a ($([math]::Round((Get-Item (Join-Path $outDir 'libwebp.a')).Length / 1KB)) KB，含 SharpYuv)"

    # ---- libjpeg-turbo（不用 turbojpeg 的 longjmp 语义，见 docs；SIMD 关闭免装 nasm）----
    $jpegBuild = Join-Path $cmakeDir "$target/libjpeg-turbo"
    Invoke-CMakeBuild -Label 'libjpeg-turbo' -SourceDir $jpegTree -BuildDir $jpegBuild -Target $target -Options @(
        '-DENABLE_SHARED=0',
        '-DENABLE_STATIC=1',
        '-DWITH_SIMD=0',
        '-DWITH_TURBOJPEG=1',
        '-DWITH_JAVA=0',
        '-DWITH_12BIT=0'
    ) -BuildTarget @('jpeg-static', 'turbojpeg-static')
    $jpegLib = Get-ChildItem $jpegBuild -Filter 'libjpeg.a' -Recurse | Select-Object -First 1
    Copy-Artifact $jpegLib.FullName $outDir 'libjpeg.a'
    $tjLib = Get-ChildItem $jpegBuild -Filter 'libturbojpeg.a' -Recurse | Select-Object -First 1
    Copy-Artifact $tjLib.FullName $outDir 'libturbojpeg.a'

    # ---- giflib（无 CMake，按上游 Makefile 的 SOURCES 编译）----
    $gifSources = @(
        'dgif_lib.c', 'egif_lib.c', 'gifalloc.c', 'gif_err.c',
        'gif_font.c', 'gif_hash.c', 'openbsd-reallocarray.c'
    ) | ForEach-Object { Join-Path $gifTree $_ }
    $gifObjDir = Join-Path $cmakeDir "$target/giflib"
    $gifObjects = Invoke-CCompile -Label 'giflib' -Target $target -OutDir $gifObjDir -SourceFiles $gifSources
    $gifLib = Join-Path $outDir 'libgif.a'
    New-Item -ItemType Directory -Force -Path $outDir | Out-Null
    if (Test-Path $gifLib) { Remove-Item $gifLib -Force }
    & $llvmAr rcs $gifLib @gifObjects
    if ($LASTEXITCODE -ne 0) { Fail "归档失败：libgif.a [$target]" }
    Write-Host "    -> libgif.a ($([math]::Round((Get-Item $gifLib).Length / 1KB)) KB)"

    # ---- msgpack-c（同样无 CMake：clang 直接编译 src/*.c 归档；配置头已在上文生成）----
    $msgpackSources = @('objectc.c', 'unpack.c', 'version.c', 'vrefbuffer.c', 'zone.c') |
        ForEach-Object { Join-Path $msgpackTree "src/$_" }
    $msgpackObjDir = Join-Path $cmakeDir "$target/msgpack-c"
    $msgpackObjects = Invoke-CCompile -Label 'msgpack-c' -Target $target -OutDir $msgpackObjDir `
        -SourceFiles $msgpackSources -IncludeDirs @($msgpackInclude)
    $msgpackLib = Join-Path $outDir 'libmsgpackc.a'
    New-Item -ItemType Directory -Force -Path $outDir | Out-Null
    if (Test-Path $msgpackLib) { Remove-Item $msgpackLib -Force }
    & $llvmAr rcs $msgpackLib @msgpackObjects
    if ($LASTEXITCODE -ne 0) { Fail "归档失败：libmsgpackc.a [$target]" }
    Write-Host "    -> libmsgpackc.a ($([math]::Round((Get-Item $msgpackLib).Length / 1KB)) KB)"
}

# ---------------------------------------------------------------- 5. 公共头文件
Info '复制公共头文件到 build/thirdparty/out/include'
$headerMap = @(
    @{ From = "$zlibTree/zlib.h";      To = 'zlib/zlib.h' }
    @{ From = "$cmakeDir/$($Targets[0])/zlib/zconf.h"; To = 'zlib/zconf.h' }
    @{ From = "$pngTree/png.h";        To = 'libpng/png.h' }
    @{ From = "$pngTree/pngconf.h";    To = 'libpng/pngconf.h' }
    @{ From = "$cmakeDir/$($Targets[0])/libpng/pnglibconf.h"; To = 'libpng/pnglibconf.h' }
    @{ From = "$webpTree/src/webp/encode.h"; To = 'libwebp/webp/encode.h' }
    @{ From = "$webpTree/src/webp/decode.h"; To = 'libwebp/webp/decode.h' }
    @{ From = "$webpTree/src/webp/types.h";  To = 'libwebp/webp/types.h' }
    @{ From = "$jpegTree/src/turbojpeg.h";   To = 'libjpeg/turbojpeg.h' }
    @{ From = "$gifTree/gif_lib.h";          To = 'giflib/gif_lib.h' }
)
foreach ($h in $headerMap) {
    if (-not (Test-Path $h.From)) { Fail "头文件不存在：$($h.From)" }
    $dest = Join-Path $incOut $h.To
    New-Item -ItemType Directory -Force -Path (Split-Path $dest -Parent) | Out-Null
    Copy-Item $h.From $dest -Force
    Write-Host "    -> $($h.To)"
}

# msgpack-c 头文件较多（msgpack.h + msgpack/ 子树，含配置生成的 sysdep.h / pack_template.h），整目录拷贝
$msgpackDest = Join-Path $incOut 'msgpack'
if (Test-Path $msgpackDest) { Remove-Item $msgpackDest -Recurse -Force }
New-Item -ItemType Directory -Force -Path $msgpackDest | Out-Null
Copy-Item (Join-Path $msgpackInclude 'msgpack.h') $incOut -Force
Copy-Item (Join-Path $msgpackInclude 'msgpack/*') $msgpackDest -Recurse -Force
Write-Host '    -> msgpack.h + msgpack/*'

Write-Host ''
Info '完成。产物：'
Get-ChildItem ($Targets | ForEach-Object { Join-Path $WorkDir "out/$_" }) -Filter '*.a' -ErrorAction SilentlyContinue |
    Select-Object @{ n = 'Target'; e = { Split-Path $_.DirectoryName -Leaf } }, Name, @{ n = 'KB'; e = { [math]::Round($_.Length / 1KB) } } |
    Format-Table -AutoSize | Out-String | Write-Host
