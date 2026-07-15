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

#ifndef SLOCK_NETWORKKMM_CURL_WRAPPER_H
#define SLOCK_NETWORKKMM_CURL_WRAPPER_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct {
    const char *first;
    const char *second;
} StringPair;

typedef struct {
    StringPair *stringPairs;
    int size;
} StringDic;

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

typedef struct {
    const char *url;
    const char *method;
    StringDic *headers;
    int64_t timeout;
    int postBodyLen;
    const char *postBody;
} CurlRequest;

typedef struct {
    int code;
    long httpCode;
    const char *errorMsg;
    int errorMsgLen;
    const char *headers;
    int headerLen;
    const char *redirectUrl;
    const char *data;
    int dataLen;
    ElapseStats elapse;
} CurlResponse;

typedef struct {
    void *callbackRef;
    void (*callback)(void *callbackRef, CurlResponse *response);
} CurlCallback;

typedef void *CurClientHandle;

CurClientHandle CreateCurlClient(const char *logTag);
void DeleteCurlClient(CurClientHandle handle);
void Cancel(CurClientHandle handle);
void StartRequest(CurClientHandle handle, CurlRequest request, CurlCallback *callback);

#ifdef __cplusplus
}
#endif

#endif  // SLOCK_NETWORKKMM_CURL_WRAPPER_H
