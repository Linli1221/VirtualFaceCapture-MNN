#!/usr/bin/env bash
# 下载官方 MediaPipe Pose Landmarker 模型到 MediapipeUpperBody/src/main/assets/
#
# 用法:
#   ./tools/fetch-pose-model.sh            # 默认 lite
#   ./tools/fetch-pose-model.sh full       # full
#   ./tools/fetch-pose-model.sh both       # lite + full
#   FORCE=1 ./tools/fetch-pose-model.sh    # 已存在也重新下载
#
# 模型文件（*.task）被 .gitignore 排除，不会提交。
set -euo pipefail

VARIANT="${1:-lite}"
case "$VARIANT" in
    lite|full|both) ;;
    *)
        echo "错误：未知档位 '$VARIANT'，可选 lite / full / both" >&2
        exit 1
        ;;
esac

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="$ROOT/MediapipeUpperBody/src/main/assets"
BASE_URL="https://storage.googleapis.com/mediapipe-models/pose_landmarker"

url_for() {
    case "$1" in
        lite) echo "$BASE_URL/pose_landmarker_lite/float16/1/pose_landmarker_lite.task" ;;
        full) echo "$BASE_URL/pose_landmarker_full/float16/1/pose_landmarker_full.task" ;;
    esac
}

name_for() {
    case "$1" in
        lite) echo "pose_landmarker_lite.task" ;;
        full) echo "pose_landmarker_full.task" ;;
    esac
}

size_mb() {
    # MiB，向下取整
    echo $(($1 / 1024 / 1024))
}

download_one() {
    local key="$1"
    local name url target temp size

    name="$(name_for "$key")"
    url="$(url_for "$key")"
    target="$DEST/$name"

    if [[ -f "$target" && "${FORCE:-0}" != "1" ]]; then
        size=$(wc -c < "$target" | tr -d ' ')
        echo "[跳过] 已存在：$target ($(size_mb "$size") MB)。如需重下：FORCE=1 ./tools/fetch-pose-model.sh $key"
        return 0
    fi

    mkdir -p "$DEST"
    temp="$target.download"
    rm -f "$temp"

    echo "[下载] $url"
    if ! curl -L --fail --retry 3 --connect-timeout 20 -o "$temp" "$url"; then
        rm -f "$temp"
        echo "错误：下载失败。请检查网络 / 代理后重试：$url" >&2
        return 1
    fi

    if [[ ! -f "$temp" ]]; then
        echo "错误：下载失败，没有生成临时文件 $temp" >&2
        return 1
    fi

    size=$(wc -c < "$temp" | tr -d ' ')
    if (( size < 1000000 )); then
        rm -f "$temp"
        echo "错误：下载不完整（$size 字节，应 > 1MB），已删除：$url" >&2
        return 1
    fi

    mv -f "$temp" "$target"
    echo "[完成] $target ($(size_mb "$size") MB)"
}

if [[ "$VARIANT" == "both" ]]; then
    download_one lite
    download_one full
else
    download_one "$VARIANT"
fi

echo "[OK] 模型已就绪：$DEST"
