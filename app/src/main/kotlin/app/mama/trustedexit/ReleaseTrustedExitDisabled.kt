package app.mama.trustedexit

/**
 * Production fail-closed placeholder until a fully authenticated HTTPS
 * Trusted Exit client is implemented, independently reviewed and configured.
 *
 * Under no circumstances may a release APK generate or validate OTP locally.
 * The fake provider is for a strictly isolated debug APK only.
 *
 * This is NOT the finished production implementation: a release containing
 * this adapter cannot perform early Trusted Exit through SMS.
 */
object ReleaseTrustedExitDisabled : TrustedExitBackend {
    override fun createChallenge(request: CreateChallengeRequest): CreateChallengeResult =
        CreateChallengeResult.Failed("trusted-exit-not-configured")

    override fun verify(challengeId: String, enteredCode: String): VerifyResult =
        VerifyResult.Unknown
}
