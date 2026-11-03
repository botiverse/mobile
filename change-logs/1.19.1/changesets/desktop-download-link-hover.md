---
type: improved
area: attachments
platforms:
  - android
  - ios
  - desktop
pull_request: https://github.com/botiverse/mobile/pull/4200
rollout: full
---

## zh-CN

电脑版：文件预览页下载时显示进度；离开预览页后下载继续，窗口底部的下载栏显示文件名和进度（多个下载时显示“2 个下载”），完成后显示“已保存到 Downloads”和“在访达中显示”，失败时可重试。预览页保存后按钮变为“在访达中显示”（Windows/Linux 为“在文件夹中显示”）并新增“打开”，用默认应用打开文件；本次使用期间再次打开该文件也会保持这一状态（文件被移走或删除后恢复为“下载”）。电脑版以及接鼠标或触控板的 iPad、Android 设备上，鼠标停在消息里的链接上片刻，窗口左下角会显示链接去向：外部链接显示完整网址并带 ↗，Raft 内部链接显示目标（如“#proj-mobile · 消息”）或其网页地址。

## en

Desktop: the file preview's Download shows its progress, and the download keeps going after you leave the page. A bar at the bottom of the window shows the file and its progress ("2 downloads" when there are several), then "Saved to Downloads" with "Show in Finder", or the failure with Retry. On the preview, the button becomes "Show in Finder" ("Show in folder" on Windows and Linux) with an "Open" button that opens the file in its default app. Reopening the file during the same session keeps this, until the file is moved or deleted. On desktop, and on iPad or Android with a mouse or trackpad, resting the pointer on a link in a message shows where it goes at the window's bottom-left: the full address with ↗ for outside links, or the target (such as "#proj-mobile · message") or its web address for Raft links.

## ja

デスクトップ版：ファイルプレビューのダウンロード中は進捗を表示し、ページを離れてもダウンロードは続きます。ウィンドウ下部のバーにファイル名と進捗（複数なら「2 件のダウンロード」）、完了後は「Downloads に保存しました」と「Finder で表示」、失敗時は再試行を表示します。プレビューではボタンが「Finder で表示」（Windows/Linux では「フォルダで表示」）に変わり、既定のアプリで開く「開く」ボタンも表示されます。同じセッション中にファイルを開き直してもこの状態が保たれます（ファイルを移動・削除すると「ダウンロード」に戻ります）。デスクトップ版と、マウスやトラックパッドを使う iPad・Android では、メッセージ内のリンクにポインタを置くとウィンドウ左下に行き先が表示されます。外部リンクは ↗ 付きの完全な URL、Raft のリンクは行き先（例：「#proj-mobile · メッセージ」）または Web アドレスです。
