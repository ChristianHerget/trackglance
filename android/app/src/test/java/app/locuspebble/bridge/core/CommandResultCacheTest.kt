package app.locuspebble.bridge.core

import app.locuspebble.bridge.protocol.BridgeProtocol
import org.junit.Assert.assertEquals
import org.junit.Test

class CommandResultCacheTest {
    @Test fun retransmissionWithinOneSessionExecutesOnlyOnce() {
        val cache = CommandResultCache()
        var executions = 0

        fun execute(session: Long, id: Long): BridgeProtocol.Result {
            cache.get(session, id)?.let { return it }
            executions++
            return BridgeProtocol.Result.OK.also { cache.put(session, id, it) }
        }

        assertEquals(BridgeProtocol.Result.OK, execute(session = 10, id = 1))
        assertEquals(BridgeProtocol.Result.OK, execute(session = 10, id = 1))
        assertEquals(1, executions)
    }

    @Test fun reopenedWatchSessionMayReuseEveryCommandId() {
        val cache = CommandResultCache()
        var executions = 0
        val sequence = listOf(
            BridgeProtocol.Command.START,
            BridgeProtocol.Command.PAUSE_RESUME,
            BridgeProtocol.Command.PAUSE_RESUME,
            BridgeProtocol.Command.ADD_WAYPOINT,
            BridgeProtocol.Command.ADD_WAYPOINT_WITH_NOTE,
            BridgeProtocol.Command.STOP_SAVE,
        )

        fun runSession(session: Long) {
            sequence.forEachIndexed { index, _ ->
                val id = (index + 1).toLong()
                if (cache.get(session, id) == null) {
                    executions++
                    cache.put(session, id, BridgeProtocol.Result.OK)
                }
            }
        }

        runSession(session = 100)
        runSession(session = 101)

        assertEquals(sequence.size * 2, executions)
    }
}
