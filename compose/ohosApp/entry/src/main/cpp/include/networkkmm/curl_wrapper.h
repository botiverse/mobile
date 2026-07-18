/*
 * Tencent is pleased to support the open source community by making KuiklyBase available.
 * Copyright (C) 2025 Tencent. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#ifndef NETWORKKMM_OHOSAPP_PBCURLWRAPPER_MAIN_CPP_WRAPPER_INCLUDE_CURL_WRAPPER_H_
#define NETWORKKMM_OHOSAPP_PBCURLWRAPPER_MAIN_CPP_WRAPPER_INCLUDE_CURL_WRAPPER_H_

#include <stdint.h>
#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

// Increment whenever a public by-value struct or exported callback contract
// changes incompatibly. Kotlin/JNI callers must verify this before passing a
// CurlRequest so a stale native runtime fails closed instead of interpreting
// a differently-sized struct.
#define CURL_WRAPPER_ABI_VERSION 27

int CurlWrapperAbiVersion(void);

/**
 * 日志 回调
 * @param level
 * @param tag
 * @param content
 * @return 是否成功
 */
typedef int (*curlLog)(int level, const char *tag, const char *content);

void setCurlLogImpl(curlLog logImpl);

// 跨端 kv 对象
typedef struct {
    const char *first;
    const char *second;
} StringPair;

// 跨端字典
typedef struct {
    StringPair *stringPairs;
    int size;
} StringDic;

// 耗时指标统计
typedef struct {
    double nameLookupTimeMs;
    double connectTimeMs;
    double sslCostTimeMs;
    double preTransferTime;
    double startTransferTimeMs;
    double redirectTime;
    double recvTime;
    double totalTimeMs;
} ElapseStats;

// Curl 请求信息
typedef struct {
    const char *url;
    const char *method;
    StringDic *headers;
    int64_t timeout;  // 单位 ms
    int64_t streamConnectTimeoutMs;
    int64_t streamResponseHeadersTimeoutMs;
    int64_t streamIdleTimeoutMs;
    int64_t streamWholeTimeoutMs;  // 0 = disabled
    int postBodyLen;
    const char *postBody;
} CurlRequest;

// Curl 响应信息
typedef struct {
    int code;          // CURLcode: 0 = transfer completed (NOT the HTTP status)
    long httpCode;     // HTTP response status (CURLINFO_RESPONSE_CODE), 0 if unavailable
    const char *errorMsg;
    int errorMsgLen;
    const char *headers;
    int headerLen;
    const char *redirectUrl;
    const char *data;
    int dataLen;
    ElapseStats elapse;
} CurlResponse;

// Curl 响应回调
// 所有权契约（对所有 *Callback / CurlUploadSource 一致）：结构体由调用方分配、
// 调用方释放，wrapper 只在请求期间借用——传栈上/自管内存都合法。wrapper
// 绝不 delete/free 它们（历史上 StartRequest 曾接管并 delete，栈上回调会炸）。
typedef struct {
    void *callbackRef;
    void (*callback)(void *callbackRef, CurlResponse *response);
} CurlCallback;

// Curl 流式响应回调 (fork #8): 响应头就绪时 onResponseStart 交付状态码+头; 响应体
// 每到一块调 onChunk (不缓冲整包); 结束时 onComplete 交付状态/错误 (CurlResponse
// 的 data 为 null, body 已通过 onChunk 交付)。
typedef struct {
    void *callbackRef;
    void (*onResponseStart)(void *callbackRef, long httpCode, const char *headers, int headerLen);
    void (*onChunk)(void *callbackRef, const char *data, int len);
    void (*onComplete)(void *callbackRef, CurlResponse *response);
} CurlStreamCallback;

// 流式上传拉取回调 (issue #8 slice 3): 把最多 maxLen 字节拷入 buffer 并返回
// 实际字节数; 0 = EOF (body 结束), 负数 = 中止传输。在 curl perform 线程上
// 同步调用, 可以阻塞等待数据。
typedef int (*curlReadChunk)(void *readRef, char *buffer, int maxLen);

// 流式上传请求体来源 (issue #8 slice 3)。totalLength >= 0 时发送真实
// Content-Length; -1 = 长度未知, 走 Transfer-Encoding: chunked。
typedef struct {
    void *readRef;
    curlReadChunk readChunk;
    int64_t totalLength;  // -1 = unknown
} CurlUploadSource;

// CurClient 对象指针
typedef void* CurClientHandle;

// 创建 CurClient 对象
CurClientHandle CreateCurlClient(const char *logTag);

// 销毁 CurClient 对象
void DeleteCurlClient(CurClientHandle handle);

// 取消 CurClient 请求
void Cancel(CurClientHandle handle);

// Override the CA bundle for this client. The path is copied. Production
// delegates validate and require an app-owned bundle before calling native.
void SetCurlCaInfo(CurClientHandle handle, const char *caInfoPath);

// Set a fixed proxy URL. The value is copied; an empty string explicitly
// disables environment/system proxy discovery for direct mode.
void SetCurlProxy(CurClientHandle handle, const char *proxyUrl);

// Add one libcurl CURLOPT_RESOLVE entry for this client. The entry is copied
// and follows libcurl's "host:port:address[,address]" format. This preserves
// the URL hostname for TLS SNI/verification while allowing a caller to supply
// an already-resolved address. Empty clears the override.
int SetCurlResolve(CurClientHandle handle, const char *resolveEntry);

// Runtime artifact capability probe. Version numbers alone are insufficient:
// this checks libcurl's compiled feature bits for an actual HTTP/3 backend.
int CurlSupportsHttp3(void);

// Select explicit HTTP/3-with-fallback for this client. Disabled clients are
// pinned to HTTP/2-over-TLS with HTTP/1.1 fallback and use a separate shared
// connection pool, so an earlier gray request cannot upgrade default traffic
// through connection reuse. Returns 0 only when HTTP/3 was requested but the
// linked artifact lacks the backend.
int SetCurlHttp3Enabled(CurClientHandle handle, int enabled);

// Actual protocol negotiated by the completed request. The returned pointer
// is a process-lifetime string literal ("h3", "h2", "http/1.1", etc.).
const char *GetCurlNegotiatedProtocol(CurClientHandle handle);

// Curl 发送请求
int StartRequestV27(CurClientHandle handle, const CurlRequest *request,
                    size_t requestSize, int abiVersion, CurlCallback *callback);

// Curl 流式发送请求 (fork #8): 响应体逐块通过 callback->onChunk 交付, 不缓冲整包。
int StartStreamRequestV27(CurClientHandle handle, const CurlRequest *request,
                          size_t requestSize, int abiVersion, CurlStreamCallback *callback);

// Curl 流式上传请求 (issue #8 slice 3): 请求体从 source->readChunk 逐块拉取,
// 不整包进内存 (request 的 postBody/postBodyLen 被忽略); 响应仍整包缓冲后经
// callback 交付, 与 StartRequest 一致。source 为非可寻址流: 需要重发 body 的
// 场景 (重定向 re-POST / 认证重试) 会以 CURLE_SEND_FAIL_REWIND 失败而不是
// 静默发送残缺 body。
int StartUploadRequestV27(CurClientHandle handle, const CurlRequest *request,
                          size_t requestSize, int abiVersion,
                          CurlUploadSource *source, CurlCallback *callback);

#ifdef __cplusplus
}
#endif

#endif  // NETWORKKMM_OHOSAPP_PBCURLWRAPPER_MAIN_CPP_WRAPPER_INCLUDE_CURL_WRAPPER_H_
