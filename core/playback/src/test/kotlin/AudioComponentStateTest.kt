package net.rokoucha.visiomata.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioComponentStateTest {
    @Test
    fun eventMetadataUpdatesPresentationWithoutClaimingDualMono() {
        val state = AudioComponentState(emptyList())
        val generation = state.beginStream()
        val mainId = aribAudioFormatId("component-16", isSub = false)
        val subId = aribAudioFormatId("component-16", isSub = true)

        assertTrue(state.isAudioTrackAvailable(mainId))
        assertFalse(state.isAudioTrackAvailable(subId))

        state.updateComponents(
            listOf(BroadcastAudioComponent(16, isMain = true, isDualMono = true, languages = listOf("JPN", "ENG"))),
        )

        assertFalse("event metadata must not expose audio absent from AAC", state.isAudioTrackAvailable(subId))
        assertEquals(AudioTrackPresentation("jpn", "主音声"), state.presentation(mainId, null, "音声"))

        state.updateDualMono("component-16", generation, true)

        assertTrue(state.isAudioTrackAvailable(subId))
        assertEquals(AudioTrackPresentation("jpn", "第一音声"), state.presentation(mainId, null, "音声"))
        assertEquals(AudioTrackPresentation("eng", "第二音声"), state.presentation(subId, null, "音声"))
    }

    @Test
    fun lateEventMetadataRelabelsAlreadyObservedDualMono() {
        val state = AudioComponentState(emptyList())
        val generation = state.beginStream()
        val mainId = aribAudioFormatId("component-16", false)
        val subId = aribAudioFormatId("component-16", true)

        state.updateDualMono("component-16", generation, true)
        assertTrue(state.isAudioTrackAvailable(subId))
        assertEquals(AudioTrackPresentation("jpn", "第一音声"), state.presentation(mainId, "jpn", "音声"))

        state.updateComponents(
            listOf(BroadcastAudioComponent(16, isMain = true, isDualMono = true, languages = listOf("jpn", "eng"))),
        )
        assertEquals(AudioTrackPresentation("eng", "第二音声"), state.presentation(subId, null, "音声"))
    }

    @Test
    fun stereoDualMonoStereoTransitionHidesLostSelection() {
        val state =
            AudioComponentState(
                listOf(BroadcastAudioComponent(16, isMain = true, isDualMono = false, languages = listOf("jpn"))),
            )
        val generation = state.beginStream()
        val mainId = aribAudioFormatId("component-16", false)
        val selectedSubId = aribAudioFormatId("component-16", true)

        state.updateDualMono("component-16", generation, false)
        assertTrue(state.isAudioTrackAvailable(mainId))
        assertFalse(state.isAudioTrackAvailable(selectedSubId))

        state.updateComponents(
            listOf(BroadcastAudioComponent(16, isMain = true, isDualMono = true, languages = listOf("jpn", "eng"))),
        )
        assertFalse(state.isAudioTrackAvailable(selectedSubId))

        state.updateDualMono("component-16", generation, true)
        assertTrue(state.isAudioTrackAvailable(selectedSubId))

        state.updateComponents(
            listOf(BroadcastAudioComponent(16, isMain = true, isDualMono = false, languages = listOf("jpn"))),
        )
        assertTrue("AAC remains authoritative while metadata leads", state.isAudioTrackAvailable(selectedSubId))

        state.updateDualMono("component-16", generation, false)
        assertTrue(state.isAudioTrackAvailable(mainId))
        assertFalse(
            "the vanished selected sub track must not remain selectable",
            state.isAudioTrackAvailable(selectedSubId),
        )
    }

    @Test
    fun componentAndLanguageChangesAreCanonicalAndIdempotent() {
        val state = AudioComponentState(emptyList())
        var changes = 0
        state.addListener { changes++ }
        val components =
            listOf(
                BroadcastAudioComponent(17, isMain = false, isDualMono = false, languages = listOf("ENG")),
                BroadcastAudioComponent(16, isMain = true, isDualMono = false, languages = listOf("JPN")),
            )

        assertTrue(state.updateComponents(components))
        assertFalse(state.updateComponents(components.reversed()))
        assertEquals(1, changes)
        assertEquals(
            AudioTrackPresentation("eng", "副音声"),
            state.presentation(aribAudioFormatId("component-17", false), null, null),
        )

        assertTrue(
            state.updateComponents(
                listOf(BroadcastAudioComponent(16, isMain = true, isDualMono = false, languages = listOf("jpn"))),
            ),
        )
        assertFalse(state.isAudioTrackAvailable(aribAudioFormatId("component-17", false)))
        assertTrue(state.isAudioTrackAvailable(aribAudioFormatId("component-16", false)))
        assertTrue(
            state.selectionPriority(aribAudioFormatId("component-16", false)) >
                state.selectionPriority(aribAudioFormatId("component-17", false)),
        )
    }

    @Test
    fun lateReportsFromOldStreamAndReportsAfterReleaseAreIgnored() {
        val state = AudioComponentState(emptyList())
        val oldGeneration = state.beginStream()
        assertTrue(state.updateDualMono("component-16", oldGeneration, true))

        val currentGeneration = state.beginStream()
        assertFalse(state.isDualMono("component-16"))
        assertFalse(state.updateDualMono("component-16", oldGeneration, true))
        assertTrue(state.updateDualMono("component-16", currentGeneration, true))

        state.release()
        assertFalse(state.updateDualMono("component-16", currentGeneration, false))
        assertFalse(
            state.updateComponents(
                listOf(BroadcastAudioComponent(17, isMain = true, isDualMono = false)),
            ),
        )
    }
}
