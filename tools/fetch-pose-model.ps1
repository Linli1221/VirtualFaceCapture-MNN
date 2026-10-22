<#
.SYNOPSIS
    下载官方 MediaPipe Pose Landmarker 模型到 MediapipeUpperBody/src/main/assets/。

.DESCRIPTION
    - lite : pose_landmarker_lite.task（约 5.5 MB，默认）
    - full : pose_landmarker_full.task（约 9 MB，更准、更慢）
    下载完成后会打印文件大小并校验 > 1MB；模型文件被 .gitignore 排除，不会提交。

.PARAMETER Variant
    lite（默认）/ full / both。

.PARAMETER Force
    已存在时也重新下载。

.EXAMPLE
    .\tools\fetch-pose-model.ps1
    .\tools\fetch-pose-model.ps1 -Variant both
    .\tools\fetch-pose-model.ps1 -Variant lite -Force
#>
[CmdletBinding()]
param(
    [ValidateSet('lite', 'full', 'both')]
    [string]$Variant = 'lite',

    [switch]$Force
)

$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent $PSScriptRoot
$destDir = Join-Path $repoRoot 'MediapipeUpperBody\src\main\assets'

$models = @{
    lite = [pscustomobject]@{
        Name = 'pose_landmarker_lite.task'
        Url  = 'https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_lite/float16/1/pose_landmarker_lite.task'
    }
    full = [pscustomobject]@{
        Name = 'pose_landmarker_full.task'
        Url  = 'https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_full/float16/1/pose_landmarker_full.task'
    }
}

$selected = if ($Variant -eq 'both') { @('lite', 'full') } else { @($Variant) }

if (-not (Test-Path -LiteralPath $destDir)) {
    New-Item -ItemType Directory -Path $destDir -Force | Out-Null
}

foreach ($key in $selected) {
    $model = $models[$key]
    $target = Join-Path $destDir $model.Name

    if ((Test-Path -LiteralPath $target) -and (-not $Force)) {
        $existingSize = (Get-Item -LiteralPath $target).Length
        $existingMb = [math]::Round($existingSize / 1MB, 2)
        Write-Host "[跳过] 已存在：$target ($existingMb MB)。如需重新下载请加 -Force" -ForegroundColor Yellow
        continue
    }

    $temp = "$target.download"
    if (Test-Path -LiteralPath $temp) {
        Remove-Item -LiteralPath $temp -Force
    }

    Write-Host "[下载] $($model.Url)"
    try {
        # CI / 无交互终端里进度条会刷屏，临时关掉
        $previousProgress = $ProgressPreference
        $ProgressPreference = 'SilentlyContinue'
        try {
            Invoke-WebRequest -Uri $model.Url -OutFile $temp -UseBasicParsing
        }
        finally {
            $ProgressPreference = $previousProgress
        }
    }
    catch {
        if (Test-Path -LiteralPath $temp) {
            Remove-Item -LiteralPath $temp -Force
        }
        throw "下载失败：$($model.Url)；原因：$($_.Exception.Message)；请检查网络 / 代理后重试。"
    }

    if (-not (Test-Path -LiteralPath $temp)) {
        throw "下载失败：没有生成临时文件 $temp"
    }

    $length = (Get-Item -LiteralPath $temp).Length
    if ($length -lt 1MB) {
        Remove-Item -LiteralPath $temp -Force
        throw "下载不完整：$($model.Name) 只有 $length 字节（应 > 1MB），已删除。请重试或检查网络。"
    }

    Move-Item -LiteralPath $temp -Destination $target -Force
    $sizeMb = [math]::Round($length / 1MB, 2)
    Write-Host "[完成] $target ($sizeMb MB)" -ForegroundColor Green
}

Write-Host "[OK] 模型已就绪，重新构建 app 后即可使用上半身功能。" -ForegroundColor Green
