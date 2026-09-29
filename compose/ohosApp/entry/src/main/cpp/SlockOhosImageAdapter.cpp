#include "SlockOhosImageAdapter.h"

#include "SlockOhosImageRequestSingleFlight.h"
#include "include/networkkmm/curl_wrapper.h"

#include <Kuikly/Kuikly.h>
#include <hilog/log.h>
#include <multimedia/image_framework/image/image_source_native.h>
#include <multimedia/image_framework/image/pixelmap_native.h>

#include <cstdint>
#include <algorithm>
#include <atomic>
#include <exception>
#include <iomanip>
#include <list>
#include <memory>
#include <mutex>
#include <sstream>
#include <string>
#include <thread>
#include <unordered_map>
#include <utility>
#include <vector>

namespace {

constexpr unsigned int kLogDomain = 0xA003;
constexpr int64_t kRequestTimeoutMs = 30'000;
constexpr size_t kPixelMapCacheBytes = 12 * 1024 * 1024;
constexpr size_t kMaxSinglePixelMapCacheBytes = 2 * 1024 * 1024;

static_assert(CURL_WRAPPER_ABI_VERSION == 27,
              "SlockOhosImageAdapter requires the NetworkKMM ABI-v27 entrypoints");
static_assert(sizeof(CurlRequest) == 80,
              "NetworkKMM ABI-v27 CurlRequest must remain 80 bytes on OHOS arm64");

struct ImageSubscriber final {
    const void *context;
    std::string src;
    KRSetImageCallback callback;
};

using ImageSingleFlight = SlockOhosImageRequestSingleFlight<std::string, ImageSubscriber>;
using SharedPixelMap = std::shared_ptr<OH_PixelmapNative>;

ImageSingleFlight g_image_flights;
napi_threadsafe_function g_image_callback_dispatcher = nullptr;
std::once_flag g_image_adapter_once;
std::atomic<bool> g_image_http3_enabled{true};

uint64_t HashUrl(const std::string &value) {
    uint64_t hash = 1469598103934665603ULL;
    for (const unsigned char character : value) {
        hash ^= character;
        hash *= 1099511628211ULL;
    }
    return hash;
}

std::string UrlHash(const std::string &value) {
    std::ostringstream stream;
    stream << std::hex << std::setw(16) << std::setfill('0') << HashUrl(value);
    return stream.str();
}

bool IsNetworkUrl(const char *src) {
    if (src == nullptr) {
        return false;
    }
    const std::string value(src);
    return value.rfind("https://", 0) == 0 || value.rfind("http://", 0) == 0;
}

struct ImageDecodeDiagnostic final {
    int sourceResult = -1;
    int decodeResult = -1;
    uint32_t width = 0;
    uint32_t height = 0;
    std::string format = "unknown";
};

SharedPixelMap DecodePixelMap(const char *data, int data_length, ImageDecodeDiagnostic &diagnostic) {
    if (data == nullptr || data_length <= 0) {
        return nullptr;
    }

    OH_ImageSourceNative *source = nullptr;
    auto *mutable_data = reinterpret_cast<uint8_t *>(const_cast<char *>(data));
    diagnostic.sourceResult = OH_ImageSourceNative_CreateFromData(
        mutable_data, static_cast<size_t>(data_length), &source);
    if (diagnostic.sourceResult != IMAGE_SUCCESS || source == nullptr) {
        return nullptr;
    }

    OH_ImageSource_Info *info = nullptr;
    if (OH_ImageSourceInfo_Create(&info) == IMAGE_SUCCESS && info != nullptr) {
        if (OH_ImageSourceNative_GetImageInfo(source, 0, info) == IMAGE_SUCCESS) {
            OH_ImageSourceInfo_GetWidth(info, &diagnostic.width);
            OH_ImageSourceInfo_GetHeight(info, &diagnostic.height);
            Image_MimeType mime{};
            if (OH_ImageSourceInfo_GetMimeType(info, &mime) == IMAGE_SUCCESS && mime.data != nullptr) {
                // MIME storage belongs to info and is not NUL-terminated. Copy only a
                // bounded, recognized format; never put downloaded payload text in logs.
                const std::string format(mime.data, std::min(mime.size, size_t{64}));
                for (const char *known : {"image/jpeg", "image/png", "image/webp", "image/gif",
                                          "image/bmp", "image/svg+xml", "image/x-icon",
                                          "image/heif", "image/heic", "image/x-adobe-dng"}) {
                    if (format == known) diagnostic.format = known;
                }
            }
        }
        OH_ImageSourceInfo_Release(info);
    }

    OH_DecodingOptions *options = nullptr;
    OH_DecodingOptions_Create(&options);
    if (options != nullptr) {
        OH_DecodingOptions_SetDesiredDynamicRange(options, IMAGE_DYNAMIC_RANGE_AUTO);
    }

    OH_PixelmapNative *pixel_map = nullptr;
    diagnostic.decodeResult = OH_ImageSourceNative_CreatePixelmap(source, options, &pixel_map);
    if (options != nullptr) {
        OH_DecodingOptions_Release(options);
    }
    OH_ImageSourceNative_Release(source);
    if (diagnostic.decodeResult != IMAGE_SUCCESS || pixel_map == nullptr) {
        return nullptr;
    }

    return SharedPixelMap(pixel_map, [](OH_PixelmapNative *value) {
        if (value != nullptr) {
            OH_PixelmapNative_Release(value);
        }
    });
}

size_t PixelMapAllocationBytes(const SharedPixelMap &pixel_map) {
    if (!pixel_map) {
        return 0;
    }
    uint32_t allocation_bytes = 0;
    if (OH_PixelmapNative_GetAllocationByteCount(pixel_map.get(), &allocation_bytes) != IMAGE_SUCCESS) {
        return 0;
    }
    return allocation_bytes;
}

class PixelMapCache final {
 public:
    SharedPixelMap Get(const std::string &key) {
        std::lock_guard<std::mutex> lock(mutex_);
        auto existing = entries_.find(key);
        if (existing == entries_.end()) {
            return nullptr;
        }
        order_.splice(order_.begin(), order_, existing->second.order);
        return existing->second.pixelMap;
    }

