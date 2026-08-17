package app.locuspebble.bridge

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class PeriodicDiagnosticsTest {
    @Test fun diagnosticsRefreshAgainAfterEveryForegroundInterval() = runBlocking {
        var refreshes = 0
        val waits = mutableListOf<Long>()

        try {
            runPeriodicDiagnostics(
                refresh = { refreshes++ },
                intervalMillis = 123L,
                wait = { duration ->
                    waits += duration
                    if (waits.size == 3) throw FinishedTest()
                },
            )
            fail("test cancellation must stop the refresh loop")
        } catch (_: FinishedTest) {
            // Expected test-only cancellation.
        }

        assertEquals(3, refreshes)
        assertEquals(listOf(123L, 123L, 123L), waits)
    }

    private class FinishedTest : CancellationException()
}
