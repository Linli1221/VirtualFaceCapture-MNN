package com.example.commondata

/**
 * Facing direction of a capture device, independent of the Android Camera2
 * integer constants so the model can travel through the SDK without leaking
 * platform details to consumers.
 */
enum class CameraLensFacing {
    FRONT,
    BACK,
    EXTERNAL,
    UNKNOWN,
}

/**
 * Lightweight description of one camera exposed by [android.hardware.camera2.CameraManager].
 *
 * Returned by `PipelineFaceTracker.getAvailableCameras()` and used by the demo
 * app's camera picker. `sensorOrientation` / `verticalFovDegrees` are read per
 * device so that switching from the front camera to a rear or USB camera keeps
 * the rotation, mirroring and head-pose scale correct.
 *
 * @property id                 Camera2 camera id (`CameraManager.cameraIdList` entry).
 * @property lensFacing         Front / back / external / unknown.
 * @property label              Human-readable short label ("Front", "Back", "External").
 * @property sensorOrientation  Sensor orientation in degrees (0 / 90 / 180 / 270).
 * @property verticalFovDegrees  Vertical field of view estimated from sensor size
 *                              and focal length; used by the head-pose solver.
 */
data class CameraDeviceInfo(
    val id: String,
    val lensFacing: CameraLensFacing,
    val label: String,
    val sensorOrientation: Int,
    val verticalFovDegrees: Float,
) {
    val isFrontFacing: Boolean get() = lensFacing == CameraLensFacing.FRONT
    val isBackFacing: Boolean get() = lensFacing == CameraLensFacing.BACK
    val isExternal: Boolean get() = lensFacing == CameraLensFacing.EXTERNAL
}
