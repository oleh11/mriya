package dev.mriya.sync

import dev.mriya.data.DawarichHttpException
import kotlinx.serialization.SerializationException
import java.io.IOException

/** What the background worker should do after a failed sync attempt. Pure logic, unit-tested. */
sealed interface SyncOutcome {
    /** Transient problem: let WorkManager back off and try again (progress is checkpointed, so it resumes). */
    data class Retry(val reason: String) : SyncOutcome

    /** Permanent problem: stop, and show [message] to the user. */
    data class Fail(val message: String) : SyncOutcome
}

object SyncErrors {
    /** After this many attempts a "transient" error is reported instead of retried forever. */
    const val MAX_ATTEMPTS = 6

    fun classify(t: Throwable): SyncOutcome = when (t) {
        is DawarichHttpException -> when (t.code) {
            401, 403 -> SyncOutcome.Fail("Dawarich rejected the API key (HTTP ${t.code}). Check it in Settings.")
            408, 425, 429, in 500..599 -> SyncOutcome.Retry("HTTP ${t.code}")
            else -> SyncOutcome.Fail(t.message ?: "Dawarich returned HTTP ${t.code}")
        }
        // Timeouts (SocketTimeoutException), DNS failures, dropped connections, TLS resets, ...
        is IOException -> SyncOutcome.Retry(t.javaClass.simpleName)
        is SerializationException -> SyncOutcome.Fail("Unexpected answer from the server. Is this a Dawarich server?")
        // OkHttp's toHttpUrl() throws this for a malformed server URL.
        is IllegalArgumentException -> SyncOutcome.Fail("The server URL looks invalid. Check it in Settings.")
        else -> SyncOutcome.Fail(t.message ?: t.javaClass.simpleName)
    }

    /** [classify] plus the retry cap. [runAttemptCount] is WorkManager's count of earlier attempts (0 on the first). */
    fun decide(t: Throwable, runAttemptCount: Int): SyncOutcome {
        val o = classify(t)
        if (o is SyncOutcome.Retry && runAttemptCount + 1 >= MAX_ATTEMPTS) {
            val why = (t as? DawarichHttpException)?.message ?: "Could not reach Dawarich. Check your connection and the server."
            return SyncOutcome.Fail(why)
        }
        return o
    }
}
