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
        /**
         * Live headroom for the unbounded, non-timeshifted MPEG-TS stream.
         *
         * Buffering far ahead of a live tuner only pushes playback further behind live and turns
         * the loader into burst-idle cycles whose idle gaps the server must absorb while nobody
         * reads. A few seconds rides out Wi-Fi jitter, so every device shares the same live
         * window and the memory tiers below only tune the BML caps.
         */
        const val LIVE_MIN_BUFFER_MS = 2_500
        const val LIVE_MAX_BUFFER_MS = 8_000

        fun from(context: Context): PlaybackMemoryPolicy {
            val activityManager = context.getSystemService(ActivityManager::class.java)
            return forDevice(activityManager?.isLowRamDevice == true)
        }

        fun forDevice(isLowRamDevice: Boolean): PlaybackMemoryPolicy =
            if (isLowRamDevice) {
                PlaybackMemoryPolicy(
                    isLowRamDevice = true,
                    minBufferMs = LIVE_MIN_BUFFER_MS,
                    maxBufferMs = LIVE_MAX_BUFFER_MS,
                    targetBufferBytes = 8 * 1024 * 1024,
                    bmlMaxModuleBytes = 4L * 1024 * 1024,
                    bmlMaxCarouselBytes = 8L * 1024 * 1024,
                    bmlQueueCapacity = 32,
                )
            } else {
                PlaybackMemoryPolicy(
                    isLowRamDevice = false,
                    minBufferMs = LIVE_MIN_BUFFER_MS,
                    maxBufferMs = LIVE_MAX_BUFFER_MS,
                    targetBufferBytes = -1,
                    bmlMaxModuleBytes = 16L * 1024 * 1024,
                    bmlMaxCarouselBytes = 32L * 1024 * 1024,
                    bmlQueueCapacity = 128,
                )
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
