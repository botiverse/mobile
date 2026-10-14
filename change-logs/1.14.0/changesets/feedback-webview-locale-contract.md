---
type: fixed
area: settings
platforms: [android, ios, ohos]
rollout: full
pull_request: null
---

Fix the standalone feedback WebView showing "Open Feedback from the Raft app to continue." on most devices: the native bootstrap passed the raw `navigator.language` (e.g. `en-US`, `zh-cn`) as the session locale, which the Web host rejects — it only accepts `en` or `zh-CN`. The bootstrap now normalizes to the contract, so the page detects the app host and renders the feedback form.
