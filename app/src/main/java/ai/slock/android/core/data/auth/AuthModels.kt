package ai.slock.android.core.data.auth

import ai.slock.android.core.model.MemberId
import ai.slock.android.core.model.MemberKind
import ai.slock.android.core.model.MemberProfile
import ai.slock.android.core.model.Presence
import ai.slock.android.core.model.ServerId
import ai.slock.android.core.model.ServerSummary
import ai.slock.android.core.model.toGravatarAvatarReference
import com.google.gson.annotations.SerializedName

data class AuthUser(
    val id: String,
    val email: String,
    val name: String?,
    val displayName: String?,
    val description: String?,
    @SerializedName(
        value = "avatarUrl",
        alternate = [
            "avatar",
            "avatar_url",
            "imageUrl",
            "profileImageUrl",
            "profileAvatar"
        ]
    )
    val avatarUrl: String?,
    @SerializedName(
        value = "gravatarHash",
        alternate = [
            "gravatar",
            "gravatar_hash",
            "gravatarMd5",
            "gravatar_md5"
        ]
    )
    val gravatarHash: String? = null,
    val emailVerified: Boolean?
) {
    val bestDisplayName: String
        get() = displayName?.takeIf { it.isNotBlank() }
            ?: name?.takeIf { it.isNotBlank() }
            ?: email.substringBefore("@").ifBlank { "Slock user" }

    fun toMemberProfile(): MemberProfile =
        MemberProfile(
            id = MemberId(id),
            displayName = bestDisplayName,
            handle = email,
            kind = MemberKind.Human,
            roleDescription = description,
            avatarUrl = avatarUrl,
            gravatarHash = gravatarHash,
            avatar = avatarUrl?.let { ai.slock.android.core.model.AvatarReference.fromWireValue(it) }
                ?: gravatarHash?.toGravatarAvatarReference(),
            avatarLabel = bestDisplayName.take(1).uppercase(),
            presence = Presence.Active
        )
}

data class AuthServer(
    val id: String,
    val name: String,
    val slug: String?,
    val ownerId: String?,
    val onboardingAgentId: String?,
    val plan: String?,
    val planDowngradedAt: String?,
    val role: String?,
    val createdAt: String?
) {
    fun toServerSummary(): ServerSummary =
        ServerSummary(
            id = ServerId(id),
            name = name,
            slug = slug,
            role = role,
            plan = plan,
            onboardingAgentId = onboardingAgentId,
            description = slug?.let { "/$it" },
            permissions = ai.slock.android.core.model.ServerPermissions(
                canManageSettings = role == "owner" || role == "admin"
            )
        )
}

data class AuthSession(
    val user: AuthUser,
    val accessToken: String,
    val refreshToken: String,
    val selectedServerId: String? = null,
    val selectedServerSlug: String? = null,
    val servers: List<AuthServer> = emptyList()
) {
    val currentServerId: String?
        get() = selectedServerId ?: servers.firstOrNull()?.id
}

data class AuthSessionSummary(
    val user: AuthUser,
    val selectedServerId: String?,
    val selectedServerSlug: String?,
    val servers: List<AuthServer>
)

sealed interface AuthResult<out T> {
    data class Success<T>(val data: T) : AuthResult<T>
    data object Unauthenticated : AuthResult<Nothing>
    data class Failure(val code: AuthErrorCode, val message: String) : AuthResult<Nothing>
}

enum class AuthErrorCode {
    Validation,
    InvalidCredentials,
    Network,
    Server,
    SessionExpired,
    Unknown
}

data class LoginRequest(
    val email: String,
    val password: String
)

data class LoginResponse(
    val user: AuthUser,
    val accessToken: String,
    val refreshToken: String
)

data class RefreshRequest(
    val refreshToken: String
)

data class RefreshResponse(
    val accessToken: String,
    val refreshToken: String
)

data class LogoutRequest(
    val refreshToken: String
)

data class LogoutResponse(
    val ok: Boolean
)

data class ApiErrorResponse(
    @SerializedName("error")
    val error: String?
)
