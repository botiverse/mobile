package ai.slock.kmp.network

import ai.slock.shared.model.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*

/**
 * Slock API client using Ktor for cross-platform networking
 */
class SlockApiClient(
    private val baseUrl: String,
    private val httpClient: HttpClient = createHttpClient()
) {
    /**
     * Get list of channels
     */
    suspend fun getChannels(archived: String? = null): Result<List<ConversationSummary>> {
        return try {
            val response = httpClient.get("$baseUrl/channels") {
                archived?.let { parameter("archived", it) }
            }
            Result.success(response.body())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Get server members
     */
    suspend fun getServerMembers(serverId: String): Result<List<MemberProfile>> {
        return try {
            val response = httpClient.get("$baseUrl/servers/$serverId/members")
            Result.success(response.body())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Get member profile
     */
    suspend fun getMemberProfile(memberId: String): Result<MemberProfile> {
        return try {
            val response = httpClient.get("$baseUrl/members/$memberId/profile")
            Result.success(response.body())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Close the HTTP client
     */
    fun close() {
        httpClient.close()
    }
}
