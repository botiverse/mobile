---
type: fix
---

`.ts` attachments no longer render as video players. Android's MIME table guesses `.ts` as `video/mp2t` and the extension map also had `ts` → MPEG-TS, so TypeScript files opened a black "视频播放失败" screen instead of a text preview. `.ts`/`.tsx` now resolve to text/code; `.m2ts`/`.mts` (unambiguous video containers) still route to the video player.
