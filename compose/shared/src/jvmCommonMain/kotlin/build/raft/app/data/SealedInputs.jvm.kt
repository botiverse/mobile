package build.raft.app.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/*
 * Android & Desktop JVM ECDH-ES + Concat KDF (RFC 7518 §4.6.2) + AES-256-GCM JWE sealer,
 * using standard `java.security` / `javax.crypto` only (zero external crypto dependencies).
 */

private const val P256_COORDINATE_BYTES = 32
private const val AES_256_GCM_IV_BYTES = 12
private const val AES_256_GCM_TAG_BYTES = 16
private const val AES_256_GCM_TAG_BITS = 128
private const val AES_256_GCM_KEY_BITS = 256
private const val CARD_ACTION_AUDIENCE_PREFIX = "urn:raft:card-action:"

private val P256_PRIME = BigInteger("FFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFF", 16)
private val P256_A = P256_PRIME.subtract(BigInteger.valueOf(3))
private val P256_B = BigInteger("5AC635D8AA3A93E7B3EBBD55769886BC651D06B0CC53B0F63BCE3C3E27D2604B", 16)
private val P256_ORDER = BigInteger("FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551", 16)

private val secureRandom = SecureRandom()
private val b64UrlEncoder = Base64.getUrlEncoder().withoutPadding()
private val b64UrlDecoder = Base64.getUrlDecoder()

private val p256Spec: ECParameterSpec by lazy {
    AlgorithmParameters.getInstance("EC")
        .apply { init(ECGenParameterSpec("secp256r1")) }
        .getParameterSpec(ECParameterSpec::class.java)
}

internal actual fun platformSealInputsJwe(
    key: RaftAppCardEncryptionKey,
    protectedHeaderBaseJson: String,
    plaintextJson: String,
): String {
    require(protectedHeaderBaseJson.startsWith("{") && protectedHeaderBaseJson.endsWith("}")) {
        "sealInputs: malformed protected header base"
    }
    val recipientPublicKey = importP256PublicKey(key.x, key.y)

    val keyPairGen = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"), secureRandom) }
    val ephemeralPair = keyPairGen.generateKeyPair()
    val ephemeralPublic = ephemeralPair.public as ECPublicKey
    val epkX = b64UrlEncoder.encodeToString(unsigned32Bytes(ephemeralPublic.w.affineX))
    val epkY = b64UrlEncoder.encodeToString(unsigned32Bytes(ephemeralPublic.w.affineY))

    val fullHeaderJson =
        protectedHeaderBaseJson.dropLast(1) +
            ""","epk":{"kty":"EC","crv":"P-256","x":"$epkX","y":"$epkY"}}"""
    val protectedB64 = b64UrlEncoder.encodeToString(fullHeaderJson.toByteArray(Charsets.UTF_8))

    val sharedSecret = deriveP256SharedSecret(ephemeralPair.private, recipientPublicKey)
    val cek = concatKdfA256Gcm(sharedSecret)

    val iv = ByteArray(AES_256_GCM_IV_BYTES).also(secureRandom::nextBytes)
    val aad = protectedB64.toByteArray(Charsets.US_ASCII)
    val plaintext = plaintextJson.toByteArray(Charsets.UTF_8)

    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(cek, "AES"), GCMParameterSpec(AES_256_GCM_TAG_BITS, iv))
    cipher.updateAAD(aad)
    val combined = cipher.doFinal(plaintext)
    val ciphertext = combined.copyOfRange(0, combined.size - AES_256_GCM_TAG_BYTES)
    val tag = combined.copyOfRange(combined.size - AES_256_GCM_TAG_BYTES, combined.size)

    return "$protectedB64..${b64UrlEncoder.encodeToString(iv)}.${b64UrlEncoder.encodeToString(ciphertext)}.${b64UrlEncoder.encodeToString(tag)}"
}

private fun importP256PublicKey(xB64: String, yB64: String): PublicKey {
    require(P256_COORDINATE_BASE64URL.matches(xB64) && P256_COORDINATE_BASE64URL.matches(yB64)) {
        "Invalid P-256 public key coordinate encoding"
    }
    val xBytes = b64UrlDecoder.decode(xB64)
    val yBytes = b64UrlDecoder.decode(yB64)
    require(xBytes.size == P256_COORDINATE_BYTES && yBytes.size == P256_COORDINATE_BYTES) {
        "Invalid P-256 public key coordinate length"
    }
    val x = BigInteger(1, xBytes)
    val y = BigInteger(1, yBytes)
    require(isPointOnP256Curve(x, y)) { "Point is not on P-256 curve" }
    return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), p256Spec))
}

