package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

import build.raft.app.raftSha256Hex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * Device-side sealing of App-sent card SecretInput values (RFC 086 §5.2, §5.3).
 *
 * Contract: botiverse/slock `packages/shared/src/sealedInputs.ts`,
 * `packages/shared/src/sealedInputs.v1.vector.json`, and
 * `packages/web/src/components/message/linkCardSeal.ts`.
 *
 * Compact JWE: `alg: "ECDH-ES"`, `enc: "A256GCM"`, P-256 ECDH + RFC 7518 §4.6.2 Concat KDF +
 * AES-256-GCM, no `zip`. Protected header keys in exact `BOUND` order:
 * `alg, enc, kid, aud, srv, sub, hnd, act, pid?, pdg?, crq, exp, epk`.
 *
 * Never log, store, persist, or embed secret values or sealed JWE strings in errors/diagnostics.
 */

@RaftCoreInternalApi const val SEALED_INPUTS_ALG = "ECDH-ES"
@RaftCoreInternalApi const val SEALED_INPUTS_ENC = "A256GCM"
@RaftCoreInternalApi const val SEALED_INPUTS_KTY = "EC"
@RaftCoreInternalApi const val SEALED_INPUTS_CRV = "P-256"
@RaftCoreInternalApi const val SEALED_INPUTS_USE = "enc"

/** A handle with less left than this (ms) is refused before sealing (`LINK_CARD_SEAL_MIN_LEFT_MS`). */
@RaftCoreInternalApi const val LINK_CARD_SEAL_MIN_LEFT_MS: Long = 5_000L

/** Canonical 32-byte P-256 coordinate in base64url without padding (`P256_COORDINATE_BASE64URL`). */
@RaftCoreInternalApi val P256_COORDINATE_BASE64URL = Regex("^[A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]$")

/** Card encryption key `kid` (`APP_CARD_ENCRYPTION_KID_PATTERN`). */
@RaftCoreInternalApi val APP_CARD_ENCRYPTION_KID_PATTERN = Regex("^[A-Za-z0-9._-]{1,64}$")

private val APP_CARD_ENCRYPTION_KEY_FIELDS = setOf("kty", "crv", "use", "alg", "kid", "x", "y")

/** The App's public P-256 ECDH-ES JWK served by Raft in `message_card.app.encryption_key`. */
data class RaftAppCardEncryptionKey(
    val kty: String = SEALED_INPUTS_KTY,
    val crv: String = SEALED_INPUTS_CRV,
    val use: String = SEALED_INPUTS_USE,
    val alg: String = SEALED_INPUTS_ALG,
    val kid: String,
    val x: String,
    val y: String,
)

/** Strictly reads `appCardEncryptionKeySchema` (rejects `d` or any extra key). */
@RaftCoreInternalApi fun readAppCardEncryptionKey(raw: JsonElement?): RaftAppCardEncryptionKey? {
    val obj = raw as? JsonObject ?: return null
    if (obj.keys != APP_CARD_ENCRYPTION_KEY_FIELDS) return null
    val kty = obj["kty"].cardString() ?: return null
    val crv = obj["crv"].cardString() ?: return null
    val use = obj["use"].cardString() ?: return null
    val alg = obj["alg"].cardString() ?: return null
    val kid = obj["kid"].cardString() ?: return null
    val x = obj["x"].cardString() ?: return null
    val y = obj["y"].cardString() ?: return null
    if (
        kty != SEALED_INPUTS_KTY ||
        crv != SEALED_INPUTS_CRV ||
        use != SEALED_INPUTS_USE ||
        alg != SEALED_INPUTS_ALG ||
        !APP_CARD_ENCRYPTION_KID_PATTERN.matches(kid) ||
        !P256_COORDINATE_BASE64URL.matches(x) ||
        !P256_COORDINATE_BASE64URL.matches(y)
    ) {
        return null
    }
    return RaftAppCardEncryptionKey(kty = kty, crv = crv, use = use, alg = alg, kid = kid, x = x, y = y)
}

/** What a SecretInput button's values are sealed to, from Raft's handle read (`LinkCardSealTo`). */
data class RaftLinkCardSealTo(
    val key: RaftAppCardEncryptionKey,
    val clientKey: String,
    val handleExp: Long,
    val revision: String,
) {
    /** Same App key, client key, and card revision (`sameTarget` in `linkCardSeal.ts`). */
    fun sameTarget(other: RaftLinkCardSealTo): Boolean =
        key.kid == other.key.kid &&
            key.x == other.key.x &&
            key.y == other.key.y &&
            clientKey == other.clientKey &&
            revision == other.revision

    /** True when fewer than [LINK_CARD_SEAL_MIN_LEFT_MS] remain before [handleExp]. */
    fun isExpiring(nowMillis: Long): Boolean = nowMillis >= handleExp * 1000L - LINK_CARD_SEAL_MIN_LEFT_MS
}

/** Client-side inputs used to build [RaftSealedInputsBinding] (`SealedInputsBindingInputs`). */
data class RaftSealedInputsBindingInputs(
    val handle: String,
    val serverId: String,
    val userId: String,
    val appClientKey: String,
    val actionId: String,
    val preparedId: String?,
    val preparedDigest: String?,
    val clientRequestId: String,
    val handleExp: Long,
)

