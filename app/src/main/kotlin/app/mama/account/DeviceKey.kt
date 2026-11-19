package app.mama.account

/**
 * The device's Ed25519 identity for MAMA. A MAMA personal account IS this key:
 * the backend binds the public key on registration (no password, no phone number,
 * so a Wi-Fi-only / no-SIM device works identically), and the same key later
 * drives the Trusted Exit device-operation proof.
 *
 * The private key is hardware-protected and never leaves the device; only the
 * public key and detached signatures are ever exposed. Production uses a
 * Keystore-protected implementation; see the project notes on why a software
 * Ed25519 key wrapped by a hardware-backed AndroidKeyStore AES key is used rather
 * than the unreliable native-Keystore Ed25519 generator. Tests inject an
 * ephemeral key, so this orchestration is verifiable without any device.
 */
interface DeviceKey {
    /** Standard base64 (padded, no newlines) of the 32 raw Ed25519 public-key bytes. */
    fun publicKeyB64(): String

    /** A detached 64-byte Ed25519 signature over [message]. */
    fun sign(message: ByteArray): ByteArray
}

/** The server-issued account identity and bearer for this device. */
data class AccountSession(
    val userId: String,
    val deviceId: String,
    val token: String,
)

/**
 * Persists the current bearer across restarts. A real implementation stores it in
 * app-private, at-rest-encrypted storage; it is per-device and never shared.
 * Cleared on an authenticated 401 so the next call re-authenticates.
 */
interface AccountSessionStore {
    fun load(): AccountSession?
    fun save(session: AccountSession)
    fun clear()
}
