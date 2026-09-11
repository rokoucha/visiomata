package net.rokoucha.visiomata

import net.rokoucha.visiomata.model.ChannelType
import net.rokoucha.visiomata.model.ProgramGuide
import net.rokoucha.visiomata.model.Service
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeUiStateTest {
    private fun service(
        id: Long,
        channelType: String = "GR",
    ) = Service(
        id = id,
        networkId = 1,
        transportStreamId = null,
        serviceId = id.toInt(),
        name = "Service $id",
        channelType = ChannelType(channelType),
        channel = "channel",
        remoteControlKeyId = null,
        logoId = null,
    )

    @Test
    fun loading_whileCatalogueHasNotArrived() {
        assertEquals(HomeUiState.Loading, homeUiStateFor(null, snapshotDone = false, snapshotError = null))
        assertEquals(
            HomeUiState.Loading,
            homeUiStateFor(
                ProgramGuide(emptyList(), emptyList()),
                snapshotDone = false,
                snapshotError = null,
            ),
        )
    }

    @Test
    fun readyWithCatalogue_beforeSnapshotCompletes() {
        val guide = ProgramGuide(listOf(service(10), service(20)), emptyList())

        val state = homeUiStateFor(guide, snapshotDone = false, snapshotError = null)

        assertTrue(state is HomeUiState.Ready)
        assertEquals(listOf(10L, 20L), (state as HomeUiState.Ready).choices.map { it.primaryServiceId })
    }

    @Test
    fun readyWithCatalogue_afterSnapshotCompletes() {
        val guide = ProgramGuide(listOf(service(10)), emptyList())

        val state = homeUiStateFor(guide, snapshotDone = true, snapshotError = null)

        assertTrue(state is HomeUiState.Ready)
        assertEquals(listOf(10L), (state as HomeUiState.Ready).choices.map { it.primaryServiceId })
    }

    @Test
    fun error_replacesOnlyEmptyHome() {
        val error = IllegalStateException("boom")

        assertEquals(
            HomeUiState.Error("boom"),
            homeUiStateFor(
                ProgramGuide(emptyList(), emptyList()),
                snapshotDone = true,
                snapshotError = error,
            ),
        )
        assertTrue(
            homeUiStateFor(
                ProgramGuide(listOf(service(10)), emptyList()),
                snapshotDone = true,
                snapshotError = error,
            ) is HomeUiState.Ready,
        )
    }

    @Test
    fun readyEmpty_whenSnapshotCompletesWithoutCatalogueOrError() {
        assertEquals(
            HomeUiState.Ready(emptyList(), emptyList()),
            homeUiStateFor(
                ProgramGuide(emptyList(), emptyList()),
                snapshotDone = true,
                snapshotError = null,
            ),
        )
    }
}
