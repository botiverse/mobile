#include "napi/native_api.h"
#include "libshared_api.h"
#include "SlockOhosImageAdapter.h"
#include <hilog/log.h>
#include <Kuikly/Kuikly.h>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <string>

namespace {

// Slock font adapter
//
// The shared UI encodes font weight in familyName. The OHOS host maps those
// family names to rawfile paths and lets the Kuikly renderer load the bytes.
// The returned path remains valid until the next call on the same thread.
static constexpr unsigned int kSlockFontLogDomain = 0xA001;
static constexpr unsigned int kSlockKuiklyLogDomain = 0xA002;

static constexpr const char *kSlockFontFamilies[] = {
    "space_grotesk",
    "space_grotesk_variable",
    "space_grotesk_regular",
    "space_grotesk_light",
    "space_grotesk_medium",
    "space_grotesk_semibold",
    "space_grotesk_bold",
    "Space Grotesk",
    "SpaceGrotesk-Variable",
    "SpaceGrotesk-Regular",
    "SpaceGrotesk-Light",
    "SpaceGrotesk-Medium",
    "SpaceGrotesk-SemiBold",
    "SpaceGrotesk-Bold",
    "space_mono_regular",
    "space_mono_bold",
    "SpaceMono-Regular",
    "SpaceMono-Bold",
};

static constexpr size_t kSlockFontFamiliesCount =
    sizeof(kSlockFontFamilies) / sizeof(kSlockFontFamilies[0]);

static char *SlockFontAdapter(const char *fontFamily,
                              char ** /*fontBuffer*/,
                              size_t * /*len*/,
                              KRFontDataDeallocator * /*deallocator*/) {
    if (fontFamily == nullptr) {
        return nullptr;
    }

    const char *fileName = nullptr;
    if (std::strcmp(fontFamily, "Space Grotesk") == 0 ||
        std::strcmp(fontFamily, "space_grotesk") == 0) {
        fileName = "space_grotesk";
    } else if (std::strcmp(fontFamily, "SpaceGrotesk-Variable") == 0 ||
               std::strcmp(fontFamily, "space_grotesk_variable") == 0) {
        fileName = "space_grotesk_variable";
    } else if (std::strcmp(fontFamily, "SpaceGrotesk-Regular") == 0 ||
               std::strcmp(fontFamily, "space_grotesk_regular") == 0) {
        fileName = "space_grotesk_regular";
    } else if (std::strcmp(fontFamily, "SpaceGrotesk-Light") == 0 ||
               std::strcmp(fontFamily, "space_grotesk_light") == 0) {
        fileName = "space_grotesk_light";
    } else if (std::strcmp(fontFamily, "SpaceGrotesk-Medium") == 0 ||
               std::strcmp(fontFamily, "space_grotesk_medium") == 0) {
        fileName = "space_grotesk_medium";
    } else if (std::strcmp(fontFamily, "SpaceGrotesk-SemiBold") == 0 ||
               std::strcmp(fontFamily, "space_grotesk_semibold") == 0) {
        fileName = "space_grotesk_semibold";
    } else if (std::strcmp(fontFamily, "SpaceGrotesk-Bold") == 0 ||
               std::strcmp(fontFamily, "space_grotesk_bold") == 0) {
        fileName = "space_grotesk_bold";
    } else if (std::strcmp(fontFamily, "SpaceMono-Regular") == 0 ||
               std::strcmp(fontFamily, "space_mono_regular") == 0) {
        fileName = "space_mono_regular";
    } else if (std::strcmp(fontFamily, "SpaceMono-Bold") == 0 ||
               std::strcmp(fontFamily, "space_mono_bold") == 0) {
        fileName = "space_mono_bold";
    }

    if (fileName == nullptr) {
        return nullptr;
    }

    static thread_local std::string path;
    path = std::string("rawfile:fonts/") + fileName + ".ttf";
    return const_cast<char *>(path.c_str());
}

static std::once_flag g_slockFontAdaptersOnceFlag;
static std::once_flag g_slockLogAdapterOnceFlag;

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

}  // namespace

static napi_value InitKuikly(napi_env env, napi_callback_info info) {
    size_t argc = 1;
    napi_value argv[1] = {nullptr};
    napi_get_cb_info(env, info, &argc, argv, nullptr, nullptr);
    napi_valuetype callback_type = napi_undefined;
    if (argc > 0) {
        napi_typeof(env, argv[0], &callback_type);
    }
    napi_value diagnostic_callback = callback_type == napi_function ? argv[0] : nullptr;
    RegisterSlockLogAdapter();
    RegisterSlockFontAdapters();
    RegisterSlockOhosImageAdapter(env, diagnostic_callback);
    auto api = libshared_symbols();
    int handler = api->kotlin.root.raftInitKuikly();
    napi_value result;
    napi_create_int32(env, handler, &result);
    return result;
}

static napi_value SetImageHttp3Enabled(napi_env env, napi_callback_info info) {
    size_t argc = 1;
    napi_value argv[1] = {nullptr};
    napi_get_cb_info(env, info, &argc, argv, nullptr, nullptr);
    bool enabled = false;
    const bool valid =
        argc == 1 &&
        argv[0] != nullptr &&
        napi_get_value_bool(env, argv[0], &enabled) == napi_ok;
    napi_value result;
    napi_get_boolean(
        env,
        valid && SetSlockOhosImageHttp3Enabled(enabled),
        &result);
    return result;
}

static napi_value UpdateProcessNetworkVisibility(napi_env env, napi_callback_info info) {
    // Reserve a second slot so napi_get_cb_info reports an extra argument instead of
    // truncating it to the one-slot capacity. The bridge contract is exactly one Boolean.
    size_t argc = 2;
    napi_value argv[2] = {nullptr, nullptr};
    const bool callbackInfoValid =
        napi_get_cb_info(env, info, &argc, argv, nullptr, nullptr) == napi_ok;
    bool foreground = false;
    const bool argumentValid =
        callbackInfoValid &&
        argc == 1 &&
        argv[0] != nullptr &&
        napi_get_value_bool(env, argv[0], &foreground) == napi_ok;

    int32_t retiredRequestCount = -1;
    if (argumentValid) {
        auto api = libshared_symbols();
        retiredRequestCount =
            api->kotlin.root.raftUpdateProcessNetworkVisibility(foreground);
    }

    napi_value result;
    napi_create_int32(env, retiredRequestCount, &result);
    return result;
}

EXTERN_C_START
static napi_value Init(napi_env env, napi_value exports) {
    napi_property_descriptor desc[] = {
        {"initKuikly", nullptr, InitKuikly, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"setImageHttp3Enabled", nullptr, SetImageHttp3Enabled, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"updateProcessNetworkVisibility", nullptr, UpdateProcessNetworkVisibility, nullptr, nullptr, nullptr, napi_default, nullptr}
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

extern "C" __attribute__((constructor)) void RegisterEntryModule(void) {
    napi_module_register(&demoModule);
}
