package net.rokoucha.visiomata.playback

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.util.Log

internal data class PlaybackMemoryPolicy(
    val isLowRamDevice: Boolean,
    val minBufferMs: Int,
    val maxBufferMs: Int,
    val targetBufferBytes: Int,
    val bmlMaxModuleBytes: Long,
    val bmlMaxCarouselBytes: Long,
    val bmlQueueCapacity: Int,
) {
    companion object {
        fun from(context: Context): PlaybackMemoryPolicy {
            val activityManager = context.getSystemService(ActivityManager::class.java)
            val lowRam = activityManager?.isLowRamDevice == true
            return if (lowRam) {
                PlaybackMemoryPolicy(
                    isLowRamDevice = true,
                    minBufferMs = 2_500,
                    maxBufferMs = 8_000,
                    targetBufferBytes = 8 * 1024 * 1024,
                    bmlMaxModuleBytes = 4L * 1024 * 1024,
                    bmlMaxCarouselBytes = 8L * 1024 * 1024,
                    bmlQueueCapacity = 32,
                )
            } else {
                PlaybackMemoryPolicy(
                    isLowRamDevice = false,
                    minBufferMs = 50_000,
                    maxBufferMs = 50_000,
                    targetBufferBytes = -1,
                    bmlMaxModuleBytes = 16L * 1024 * 1024,
                    bmlMaxCarouselBytes = 32L * 1024 * 1024,
                    bmlQueueCapacity = 128,
                )
            }
        }
    }
}

internal object PlaybackMemoryMonitor {
    private const val tag = "VisiomataMemory"

    fun record(
        context: Context,
        event: String,
        videoPath: String,
        bmlEnabled: Boolean,
    ) {
        val memory = Debug.MemoryInfo()
        Debug.getMemoryInfo(memory)
        val runtime = Runtime.getRuntime()
        val javaHeapKb = (runtime.totalMemory() - runtime.freeMemory()) / 1024
        val nativeHeapKb = Debug.getNativeHeapAllocatedSize() / 1024
        val lowRam = context.getSystemService(ActivityManager::class.java)?.isLowRamDevice == true
        Log.i(
            tag,
            "event=$event path=$videoPath bml=$bmlEnabled lowRam=$lowRam " +
                "pssKb=${memory.totalPss} javaHeapKb=$javaHeapKb nativeHeapKb=$nativeHeapKb",
        )
    }
}