private fun importP256PrivateKey(dB64: String): PrivateKey {
    require(P256_COORDINATE_BASE64URL.matches(dB64)) { "Invalid P-256 private key encoding" }
    val dBytes = b64UrlDecoder.decode(dB64)
    require(dBytes.size == P256_COORDINATE_BYTES) { "Invalid P-256 private key length" }
    val d = BigInteger(1, dBytes)
    require(d > BigInteger.ZERO && d < P256_ORDER) { "Invalid P-256 private key scalar" }
    return KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(d, p256Spec))
}

private fun isPointOnP256Curve(x: BigInteger, y: BigInteger): Boolean {
    if (x < BigInteger.ZERO || x >= P256_PRIME || y < BigInteger.ZERO || y >= P256_PRIME) return false
    val lhs = y.multiply(y).mod(P256_PRIME)
    val rhs = x.pow(3).add(P256_A.multiply(x)).add(P256_B).mod(P256_PRIME)
    return lhs == rhs
}

private fun deriveP256SharedSecret(privateKey: PrivateKey, publicKey: PublicKey): ByteArray {
    val agreement = KeyAgreement.getInstance("ECDH")
    agreement.init(privateKey)
    agreement.doPhase(publicKey, true)
    return normalizeUnsigned32Bytes(agreement.generateSecret())
}

/** RFC 7518 §4.6.2 Concat KDF with SHA-256 for `alg="ECDH-ES"`, `enc="A256GCM"` (256-bit CEK). */
private fun concatKdfA256Gcm(
    sharedSecret: ByteArray,
    apu: ByteArray = ByteArray(0),
    apv: ByteArray = ByteArray(0),
): ByteArray {
    val algorithmId = SEALED_INPUTS_ENC.toByteArray(Charsets.US_ASCII)
    val out = ByteArrayOutputStream(4 + sharedSecret.size + 4 + algorithmId.size + 4 + apu.size + 4 + apv.size + 4)
    out.writeUint32Be(1) // Round 1 counter
    out.write(sharedSecret)
    out.writeLengthPrefixed(algorithmId)
    out.writeLengthPrefixed(apu)
    out.writeLengthPrefixed(apv)
    out.writeUint32Be(AES_256_GCM_KEY_BITS)
    return MessageDigest.getInstance("SHA-256").digest(out.toByteArray())
}

private fun ByteArrayOutputStream.writeUint32Be(value: Int) {
    write((value ushr 24) and 0xFF)
    write((value ushr 16) and 0xFF)
    write((value ushr 8) and 0xFF)
    write(value and 0xFF)
}

private fun ByteArrayOutputStream.writeLengthPrefixed(bytes: ByteArray) {
    writeUint32Be(bytes.size)
    write(bytes)
}

private fun unsigned32Bytes(value: BigInteger): ByteArray = normalizeUnsigned32Bytes(value.toByteArray())

private fun normalizeUnsigned32Bytes(raw: ByteArray): ByteArray {
    val stripped =
        if (raw.size == P256_COORDINATE_BYTES + 1 && raw[0] == 0.toByte()) {
            raw.copyOfRange(1, raw.size)
        } else {
            raw
        }
    require(stripped.size <= P256_COORDINATE_BYTES) { "P-256 coordinate exceeds 32 bytes" }
    if (stripped.size == P256_COORDINATE_BYTES) return stripped
    val padded = ByteArray(P256_COORDINATE_BYTES)
    stripped.copyInto(padded, destinationOffset = P256_COORDINATE_BYTES - stripped.size)
    return padded
}

// ---------------------------------------------------------------------------
// Test-only reference verifier (`openSealedInputs` parity with `sealedInputs.ts`)
// so `SealedInputsVectorTest` runs every positive & refusal vector in `sealedInputs.v1.vector.json`.

internal data class SealedInputsPrivateJwkJvm(
    val kid: String,
    val x: String,
    val y: String,
    val d: String,
)

