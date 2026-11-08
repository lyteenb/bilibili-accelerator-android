package io.github.jh_mmm.biliaccelerator.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayHealthRegistryTest {

    private val ws = "proxy-tf-all-ws.bilivideo.com"
    private val tx = "proxy-tf-all-tx.bilivideo.com"
    private val hw = "proxy-tf-all-hw.bilivideo.com"

    @Test
    fun testFallbackStaysStickyWithinSameMediaSession() {
        RelayHealthRegistry.resetForTesting()
        val now = 1_760_000_000_000L

        val first = RelayHealthRegistry.selectRelay(ws, "video-1", now)
        assertEquals(ws, first.relayHost)
        assertFalse(first.usedFallback)

        RelayHealthRegistry.markFailure(ws, 60_000L, now)
        val fallback = RelayHealthRegistry.selectRelay(ws, "video-1", now + 1_000L)
        assertTrue(fallback.usedFallback)
        assertEquals(tx, fallback.relayHost)

        RelayHealthRegistry.markSuccess(ws)
        val stillSticky = RelayHealthRegistry.selectRelay(ws, "video-1", now + 2_000L)
        assertEquals("Preferred relay recovery must not switch mid-session", tx, stillSticky.relayHost)

        val newSession = RelayHealthRegistry.selectRelay(ws, "video-2", now + 3_000L)
        assertEquals("New media session should retry preferred relay", ws, newSession.relayHost)
    }

    @Test
    fun testAllOfficialRelaysUnavailableIsReported() {
        RelayHealthRegistry.resetForTesting()
        val now = 1_760_000_000_000L
        RelayHealthRegistry.markFailure(ws, 60_000L, now)
        RelayHealthRegistry.markFailure(tx, 60_000L, now)
        RelayHealthRegistry.markFailure(hw, 60_000L, now)

        val selection = RelayHealthRegistry.selectRelay(ws, "video-unavailable", now + 1_000L)
        assertTrue(selection.allUnavailable)
        assertEquals(ws, selection.relayHost)
        assertTrue(RelayHealthRegistry.allRelaysUnavailable(ws, now + 1_000L))

        val recovered = RelayHealthRegistry.selectRelay(ws, "video-unavailable", now + 61_000L)
        assertFalse(recovered.allUnavailable)
        assertEquals("Same media session should keep using a fallback relay", tx, recovered.relayHost)
    }

    @Test
    fun testNonRelayPreferredIsNotOverridden() {
        RelayHealthRegistry.resetForTesting()
        val selection = RelayHealthRegistry.selectRelay("example.com", "video-1")
        assertEquals("example.com", selection.relayHost)
        assertFalse(selection.usedFallback)
        assertFalse(selection.allUnavailable)
    }
}
