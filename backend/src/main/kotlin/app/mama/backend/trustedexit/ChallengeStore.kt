package app.mama.backend.trustedexit

/**
 * Where the server keeps active challenges. One challenge per idempotency key
 * at a time. A production deployment backs this with a database so a server
 * restart does not drop a pending challenge; because the state lives on the
 * server, it already survives the phone closing the app or rebooting. The
 * in-memory implementation is enough for the mock-SMS phase and for tests.
 */
interface ChallengeStore {
    fun byId(challengeId: String): ServerChallenge?
    fun byKey(idempotencyKey: String): ServerChallenge?
    fun put(challenge: ServerChallenge)
    fun remove(challengeId: String)
}

/** Thread-safe in-memory store. */
class InMemoryChallengeStore : ChallengeStore {
    private val byId = HashMap<String, ServerChallenge>()

    @Synchronized override fun byId(challengeId: String) = byId[challengeId]

    @Synchronized override fun byKey(idempotencyKey: String) =
        byId.values.firstOrNull { it.idempotencyKey == idempotencyKey }

    @Synchronized override fun put(challenge: ServerChallenge) {
        byId[challenge.challengeId] = challenge
    }

    @Synchronized override fun remove(challengeId: String) {
        byId.remove(challengeId)
    }
}
