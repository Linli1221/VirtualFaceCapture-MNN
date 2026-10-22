package com.example.virtualfacecapture.web

import java.nio.ByteBuffer

/**
 * GL 线程与输出管线之间的桥。
 *
 * `Live2DCaptureRenderer` 每帧向它查询「现在要不要抓帧、抓多大、多少 fps」，
 * 抓帧完成后把 RGBA（预乘 alpha、已翻正）连同缓冲区一起交回来；
 * 缓冲区由实现方（[WebOutputManager]）负责回收。
 */
interface GlCaptureSink {

    /** 当前是否有浏览器在看（没人看就完全不抓帧，零额外开销）。 */
    fun isCaptureWanted(): Boolean

    /** 期望的输出高度（像素），0 表示跟随屏幕高度。 */
    fun requestedHeight(): Int

    /** 期望的输出帧率。 */
    fun requestedFps(): Int

    /**
     * 申请一块至少 `width * height * 4` 字节的直接缓冲区。
     * 返回 null 表示上一帧还没处理完，调用方应该直接跳过本帧（丢帧保延迟）。
     */
    fun acquireBuffer(width: Int, height: Int): ByteBuffer?

    /** 抓帧完成，缓冲区所有权移交给实现方。 */
    fun submitFrame(buffer: ByteBuffer, width: Int, height: Int)
}