/** Bound protected-header fields (`SealedInputsBinding`), matching execute JWT claims. */
data class RaftSealedInputsBinding(
    val aud: String,
    val srv: String,
    val sub: String,
    val hnd: String,
    val act: String,
    val pid: String? = null,
    val pdg: String? = null,
    val crq: String,
    val exp: Long,
)

/**
 * Builds the protected header binding from client inputs (`sealedInputsBinding`).
 * Throws [IllegalArgumentException] if any required member is blank, `preparedId`/`preparedDigest`
 * are mismatched, or `handleExp` is outside JS safe integer bounds.
 */
@RaftCoreInternalApi fun sealedInputsBinding(inputs: RaftSealedInputsBindingInputs): RaftSealedInputsBinding {
    val preparedBothNull = inputs.preparedId == null && inputs.preparedDigest == null
    val preparedBothValid = !inputs.preparedId.isNullOrEmpty() && !inputs.preparedDigest.isNullOrEmpty()
    require(
        inputs.handle.isNotEmpty() &&
            inputs.serverId.isNotEmpty() &&
            inputs.userId.isNotEmpty() &&
            inputs.appClientKey.isNotEmpty() &&
            inputs.actionId.isNotEmpty() &&
            inputs.clientRequestId.isNotEmpty() &&
            inputs.handleExp in -9_007_199_254_740_991L..9_007_199_254_740_991L &&
            (preparedBothNull || preparedBothValid),
    ) {
        "sealedInputsBinding: invalid binding inputs"
    }
    val hnd = raftSha256Hex(inputs.handle.encodeToByteArray())
    return RaftSealedInputsBinding(
        aud = inputs.appClientKey,
        srv = inputs.serverId,
        sub = inputs.userId,
        hnd = hnd,
        act = inputs.actionId,
        pid = inputs.preparedId,
        pdg = inputs.preparedDigest,
        crq = inputs.clientRequestId,
        exp = inputs.handleExp,
    )
}

/**
 * Protected header fields in exact `BOUND` order (`sealedInputsProtectedHeader`), before `epk` is
 * added by the platform ECDH-ES sealer.
 */
@RaftCoreInternalApi fun sealedInputsProtectedHeader(kid: String, binding: RaftSealedInputsBinding): JsonObject =
    buildJsonObject {
        put("alg", SEALED_INPUTS_ALG)
        put("enc", SEALED_INPUTS_ENC)
        put("kid", kid)
        put("aud", binding.aud)
        put("srv", binding.srv)
        put("sub", binding.sub)
        put("hnd", binding.hnd)
        put("act", binding.act)
        binding.pid?.let { put("pid", it) }
        binding.pdg?.let { put("pdg", it) }
        put("crq", binding.crq)
        put("exp", JsonPrimitive(binding.exp))
    }

/**
 * Seals [values] to [key] under [binding] (`sealInputs`): returns a compact JWE string
 * (`alg: "ECDH-ES"`, `enc: "A256GCM"`, P-256, fresh ephemeral key and 12-byte IV).
 *
 * Refuses invalid keys, oversize values (> 4096 UTF-8 bytes), or oversize JWE (> 8192 bytes)
 * without including secret values in any thrown message.
 */
@RaftCoreInternalApi fun sealInputs(
    key: RaftAppCardEncryptionKey,
    binding: RaftSealedInputsBinding,
    values: Map<String, String>,
): String {
    require(
        key.kty == SEALED_INPUTS_KTY &&
            key.crv == SEALED_INPUTS_CRV &&
            key.use == SEALED_INPUTS_USE &&
            key.alg == SEALED_INPUTS_ALG &&
            APP_CARD_ENCRYPTION_KID_PATTERN.matches(key.kid) &&
            P256_COORDINATE_BASE64URL.matches(key.x) &&
            P256_COORDINATE_BASE64URL.matches(key.y),
    ) {
        "sealInputs: key is not a valid P-256 ECDH-ES encryption key"
    }
    for (value in values.values) {
        require(messageCardUtf8ByteLength(value) <= MESSAGE_CARD_FORM_SECRET_INPUT_MAX_BYTES) {
            "sealInputs: value exceeds byte limit"
        }
    }
    val headerBaseJson = Json.encodeToString(JsonObject.serializer(), sealedInputsProtectedHeader(key.kid, binding))
    val plaintextJson =
        Json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                for ((fieldId, value) in values) {
                    put(fieldId, value)
                }
            },
        )
    val jwe = platformSealInputsJwe(key = key, protectedHeaderBaseJson = headerBaseJson, plaintextJson = plaintextJson)
    require(jwe.length <= MESSAGE_CARD_SEALED_INPUTS_MAX_BYTES && MESSAGE_CARD_SEALED_INPUTS_PATTERN.matches(jwe)) {
        "sealInputs: sealed JWE failed output validation"
    }
    return jwe
}

/**
 * Platform ECDH-ES + Concat KDF (RFC 7518 §4.6.2) + AES-256-GCM compact JWE sealer.
 * [protectedHeaderBaseJson] is a serialized JSON object ending with `}` containing all protected
 * header members in exact `BOUND` order without `epk`; the platform sealer appends `epk` and
 * computes the compact JWE.
 */
@RaftCoreInternalApi expect fun platformSealInputsJwe(
    key: RaftAppCardEncryptionKey,
    protectedHeaderBaseJson: String,
    plaintextJson: String,
): String
