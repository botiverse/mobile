# Native Module implementations

This reference describes registration and dispatch shapes. Always verify imports and signatures against the project's exact Kuikly renderer version.

The examples assume shared `moduleName() == "KRMyLogModule"` and JSON methods named `log`, `logWithCallback`, and `syncLog`.

## Android

### Implement

```kotlin
import com.tencent.kuikly.core.render.android.export.KuiklyRenderBaseModule
import com.tencent.kuikly.core.render.android.export.KuiklyRenderCallback
import org.json.JSONObject

internal class KRMyLogModule : KuiklyRenderBaseModule() {
    override fun call(
        method: String,
        params: String?,
        callback: KuiklyRenderCallback?,
    ): Any? = when (method) {
        "log" -> {
            log(parseContent(params))
            null
        }
        "logWithCallback" -> {
            log(parseContent(params))
            callback?.invoke(mapOf("result" to "ok"))
            null
        }
        "syncLog" -> {
            log(parseContent(params))
            "ok"
        }
        else -> super.call(method, params, callback)
    }

    private fun parseContent(params: String?): String =
        runCatching { JSONObject(params.orEmpty()).optString("content") }.getOrDefault("")

    private fun log(content: String) = Unit
}
```

The `JSONObject` shared overload arrives as a JSON string. The `Array<Any>` overload uses `call(method, params: Any?, callback)` and must be decoded according to the renderer's exact atomic transport types.

### Register

```kotlin
override fun registerExternalModule(export: IKuiklyRenderExport) {
    super.registerExternalModule(export)
    export.moduleExport("KRMyLogModule") { KRMyLogModule() }
}
```

Register through the actual delegator used by every relevant render surface. In apps with activity and embedded render views, add a source-contract test proving both reach the same export path.

## iOS

Kuikly iOS resolves native modules through the Objective-C runtime. The runtime class name must equal shared `moduleName()`.

### Objective-C

```objc
// KRMyLogModule.h
#import <OpenKuiklyIOSRender/KRBaseModule.h>

@interface KRMyLogModule : KRBaseModule
@end
```

```objc
// KRMyLogModule.m
#import "KRMyLogModule.h"

@implementation KRMyLogModule

- (void)log:(NSDictionary *)args {
    NSDictionary *payload = [self payloadFromArgs:args];
    NSString *content = [payload[@"content"] isKindOfClass:NSString.class]
        ? payload[@"content"] : @"";
    (void)content;
}

- (void)logWithCallback:(NSDictionary *)args {
    [self log:args];
    KuiklyRenderCallback callback = args[KR_CALLBACK_KEY];
    if (callback) callback(@{ @"result": @"ok" });
}

- (id)syncLog:(NSDictionary *)args {
    [self log:args];
    return @"ok";
}

- (NSDictionary *)payloadFromArgs:(NSDictionary *)args {
    id raw = args[KR_PARAM_KEY];
    if ([raw isKindOfClass:NSDictionary.class]) return raw;
    if (![raw isKindOfClass:NSString.class]) return @{};
    NSData *data = [(NSString *)raw dataUsingEncoding:NSUTF8StringEncoding];
    id value = data ? [NSJSONSerialization JSONObjectWithData:data options:0 error:nil] : nil;
    return [value isKindOfClass:NSDictionary.class] ? value : @{};
}

@end
```

No separate export map is needed for this standard ObjC runtime path, but the class must be linked into the host target. A class present only in source or in the wrong target is not registered.

### Swift

Bare `@objc` does not guarantee the required unqualified runtime name. Pin it explicitly and expose Objective-C selectors:

```swift
@objc(KRMyLogModule)
final class KRMyLogModule: KRBaseModule {
    @objc(log:)
    func log(_ args: NSDictionary) { /* decode args[KR_PARAM_KEY] */ }

    @objc(syncLog:)
    func syncLog(_ args: NSDictionary) -> Any { "ok" }
}
```

Verify with `NSClassFromString("KRMyLogModule")` in a native test and compile the actual host target.

## HarmonyOS / OHOS ArkTS

### Implement

```ts
import {
  KRAny,
  KuiklyRenderBaseModule,
  KuiklyRenderCallback,
} from '@kuikly-open/render';

export class KRMyLogModule extends KuiklyRenderBaseModule {
  static readonly MODULE_NAME: string = 'KRMyLogModule';

  syncMode(): boolean {
    return true;
  }

  call(method: string, params: KRAny, callback: KuiklyRenderCallback | null): KRAny {
    switch (method) {
      case 'log':
        this.log(params);
        return null;
      case 'logWithCallback':
        this.log(params);
        callback?.({ result: 'ok' });
        return null;
      case 'syncLog':
        this.log(params);
        return 'ok';
      default:
        return null;
    }
  }

  private log(params: KRAny): void {
    // Decode the JSON-string or atomic transport shape used by the shared overload.
    console.info(params?.toString() ?? '');
  }
}
```

`syncMode() == true` runs the module in Kuikly's thread mode and supports sync plus async calls. `false` is UI-thread/async-only; do not make shared sync calls to such a module.

### Register

```ts
getCustomRenderModuleCreatorRegisterMap(): Map<string, KRRenderModuleExportCreator> {
  const map = new Map<string, KRRenderModuleExportCreator>();
  map.set(KRMyLogModule.MODULE_NAME, () => new KRMyLogModule());
  return map;
}
```

Import the creator type and base class from the exact installed `@kuikly-open/render` version. Build with the project's Hvigor/lockfile rather than assuming the skill checkout package shape.

## OHOS C module

Use `KRRenderModuleRegisterV2` only when the project intentionally implements the module in C/C++. Confirm ownership of returned `KRAnyData`, callback context lifetime, and ArkTS-vs-C precedence in the exact renderer source. Do not introduce a C module only to avoid ordinary ArkTS registration.

## H5 and mini-app

Both use the web renderer's `KuiklyRenderBaseModule` and host export mechanism, but available browser/mini-app globals and thread behavior differ. Read the project's `core-render-web` and host adapter before implementing. Do not copy Android imports or assume `window`/`wx` exists in common code.

## Cross-platform checklist

- Exact module and method names match.
- JSON vs atomic payload encoding matches on each platform.
- Sync methods return bridge-supported values and never block on slow work.
- Callback result shape matches shared parsing; repeated callbacks have explicit teardown.
- Page destruction, cancellation, duplicate result and unsupported platform are safe.
- Native class/file is linked into the actual target and every render entry uses the registration path.
