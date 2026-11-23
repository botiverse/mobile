---
type: changed
area: attachments
platforms:
  - android
  - ios
  - desktop
pull_request: 5519
rollout: full
---

## zh-CN

无法直接预览的文件（包括无法加载的图片、表格及不受支持的文件）底部的「在浏览器中打开」改为「在其他应用中打开」，通过系统打开方式或分享面板交给其他应用处理，不再跳转系统浏览器触发重新登录；当文件已下载（或桌面端已显示打开按钮）时不再重复显示该按钮。

## en

Replaced "Open in browser" on file preview fallback cards (unsupported files, unavailable images, and spreadsheet fallbacks) with "Open in another app", handing the file off through the system open-with or share sheet instead of opening an unauthenticated browser tab. The secondary button is hidden once the file is downloaded or when Desktop saved-file actions already show Open.

## ja

プレビューできないファイル（表示できない画像、スプレッドシートのフォールバック、非対応ファイル）の下部アクションを「ブラウザで開く」から「他のアプリで開く」に変更しました。システムブラウザへ遷移して再ログインを求められる代わりに、システムのアプリ選択／共有シートでファイルを開けます。ダウンロード済みの場合や、デスクトップ版で保存済みファイルの「開く」が表示されている場合は重複ボタンを表示しません。
