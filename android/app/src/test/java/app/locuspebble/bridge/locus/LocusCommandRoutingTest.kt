package app.locuspebble.bridge.locus

import app.locuspebble.bridge.protocol.BridgeProtocol.Command
import app.locuspebble.bridge.protocol.BridgeProtocol.RecordingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocusCommandRoutingTest {
    @Test fun pauseAndResumeUseDifferentLocusActions() {
        assertEquals(
            LocusRecordingAction.PAUSE,
            LocusCommandRouting.actionFor(Command.PAUSE_RESUME, RecordingState.RECORDING),
        )
        assertEquals(
            LocusRecordingAction.START_OR_RESUME,
            LocusCommandRouting.actionFor(Command.PAUSE_RESUME, RecordingState.PAUSED),
        )
    }

    @Test fun waypointRequiresAnActivelyRecordingTrack() {
        assertTrue("Watch waypoints must not require the phone", LocusCommandRouting.WAYPOINT_AUTO_SAVE)
        assertEquals(
            LocusRecordingAction.ADD_WAYPOINT,
            LocusCommandRouting.actionFor(Command.ADD_WAYPOINT, RecordingState.RECORDING),
        )
        assertEquals(
            LocusRecordingAction.INVALID,
            LocusCommandRouting.actionFor(Command.ADD_WAYPOINT, RecordingState.PAUSED),
        )
        assertEquals(
            LocusRecordingAction.ADD_WAYPOINT,
            LocusCommandRouting.actionFor(Command.ADD_WAYPOINT_WITH_NOTE, RecordingState.RECORDING),
        )
        assertEquals(
            LocusRecordingAction.INVALID,
            LocusCommandRouting.actionFor(Command.ADD_WAYPOINT_WITH_NOTE, RecordingState.PAUSED),
        )
    }

    @Test fun waypointNamesPreservePlainAndDictatedBehavior() {
        assertEquals(
            "Pebble waypoint",
            LocusCommandRouting.waypointNameFor(Command.ADD_WAYPOINT, null),
        )
        assertEquals(
            "Felsüberhang – später prüfen!",
            LocusCommandRouting.waypointNameFor(
                Command.ADD_WAYPOINT_WITH_NOTE,
                "Felsüberhang – später prüfen!",
            ),
        )
        assertNull(LocusCommandRouting.waypointNameFor(Command.ADD_WAYPOINT_WITH_NOTE, null))
        assertNull(LocusCommandRouting.waypointNameFor(Command.ADD_WAYPOINT_WITH_NOTE, "   "))
    }
}
