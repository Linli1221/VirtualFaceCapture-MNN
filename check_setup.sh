#!/bin/bash
# 环境自检：检查「安卓面捕 + Windows 渲染」这套拆分架构所需的关键文件。
# 用法：./check_setup.sh
# 只做存在性检查，不做构建；构建与验证命令见 README.md。

echo "=============================================="
echo " VirtualFaceCapture-MNN 环境自检"
echo "=============================================="
echo ""

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ERRORS=0
WARNINGS=0

ok()   { echo -e "${GREEN}✓${NC} $1"; }
bad()  { echo -e "${RED}✗${NC} $1"; ERRORS=$((ERRORS + 1)); }
warn() { echo -e "${YELLOW}⚠${NC} $1"; WARNINGS=$((WARNINGS + 1)); }

check_file() { [ -f "$1" ] && ok "$2" || bad "$2 (缺少: ${1#$PROJECT_ROOT/})"; }
check_dir()  { [ -d "$1" ] && ok "$2" || bad "$2 (缺少: ${1#$PROJECT_ROOT/})"; }

echo "1. 安卓面捕模块"
check_dir  "$PROJECT_ROOT/MediapipeFaceLandmark/src/main/assets" "MNN 模型目录"
check_dir  "$PROJECT_ROOT/MediapipeFaceLandmark/src/main/jniLibs/arm64-v8a" "libMNN.so (arm64-v8a)"
check_file "$PROJECT_ROOT/MediapipeFaceLandmark/src/main/cpp/MediaPipeFaceLandmarkJNI.cpp" "JNI 入口"
check_file "$PROJECT_ROOT/OpenSeeFaceProcess/src/main/java/com/live2d/facecapture/FaceCapturePipeline.kt" "面捕后处理管线"
check_file "$PROJECT_ROOT/CommonData/src/main/java/com/example/commondata/Landmark.kt" "ARKitBlendShapes 数据类型"
echo ""

echo "2. 追踪数据服务（手机端 → PC）"
check_file "$PROJECT_ROOT/app/src/main/java/com/example/virtualfacecapture/tracking/TrackingServer.kt" "TrackingServer"
check_file "$PROJECT_ROOT/app/src/main/java/com/example/virtualfacecapture/tracking/TrackingProtocol.kt" "TrackingProtocol"
check_file "$PROJECT_ROOT/docs/tracking-protocol.md" "协议文档"
echo ""

echo "3. Windows 渲染端"
check_dir  "$PROJECT_ROOT/windows-client/renderer" "渲染页"
check_file "$PROJECT_ROOT/windows-client/server.js" "本地静态服务"
check_file "$PROJECT_ROOT/windows-client/renderer/mapping.js" "ARKit → Live2D 映射"
check_dir  "$PROJECT_ROOT/windows-client/models" "Live2D 模型目录"
MODEL_COUNT=$(ls -d "$PROJECT_ROOT/windows-client/models"/*/ 2>/dev/null | wc -l | tr -d ' ')
if [ "$MODEL_COUNT" -gt 0 ]; then ok "已发现 $MODEL_COUNT 个 Live2D 模型"; else warn "models/ 下还没有模型"; fi
if [ -d "$PROJECT_ROOT/windows-client/node_modules" ]; then
    ok "node_modules 已安装"
else
    warn "windows-client 还没 npm install（只影响 Electron / npm start）"
fi
echo ""

echo "4. 第三方依赖"
if [ -d "$PROJECT_ROOT/ThirdParty/MNN/include/MNN" ]; then
    ok "MNN 头文件已就位"
else
    warn "MNN 头文件缺失，请执行：git submodule update --init --depth 1 ThirdParty/MNN"
fi
echo ""

echo "5. 旧版手机端 Live2D 渲染模块（已停止构建，仅参考）"
if grep -q "include ':Live2D'" "$PROJECT_ROOT/settings.gradle" 2>/dev/null; then
    warn "settings.gradle 里仍有 include ':Live2D'（当前版本应为注释状态）"
else
    ok "settings.gradle 未包含 :Live2D（符合当前设计）"
fi
echo ""

echo "=============================================="
if [ "$ERRORS" -eq 0 ]; then
    echo -e "${GREEN}自检通过${NC}（警告 ${WARNINGS} 项）"
    echo "下一步：./gradlew :app:assembleDebug；cd windows-client && npm i && npm start"
    exit 0
else
    echo -e "${RED}发现 ${ERRORS} 个问题${NC}（警告 ${WARNINGS} 项）"
    exit 1
fi
