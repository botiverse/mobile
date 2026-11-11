---
type: fixed
area: composer
platforms:
  - android
  - ios
pull_request: 4596
rollout: full
---

## zh-CN

输入框上方待发送附件的上传状态不再溢出：图片缩略图上的「正在完成上传…」「正在检查文件…」「上传失败」等文字始终留在缩略图内，放不下时换行或以省略号结尾，不再被屏幕边缘截断或盖住旁边的内容。完成上传时进度条改为来回滑动，表示正在等待服务器确认。

## en

The upload status on attachments waiting above the message box no longer spills out: "Finishing upload…", "Checking file…", "Upload failed" and the other labels stay inside the image tile, wrapping or ending in "…" when they don't fit, instead of being cut off at the screen edge or covering what's next to them. While an upload finishes, its bar now sweeps to show it is waiting for the server to confirm.

## ja

メッセージ入力欄の上にある送信待ち添付ファイルのアップロード状態がはみ出さなくなりました。「アップロードの完了処理中…」「ファイルを確認中…」「アップロードに失敗しました」などの表示は画像タイルの中に収まり、入りきらない場合は折り返すか「…」で省略されます。画面の端で切れたり、隣の内容に重なったりすることはありません。アップロードの完了処理中は、サーバーの確認を待っていることを示すため、バーが左右に流れるようになりました。
