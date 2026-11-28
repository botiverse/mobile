---
type: fixed
area: feedback
platforms:
  - android
  - ios
  - desktop
rollout: full
---

## zh-CN

按当前 API 环境动态解析客户端埋点与问题反馈上传地址：Staging 环境（`api-aws-staging.botiverse.dev`）改用同域 `/__trace` 接收端，生产环境（`api.raft.build`、`api.slock.ai`）继续使用生产接收端，自建/自定义/本地开发服务器则跳过上传，修复 Staging 环境向生产接收端上报返回 401 的问题，并在运行时切换环境后即时生效。

## en

Resolved client trace (`/api/web-traces`) and agent issue feedback (`/api/reports`) upload URLs dynamically from the active API environment: staging (`api-aws-staging.botiverse.dev`) now uploads to `https://api-aws-staging.botiverse.dev/__trace`, production (`api.raft.build`, `api.slock.ai`) continues to use the production receiver, and custom/self-hosted/local servers cleanly skip uploads instead of spamming 401s across environments.

## ja

クライアントトレース（`/api/web-traces`）およびエージェント不具合報告（`/api/reports`）のアップロード先を現在の API 環境から動的に解決するように修正しました。ステージング環境（`api-aws-staging.botiverse.dev`）は `/__trace` 受信エンドポイントを使用し、本番環境（`api.raft.build`・`api.slock.ai`）は従来の本番受信先を使用します。カスタム／セルフホスト／ローカルサーバーではアップロードを安全にスキップし、環境切替時の 401 エラーを解消します。
