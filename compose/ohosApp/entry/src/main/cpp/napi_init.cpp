#include "napi/native_api.h"
#include "libshared_api.h"
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

}  // namespace

static napi_value InitKuikly(napi_env env, napi_callback_info info) {
    // 在 Kuikly init 之前注册 OHOS host 的 font adapter
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
        {"initKuikly", nullptr, InitKuikly, nullptr, nullptr, nullptr, napi_default, nullptr}
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