#include "napi/native_api.h"
#include "libshared_api.h"
#include <cstdint>
#include <hilog/log.h>
#include <Kuikly/Kuikly.h>
#include <cstring>
#include <mutex>
#include <string>

namespace {

// Slock 字体适配器
//
// 注册策略：在 fork staging 上撤掉 #1424 的 fontName:weight 序列化 delta 后，
// Kuikly runtime 不再把 weight 自动塞进 FontFamily 协议。Slock 业务侧约定用
// familyName 字符串编码 weight（space_grotesk_bold / semibold / medium /
// regular / light / variable / space_grotesk 兜底），OHOS host 端把这条
// 字符串映射到 rawfile 路径，由 Kuikly 渲染层 + OHOS ResourceManager 自取
// ttf bytes。
//
// Adapter 协议约定：
// - 返回 path：rawfile 路径字符串，caller 直接用，不让 adapter 预读 buffer
// - fontBuffer / len / deallocator：不使用，caller (KRRichTextShadow) 拿到
//   path 后自己走 OHOS ResourceManager 读 ttf bytes 再调 OH_Drawing_RegisterFont
// - path lifetime：返回的字符串由 thread_local std::string 持有，函数返回后
//   仍有效直到下一次同线程调用覆盖。OHOS 渲染层应同步使用，不跨线程缓存。
//   如需跨线程，caller 自己拷贝。
static constexpr unsigned int kSlockFontLogDomain = 0xA001;
static constexpr unsigned int kSlockKuiklyLogDomain = 0xA002;
static constexpr unsigned int kSlockRealtimeLogDomain = 0xA003;

static constexpr const char *kSlockFontFamilies[] = {
    "space_grotesk",
    "space_grotesk_variable",
    "space_grotesk_regular",
    "space_grotesk_light",
    "space_grotesk_medium",
    "space_grotesk_semibold",
    "space_grotesk_bold",
};

static constexpr size_t kSlockFontFamiliesCount =
    sizeof(kSlockFontFamilies) / sizeof(kSlockFontFamilies[0]);

static char *SlockFontAdapter(const char *fontFamily,
                              char ** /*fontBuffer - 不使用，caller 自己读 ttf bytes*/,
                              size_t * /*len - 不使用*/,
                              KRFontDataDeallocator * /*deallocator - 不使用*/) {
    if (fontFamily == nullptr) {
        return nullptr;
    }
    for (const auto *known : kSlockFontFamilies) {
        if (std::strcmp(fontFamily, known) == 0) {
            static thread_local std::string path;
            path = std::string("rawfile:fonts/") + fontFamily + ".ttf";
            return const_cast<char *>(path.c_str());
        }
    }
    return nullptr;
}

static std::once_flag g_slockFontAdaptersOnceFlag;
static std::once_flag g_slockLogAdapterOnceFlag;
static std::mutex g_slockRealtimeEngineMutex;
static libshared_kref_ai_slock_compose_data_realtime_SlockRealtimeAppScopeEngine g_slockRealtimeEngine{nullptr};

static void SlockKuiklyLogAdapter(int /*logLevel*/, const char *tag, const char *message) {
    const char *safeTag = tag != nullptr && tag[0] != '\0' ? tag : "KuiklyRender";
    const char *safeMessage = message != nullptr ? message : "";
    OH_LOG_Print(LOG_APP, LOG_INFO, kSlockKuiklyLogDomain, safeTag, "%{public}s", safeMessage);
}

static void RegisterSlockFontAdapters() {
    std::call_once(g_slockFontAdaptersOnceFlag, []() {
        for (const auto *family : kSlockFontFamilies) {
            KRRegisterFontAdapter(SlockFontAdapter, family);
        }
        OH_LOG_Print(LOG_APP, LOG_INFO, kSlockFontLogDomain, "SlockFontAdapter",
                     "Registered Slock font adapter for %{public}zu family names",
                     kSlockFontFamiliesCount);
    });
}

static void RegisterSlockLogAdapter() {
    std::call_once(g_slockLogAdapterOnceFlag, []() {
        KRRegisterLogAdapter(SlockKuiklyLogAdapter);
    });
}

static std::string GetStringArg(napi_env env, napi_value value) {
    size_t length = 0;
    napi_status status = napi_get_value_string_utf8(env, value, nullptr, 0, &length);
    if (status != napi_ok) {
        return "";
    }
    std::string buffer(length + 1, '\0');
    napi_get_value_string_utf8(env, value, buffer.data(), buffer.size(), &length);
    buffer.resize(length);
    return buffer;
}

static std::string GetStringArgOrDefault(napi_env env,
                                         size_t argc,
                                         napi_value *argv,
                                         size_t index,
                                         const char *defaultValue = "") {
    if (index >= argc) {
        return defaultValue;
    }
    napi_valuetype type;
    if (napi_typeof(env, argv[index], &type) != napi_ok || type != napi_string) {
        return defaultValue;
    }
    return GetStringArg(env, argv[index]);
}

static bool GetBoolArgOrDefault(napi_env env,
                                size_t argc,
                                napi_value *argv,
                                size_t index,
                                bool defaultValue = false) {
    if (index >= argc) {
        return defaultValue;
    }
    napi_valuetype type;
    if (napi_typeof(env, argv[index], &type) != napi_ok || type != napi_boolean) {
        return defaultValue;
    }
    bool result = defaultValue;
    napi_get_value_bool(env, argv[index], &result);
    return result;
}

static int64_t GetInt64ArgOrDefault(napi_env env,
                                    size_t argc,
                                    napi_value *argv,
                                    size_t index,
                                    int64_t defaultValue = 0) {
    if (index >= argc) {
        return defaultValue;
    }
    napi_valuetype type;
    if (napi_typeof(env, argv[index], &type) != napi_ok || type != napi_number) {
        return defaultValue;
    }
    int64_t result = defaultValue;
    napi_get_value_int64(env, argv[index], &result);
    return result;
}

static void SetBoolProperty(napi_env env, napi_value object, const char *name, bool value) {
    napi_value property;
    napi_get_boolean(env, value, &property);
    napi_set_named_property(env, object, name, property);
}

static void SetInt64Property(napi_env env, napi_value object, const char *name, int64_t value) {
    napi_value property;
    napi_create_int64(env, value, &property);
    napi_set_named_property(env, object, name, property);
}

static void SetStringProperty(napi_env env, napi_value object, const char *name, const char *value) {
    napi_value property;
    napi_create_string_utf8(env, value != nullptr ? value : "", NAPI_AUTO_LENGTH, &property);
    napi_set_named_property(env, object, name, property);
}

static void SetOptionalStringProperty(napi_env env, napi_value object, const char *name, const char *value) {
    napi_value property;
    if (value == nullptr) {
        napi_get_null(env, &property);
    } else {
        napi_create_string_utf8(env, value, NAPI_AUTO_LENGTH, &property);
    }
    napi_set_named_property(env, object, name, property);
}

static libshared_ExportedSymbols *SharedSymbols() {
    return libshared_symbols();
}

static libshared_kref_ai_slock_compose_data_realtime_SlockRealtimeAppScopeEngine EnsureRealtimeEngineLocked() {
    if (g_slockRealtimeEngine.pinned == nullptr) {
        auto api = SharedSymbols();
        g_slockRealtimeEngine =
            api->kotlin.root.ai.slock.compose.data.realtime.SlockRealtimeAppScopeEngine
                .SlockRealtimeAppScopeEngine();
        OH_LOG_Print(LOG_APP, LOG_INFO, kSlockRealtimeLogDomain, "SlockRealtimeEngine",
                     "Created shared app-scope realtime engine");
    }
    return g_slockRealtimeEngine;
}

static napi_value SlockRealtimeConnectDecision(napi_env env, napi_callback_info info) {
    size_t argc = 5;
    napi_value argv[5];
    napi_get_cb_info(env, info, &argc, argv, nullptr, nullptr);

    const std::string serverId = GetStringArgOrDefault(env, argc, argv, 0);
    const std::string accessToken = GetStringArgOrDefault(env, argc, argv, 1);
    const int64_t requestedLastSeq = GetInt64ArgOrDefault(env, argc, argv, 2);
    const bool forceReconnect = GetBoolArgOrDefault(env, argc, argv, 3);
    const std::string connectionStateName = GetStringArgOrDefault(env, argc, argv, 4, "Disconnected");

    napi_value result;
    napi_create_object(env, &result);
    std::lock_guard<std::mutex> lock(g_slockRealtimeEngineMutex);
    auto api = SharedSymbols();
    auto engine = EnsureRealtimeEngineLocked();
    auto decision =
        api->kotlin.root.ai.slock.compose.data.realtime.SlockRealtimeAppScopeEngine
            .connectDecisionForHost(
                engine,
                serverId.c_str(),
                accessToken.c_str(),
                requestedLastSeq,
                forceReconnect,
                connectionStateName.c_str());

    auto decisionApi = api->kotlin.root.ai.slock.compose.data.realtime.SlockRealtimeAppScopeConnectDecision;
    SetBoolProperty(env, result, "shouldConnect", decisionApi.get_shouldConnect(decision));
    SetInt64Property(env, result, "resumeSeq", decisionApi.get_resumeSeq(decision));
    SetBoolProperty(env, result, "sessionChanged", decisionApi.get_sessionChanged(decision));
    SetOptionalStringProperty(env, result, "logMessage", decisionApi.get_logMessage(decision));
    return result;
}

static napi_value SlockRealtimeDisconnectForSessionEnd(napi_env env, napi_callback_info info) {
    size_t argc = 1;
    napi_value argv[1];
    napi_get_cb_info(env, info, &argc, argv, nullptr, nullptr);
    const std::string reason = GetStringArgOrDefault(env, argc, argv, 0, "shared_disconnect");

    bool shouldDisconnect = false;
    {
        std::lock_guard<std::mutex> lock(g_slockRealtimeEngineMutex);
        auto api = SharedSymbols();
        auto engine = EnsureRealtimeEngineLocked();
        shouldDisconnect =
            api->kotlin.root.ai.slock.compose.data.realtime.SlockRealtimeAppScopeEngine
                .disconnectForSessionEnd(engine, reason.c_str());
    }
    napi_value result;
    napi_get_boolean(env, shouldDisconnect, &result);
    return result;
}

static napi_value SlockRealtimeJoinChannelDecision(napi_env env, napi_callback_info info) {
    size_t argc = 2;
    napi_value argv[2];
    napi_get_cb_info(env, info, &argc, argv, nullptr, nullptr);
    const std::string channelId = GetStringArgOrDefault(env, argc, argv, 0);
    const std::string reason = GetStringArgOrDefault(env, argc, argv, 1, "direct");

    napi_value result;
    napi_create_object(env, &result);
    std::lock_guard<std::mutex> lock(g_slockRealtimeEngineMutex);
    auto api = SharedSymbols();
    auto engine = EnsureRealtimeEngineLocked();
    auto decision =
        api->kotlin.root.ai.slock.compose.data.realtime.SlockRealtimeAppScopeEngine
            .joinChannelDecisionForHost(engine, channelId.c_str(), reason.c_str());
    auto decisionApi = api->kotlin.root.ai.slock.compose.data.realtime.SlockRealtimeAppScopeJoinDecision;
    auto modelApi = api->kotlin.root.ai.slock.compose.model;
    auto decidedChannel = decisionApi.get_channelId(decision);
    const char *decidedChannelValue =
        decidedChannel.pinned != nullptr ? modelApi.ChannelId.get_value(decidedChannel) : nullptr;

    SetBoolProperty(env, result, "shouldJoin", decisionApi.get_shouldJoin(decision));
    SetOptionalStringProperty(env, result, "channelId", decidedChannelValue);
    SetStringProperty(env, result, "logMessage", decisionApi.get_logMessage(decision));
    return result;
}

static napi_value SlockRealtimeRawDecision(napi_env env, napi_callback_info info) {
    size_t argc = 2;
    napi_value argv[2];
    napi_get_cb_info(env, info, &argc, argv, nullptr, nullptr);
    const std::string eventName = GetStringArgOrDefault(env, argc, argv, 0);
    const std::string rawPayload = GetStringArgOrDefault(env, argc, argv, 1, "{}");

    napi_value result;
    napi_create_object(env, &result);
    std::lock_guard<std::mutex> lock(g_slockRealtimeEngineMutex);
    auto api = SharedSymbols();
    auto engine = EnsureRealtimeEngineLocked();
    auto decision =
        api->kotlin.root.ai.slock.compose.data.realtime.SlockRealtimeAppScopeEngine
            .onRawEvent(engine, eventName.c_str(), rawPayload.c_str());
    auto decisionApi = api->kotlin.root.ai.slock.compose.data.realtime.SlockRealtimeAppScopeRawDecision;
    auto modelApi = api->kotlin.root.ai.slock.compose.model;
    auto decidedServer = decisionApi.get_serverId(decision);
    const char *decidedServerValue =
        decidedServer.pinned != nullptr ? modelApi.ServerId.get_value(decidedServer) : nullptr;

    SetBoolProperty(env, result, "shouldFanOut", decisionApi.get_shouldFanOut(decision));
    SetOptionalStringProperty(env, result, "serverId", decidedServerValue);
    SetStringProperty(env, result, "eventName", decisionApi.get_eventName(decision));
    SetStringProperty(env, result, "rawPayload", decisionApi.get_rawPayload(decision));
    SetStringProperty(env, result, "logMessage", decisionApi.get_logMessage(decision));
    return result;
}

static napi_value SlockRealtimeOnAppShown(napi_env env, napi_callback_info /*info*/) {
    std::lock_guard<std::mutex> lock(g_slockRealtimeEngineMutex);
    auto api = SharedSymbols();
    auto engine = EnsureRealtimeEngineLocked();
    api->kotlin.root.ai.slock.compose.data.realtime.SlockRealtimeAppScopeEngine.onAppShown(engine);
    napi_value result;
    napi_get_undefined(env, &result);
    return result;
}

static napi_value SlockRealtimeOnAppHidden(napi_env env, napi_callback_info /*info*/) {
    std::lock_guard<std::mutex> lock(g_slockRealtimeEngineMutex);
    auto api = SharedSymbols();
    auto engine = EnsureRealtimeEngineLocked();
    api->kotlin.root.ai.slock.compose.data.realtime.SlockRealtimeAppScopeEngine.onAppHidden(engine);
    napi_value result;
    napi_get_undefined(env, &result);
    return result;
}

}  // namespace

