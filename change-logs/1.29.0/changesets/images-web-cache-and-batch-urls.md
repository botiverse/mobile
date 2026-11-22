---
type: improved
area: attachments
platforms:
  - android
  - ios
  - desktop
pull_request: 5394
rollout: full
---

## zh-CN

优化了消息图片与附件的加载和缓存体验：聊天中滚入视野的附件图片会合并为一次批量请求解析链接，不再逐张串行等待；已在聊天中加载过的原图打开全屏预览或大图查看时直接命中本地缓存，即使 5 分钟预签名链接过期也不会重新下载；同时遵循服务端 `Cache-Control` / `Expires` 缓存头并在链接失效时自动刷新重试。

## en

Improved image and attachment loading and caching: inline image attachments scrolling into view now resolve their URLs in a single batched request instead of sequentially; original images already loaded in chat reuse Coil's memory and disk cache in full-screen preview and lightbox even after presigned URLs rotate; and disk freshness now respects server `Cache-Control` / `Expires` headers while automatically invalidating expired URLs on load errors.

## ja

メッセージ内の画像・添付ファイルの読み込みとキャッシュを改善しました。チャット画面に表示される添付画像の URL を 1 回のバッチリクエストでまとめて解決するようになり、チャット上で読み込み済みの元画像は署名付き URL が更新されたあとも全画面プレビューやライトボックスでローカルキャッシュを再利用します。また、サーバーの `Cache-Control` / `Expires` ヘッダーに従ってキャッシュ鮮度を管理し、リンク失効時はエラー時に自動で再取得します。