internal data class SealedInputsDeliveryJvm(
    val hnd: String,
    val crq: String,
    val exp: Long,
)

internal sealed interface OpenSealedInputsResultJvm {
    data class Ok(val values: Map<String, String>) : OpenSealedInputsResultJvm
    data class Refused(val reason: String) : OpenSealedInputsResultJvm
}

internal class SealedInputsMemoryJvm(
    private val nowSeconds: () -> Long,
) {
    private val accepted = HashMap<Pair<String, String>, Long>()

    fun remember(delivery: SealedInputsDeliveryJvm): Boolean {
        val now = nowSeconds()
        accepted.entries.removeAll { it.value <= now }
        val key = delivery.hnd to delivery.crq
        if (key in accepted) return false
        accepted[key] = delivery.exp
        return true
    }
}

private data class ExecuteClaimsJvm(
    val aud: String,
    val srv: String,
    val sub: String,
    val hnd: String,
    val act: String,
    val pid: String?,
    val pdg: String?,
    val crq: String,
    val exp: Long,
)

private val HEX_64 = Regex("^[0-9a-f]{64}$")

private fun parseExecuteClaimsJvm(claims: JsonObject): ExecuteClaimsJvm? {
    val aud = claims["aud"].cardString() ?: return null
    if (!aud.startsWith(CARD_ACTION_AUDIENCE_PREFIX) || aud.length == CARD_ACTION_AUDIENCE_PREFIX.length) return null
    val sub = claims["sub"].cardString()?.takeIf { it.isNotEmpty() } ?: return null
    val srv = claims["server_id"].cardString()?.takeIf { it.isNotEmpty() } ?: return null
    val hnd = claims["handle_sha256"].cardString()?.takeIf(HEX_64::matches) ?: return null
    val act = claims["action_id"].cardString()?.takeIf { it.isNotEmpty() } ?: return null
    val crq = claims["client_request_id"].cardString()?.takeIf { it.isNotEmpty() } ?: return null
    val exp = claims["handle_exp"].cardSafeInteger() ?: return null
    val hasPid = "prepared_action_id" in claims
    val hasPdg = "prepared_digest" in claims
    if (hasPid != hasPdg) return null
    val pid = if (hasPid) claims["prepared_action_id"].cardString()?.takeIf { it.isNotEmpty() } ?: return null else null
    val pdg = if (hasPdg) claims["prepared_digest"].cardString()?.takeIf { it.isNotEmpty() } ?: return null else null
    return ExecuteClaimsJvm(
        aud = aud.removePrefix(CARD_ACTION_AUDIENCE_PREFIX),
        srv = srv,
        sub = sub,
        hnd = hnd,
        act = act,
        pid = pid,
        pdg = pdg,
        crq = crq,
        exp = exp,
    )
}