    void Put(const std::string &key, SharedPixelMap pixel_map) {
        const size_t bytes = PixelMapAllocationBytes(pixel_map);
        if (!pixel_map || bytes == 0 || bytes > kMaxSinglePixelMapCacheBytes) {
            return;
        }

        std::lock_guard<std::mutex> lock(mutex_);
        auto existing = entries_.find(key);
        if (existing != entries_.end()) {
            total_bytes_ -= existing->second.bytes;
            order_.erase(existing->second.order);
            entries_.erase(existing);
        }

        order_.push_front(key);
        entries_.emplace(key, Entry{std::move(pixel_map), bytes, order_.begin()});
        total_bytes_ += bytes;
        while (total_bytes_ > kPixelMapCacheBytes && !order_.empty()) {
            const std::string oldest_key = order_.back();
            auto oldest = entries_.find(oldest_key);
            if (oldest != entries_.end()) {
                total_bytes_ -= oldest->second.bytes;
                entries_.erase(oldest);
            }
            order_.pop_back();
        }
    }

 private:
    struct Entry final {
        SharedPixelMap pixelMap;
        size_t bytes;
        std::list<std::string>::iterator order;
    };

    std::mutex mutex_;
    std::list<std::string> order_;
    std::unordered_map<std::string, Entry> entries_;
    size_t total_bytes_ = 0;
};

PixelMapCache g_pixel_map_cache;

void RecordImageDiagnostic(napi_env env, napi_value callback, const std::string &message, bool failed) {
    if (env == nullptr || callback == nullptr || message.empty()) return;
    napi_value args[2];
    napi_value receiver;
    if (napi_create_string_utf8(env, message.c_str(), message.size(), &args[0]) != napi_ok ||
        napi_get_boolean(env, failed, &args[1]) != napi_ok ||
        napi_get_undefined(env, &receiver) != napi_ok) return;
    if (napi_call_function(env, receiver, callback, 2, args, nullptr) != napi_ok) {
        // Diagnostics must not prevent image delivery if the ArkTS recorder fails.
        bool pending = false;
        napi_is_exception_pending(env, &pending);
        if (pending) {
            napi_value ignored;
            napi_get_and_clear_last_exception(env, &ignored);
        }
        OH_LOG_Print(LOG_APP, LOG_WARN, kLogDomain, "SlockImageFlight", "diagnostic_callback_failed");
    }
}

struct ImageFanoutTask final {
    std::vector<ImageSubscriber> subscribers;
    SharedPixelMap pixelMap;
    std::string diagnostic;

