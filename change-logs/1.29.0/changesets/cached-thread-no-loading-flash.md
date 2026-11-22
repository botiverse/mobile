---
type: fixed
area: thread
platforms: [android, ios, desktop]
pull_request: 0
rollout: full
---

## zh-CN
修复打开已缓存的频道、私聊或讨论串时首帧短暂闪烁「加载中…」的问题：后台有新消息更新内存缓存版本时首帧直接渲染已有缓存消息并在后台无感刷新；冷启动或内存淘汰后从本地数据库读取缓存期间隐藏短暂的加载态提示，仅在真正需要等待网络加载时显示。

## en
Fix cached channels, DMs, and threads briefly flashing "Loading…" when opened: conversations with an in-memory cache superseded by newer background messages now render cached messages immediately on Frame 1 while refreshing in the background, and cold/evicted local-database opens defer the loading indicator briefly so fast local cache reads never flash a spinner.

## ja
キャッシュ済みのチャンネル・DM・スレッドを開く際に「読み込み中…」が一瞬ちらつく問題を修正しました。バックグラウンドの新着メッセージでメモリキャッシュが更新待ちになった場合でも初回フレームで既存キャッシュを即座に表示して裏側で更新し、コールドスタートやメモリ退避後のローカルデータベース読み込み中も短い猶予時間を設けて不要なスピナー表示を防ぎます。
