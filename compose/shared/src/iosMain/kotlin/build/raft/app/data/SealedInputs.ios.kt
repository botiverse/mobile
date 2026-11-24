package build.raft.app.data

import kotlin.concurrent.Volatile

/**
 * Implemented by the iOS app (Swift, `RaftIosSealedInputsHost`) using Apple `CryptoKit`:
 * P-256 ECDH-ES + RFC 7518 §4.6.2 Concat KDF (SHA-256) + AES-256-GCM compact JWE sealing.
 *
 * Returns null on any invalid public key or cryptographic failure (fail closed; never include
 * plaintext values or secrets in logs or errors).
 */
interface RaftIosSealedInputsSealer {
    fun sealEcdhEsA256Gcm(
        publicXBase64Url: String,
        publicYBase64Url: String,
        protectedHeaderBaseJson: String,
        plaintextJson: String,
    ): String?
}

/**
 * Process-wide registration for the iOS CryptoKit JWE sealer, installed at app startup
 * (`iOSApp.init`) before any card message form can be submitted.
 */
object RaftIosSealedInputsHost {
    @Volatile
    private var sealer: RaftIosSealedInputsSealer? = null

    fun install(sealer: RaftIosSealedInputsSealer) {
        this.sealer = sealer
    }

    internal fun current(): RaftIosSealedInputsSealer? = sealer
}

internal actual fun platformSealInputsJwe(
    key: RaftAppCardEncryptionKey,
    protectedHeaderBaseJson: String,
    plaintextJson: String,
): String {
    val sealer = RaftIosSealedInputsHost.current()
        ?: error("sealInputs: iOS CryptoKit sealer is not installed")
    return sealer.sealEcdhEsA256Gcm(
        publicXBase64Url = key.x,
        publicYBase64Url = key.y,
        protectedHeaderBaseJson = protectedHeaderBaseJson,
        plaintextJson = plaintextJson,
    ) ?: error("sealInputs: iOS CryptoKit sealing failed")
}
