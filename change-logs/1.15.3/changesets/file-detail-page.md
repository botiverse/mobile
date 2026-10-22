---
type: improved
area: messages
platforms:
  - android
  - ios
pull_request: 3501
rollout: full
---

## zh-CN

点文件先进文件详情页，在详情页下载并显示进度。点对话里的文件（例如 apk、zip）不再直接开始下载：能预览的文件照常打开预览，其他文件打开详情页，显示文件名、大小和类型图标，点「下载」后显示进度，可暂停、取消，失败可重试；下载完成后可直接「打开」（Android 上 apk 会打开系统安装程序）或「分享」。已下载过的文件进入详情页时直接显示打开和分享。从详情页开始的下载在对话中的文件卡片上也会显示进度。

## en

Tapping a file opens its detail page, and the download happens there with progress. Tapping a file in a conversation (an apk or zip, for example) no longer starts a download right away: files the app can preview still open their preview, and other files open a detail page with the file's name, size and type icon. Download shows its progress there, with Pause, Cancel and Retry on failure; once done you can Open the file (an apk opens the Android installer) or Share it. A file you already downloaded shows Open and Share straight away. While a download from the detail page runs, the file's card in the conversation shows its progress too.
