package build.raft.app

internal expect fun slockPlatformSupportsKuiklyPullToRefreshItem(): Boolean

internal expect fun slockPlatformUsesNativeLucideIconRotation(): Boolean

/**
 * Whether the HTTP layer must opt into gzip by sending an explicit
 * `Accept-Encoding: gzip` request header.
 *
 * OHOS uses NetworkKMM's libcurl wrapper, which only enables gzip (and
 * decompresses the response) when the request carries that header; without it
 * the wrapper negotiates `identity` and gets uncompressed bytes. Android and
 * iOS use ktor's Android/Darwin engines (HttpURLConnection / NSURLSession),
 * which transparently negotiate and decompress gzip precisely because we do
 * NOT set the header — setting it there disables their auto-decompression and
 * would surface raw gzip bytes.
 */
internal expect fun slockPlatformNeedsExplicitGzipAcceptEncoding(): Boolean
