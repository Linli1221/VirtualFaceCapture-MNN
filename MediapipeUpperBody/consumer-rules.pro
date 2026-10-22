# MediaPipe Tasks（com.google.mediapipe:tasks-vision）自带 consumer rules。
# 这里只需要保留本模块对外暴露的公开 API（reflection / JNI 不会用到，但防止裁剪后
# app 侧按类名查找时找不到）。
-keep public class com.example.mediapipeupperbody.UpperBodyPoseDetector {
    public *;
}
-keep class com.example.mediapipeupperbody.Yuv420Frame {
    public *;
}
