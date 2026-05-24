package ai.slock.android.core.data.network

import ai.slock.compose.data.DataResult
import ai.slock.android.core.state.EmptyReason
import ai.slock.android.core.state.PermissionBlocker
import ai.slock.android.core.state.ScreenError
import java.io.IOException
import kotlinx.coroutines.CancellationException

internal suspend fun <T> execute(
    emptyReason: EmptyReason,
    block: suspend () -> ApiResponse,
    emptyWhen: (T) -> Boolean = { false },
    source: String? = null,
    warningLogger: (String) -> Unit = {},
    mapper: (String) -> T
): DataResult<T> {
    var statusCode: Int? = null
    return try {
        val response = block()
        statusCode = response.code()
        if (!response.isSuccessful) return response.toDataError()
        val body = response.body()?.string().orEmpty()
        if (body.isBlank()) return DataResult.Empty(emptyReason)
        val result = mapper(body)
        if (emptyWhen(result)) DataResult.Empty(emptyReason) else DataResult.Content(result)
    } catch (exception: CancellationException) {
        throw exception
    } catch (_: IOException) {
        DataResult.Error(ScreenError(code = "network_unavailable", retryable = true))
    } catch (exception: Exception) {
        if (source != null) {
            warningLogger(
                "parse_failed source=$source stage=response_or_mapper status=${statusCode ?: "unknown"} exception=${exception::class.java.simpleName}"
            )
        }
        DataResult.Error(ScreenError(code = "network_parse_failed", retryable = true))
    }
}

internal fun <T> ApiResponse.toDataError(): DataResult<T> =
    when (code()) {
        401 -> authRequired()
        403 -> DataResult.NoPermission(PermissionBlocker(code = "server_forbidden", requiredCapability = "server_access"))
        in 500..599 -> DataResult.Error(ScreenError(code = "server_unavailable", retryable = true))
        else -> DataResult.Error(ScreenError(code = "http_${code()}", retryable = code() != 404))
    }

internal fun <T> authRequired(): DataResult<T> =
    DataResult.NoPermission(PermissionBlocker(code = "auth_required", requiredCapability = "auth_session"))
