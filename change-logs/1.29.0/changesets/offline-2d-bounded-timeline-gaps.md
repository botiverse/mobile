---
type: improved
area: offline
platforms:
  - android
  - ios
  - desktop
pull_request: 5368
rollout: full
---

## zh-CN

优化离线和弱网下的会话消息读取体验：频道和讨论串不再整段载入全部缓存消息，而是按当前浏览位置分页读取本地 SQLite 消息窗口，并在尚未同步的历史消息区间之间显示分段间隔提示，点击即可补拉，离线时也会明确提示。

## en

Improved offline and weak-network conversation reading by paging bounded local SQLite message windows around the current viewport instead of hydrating full cached timelines at once, and displaying inline gap rows between unsynced history segments with tap-to-load and offline status hints.

## ja

オフラインや不安定な通信環境での会話表示を改善しました。チャンネルやスレッドのキャッシュ済み履歴を一括読み込みせず、表示位置周辺のローカル SQLite ウィンドウをページ単位で読み込み、未同期の履歴区間にはタップで読み込みできるギャップ行とオフライン時の案内を表示します。
