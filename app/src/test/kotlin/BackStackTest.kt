package net.rokoucha.visiomata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackStackTest {
    @Test
    fun popLastIfNotRoot_neverRemovesRoot() {
        val backStack = mutableListOf("home", "player")

        assertTrue(backStack.popLastIfNotRoot())
        assertFalse(backStack.popLastIfNotRoot())

        assertEquals(listOf("home"), backStack)
    }

    @Test
    fun popIfCurrent_ignoresRepeatedCallbackFromRemovedRoute() {
        val backStack = mutableListOf("home", "guide", "player")

        assertTrue(backStack.popIfCurrent("player"))
        assertFalse(backStack.popIfCurrent("player"))

        assertEquals(listOf("home", "guide"), backStack)
    }
}
