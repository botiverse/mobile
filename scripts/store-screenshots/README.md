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

## Play phone set of 8 (`--set play8`)

`StorePlay8ScreenshotsTest` renders the 8 Google Play phone scenes (01-chat, 02-mention, 03-thread,
04-tasks, 05-agent, 06-activity, 07-swipe, 08-home) in en, zh-Hans, zh-Hant and ja, with the app
in the default Raft theme, brand yellow (pixel decor off) and the demo cast Maya Chen, Leo Park, Forge, Scout
and the fixture agents. The app has no Traditional Chinese UI and a zh-TW device falls back to
English, so zh-Hant shows the English UI with the English demo content; only its framed copy is
Traditional (Play zh-TW and zh-HK). en and zh-Hant use the 12-hour clock. The chat lists start at
the same whole message in every language, and the frame reserves two headline lines and one
subline so the phone never moves between shots.

```bash
cd compose
# each language takes ~15 min, past the 60 s per-test hang watchdog: raise it
RAFT_STORE_SCREENSHOTS=/tmp/store-raw8 ./gradlew :shared:testDebugUnitTest \
  -Draft.test.watchdog.timeout.ms=1200000 --tests 'build.raft.app.StorePlay8ScreenshotsTest'
npm install --prefix /tmp/store-fonts @fontsource/noto-sans-tc   # zh-Hant headlines
python3 scripts/store-screenshots/frames.py --set play8 --raw /tmp/store-raw8 --out /tmp/play8 \
  --fonts /tmp/store-fonts/node_modules/@fontsource
```

Output: `<out>/<lang>/NN-scene.png` (1080×1920, RGB) and `<out>/contact-<lang>.png`. The copy
lives in `PLAY8_SCENES`.

## App Store set of 8 (`--set appstore8`)

`StorePlay8AppStoreScreenshotsTest` renders the Play 8 scenes (same demo content, Raft yellow) for
the App Store: `iphone` (430×836dp, every scene) and iPhone Duo on the current Duo layout:
`duo-outer` (466×678dp, the pushed pages 01, 02, 03, 05 beside the side column) and
`duo-inner-portrait` (669×907dp) / `duo-inner-landscape` (951×669dp), the desktop mode without
the top bar (rail with the server tile, Search first, the bell in the footer; 01, 02, 03, 04, 06).
zh-Hant uses en's renders (English UI). On iPhone the tab scenes render no Compose bar: the frame
draws the iOS 26 glass tab bar (Home, Activity, Tasks, Search + the server's round button), the
iOS status bar with the Dynamic Island and the home indicator.

```bash
cd compose
RAFT_STORE_SCREENSHOTS=/tmp/store-rawA8 ./gradlew :shared:testDebugUnitTest \
  -Draft.test.watchdog.timeout.ms=3600000 --tests 'build.raft.app.StorePlay8AppStoreScreenshotsTest'
python3 scripts/store-screenshots/frames.py --set appstore8 --raw /tmp/store-rawA8 --out /tmp/appstore8 \
  --fonts /tmp/store-fonts/node_modules/@fontsource
```

Output: `<out>/<device>/<lang>/NN-scene.png` for `iphone-6.9` (1320×2868), `iphone-6.5`
(1284×2778), `duo-outer`, `duo-inner-portrait`, `duo-inner-landscape`; `<out>/contact-<device>.png`
and `<out>/index.html`.
