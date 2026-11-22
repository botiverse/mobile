---
type: improved
area: attachments
platforms:
  - android
  - ios
  - desktop
pull_request: TBD
rollout: full
---

## zh-CN

在会话消息、文件列表或图片查看器中下载附件时，下载任务现在统一交由应用级下载中心托管：离开当前会话后下载会继续进行，电脑版窗口底部的下载栏会同步显示进度、“已保存到 <文件夹>”与“在访达中显示”/“在文件夹中显示”以及失败重试，并且不再重复弹出通用保存提示；同一附件在会话与文件详情页之间共享下载与已保存状态。

## en

Attachment downloads started from conversation messages, the Files tab, or the image viewer now run through the app-scoped download centre: downloads keep going after you leave the conversation, the desktop window download bar shows live progress, "Saved to <Folder>" with "Show in Finder" / "Show in folder", and Retry on failure without a duplicate generic saved toast, and the conversation and file detail page share the same download and saved-file state.

## ja

会話メッセージ、ファイル一覧、または画像ビューアから開始した添付ファイルのダウンロードもアプリ共通のダウンロードセンターで管理されるようになりました。会話を離れてもダウンロードは継続し、デスクトップ版のウィンドウ下部バーに進捗、「<フォルダ> に保存しました」と「Finder で表示」／「フォルダで表示」、失敗時の再試行が表示され、重複する保存トーストは出なくなります。会話とファイル詳細ページでも同じダウンロード・保存状態が共有されます。
