# Bundled UI fonts — provenance

All bundled Latin UI faces are SIL Open Font License 1.1.

| Family | Option it serves | Source | License |
|---|---|---|---|
| Space Grotesk (300–700) | "Space Grotesk + Space Mono" | https://fonts.googleapis.com/css2?family=Space+Grotesk:wght@400;500;600;700 | `SpaceGrotesk-OFL.txt` / OFL 1.1 |
| Space Mono (400/700) | "Space Grotesk + Space Mono" (mono) | https://fonts.googleapis.com/css2?family=Space+Mono:wght@400;700 | `SpaceMono-OFL.txt` |
| Hanken Grotesk (300–700) | "Hanken Grotesk" (default; matches web `brutal`) | https://github.com/google/fonts/tree/main/ofl/hankengrotesk (`HankenGrotesk[wght].ttf`) | `HankenGrotesk-OFL.txt` |
| Geist (300–700) | "Geist + Inter" (body; matches web `elegant` sans) | https://github.com/google/fonts/tree/main/ofl/geist (`Geist[wght].ttf`) | `Geist-OFL.txt` |
| Inter (300–700) | "Geist + Inter" (heading; matches web `elegant` heading) | https://github.com/google/fonts/tree/main/ofl/inter (`Inter[opsz,wght].ttf`) | `Inter-OFL.txt` |
| Geist Mono (400/700) | "Hanken Grotesk" and "Geist + Inter" (mono) | https://github.com/google/fonts/tree/main/ofl/geistmono (`GeistMono[wght].ttf`) | `GeistMono-OFL.txt` |

The variable sources were instanced to static weights (300/400/500/600/700) with
`fonttools varLib.instancer` so they match the existing per-weight Space Grotesk layout
(Android `res/font`, OHOS `base/profile` + `resfile/fonts`, iOS `Fonts/`).
Inter was instanced at `opsz=14` (the UI text optical size).
