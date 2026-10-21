# 1.15.2 release plan

## Owner and intent

- Release owner: @artin. Mobile DRI: @Lagoon. Release operator: @Codex-Android-DevOPS.
- Goal (artin, 2026-10-21): fix every known issue overnight and hand artin internal
  Android and iOS builds on the morning of 2026-10-22 (Asia/Shanghai).
- Delivery intent: Android Hands drafts on `main` (same APK also on `preview`) and
  iOS TestFlight internal group Botiverse. Activation, Google Play and external
  TestFlight each need the owner's approval.

## Dates (UTC)

| Step | Date |
|---|---|
| Scope lock / branch cut | 2026-10-22 ~04:00–05:00, at the `main` commit Lagoon names |
| Internal builds | 2026-10-22 morning, Asia/Shanghai |

## Coordinates

- Android versionCode starts at `11502001`.
- iOS 1.15.2, build numbers from 1.
- Android and iOS are built from the same exact source on `release/1.15.2`.

## Scope (from #wg-mobile, 2026-10-21 22:04Z; changesets by Lagoon)

| Owner | Items |
|---|---|
| Lagoon | assistant-sent marker (task #6), large-screen sidebar and split view, attachment download page and download center, Activity re-tap jumps to next unread (#3420), cache work, pinyin input fix (#3439) |
| sindresorhus | Activity parity (#3412, #3414, #3431), raft-ui 0.18 tokens (#3413), input border (#3416), server switch button (#3403), iOS tab bar dark/light (#3428), black screen on server switch (#3434), Android fullscreen video rotation (#3436), Home menu wording, Mermaid fullscreen swipe-back, dead image overlay removal |
| HanXin | Activity preload and live replies (#3389, #3391), no 0.5 s wait on local data (#3417), DM unread after leaving (#3430), forward search on the server (#3441), unified agent status (#3442), runtime form v2 default (#3396), preload pause on open (#3444) |
| Codex-Android-DevOPS | long-press select all / partial menu (#3411), partial reply quote (#3432), iOS hides Background realtime (#3426), Multiple app windows on iPad only (#3427) |

Not in scope: Android widget status colour, Activity deep link to the reply (task #14 last step).

## Device checks needed on the 1.15.2 builds

#3411, #3426, #3427, #3428, #3432, #3436, #3439 have no device verification yet.

## Exceptions

None yet.
