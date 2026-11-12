---
type: changed
area: ui
platforms:
  - android
  - ios
  - desktop
pull_request: 4631
rollout: full
---

## zh-CN

做不了的操作不再用一行字解释原因：设备没有通知权限设置时，「通知」里不再显示权限那一行；「发送测试通知」一开始可以点；如果发送时发现通知未被允许，这一行会变为不可点，并提示"允许 Raft 发送通知后，即可发送测试。"；通知渠道被屏蔽时，这一行保留同样的提示并可再试一次；设备无法发送测试时不显示这一行。设备不提供存储信息时，设置里不显示「存储」。应用还不能执行的操作卡片，按钮改为「在网页版中打开」，直接打开这条消息的网页版。桌面版无法在应用内播放的媒体或显示的网页内容，改为「在浏览器中打开」按钮，用浏览器打开。

## en

Actions that can't work here no longer come with a line explaining why. Notifications no longer shows the permission row on a device without a permission setting. Send test notification starts enabled. If a send finds notifications aren't allowed, the row turns disabled with "Allow notifications for Raft to send a test.". If the notification channel is blocked, it keeps that line and can be tried again. A device that can't send a test doesn't show the row. Settings no longer lists Storage on a device that doesn't report it. An action card this app can't run now offers Open on the web, which opens that message on the web. On desktop, media the app can't play and web content it can't show offer Open in browser (web links only) instead of a notice.

## ja

ここではできない操作に、理由を説明する一文を添えないようにしました。通知の権限設定がないデバイスでは、「通知」に権限の行を表示しません。「テスト通知を送信」は最初は押せます。送信時に通知が許可されていないと分かると「テストを送信するには、Raft の通知を許可してください。」と表示して押せない状態になります。通知チャンネルがブロックされている場合は同じ案内を表示したまま再試行できます。テストを送信できないデバイスでは表示しません。ストレージ情報を報告しないデバイスでは、設定に「ストレージ」を表示しません。アプリで実行できないアクションカードには「Web 版で開く」が表示され、そのメッセージを Web 版で開けます。デスクトップ版では、アプリ内で再生できないメディアや表示できない Web コンテンツに、案内文の代わりに「ブラウザで開く」ボタンを表示します。
