#ifndef SLOCK_OHOS_IMAGE_ADAPTER_H
#define SLOCK_OHOS_IMAGE_ADAPTER_H

#include "napi/native_api.h"

void RegisterSlockOhosImageAdapter(napi_env env, napi_value diagnostic_callback);
bool SetSlockOhosImageHttp3Enabled(bool enabled);

#endif  // SLOCK_OHOS_IMAGE_ADAPTER_H
