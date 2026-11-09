---
type: changed
area: artifacts
platforms:
  - android
  - ios
pull_request: null
rollout: full
---

## zh-CN

网页版现在会把正在查看的 Artifact 写进地址（Artifacts 页面上的 `?artifact=…`，选了某个版本时还带 `&version=…`）。在 App 里点这样的链接，或从别处打开它，会直接在 App 的 Artifact 查看器里打开，并停在链接里的版本；没写版本时打开最新版。另外，头像不再使用 Gravatar：没有上传头像的人显示默认头像。

## en

The web app now keeps the open Artifact in its address (`?artifact=…` on the Artifacts page, plus `&version=…` when a version is picked). Tapping such a link in the app, or opening one from elsewhere, now opens it in the app's Artifact viewer at that version, or at the latest version when the link has none. Avatars also no longer come from Gravatar: people without an uploaded avatar show the default one.

## ja

Web 版は、開いている Artifact をアドレスに入れるようになりました（Artifacts ページの `?artifact=…`、バージョンを選んだときは `&version=…` も付きます）。アプリでこのリンクをタップするか、ほかの場所から開くと、アプリの Artifact ビューアでそのバージョンが開きます。バージョンがないリンクでは最新版が開きます。また、アバターに Gravatar を使わなくなりました。アバターをアップロードしていない人にはデフォルトのアバターが表示されます。
