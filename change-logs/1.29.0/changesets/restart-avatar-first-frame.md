---
type: improved
area: avatars
platforms:
  - android
  - ios
  - desktop
pull_request: 5395
rollout: full
---

## zh-CN

优化了重启应用首帧的头像与服务器图标展示：成员头像、跨服联合频道成员头像以及服务器栏图标现在会在启动时从本地持久化记录同步还原，并将最近使用过的磁盘缓存图片预热到内存缓存中，首帧即可直接显示真实头像，不再短暂闪烁首字母或默认占位图标；成员列表尚未加载完成时的 Agent 头像也会保持静默占位，避免误闪默认图标。

## en

Improved first-frame avatar and server icon rendering after app restart: member avatars, joint-channel peer avatars, and server rail icons now restore synchronously from durable local storage and warm recently used bitmaps from disk cache into memory at startup so real avatars paint immediately without flashing initials or fallback glyphs; bare agent avatars also stay quiet until the server roster settles.

## ja

アプリ再起動時の初回フレームにおけるアバターとサーバーアイコンの表示を改善しました。メンバーアバター、ジョイントチャンネルの外部メンバーアバター、サーバーレールのアイコンをローカルの永続ストレージから同期復元し、起動時にディスクキャッシュからメモリキャッシュへ事前読み込みすることで、イニシャルやデフォルトアイコンの一瞬のちらつきを解消しました。メンバー一覧の取得前もエージェントアバターが誤ってデフォルトアイコンにちらつかないようにしています。
