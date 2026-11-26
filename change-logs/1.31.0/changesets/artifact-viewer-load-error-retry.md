---
type: fix
platforms: [android, ios, desktop]
---
Fixed Artifact viewer staying stuck indefinitely on the loading screen ("Fetching page" / "正在获取页面") when the WebView encounters a main-frame network or connection error; it now immediately shows the error state with a Retry button.
