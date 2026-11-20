---
type: added
area: diagnostics
platforms:
  - android
  - ios
  - desktop
pull_request: 5169
rollout: full
---

## zh-CN

（内部诊断）在输入框里上传附件时，App 会记录这次上传的时间花在哪里：准备文件、发送数据、等待服务器或存储的应答，以及发送的数据大小和应答的边缘节点。记录写入本机诊断日志，并在服务器未开启隐私模式时作为诊断事件上传；不包含文件名、文件内容或链接。上传本身的行为没有变化。

## en

(Internal diagnostics) When you upload an attachment from the composer, the app records where that upload's time went: preparing the file, sending the data, and waiting for the server or the storage to answer, with the size of the data sent and the edge location that answered. The record goes to the on-device diagnostics log and, unless the server runs in privacy mode, is uploaded as a diagnostic event; it holds no file name, file content or link. Uploading itself behaves as before.

## ja

（内部診断）入力欄から添付ファイルをアップロードするとき、そのアップロードの時間がどこにかかったかを記録します。ファイルの準備、データの送信、サーバーまたはストレージの応答待ち、および送信したデータのサイズと応答したエッジ拠点です。記録は端末内の診断ログに残り、サーバーがプライバシーモードでなければ診断イベントとして送信されます。ファイル名、ファイルの内容、リンクは含みません。アップロードの動作は変わりません。