    void Run(napi_env env, napi_value diagnostic_callback) {
        RecordImageDiagnostic(env, diagnostic_callback, diagnostic, !pixelMap);
        for (const auto &subscriber : subscribers) {
            if (pixelMap) {
                ArkUI_DrawableDescriptor *descriptor =
                    OH_ArkUI_DrawableDescriptor_CreateFromPixelMap(pixelMap.get());
                if (descriptor != nullptr) {
                    subscriber.callback(subscriber.context, subscriber.src.c_str(), descriptor, nullptr);
                    OH_ArkUI_DrawableDescriptor_Dispose(descriptor);
                    continue;
                }
                RecordImageDiagnostic(env, diagnostic_callback,
                    "drawable_failure urlHash=" + UrlHash(subscriber.src), true);
            }
            subscriber.callback(subscriber.context, subscriber.src.c_str(), nullptr, nullptr);
        }
    }
};

void RunImageFanoutOnMain(napi_env env, napi_value js_callback, void * /*context*/, void *data) {
    std::unique_ptr<ImageFanoutTask> task(static_cast<ImageFanoutTask *>(data));
    if (task && env != nullptr) {
        task->Run(env, js_callback);
    }
}

void DispatchFanout(std::vector<ImageSubscriber> subscribers, SharedPixelMap pixel_map,
                    std::string diagnostic = {}) {
    if (subscribers.empty()) {
        return;
    }
    auto task = std::make_unique<ImageFanoutTask>();
    task->subscribers = std::move(subscribers);
    task->pixelMap = std::move(pixel_map);
    task->diagnostic = std::move(diagnostic);
    const napi_status status = napi_call_threadsafe_function(
        g_image_callback_dispatcher,
        task.get(),
        napi_tsfn_nonblocking);
    if (status == napi_ok) {
        task.release();
    } else {
        OH_LOG_Print(LOG_APP, LOG_ERROR, kLogDomain, "SlockImageFlight",
                     "dispatch_failed status=%{public}d", static_cast<int>(status));
    }
}

struct CurlResponseState final {
    SharedPixelMap pixelMap;
    int curlCode = -1;
    long httpCode = 0;
    double totalTimeMs = 0;
    bool http3Requested = false;
    int responseBytes = 0;
    ImageDecodeDiagnostic decode;
    std::string negotiatedProtocol = "unknown";
};

void OnCurlResponse(void *callback_ref, CurlResponse *response) {
    auto *state = static_cast<CurlResponseState *>(callback_ref);
    if (state == nullptr || response == nullptr) {
        return;
    }
    state->curlCode = response->code;
    state->httpCode = response->httpCode;
    state->totalTimeMs = response->elapse.totalTimeMs;
    state->responseBytes = response->dataLen;
    if (response->code == 0 && response->httpCode >= 200 && response->httpCode < 300) {
        state->pixelMap = DecodePixelMap(response->data, response->dataLen, state->decode);
    }
}

void RunImageProducer(std::string key, uint64_t flight_id) {
    CurlResponseState response_state;
    response_state.http3Requested = g_image_http3_enabled.load(std::memory_order_acquire);
    CurClientHandle client = CreateCurlClient("SlockImageFlight");
    if (client != nullptr) {
        const int runtime_abi = CurlWrapperAbiVersion();
        if (runtime_abi != CURL_WRAPPER_ABI_VERSION) {
            response_state.curlCode = -2;
            OH_LOG_Print(LOG_APP, LOG_ERROR, kLogDomain, "SlockImageFlight",
                         "abi_mismatch expected=%{public}d actual=%{public}d",
                         CURL_WRAPPER_ABI_VERSION, runtime_abi);
        } else if (SetCurlHttp3Enabled(client, response_state.http3Requested ? 1 : 0) == 0) {
            response_state.curlCode = -2;
            OH_LOG_Print(LOG_APP, LOG_ERROR, kLogDomain, "SlockImageFlight",
                         "http3_policy_rejected requested=%{public}d",
                         response_state.http3Requested ? 1 : 0);
        } else {
            const std::string method = "GET";
            StringDic empty_headers{};
            CurlRequest request{};
            request.url = key.c_str();
            request.method = method.c_str();
            // NetworkKMM's buffered wrapper treats the dictionary itself as required
            // even when the request has no headers; only stringPairs may be null.
            request.headers = &empty_headers;
            request.timeout = kRequestTimeoutMs;
            request.postBodyLen = 0;
            request.postBody = nullptr;
            CurlCallback callback{&response_state, &OnCurlResponse};
            if (StartRequestV27(
                    client,
                    &request,
                    sizeof(request),
                    CURL_WRAPPER_ABI_VERSION,
                    &callback) == 0) {
                response_state.curlCode = -2;
                OH_LOG_Print(LOG_APP, LOG_ERROR, kLogDomain, "SlockImageFlight",
                             "request_rejected expectedAbi=%{public}d requestSize=%{public}zu",
                             CURL_WRAPPER_ABI_VERSION, sizeof(request));
            }
            response_state.negotiatedProtocol = GetCurlNegotiatedProtocol(client);
        }
        DeleteCurlClient(client);
    }

    auto subscribers = response_state.pixelMap
        ? g_image_flights.CompleteWithPublication(key, flight_id, [&]() {
              g_pixel_map_cache.Put(key, response_state.pixelMap);
          })
        : g_image_flights.Complete(key, flight_id);
    if (subscribers.empty()) {
        return;
    }

    const std::string url_hash = UrlHash(key);
    if (response_state.pixelMap) {
        OH_LOG_Print(LOG_APP, LOG_INFO, kLogDomain, "SlockImageFlight",
                     "flight_success urlHash=%{public}s consumers=%{public}zu durationMs=%{public}.1f "
                     "http3Requested=%{public}d negotiatedProtocol=%{public}s",
                     url_hash.c_str(), subscribers.size(), response_state.totalTimeMs,
                     response_state.http3Requested ? 1 : 0,
                     response_state.negotiatedProtocol.c_str());
    } else {
        OH_LOG_Print(LOG_APP, LOG_WARN, kLogDomain, "SlockImageFlight",
                     "flight_failure urlHash=%{public}s consumers=%{public}zu curlCode=%{public}d "
                     "httpCode=%{public}ld durationMs=%{public}.1f http3Requested=%{public}d "
                     "negotiatedProtocol=%{public}s",
                     url_hash.c_str(), subscribers.size(), response_state.curlCode,
                     response_state.httpCode, response_state.totalTimeMs,
                     response_state.http3Requested ? 1 : 0,
                     response_state.negotiatedProtocol.c_str());
    }
    // A decoded pixelmap is not proof of a displayed frame. Keep this event
    // distinct from ArkUI's later load/draw result and use only URL hashes.
    std::ostringstream diagnostic;
    diagnostic << (response_state.pixelMap ? "flight_decoded" : "flight_failure")
               << " urlHash=" << url_hash
               << " curlCode=" << response_state.curlCode << " httpCode=" << response_state.httpCode
               << " responseBytes=" << response_state.responseBytes
               << " sourceResult=" << response_state.decode.sourceResult
               << " decodeResult=" << response_state.decode.decodeResult
               << " format=" << response_state.decode.format
               << " width=" << response_state.decode.width << " height=" << response_state.decode.height;
    DispatchFanout(std::move(subscribers), std::move(response_state.pixelMap), diagnostic.str());
}

int32_t SlockImageAdapterV3(const void *context,
                            const char *src,
                            KRAnyData /*image_params*/,
                            KRSetImageCallback callback) {
    if (!IsNetworkUrl(src) || callback == nullptr || g_image_callback_dispatcher == nullptr) {
        return 0;
    }

    const std::string key(src);
    const std::string url_hash = UrlHash(key);
    const auto acquire = g_image_flights.JoinOrGetCached<SharedPixelMap>(
        key,
        ImageSubscriber{context, key, callback},
        [&]() { return g_pixel_map_cache.Get(key); });
    if (acquire.cacheHit) {
        OH_LOG_Print(LOG_APP, LOG_INFO, kLogDomain, "SlockImageFlight",
                     "cache_hit urlHash=%{public}s", url_hash.c_str());
        DispatchFanout({ImageSubscriber{context, key, callback}}, acquire.cachedValue);
        return 1;
    }

    const auto &join = acquire.join;
    if (!join.shouldStart) {
        OH_LOG_Print(LOG_APP, LOG_INFO, kLogDomain, "SlockImageFlight",
                     "flight_join urlHash=%{public}s consumers=%{public}zu",
                     url_hash.c_str(), join.subscriberCount);
        return 1;
    }

    OH_LOG_Print(LOG_APP, LOG_INFO, kLogDomain, "SlockImageFlight",
                 "flight_start urlHash=%{public}s", url_hash.c_str());
    try {
        std::thread(RunImageProducer, key, join.flightId).detach();
    } catch (const std::exception &exception) {
        OH_LOG_Print(LOG_APP, LOG_ERROR, kLogDomain, "SlockImageFlight",
                     "flight_thread_start_failed urlHash=%{public}s reason=%{public}s",
                     url_hash.c_str(), exception.what());
        DispatchFanout(g_image_flights.Complete(key, join.flightId), nullptr,
                       "flight_thread_start_failed urlHash=" + url_hash);
    }
    return 1;
}

}  // namespace

