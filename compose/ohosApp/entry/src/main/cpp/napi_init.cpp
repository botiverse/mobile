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
//
// The table covers every bundled family, not only the Space set: Settings >
// Appearance (task #179) can select Hanken Grotesk, Geist+Inter, or Space
// Grotesk, with Geist Mono / Space Mono as the mono role. The PascalCase
// names are what the shared KuiklyFont serialization emits (see
// SlockDesignTokens.fontFamilyFor); the snake_case aliases match the rawfile
// base names under resources/resfile/fonts/. Unknown names still return
// nullptr so Kuikly falls back to the platform face — which is also how the
// "System" option resolves (shared emits FontFamily.Default/Monospace then).
static constexpr unsigned int kSlockFontLogDomain = 0xA001;
static constexpr unsigned int kSlockKuiklyLogDomain = 0xA002;

struct SlockFontMapping {
    const char *familyName;
    const char *fileName;
};

static constexpr SlockFontMapping kSlockFontTable[] = {
    {"Space Grotesk", "space_grotesk"},
    {"space_grotesk", "space_grotesk"},
    {"SpaceGrotesk-Variable", "space_grotesk_variable"},
    {"space_grotesk_variable", "space_grotesk_variable"},
    {"SpaceGrotesk-Regular", "space_grotesk_regular"},
    {"space_grotesk_regular", "space_grotesk_regular"},
    {"SpaceGrotesk-Light", "space_grotesk_light"},
    {"space_grotesk_light", "space_grotesk_light"},
    {"SpaceGrotesk-Medium", "space_grotesk_medium"},
    {"space_grotesk_medium", "space_grotesk_medium"},
    {"SpaceGrotesk-SemiBold", "space_grotesk_semibold"},
    {"space_grotesk_semibold", "space_grotesk_semibold"},
    {"SpaceGrotesk-Bold", "space_grotesk_bold"},
    {"space_grotesk_bold", "space_grotesk_bold"},
    {"SpaceMono-Regular", "space_mono_regular"},
    {"space_mono_regular", "space_mono_regular"},
    {"SpaceMono-Bold", "space_mono_bold"},
    {"space_mono_bold", "space_mono_bold"},
    {"HankenGrotesk-Light", "hanken_grotesk_light"},
    {"hanken_grotesk_light", "hanken_grotesk_light"},
    {"HankenGrotesk-Regular", "hanken_grotesk_regular"},
    {"hanken_grotesk_regular", "hanken_grotesk_regular"},
    {"HankenGrotesk-Medium", "hanken_grotesk_medium"},
    {"hanken_grotesk_medium", "hanken_grotesk_medium"},
    {"HankenGrotesk-SemiBold", "hanken_grotesk_semibold"},
    {"hanken_grotesk_semibold", "hanken_grotesk_semibold"},
    {"HankenGrotesk-Bold", "hanken_grotesk_bold"},
    {"hanken_grotesk_bold", "hanken_grotesk_bold"},
    {"Geist-Light", "geist_light"},
    {"geist_light", "geist_light"},
    {"Geist-Regular", "geist_regular"},
    {"geist_regular", "geist_regular"},
    {"Geist-Medium", "geist_medium"},
    {"geist_medium", "geist_medium"},
    {"Geist-SemiBold", "geist_semibold"},
    {"geist_semibold", "geist_semibold"},
    {"Geist-Bold", "geist_bold"},
    {"geist_bold", "geist_bold"},
    {"Inter-Light", "inter_light"},
    {"inter_light", "inter_light"},
    {"Inter-Regular", "inter_regular"},
    {"inter_regular", "inter_regular"},
    {"Inter-Medium", "inter_medium"},
    {"inter_medium", "inter_medium"},
    {"Inter-SemiBold", "inter_semibold"},
    {"inter_semibold", "inter_semibold"},
    {"Inter-Bold", "inter_bold"},
    {"inter_bold", "inter_bold"},
    {"GeistMono-Regular", "geist_mono_regular"},
    {"geist_mono_regular", "geist_mono_regular"},
    {"GeistMono-Bold", "geist_mono_bold"},
    {"geist_mono_bold", "geist_mono_bold"},
};

static constexpr size_t kSlockFontMappingCount =
    sizeof(kSlockFontTable) / sizeof(kSlockFontTable[0]);

static char *SlockFontAdapter(const char *fontFamily,
                              char ** /*fontBuffer*/,
                              size_t * /*len*/,
                              KRFontDataDeallocator * /*deallocator*/) {
    if (fontFamily == nullptr) {
        return nullptr;
    }

    const char *fileName = nullptr;
    for (const auto &mapping : kSlockFontTable) {
        if (std::strcmp(fontFamily, mapping.familyName) == 0) {
            fileName = mapping.fileName;
            break;
        }
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
        for (const auto &mapping : kSlockFontTable) {
            KRRegisterFontAdapter(SlockFontAdapter, mapping.familyName);
        }
        OH_LOG_Print(LOG_APP, LOG_INFO, kSlockFontLogDomain, "SlockFontAdapter",
                     "Registered Slock font adapter for %{public}zu family names",
                     kSlockFontMappingCount);
    });
}

static void RegisterSlockLogAdapter() {
    std::call_once(g_slockLogAdapterOnceFlag, []() {
        KRRegisterLogAdapter(SlockKuiklyLogAdapter);
    });
}

}  // namespace

static napi_value InstallKnoiAsyncLog(napi_env env, napi_callback_info info) {
    const int installed = libshared_symbols()->kotlin.root.raftInstallKnoiAsyncLog();
    napi_value result;
    napi_create_int32(env, installed, &result);
    return result;
}

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
        {"installKnoiAsyncLog", nullptr, InstallKnoiAsyncLog, nullptr, nullptr, nullptr, napi_default, nullptr},
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
