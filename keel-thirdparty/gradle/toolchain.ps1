#Requires -Version 5.1
<#
.SYNOPSIS
    thirdparty 下各构建脚本共用的工具链定位（被 dot-source，不单独运行）。

.DESCRIPTION
    两件事，都是为了「脚本比文档靠谱」：

      1. 定位 Kotlin/Native 自带的那套交叉工具链 —— clang + msys2 sysroot + windres。
         最终可执行文件也由它编译链接，产物 ABI / 运行时库天然同源；
      2. 定位 cmake / ninja —— 本机可能只装在 python 的用户级 Scripts 目录里（不在 PATH）。

    另外提供各目标的三元组与 sysroot 特征，以及 Windows 路径 → CMake 路径的转换
    （CMake 会把反斜杠写进生成的 *.cmake 里当转义用，`\U` 直接报 Invalid character escape）。

    注意：本文件用 UTF-8 带 BOM 保存，否则 PowerShell 5.1 会按 GBK 解析中文注释而语法报错。
#>

function Write-Step([string]$Message) { Write-Host "==> $Message" -ForegroundColor Cyan }
function Stop-WithError([string]$Message) { throw $Message }

function Get-LolosiaRepoRoot { return (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path }

function ConvertTo-CmPath([string]$Path) { return ($Path -replace '\\', '/') }

# 问 python 它的 Scripts 目录在哪。不能直接写 `& python -c ...`：PATH 上可能只有一个
# Windows 商店占位 stub，执行它会往 stderr 吐错误，在 $ErrorActionPreference='Stop' 下
# 直接变成终止错误（脚本还没开始构建就挂了）。因此这里压低错误级别并整体兜住。
function Get-PythonScriptsDir {
    $probe = 'import sysconfig; print(sysconfig.get_path("scripts"))'
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $out = & python -c $probe 2>$null
        if ($LASTEXITCODE -eq 0 -and $out) { return ([string]$out).Trim() }
    } catch {
        return $null
    } finally {
        $ErrorActionPreference = $previous
    }
    return $null
}

function Resolve-BuildTool([string]$Name) {
    $cmd = Get-Command "$Name.exe" -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }

    $candidates = @()
    if ($env:APPDATA) {
        $pyUser = Join-Path $env:APPDATA 'Python'
        if (Test-Path $pyUser) {
            $candidates += Get-ChildItem $pyUser -Directory -Filter 'Python3*' -ErrorAction SilentlyContinue |
                ForEach-Object { Join-Path $_.FullName 'Scripts' }
        }
    }
    $scripts = Get-PythonScriptsDir
    if ($scripts) { $candidates += $scripts }

    foreach ($dir in $candidates) {
        $p = Join-Path $dir "$Name.exe"
        if (Test-Path $p) { return $p }
    }
    Stop-WithError "找不到 $Name。安装：python -m pip install cmake ninja（装完无需加 PATH，本脚本会自行定位）"
}

$script:LolosiaTargetDefs = @{
    mingwX64 = @{
        Triple        = 'x86_64-w64-mingw32'
        SysrootFilter = 'msys2-mingw-w64-x86_64-*'
    }
}

function Get-LolosiaTargetDef([string]$Target) {
    $def = $script:LolosiaTargetDefs[$Target]
    if (-not $def) { Stop-WithError "未知目标 '$Target'，目前支持：$($script:LolosiaTargetDefs.Keys -join ' / ')" }
    return $def
}

function Get-KonanToolchain([string]$Target) {
    $def = Get-LolosiaTargetDef $Target
    $deps = Join-Path $env:USERPROFILE '.konan/dependencies'
    if (-not (Test-Path $deps)) { Stop-WithError "找不到 $deps，请先执行一次 Gradle 构建以下载 Kotlin/Native 依赖" }

    $llvm = Get-ChildItem $deps -Directory -Filter 'llvm-*-windows-essentials-*' |
        Sort-Object Name -Descending | Select-Object -First 1
    $sys = Get-ChildItem $deps -Directory -Filter $def.SysrootFilter | Select-Object -First 1
    if (-not $llvm -or -not $sys) {
        Stop-WithError 'Kotlin/Native 依赖不完整（缺 llvm 或 sysroot），请先执行一次 Gradle 构建'
    }

    $tool = @{
        Triple   = $def.Triple
        LlvmHome = $llvm.FullName
        Clang    = Join-Path $llvm.FullName 'bin/clang.exe'
        ClangXX  = Join-Path $llvm.FullName 'bin/clang++.exe'
        LlvmAr   = Join-Path $llvm.FullName 'bin/llvm-ar.exe'
        Sysroot  = $sys.FullName
        Windres  = Join-Path $sys.FullName 'bin/windres.exe'
    }
    foreach ($p in @($tool.Clang, $tool.ClangXX, $tool.LlvmAr, $tool.Sysroot, $tool.Windres)) {
        if (-not (Test-Path $p)) { Stop-WithError "缺文件：$p" }
    }
    return $tool
}

function Write-ToolchainSummary($Tool) {
    Write-Host "    triple  : $($Tool.Triple)"
    Write-Host "    clang   : $($Tool.Clang)"
    Write-Host "    clang++ : $($Tool.ClangXX)"
    Write-Host "    sysroot : $($Tool.Sysroot)"
}

