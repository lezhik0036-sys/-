package app.mama.trustedexit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** A release without reviewed server connectivity MUST NEVER locally unlock. */
class ReleaseTrustedExitDisabledTest {
    @Test
    fun `release never creates simulated SMS challenges`() {
        val result = ReleaseTrustedExitDisabled.createChallenge(
            CreateChallengeRequest(
                sessionId = "synthetic-session",
                kind = "test",
                idempotencyKey = "synthetic-request",
            ),
        )
        assertEquals(
            "trusted-exit-not-configured",
            (result as CreateChallengeResult.Failed).reason,
        )
    }

    @Test
    fun `release never accepts typed code without server`() {
        assertSame(
            VerifyResult.Unknown,
            ReleaseTrustedExitDisabled.verify("tec-synthetic", "12345"),
        )
    }
}
