# R8 rules for the release / play builds (isMinifyEnabled + isShrinkResources).
#
# Most dependencies ship their own consumer rules and need nothing here:
#   - kotlinx.serialization (generated $serializer / Companion.serializer()),
#     kotlinx.coroutines (ServiceLoader for Dispatchers.Main), OkHttp, Coil 3,
#     Compose / CMP resources, Media3, AndroidX, Firebase / FCM.
#   - MMKV: keeps its native methods and the static callbacks called from JNI.
#   - Hands SDK: JNI entry point is a Java_* symbol for a native method, which the
#     default proguard-android-optimize.txt keeps (-keepclasseswithmembernames native).
# Manifest components (activities, activity-aliases, services, receivers, the
# AppWidget providers, FileProvider) are kept by the AAPT-generated rules.
#
# The app code has no Class.forName / getDeclared* / ServiceLoader / @JavascriptInterface
# usage. The two reflective paths that existed (R.font.getField font lookup, Gson decode of
# StoredAuthSession) were replaced with static code rather than kept. Re-check when adding
# reflection: prefer static code over keep rules.

# Preserve source and line number information for Hands crash stack traces.
# Upload build/outputs/mapping/<variant>/mapping.txt with the release to deobfuscate.
-keepattributes SourceFile,LineNumberTable

# Kuikly NetworkKMM curl runtime (libnetworkkmmcurl.so, network-android-curl-runtime).
# The native code resolves callback methods by JVM name + signature (GetMethodID) and
# registers the bridge natives by name. The consumer rules bundled in
# com.tencent.kuiklybase:network:0.1.0-raft.47 are stale: they pin an old 14-arg
# onComplete and omit onTransferFacts / onCompletionFacts / onMultiFacts /
# bufferedBodyIdleTimeoutMillis / ..., so the first HTTP request aborted the process with
# NoSuchMethodError from JNI. Keep the whole JNI surface instead of exact signatures.
-keep class com.tencent.kmm.network.internal.platform.AndroidCurlJniCallback { *; }
-keep class com.tencent.kmm.network.internal.platform.AndroidCurlSocketIoCallback { *; }
-keep class com.tencent.kmm.network.internal.platform.AndroidCurlJniBridge { *; }

# Exception class names feed error classification (threadSendExceptionErrorCode
# matches "Timeout" / "Cancel" / "IOException" / "Network" in the simple name) and
# the errorType=... diagnostics fields. Keep them readable.
-keepnames class * extends java.lang.Throwable

# slf4j-api 1.7 (transitive via Ktor in NetworkKMM) probes for an optional binding class and falls back to
# the no-op logger when it is absent; no binding is shipped on Android.
-dontwarn org.slf4j.impl.StaticLoggerBinder

# Keep RMonitor SDK entry points intact (legacy; harmless if absent).
-keep class com.tencent.rmonitor.** { *; }
-keep class com.tencent.feedback.** { *; }
