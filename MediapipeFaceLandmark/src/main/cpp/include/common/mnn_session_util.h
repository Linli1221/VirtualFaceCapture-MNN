/**
 * @file mnn_session_util.h
 * @brief MNN 会话配置 + 「真实后端」探测的唯一入口。
 *
 * 背景（第三轮审计 docs/perf/round3-native.md §4）：
 * 1) `MNN::ScheduleConfig::numThread` 与 `mode` 是 **union**（MNN/Interpreter.hpp:29-32）。
 *    Vulkan 后端下这个字段被解释成 `MNN_GPU_TUNING_*` 位标志，原来的
 *    `config.numThread = num_threads` 等于在 GPU 下调优档位上写了个 2/3/4，
 *    其中 2 = `MNN_GPU_TUNING_HEAVY`（头文件原话 "usually not suggested"）、
 *    3 = `NONE|HEAVY`（自相矛盾）。本文件把两条路径拆开写。
 * 2) `createSession(VULKAN)` 在设备上装不到 Vulkan 后端时会**静默**退到别的后端
 *    （`backupType` 默认 `MNN_FORWARD_CPU`）。所以「请求了 Vulkan」不等于「跑在 Vulkan 上」，
 *    必须用 `getSessionInfo(BACKENDS)` 事后探测真实后端，否则上层所有判断都是假的。
 */

#ifndef MEDIAPIPE_FACE_MNN_SESSION_UTIL_H
#define MEDIAPIPE_FACE_MNN_SESSION_UTIL_H

#include <cstddef>

#include "MNN/Interpreter.hpp"
#include "MNN/MNNForwardType.h"
#include "MNN/ErrorCode.hpp"

namespace mediapipe_face {

/**
 * 是否启用 `MNN_GPU_RECORD_BATCH`（Vulkan 下所有 op 共用一个 commandBuffer）。
 *
 * 默认 **关闭**：它被 MNN 标为 Vulkan 有效，但在部分驱动上会引入状态切换问题，
 * 而本仓库没有真机可以 A/B。需要时在 CMake/编译选项里定义
 * `-DMNN_FACE_GPU_RECORD_BATCH=1` 打开，不改代码。
 */
#ifndef MNN_FACE_GPU_RECORD_BATCH
#define MNN_FACE_GPU_RECORD_BATCH 0
#endif

/**
 * @brief 按后端类型填写 ScheduleConfig / BackendConfig。
 *
 * - Vulkan：只写 `type` 与 `mode`（GPU 调优档位），**绝不写 numThread**。
 * - CPU：写 `type` 与 `numThread`。
 * - precision 两个后端都保持 `Precision_Low_BF16`（与改动前一致，本轮不改精度，
 *   避免引入 478 点 z / 头姿的数值变化）。
 *
 * @param config       待填的调度配置（调用方保证生命周期覆盖 createSession）
 * @param backendCfg   待填的后端配置（同上）
 * @param use_vulkan   true = MNN_FORWARD_VULKAN
 * @param num_threads  CPU 后端线程数（<=0 时按 1 处理）
 */
inline void applyScheduleConfig(MNN::ScheduleConfig& config,
                                MNN::BackendConfig& backendCfg,
                                bool use_vulkan,
                                int num_threads) {
    backendCfg.precision = MNN::BackendConfig::Precision_Low_BF16;
    config.backendConfig = &backendCfg;

    if (use_vulkan) {
        config.type = MNN_FORWARD_VULKAN;
        int gpu_mode = MNN_GPU_TUNING_WIDE;
#if MNN_FACE_GPU_RECORD_BATCH
        gpu_mode |= MNN_GPU_RECORD_BATCH;
#endif
        // 注意：这一行写的是 union 的 mode 侧，不是 numThread。
        config.mode = gpu_mode;
    } else {
        config.type = MNN_FORWARD_CPU;
        config.numThread = (num_threads > 0) ? num_threads : 1;
    }
}

/**
 * @brief 判断一个已创建的 session 是否真的跑在 Vulkan 后端上。
 *
 * MNN 的 `getSessionInfo(BACKENDS)` 返回 `int*`，但不同 MNN 版本对这段内存的
 * 填充布局并不完全一致（可能是 `[count, type...]`，也可能直接是 `[type, ...]`）。
 * 因为本工程每个 session 只用一个 config（最多再加一个 CPU backup），这里**不解析
 * 个数**，直接扫描 4 个 int 里是否出现 `MNN_FORWARD_VULKAN`：
 * - 布局带 count → Vulkan 出现在 type 段；
 * - 布局不带 count → Vulkan 出现在第一个位置。
 * 反例只有「backend 个数恰好 = 3 且不含 Vulkan」，在本工程的 config 下不可能。
 *
 * @param interp     解释器
 * @param session    会话
 * @param raw_out    可选，回传原始 4 个整数（用于日志）；传 nullptr 时忽略
 * @return true = 真实后端里含 MNN_FORWARD_VULKAN
 */
inline bool sessionVulkanActive(MNN::Interpreter* interp,
                                MNN::Session* session,
                                int* raw_out) {
    if (raw_out != nullptr) {
        raw_out[0] = -1;
        raw_out[1] = -1;
        raw_out[2] = -1;
        raw_out[3] = -1;
    }
    if (interp == nullptr || session == nullptr) {
        return false;
    }

    int buf[4] = {-1, -1, -1, -1};
    const bool ok = interp->getSessionInfo(session, MNN::Interpreter::BACKENDS, buf);

    if (raw_out != nullptr) {
        raw_out[0] = buf[0];
        raw_out[1] = buf[1];
        raw_out[2] = buf[2];
        raw_out[3] = buf[3];
    }
    if (!ok) {
        return false;
    }

    const int kVulkanType = static_cast<int>(MNN_FORWARD_VULKAN);

    // 不猜布局，直接扫全 4 个槽位：
    // - 若布局是 [count, type...]，Vulkan 会在 type 段里出现；
    // - 若布局是 [type, ...]（无 count），Vulkan 会在第一个位置出现。
    // 唯一可能的误判是「backend 个数恰好等于 3 且其中没有 Vulkan」，而本工程只
    // 创建 1 个 config（最多再加一个 CPU backup = 2），不构成实际风险。
    for (int i = 0; i < 4; ++i) {
        if (buf[i] == kVulkanType) {
            return true;
        }
    }
    return false;
}

/**
 * @brief 后端名（只用于日志文案）。
 *
 * 注意：本头文件**不**调用任何 FACE_LOG_* 宏。FACE_LOG_TAG 是每个 .cpp 自己在
 * include 之后改写的模块 tag，若在这个头里的 inline 函数中打日志，同一个 inline
 * 函数在不同 TU 里展开成不同的 token 序列，属于 ODR 违规（链接器只会保留一份）。
 * 所以日志统一由各 .cpp 用本模块 tag 输出，这里只提供解析与文案。
 */
inline const char* backendLabel(bool vulkan) {
    return vulkan ? "Vulkan" : "CPU";
}

} // namespace mediapipe_face

#endif // MEDIAPIPE_FACE_MNN_SESSION_UTIL_H
