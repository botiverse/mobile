package ai.slock.android.core.data.network

import ai.slock.compose.network.KmpHttpResponse
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaType

data class ApiResponse(
    val code: Int,
    val body: ResponseBody?,
    val errorBody: ResponseBody?
) {
    val isSuccessful: Boolean
        get() = code in 200..299

    fun code(): Int = code

    fun body(): ResponseBody? = body

    fun errorBody(): ResponseBody? = errorBody

    companion object {
        fun success(body: ResponseBody?, code: Int = 200): ApiResponse =
            ApiResponse(code = code, body = body, errorBody = null)

        fun error(code: Int, errorBody: ResponseBody?): ApiResponse =
            ApiResponse(code = code, body = null, errorBody = errorBody)

        fun from(response: KmpHttpResponse): ApiResponse =
            if (response.isSuccessful) {
                success(response.bodyText.orEmpty().toResponseBody(jsonUtf8MediaType), response.code)
            } else {
                error(response.code, response.errorBodyText.orEmpty().toResponseBody(jsonUtf8MediaType))
            }
    }
}

private val jsonUtf8MediaType = "application/json; charset=utf-8".toMediaType()
