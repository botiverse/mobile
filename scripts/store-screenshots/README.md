# Store screenshots

Google Play and App Store screenshots, rendered from the real Compose screens with fictional
demo content (team "Northwind Labs", agents Ruby, Atlas, Nova, Echo, Sage, Pixel, Quill, Juno;
people Maya, Leo, Priya, Sam, Mina). No real server data.

## 1. Render the app screens

`StoreScreenshotsTest` (compose/shared androidUnitTest, Robolectric) renders every scene for
every device profile in en, zh-Hans and ja. It is skipped unless `RAFT_STORE_SCREENSHOTS` is set.

```bash
cd compose
RAFT_STORE_SCREENSHOTS=/tmp/store-raw ./gradlew :shared:testDebugUnitTest \
  --tests 'build.raft.app.StoreScreenshotsTest'
# optional filters: RAFT_STORE_PROFILES=phone,ipad  RAFT_STORE_SCENES=01-chat,03-agents
```

Output: `<dir>/<profile>/<lang>/NN-scene.png`. Profiles (dp, density) are in `StoreProfiles`:
phone 390×844, iphone 430×932, tablet 1024×640, ipad 1032×1376, duo-inner-portrait 669×951,
duo-inner-landscape 951×669, duo-outer 466×678. Profiles 840dp or wider get the app's wide
layout (top bar, navigation rail, list + detail) and five scenes; the others the phone layout and
six scenes (plus Home).

## 2. Frame them

Needs `chromium` on PATH, Pillow, and the Noto CJK web fonts for the headlines:

```bash
npm install --prefix /tmp/store-fonts @fontsource/noto-sans-sc @fontsource/noto-sans-jp
python3 scripts/store-screenshots/frames.py --raw /tmp/store-raw --out /tmp/store-final \
  --fonts /tmp/store-fonts/node_modules/@fontsource          # --style B for the yellow variant
python3 scripts/store-screenshots/review.py /tmp/store-final  # manifest.json + index.html
```

`frames.py` writes `<out>/<store>/<device>/<lang>/NN-scene.png` (store `play` | `appstore`;
devices `phone`, `tablet-7`, `tablet-10`, `iphone-6.9`, `iphone-6.5`, `ipad-13`,
`duo-inner-portrait`, `duo-inner-landscape`, `duo-outer`) and the Play feature graphic
(`play/feature-graphic/<lang>/feature-graphic.png`, 1024×500). Headlines live in `SCENES` /
`FEATURE`. Every PNG is flattened to RGB; `review.py` fails if any size or alpha is wrong.