<#
.SYNOPSIS
    读取自建工件仓库配置；没配置时返回 $null。
.DESCRIPTION
    地址与账号只有本地知道，不入库。取值顺序：
      1. Gradle 传下来的环境变量 LOLOSIA_THIRDPARTY_REPO / _USERNAME / _PASSWORD；
      2. 主工程根目录的 local.properties（直接跑脚本时）。
    静态库由 Gradle 从 <repo>/maven 拉取，源码包放在同一仓库的 /sources 下。
    脚本在下载源码前先用这份配置拼出镜像地址，取不到再回退到上游。
#>
function Get-LolosiaArtifactRepo {
    $url = $env:LOLOSIA_THIRDPARTY_REPO
    $username = $env:LOLOSIA_THIRDPARTY_USERNAME
    $password = $env:LOLOSIA_THIRDPARTY_PASSWORD

    if (-not $url) {
        $props = Join-Path (Get-LolosiaRepoRoot) 'local.properties'
        if (Test-Path $props) {
            $map = @{}
            foreach ($line in Get-Content $props) {
                if ($line -match '^\s*([A-Za-z0-9_.\-]+)\s*=\s*(.+?)\s*$') { $map[$Matches[1]] = $Matches[2] }
            }
            $url = $map['lolosia.thirdparty.repo']
            $username = $map['lolosia.thirdparty.username']
            $password = $map['lolosia.thirdparty.password']
        }
    }
    if (-not $url) { return $null }

    return @{
        Sources  = ($url -replace '/maven/?$', '/sources').TrimEnd('/')
        Username = $username
        Password = $password
    }
}

<#
.SYNOPSIS
    下载并解压第三方源码包，返回解压后的目录。
.DESCRIPTION
    源码落在 <WorkDir>/src 与 <WorkDir>/tree（默认 <repo>/build/thirdparty，已被 .gitignore 忽略），
    不随仓库提交；产物也写在 build/thirdparty/out/ 下，同样不入库。下载时会检查前两个字节，避免把 404 页面当成 tar 包解压。

    给了 -Library / -Version / -FileName 且 local.properties 配了工件仓库时，先按
    <repo>/sources/<Library>/<Version>/<FileName> 取；镜像不可用或文件缺失再回退 [Url]。
#>
function Get-ThirdpartySource(
    [string]$Name,
    [string]$Url,
    [string]$WorkDir,
    [string]$Library,
    [string]$Version,
    [string]$FileName,
    [switch]$Force
) {
    $srcDir = Join-Path $WorkDir 'src'
    $treeDir = Join-Path $WorkDir 'tree'
    $tarball = Join-Path $srcDir "$Name.tar.gz"
    $tree = Join-Path $treeDir $Name

    if ($Force) {
        if (Test-Path $tree) { Remove-Item $tree -Recurse -Force }
        if (Test-Path $tarball) { Remove-Item $tarball -Force }
    }
    New-Item -ItemType Directory -Force -Path $srcDir, $treeDir | Out-Null

    if (-not (Test-Path $tarball)) {
        $repo = Get-LolosiaArtifactRepo
        $urls = @()
        if ($repo -and $Library -and $Version -and $FileName) {
            $urls += "$($repo.Sources)/$Library/$Version/$FileName"
        }
        if ($Url) { $urls += $Url }
        if ($urls.Count -eq 0) { Stop-WithError "没有可用的 $Name 源码地址" }

        $downloaded = $false
        foreach ($u in $urls) {
            try {
                Write-Step "下载 $Name <- $u"
                $headers = @{}
                if ($repo -and $u.StartsWith($repo.Sources) -and $repo.Username) {
                    $pair = "$($repo.Username):$($repo.Password)"
                    $headers['Authorization'] = 'Basic ' + [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($pair))
                }
                Invoke-WebRequest -Uri $u -OutFile $tarball -Headers $headers -UseBasicParsing -TimeoutSec 600
                $magic = [IO.File]::ReadAllBytes($tarball)[0..1]
                if ($magic[0] -eq 0x3C -and $magic[1] -eq 0x21) {
                    throw '下载到的是 HTML 而非 tar 包'
                }
                $downloaded = $true
                break
            } catch {
                Write-Host "!!  失败：$($_.Exception.Message)" -ForegroundColor Yellow
                if (Test-Path $tarball) { Remove-Item $tarball -Force }
            }
        }
        if (-not $downloaded) { Stop-WithError "无法下载 $Name 源码（已尝试：$($urls -join ' | ')）" }
    } else {
        Write-Host "    已存在 $Name.tar.gz"
    }

    # 注意不能只看目录是否存在：Gradle 会为声明了 outputs.dir 的任务预先建好空目录，
    # 空目录直接跳过解压就会得到「源码目录不完整」。
    if (-not (Test-Path (Join-Path $tree 'CMakeLists.txt'))) {
        Write-Step "解压 $Name"
        if (Test-Path $tree) { Remove-Item $tree -Recurse -Force }
        & tar -xzf $tarball -C $treeDir
        if ($LASTEXITCODE -ne 0) { Stop-WithError "解压失败：$tarball" }
    }
    if (-not (Test-Path (Join-Path $tree 'CMakeLists.txt'))) { Stop-WithError "源码目录不完整：$tree" }
    return $tree
}
