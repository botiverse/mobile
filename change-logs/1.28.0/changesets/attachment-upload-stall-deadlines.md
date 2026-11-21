---
type: fixed
area: composer
platforms:
  - android
  - ios
rollout: full
---

## zh-CN

网络没有应答时，一起选择的多个图片或文件不再一个接一个地排队：查询上传限制的请求超时后（最多 30 秒），所有在等它的附件会同时改用普通方式继续上传，而不是每个再重新查询、在“正在检查文件…”上各多等一次。较大文件上传的最后一步（服务器确认文件已存好）现在最多等待 45 秒，与网页端一致，服务器较慢时不会被提前中断；仍然没有应答时，附件显示“上传失败”和“重试”。

## en

When the network stops answering, images or files picked together no longer wait in line one after another: once the check of your upload limits runs out of time (within 30 seconds), every attachment waiting on it goes on with a regular upload at the same time, instead of each asking again and waiting once more on "Checking file…". The last step of a large file's upload, where the server confirms the file is stored, now waits up to 45 seconds, as on the web, so a slow server is no longer cut off early; if there is still no answer, the attachment shows "Upload failed" with Retry.

## ja

ネットワークが応答しないとき、まとめて選んだ画像やファイルが一つずつ順番待ちになることはなくなりました。アップロード上限の確認が時間切れになると（最大 30 秒）、それを待っていたすべての添付が同時に通常のアップロードで続行し、それぞれが確認をやり直して「ファイルを確認中…」のまま再び待つことはありません。大きなファイルのアップロードの最後の手順（サーバーがファイルの保存を確認する段階）は、ウェブ版と同じく最大 45 秒待つようになり、サーバーの応答が遅くても途中で打ち切られません。それでも応答がない場合は、「アップロードに失敗しました」と「再試行」が表示されます。
