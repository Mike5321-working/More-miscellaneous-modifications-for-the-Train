package com.habitrain.lottery.client.record;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies {@link MatchReplayTableBridge} state math and graceful degradation when compiled/tested
 * against the pinned {@code libs/habitrain_core-2.0.37.jar} baseline without nearby-table methods.
 */
class MatchReplayTableBridgeTest {

    @Test
    void nearbyTableStateComputesClampedProgressAndFollowPresence() {
        var playing = new MatchReplayTableBridge.NearbyTableState(
                "134142b4-3b6f-4aa1-b4bd-db01e433403c", "Express", 300.0, 1200,
                2.0f, false, false, false, "00000000-0000-0000-0000-000000000001", "Alice",
                false, true, 6.5);
        assertEquals(0.25, playing.progress(), 1e-6);
        assertTrue(playing.hasFollowPlayer());

        var clamped = new MatchReplayTableBridge.NearbyTableState(
                "id", "Map", 9999.0, 100, 1.0f, true, false, true, "", "", false, true, 4.0);
        assertEquals(1.0, clamped.progress(), 1e-6);
        assertFalse(clamped.hasFollowPlayer());

        var zeroDur = new MatchReplayTableBridge.NearbyTableState(
                "id", "Map", 10.0, 0, 1.0f, false, true, false, null, "", false, true, 2.0);
        assertEquals(0.0, zeroDur.progress(), 1e-6);
        assertFalse(zeroDur.hasFollowPlayer());
    }

    @Test
    void bridgeDegradesGracefullyAgainstOlderCoreJar() {
        // Against pinned libs/habitrain_core-2.0.37.jar, extended nearby-table methods are absent;
        // every call must return null/false safely without throwing NoSuchMethodError or LinkageError.
        assertFalse(MatchReplayTableBridge.supportsNearbyTableSync());
        assertNull(MatchReplayTableBridge.pollNearbyTable());
        assertFalse(MatchReplayTableBridge.control("TOGGLE_PAUSE", 0.0, null, false));
        assertNull(MatchReplayTableBridge.openNearbyTableExport(null));
        assertNull(MatchReplayTableBridge.openNearbyTablePreview(null));
    }
}
