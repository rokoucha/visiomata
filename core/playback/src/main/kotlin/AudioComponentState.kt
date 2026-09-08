package net.rokoucha.visiomata.playback

import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Program audio metadata shared by the UI thread and the extractor thread.
 *
 * Event information describes labels and intended components. Whether an AAC payload is actually
 * dual mono is deliberately recorded separately from the event: the two can change at different
 * times around a program boundary.
 */
internal class AudioComponentState(
    initialComponents: List<BroadcastAudioComponent>,
) {
    private data class Snapshot(
        val components: Map<Int, BroadcastAudioComponent>,
        val streamGeneration: Long,
        val dualMonoByStream: Map<String, Boolean>,
    )

    private val snapshot =
        AtomicReference(
            Snapshot(
                components = initialComponents.canonicalComponents(),
                streamGeneration = 0,
                dualMonoByStream = emptyMap(),
            ),
        )
    private val released = AtomicBoolean(false)
    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    fun updateComponents(components: List<BroadcastAudioComponent>): Boolean {
        if (released.get()) return false
        val canonical = components.canonicalComponents()
        while (true) {
            val current = snapshot.get()
            if (current.components == canonical) return false
            if (snapshot.compareAndSet(current, current.copy(components = canonical))) {
                notifyChanged()
                return true
            }
        }
    }

    /** Starts an extractor generation and invalidates reports from the preceding connection. */
    fun beginStream(): Long {
        if (released.get()) return RELEASED_GENERATION
        while (true) {
            val current = snapshot.get()
            val next =
                current.copy(
                    streamGeneration = current.streamGeneration + 1,
                    dualMonoByStream = emptyMap(),
                )
            if (snapshot.compareAndSet(current, next)) {
                if (current.dualMonoByStream.isNotEmpty()) notifyChanged()
                return next.streamGeneration
            }
        }
    }

    /** Records a property observed in AAC itself, ignoring readers from older connections. */
    fun updateDualMono(
        streamKey: String,
        streamGeneration: Long,
        isDualMono: Boolean,
    ): Boolean {
        if (released.get() || streamGeneration == RELEASED_GENERATION) return false
        while (true) {
            val current = snapshot.get()
            if (current.streamGeneration != streamGeneration) return false
            if (current.dualMonoByStream[streamKey] == isDualMono) return false
            val next = current.copy(dualMonoByStream = current.dualMonoByStream + (streamKey to isDualMono))
            if (snapshot.compareAndSet(current, next)) {
                notifyChanged()
                return true
            }
        }
    }

    fun component(componentTag: Int?): BroadcastAudioComponent? = componentTag?.let(snapshot.get().components::get)

    fun isComponentAvailable(componentTag: Int?): Boolean {
        val components = snapshot.get().components
        return componentTag == null || components.isEmpty() || componentTag in components
    }

    fun isDualMono(streamKey: String): Boolean = snapshot.get().dualMonoByStream[streamKey] == true

    fun isAudioTrackAvailable(formatId: String?): Boolean {
        val id = formatId?.toAribAudioId() ?: return true
        val current = snapshot.get()
        if (id.componentTag != null && current.components.isNotEmpty() && id.componentTag !in current.components) {
            return false
        }
        return !id.isSub || current.dualMonoByStream[id.streamKey] == true
    }

    /** Higher values are preferred when an explicit selection disappears. */
    fun selectionPriority(formatId: String?): Int {
        val id = formatId?.toAribAudioId() ?: return GENERIC_TRACK_PRIORITY
        val current = snapshot.get()
        if (id.componentTag != null && current.components.isNotEmpty() && id.componentTag !in current.components) {
            return UNAVAILABLE_TRACK_PRIORITY
        }
        if (id.isSub) {
            return if (current.dualMonoByStream[id.streamKey] ==
                true
            ) {
                SUB_TRACK_PRIORITY
            } else {
                UNAVAILABLE_TRACK_PRIORITY
            }
        }
        return when (id.componentTag?.let(current.components::get)?.isMain) {
            true -> MAIN_COMPONENT_PRIORITY
            false -> AUXILIARY_COMPONENT_PRIORITY
            null -> UNKNOWN_MAIN_TRACK_PRIORITY
        }
    }

    fun presentation(
        formatId: String?,
        fallbackLanguage: String?,
        fallbackLabel: String?,
    ): AudioTrackPresentation {
        val id =
            formatId?.toAribAudioId()
                ?: return AudioTrackPresentation(fallbackLanguage, fallbackLabel)
        val current = snapshot.get()
        val component = id.componentTag?.let(current.components::get)
        val dualMono = current.dualMonoByStream[id.streamKey] == true
        val language = component?.languages?.getOrNull(if (id.isSub) 1 else 0) ?: fallbackLanguage
        val label =
            when {
                id.isSub -> "第二音声"
                dualMono -> "第一音声"
                component?.isMain == true -> "主音声"
                component?.isMain == false -> "副音声"
                else -> fallbackLabel
            }
        return AudioTrackPresentation(language, label)
    }

    fun addListener(listener: () -> Unit) {
        if (!released.get()) listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    fun release() {
        if (!released.compareAndSet(false, true)) return
        listeners.clear()
        while (true) {
            val current = snapshot.get()
            if (
                snapshot.compareAndSet(
                    current,
                    current.copy(
                        streamGeneration = current.streamGeneration + 1,
                        dualMonoByStream = emptyMap(),
                    ),
                )
            ) {
                return
            }
        }
    }

    private fun notifyChanged() = listeners.forEach { it() }

    private fun List<BroadcastAudioComponent>.canonicalComponents(): Map<Int, BroadcastAudioComponent> =
        associate { component ->
            component.componentTag to
                component.copy(
                    languages =
                        component.languages
                            .map { it.lowercase(Locale.ROOT) }
                            .distinct(),
                )
        }.toSortedMap()

    private companion object {
        const val RELEASED_GENERATION = -1L
        const val UNAVAILABLE_TRACK_PRIORITY = -1
        const val SUB_TRACK_PRIORITY = 0
        const val AUXILIARY_COMPONENT_PRIORITY = 1
        const val GENERIC_TRACK_PRIORITY = 2
        const val UNKNOWN_MAIN_TRACK_PRIORITY = 2
        const val MAIN_COMPONENT_PRIORITY = 3
    }
}

internal data class AudioTrackPresentation(
    val language: String?,
    val label: String?,
)

internal data class AribAudioTrackId(
    val streamKey: String,
    val componentTag: Int?,
    val isSub: Boolean,
)

internal fun aribAudioFormatId(
    streamKey: String,
    isSub: Boolean,
): String = "$ARIB_AUDIO_ID_PREFIX$streamKey/${if (isSub) "sub" else "main"}"

private fun String.toAribAudioId(): AribAudioTrackId? {
    if (!startsWith(ARIB_AUDIO_ID_PREFIX)) return null
    val suffixStart = lastIndexOf('/')
    if (suffixStart < ARIB_AUDIO_ID_PREFIX.length) return null
    val streamKey = substring(ARIB_AUDIO_ID_PREFIX.length, suffixStart)
    val isSub =
        when (substring(suffixStart + 1)) {
            "main" -> false
            "sub" -> true
            else -> return null
        }
    return AribAudioTrackId(
        streamKey = streamKey,
        componentTag = streamKey.takeIf { it.startsWith("component-") }?.removePrefix("component-")?.toIntOrNull(),
        isSub = isSub,
    )
}

private const val ARIB_AUDIO_ID_PREFIX = "arib-audio/"
