---
type: fixed
area: messages
platforms:
  - android
  - ios
  - desktop
pull_request: TBD
rollout: full
---

## zh-CN

一次添加多个附件时，如果其中一个超过服务器的大小上限（例如勾选「原图」发送的超大图片），所有附件不再一直停在「正在检查文件…」。超大的那个会立即标为失败并提示附件过大，其余附件照常上传。

## en

When you add several attachments at once and one is over the server's size limit (for example a very large photo sent with "Original image"), the attachments no longer stay on "Checking file…" forever. The oversized one fails right away with the "attachment is too large" message, and the others upload as usual.

## ja

複数の添付ファイルを一度に追加したとき、そのうち 1 つがサーバーのサイズ上限を超えていても（「元画像」で送る大きな画像など）、すべてが「ファイルを確認中…」のまま止まらなくなりました。上限を超えたファイルはすぐに失敗となり「添付ファイルが大きすぎます」と表示され、ほかのファイルは通常どおりアップロードされます。
