package io.github.jh_mmm.biliaccelerator.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigRefreshStateTest {
    @Test
    fun changesDuringReadDiscardStaleSettingsAndScheduleOneNewRead() {
        val state = ConfigRefreshState()
        val old = requireNotNull(state.begin())
        val applied = mutableListOf<String>()

        repeat(2) { state.invalidate() }
        assertNull(state.begin())
        state.publish(old, 30) { applied.add("old") }
        assertFalse(state.isFresh(1))
        assertTrue(state.finish(old))

        val fresh = requireNotNull(state.begin())
        state.publish(fresh, 31) { applied.add("fresh") }
        assertFalse(state.finish(fresh))
        assertEquals(listOf("fresh"), applied)
        assertTrue(state.isFresh(30))
        assertFalse(state.isFresh(31))
    }

    @Test
    fun notificationClearsRetryDelayAfterAnUnavailableProvider() {
        val state = ConfigRefreshState()
        val read = requireNotNull(state.begin())
        state.publish(read, 5) {}
        assertFalse(state.finish(read))
        assertTrue(state.isFresh(4))

        state.invalidate()
        assertFalse(state.isFresh(4))
        assertTrue(requireNotNull(state.begin()) > read)
    }
}
