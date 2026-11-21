---
type: improved
area: diagnostics
platforms:
  - android
  - ios
  - desktop
rollout: full
pull_request: 5237
---

## zh-CN

（内部诊断）输入框里的附件上传结束时，诊断记录会注明与服务器协商上传所花的时间（查询上传限制、创建上传会话、第一次确认上传），与网页端的记录方式一致。不包含文件名、文件内容或链接。上传本身的行为没有变化。

## en

(Internal diagnostics) When an attachment upload from the composer ends, the diagnostic record now says how long the upload's calls to the server took (checking upload limits, creating the upload session, the first request to confirm it), measured as on the web. It holds no file name, file content or link. Uploading itself behaves as before.

## ja

（内部診断）入力欄からの添付ファイルのアップロードが終わったとき、サーバーとのやり取り（アップロード上限の確認、アップロードセッションの作成、最初の完了確認）にかかった時間を、ウェブ版と同じ方法で診断記録に残すようにしました。ファイル名、ファイルの内容、リンクは含みません。アップロードの動作は変わりません。