static napi_value InitKuikly(napi_env env, napi_callback_info info) {
    // 在 Kuikly init 之前注册 OHOS host adapters
    RegisterSlockLogAdapter();
    RegisterSlockFontAdapters();
    auto api = libshared_symbols();
    int handler = api->kotlin.root.initKuikly();
    napi_value result;
    napi_create_int32(env, handler, &result);
    return result;
}

EXTERN_C_START
static napi_value Init(napi_env env, napi_value exports)
{
    napi_property_descriptor desc[] = {
        {"initKuikly", nullptr, InitKuikly, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"slockRealtimeConnectDecision", nullptr, SlockRealtimeConnectDecision, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"slockRealtimeDisconnectForSessionEnd", nullptr, SlockRealtimeDisconnectForSessionEnd, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"slockRealtimeJoinChannelDecision", nullptr, SlockRealtimeJoinChannelDecision, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"slockRealtimeRawDecision", nullptr, SlockRealtimeRawDecision, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"slockRealtimeOnAppShown", nullptr, SlockRealtimeOnAppShown, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"slockRealtimeOnAppHidden", nullptr, SlockRealtimeOnAppHidden, nullptr, nullptr, nullptr, napi_default, nullptr}
    };
    napi_define_properties(env, exports, sizeof(desc) / sizeof(desc[0]), desc);
    return exports;
}
EXTERN_C_END

static napi_module demoModule = {
    .nm_version = 1,
    .nm_flags = 0,
    .nm_filename = nullptr,
    .nm_register_func = Init,
    .nm_modname = "entry",
    .nm_priv = ((void*)0),
    .reserved = { 0 },
};

extern "C" __attribute__((constructor)) void RegisterEntryModule(void)
{
    napi_module_register(&demoModule);
}
