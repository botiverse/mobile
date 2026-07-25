#!/bin/sh

set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
OHOS_APP_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)
COMPOSE_ROOT=$(CDPATH= cd -- "$OHOS_APP_ROOT/.." && pwd)
MOBILE_ROOT=$(CDPATH= cd -- "$COMPOSE_ROOT/.." && pwd)
DEVECO_STUDIO_HOME=${DEVECO_STUDIO_HOME:-/Applications/DevEco-Studio.app/Contents}

node "$SCRIPT_DIR/test_process_network_lifecycle_source_contract.mjs"

cd "$COMPOSE_ROOT"
./gradlew --no-daemon --console=plain \
  -c settings.ohos.gradle.kts \
  copyNetworkOhosRuntimeLibs \
  :ohosBridge:linkDebugSharedOhosArm64

SHARED_OUTPUT="$COMPOSE_ROOT/ohosBridge/build/bin/ohosArm64/debugShared"
test -s "$SHARED_OUTPUT/libshared.so"
test -s "$SHARED_OUTPUT/libshared_api.h"
grep -q 'raftUpdateProcessNetworkVisibility' "$SHARED_OUTPUT/libshared_api.h"
test "$(wc -l < "$SHARED_OUTPUT/libshared_api.h")" -le 500

mkdir -p "$OHOS_APP_ROOT/entry/libs/arm64-v8a"
mkdir -p "$OHOS_APP_ROOT/entry/src/main/cpp"
install -m 755 \
  "$SHARED_OUTPUT/libshared.so" \
  "$OHOS_APP_ROOT/entry/libs/arm64-v8a/libshared.so"
install -m 644 \
  "$SHARED_OUTPUT/libshared_api.h" \
  "$OHOS_APP_ROOT/entry/src/main/cpp/libshared_api.h"

export DEVECO_SDK_HOME="$DEVECO_STUDIO_HOME/sdk"
export PATH="$DEVECO_STUDIO_HOME/jbr/Contents/Home/bin:$DEVECO_STUDIO_HOME/tools/node/bin:$DEVECO_STUDIO_HOME/tools/ohpm/bin:$DEVECO_STUDIO_HOME/tools/hvigor/bin:$PATH"

cd "$OHOS_APP_ROOT"
ohpm install --all
hvigorw --sync -p product=default --analyze=normal --no-daemon
hvigorw --mode module \
  -p module=entry@default -p product=default -p requiredDeviceType=phone \
  default@BuildNativeWithNinja --analyze=normal --no-daemon
hvigorw --mode module \
  -p module=entry@default -p product=default -p requiredDeviceType=phone \
  test --analyze=normal --no-daemon

echo 'OHOS process-network lifecycle native bridge compile/link: PASS'
