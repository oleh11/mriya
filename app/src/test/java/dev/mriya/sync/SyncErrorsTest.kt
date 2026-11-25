package dev.mriya.sync

import dev.mriya.data.DawarichHttpException
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class SyncErrorsTest {
    @Test fun networkProblemsRetry() {
        assertTrue(SyncErrors.classify(IOException("reset")) is SyncOutcome.Retry)
        assertTrue(SyncErrors.classify(SocketTimeoutException("timeout")) is SyncOutcome.Retry)
        assertTrue(SyncErrors.classify(UnknownHostException("host")) is SyncOutcome.Retry)
    }

    @Test fun serverErrorsRetry() {
        for (c in listOf(500, 502, 503, 504, 429, 408)) assertTrue("HTTP $c", SyncErrors.classify(DawarichHttpException(c)) is SyncOutcome.Retry)
    }

    @Test fun authErrorsFail() {
        for (c in listOf(401, 403)) {
            val o = SyncErrors.classify(DawarichHttpException(c))
            assertTrue("HTTP $c", o is SyncOutcome.Fail)
            assertTrue((o as SyncOutcome.Fail).message.contains("API key"))
        }
    }

    @Test fun otherClientErrorsFailWithFriendlyMessage() {
        val o = SyncErrors.classify(DawarichHttpException(404)) as SyncOutcome.Fail
        assertEquals("Dawarich returned HTTP 404 (check the server URL and API key)", o.message)
    }

    @Test fun badUrlAndBadBodyFail() {
        assertTrue(SyncErrors.classify(IllegalArgumentException("Expected URL scheme")) is SyncOutcome.Fail)
        assertTrue(SyncErrors.classify(SerializationException("bad json")) is SyncOutcome.Fail)
        assertTrue(SyncErrors.classify(RuntimeException("boom")) is SyncOutcome.Fail)
    }

    @Test fun retriesAreCapped() {
        val t = IOException("down")
        assertTrue(SyncErrors.decide(t, 0) is SyncOutcome.Retry)
        assertTrue(SyncErrors.decide(t, SyncErrors.MAX_ATTEMPTS - 2) is SyncOutcome.Retry)
        assertTrue(SyncErrors.decide(t, SyncErrors.MAX_ATTEMPTS - 1) is SyncOutcome.Fail)
        assertTrue(SyncErrors.decide(DawarichHttpException(401), 0) is SyncOutcome.Fail)
    }
}
