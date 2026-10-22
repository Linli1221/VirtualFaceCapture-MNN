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
 * Two kinds of entries exist:
 *
 *  - **logical camera** — [physicalCameraId] is `null`; [id] is a normal
 *    `CameraManager.cameraIdList` entry and can be opened directly;
 *  - **physical camera** — [physicalCameraId] is set; [id] is the *logical*
 *    multi-camera that owns it. Such a sensor (typically the rear ultra-wide
 *    or telephoto lens) is hidden from `cameraIdList` on many phones, so it
 *    cannot be opened by id. Instead the logical camera is opened and the
 *    stream is bound to the physical sensor with
 *    `OutputConfiguration.setPhysicalCameraId` (API 28+).
 *
 * @property id                    Logical Camera2 camera id used with `openCamera`.
 * @property lensFacing            Front / back / external / unknown.
 * @property label                 Human-readable short label ("Front", "Back 超广角").
 * @property sensorOrientation     Sensor orientation in degrees (0 / 90 / 180 / 270).
 * @property verticalFovDegrees    Vertical field of view estimated from sensor size
 *                                 and focal length; used by the head-pose solver.
 * @property physicalCameraId      Physical sensor id when this entry is a physical
 *                                 camera of a logical multi-camera, else `null`.
 * @property equivalentFocalLengthMm  35 mm-equivalent focal length (sensor diagonal
 *                                 based), used to label ultra-wide / telephoto lenses
 *                                 and shown in the picker. `null` if unknown.
 */
data class CameraDeviceInfo(
    val id: String,
    val lensFacing: CameraLensFacing,
    val label: String,
    val sensorOrientation: Int,
    val verticalFovDegrees: Float,
    val physicalCameraId: String? = null,
    val equivalentFocalLengthMm: Float? = null,
) {
    val isFrontFacing: Boolean get() = lensFacing == CameraLensFacing.FRONT
    val isBackFacing: Boolean get() = lensFacing == CameraLensFacing.BACK
    val isExternal: Boolean get() = lensFacing == CameraLensFacing.EXTERNAL

    /** True when frames come from a physical sensor behind a logical multi-camera. */
    val isPhysical: Boolean get() = !physicalCameraId.isNullOrEmpty()

    /**
     * Unique key for picker selection and persisted preferences.
     *
     * A logical camera uses its camera id (`"0"`), a physical one appends the
     * sensor id (`"0#2"`). [id] alone is not unique: several physical entries
     * share the same logical parent.
     */
    val key: String
        get() = if (isPhysical) "$id#$physicalCameraId" else id
}