bool SetSlockOhosImageHttp3Enabled(bool enabled) {
    g_image_http3_enabled.store(enabled, std::memory_order_release);
    OH_LOG_Print(LOG_APP, LOG_INFO, kLogDomain, "SlockImageFlight",
                 "http3_policy_updated enabled=%{public}d", enabled ? 1 : 0);
    return true;
}

void RegisterSlockOhosImageAdapter(napi_env env, napi_value diagnostic_callback) {
    if (g_image_callback_dispatcher == nullptr) {
        napi_value work_name;
        napi_create_string_utf8(env, "Slock image callback", NAPI_AUTO_LENGTH, &work_name);
        const napi_status status = napi_create_threadsafe_function(
            env,
            diagnostic_callback,
            nullptr,
            work_name,
            0,
            1,
            nullptr,
            nullptr,
            nullptr,
            RunImageFanoutOnMain,
            &g_image_callback_dispatcher);
        if (status != napi_ok) {
            g_image_callback_dispatcher = nullptr;
            OH_LOG_Print(LOG_APP, LOG_ERROR, kLogDomain, "SlockImageFlight",
                         "dispatcher_create_failed status=%{public}d", static_cast<int>(status));
            return;
        }
    }

    std::call_once(g_image_adapter_once, []() {
        KRRegisterImageAdapterV3(SlockImageAdapterV3);
        OH_LOG_Print(LOG_APP, LOG_INFO, kLogDomain, "SlockImageFlight",
                     "adapter_registered identity=raw_url cacheBytes=%{public}zu",
                     kPixelMapCacheBytes);
    });
}