internal fun openSealedInputsJvm(
    jwe: String,
    keys: List<SealedInputsPrivateJwkJvm>,
    claims: JsonObject,
    nowSeconds: Long,
    remember: (SealedInputsDeliveryJvm) -> Boolean,
): OpenSealedInputsResultJvm {
    fun refuse(reason: String) = OpenSealedInputsResultJvm.Refused(reason)
    val parsedClaims = parseExecuteClaimsJvm(claims) ?: return refuse("claims_invalid")
    if (jwe.length > MESSAGE_CARD_SEALED_INPUTS_MAX_BYTES || !MESSAGE_CARD_SEALED_INPUTS_PATTERN.matches(jwe)) {
        return refuse("malformed")
    }
    val parts = jwe.split('.')
    if (parts.size != 5) return refuse("malformed")
    val header =
        runCatching {
            val headerBytes = decodeStrictBase64Url(parts[0])
            val headerText = decodeStrictUtf8(headerBytes)
            Json.parseToJsonElement(headerText) as? JsonObject
        }.getOrNull() ?: return refuse("malformed")
    if ("zip" in header) return refuse("compression_not_allowed")
    if (header["alg"].cardString() != SEALED_INPUTS_ALG || header["enc"].cardString() != SEALED_INPUTS_ENC) {
        return refuse("unsupported_algorithm")
    }
    val kid = header["kid"].cardString() ?: return refuse("unknown_kid")
    val privateJwk = keys.firstOrNull { it.kid == kid } ?: return refuse("unknown_kid")

    if (header["aud"].cardString() != parsedClaims.aud) return refuse("aud_mismatch")
    if (header["srv"].cardString() != parsedClaims.srv) return refuse("srv_mismatch")
    if (header["sub"].cardString() != parsedClaims.sub) return refuse("sub_mismatch")
    if (header["hnd"].cardString() != parsedClaims.hnd) return refuse("hnd_mismatch")
    if (header["act"].cardString() != parsedClaims.act) return refuse("act_mismatch")
    if (!matchesOptionalHeaderString(header, "pid", parsedClaims.pid)) return refuse("pid_mismatch")
    if (!matchesOptionalHeaderString(header, "pdg", parsedClaims.pdg)) return refuse("pdg_mismatch")
    if (header["crq"].cardString() != parsedClaims.crq) return refuse("crq_mismatch")
    if (header["exp"].cardSafeInteger() != parsedClaims.exp) return refuse("exp_mismatch")

    if (nowSeconds >= parsedClaims.exp) return refuse("expired")

    val plaintextBytes =
        runCatching {
            require(parts[1].isEmpty()) { "encrypted key must be empty under ECDH-ES" }
            val epk = header["epk"] as? JsonObject ?: error("missing epk")
            require(epk["kty"].cardString() == SEALED_INPUTS_KTY && epk["crv"].cardString() == SEALED_INPUTS_CRV) { "bad epk" }
            val epkX = epk["x"].cardString() ?: error("missing epk.x")
            val epkY = epk["y"].cardString() ?: error("missing epk.y")
            val epkPublicKey = importP256PublicKey(epkX, epkY)
            val appPrivateKey = importP256PrivateKey(privateJwk.d)

            val apu = header["apu"].cardString()?.let(::decodeStrictBase64Url) ?: ByteArray(0)
            val apv = header["apv"].cardString()?.let(::decodeStrictBase64Url) ?: ByteArray(0)
            val sharedSecret = deriveP256SharedSecret(appPrivateKey, epkPublicKey)
            val cek = concatKdfA256Gcm(sharedSecret, apu = apu, apv = apv)

            val iv = decodeStrictBase64Url(parts[2])
            val ciphertext = decodeStrictBase64Url(parts[3])
            val tag = decodeStrictBase64Url(parts[4])
            require(iv.size == AES_256_GCM_IV_BYTES && tag.size == AES_256_GCM_TAG_BYTES) { "invalid iv or tag length" }

            val combined = ByteArray(ciphertext.size + tag.size)
            ciphertext.copyInto(combined, 0)
            tag.copyInto(combined, ciphertext.size)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(cek, "AES"), GCMParameterSpec(AES_256_GCM_TAG_BITS, iv))
            cipher.updateAAD(parts[0].toByteArray(Charsets.US_ASCII))
            cipher.doFinal(combined)
        }.getOrNull() ?: return refuse("decryption_failed")

    val parsedPlaintext =
        runCatching {
            val text = decodeStrictUtf8(plaintextBytes)
            Json.parseToJsonElement(text) as? JsonObject
        }.getOrNull() ?: return refuse("plaintext_invalid")

    val values = LinkedHashMap<String, String>(parsedPlaintext.size)
    for ((k, v) in parsedPlaintext) {
        val str = v.cardString() ?: return refuse("plaintext_invalid")
        values[k] = str
    }
    if (values.values.any { messageCardUtf8ByteLength(it) > MESSAGE_CARD_FORM_SECRET_INPUT_MAX_BYTES }) {
        return refuse("value_too_long")
    }
    if (!remember(SealedInputsDeliveryJvm(parsedClaims.hnd, parsedClaims.crq, parsedClaims.exp))) {
        return refuse("replayed")
    }
    return OpenSealedInputsResultJvm.Ok(values)
}

private fun matchesOptionalHeaderString(header: JsonObject, key: String, expected: String?): Boolean {
    if (expected == null) return key !in header
    return header[key].cardString() == expected
}

private fun decodeStrictBase64Url(segment: String): ByteArray {
    val decoded = b64UrlDecoder.decode(segment)
    require(b64UrlEncoder.encodeToString(decoded) == segment) { "non-canonical base64url" }
    return decoded
}

private fun decodeStrictUtf8(bytes: ByteArray): String =
    Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
