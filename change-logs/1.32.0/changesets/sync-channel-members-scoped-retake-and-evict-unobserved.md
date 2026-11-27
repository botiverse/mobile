---
type: fixed
area: performance
platforms: [android, ios, desktop]
rollout: full
---

## zh-CN
修复了长驻进程在断网恢复或增量同步重试时，单个频道成员拉取失败触发 `sync_changes_retake snapshots=channel_members` 进而把历史上打开过的全部数十个频道成员串行重拉（引发上百次网络请求与解析抖动）的问题。现在同步仅立即重拉当前仍在展示/观察中的频道成员列表，其余未打开的后台历史缓存直接失效待下次进入时按需拉新，且失败重试精确收敛到失败频道本身，不再连带全服重扫。

## en
Fixed an issue where a single transient channel-member refresh failure during incremental sync triggered `sync_changes_retake snapshots=channel_members` across all historically opened channels in a long-lived session (causing a burst of serial network fetches and parses). Incremental sync now immediately refetches only actively observed channel rosters, invalidates unobserved cached slices so their next open fetches fresh data, and scopes failure retakes to the exact failed channel IDs instead of rescanning the entire server.

## ja
長時間稼働したセッションでネットワーク復帰や差分同期のリトライ時に、1 チャンネルのメンバー取得失敗が `sync_changes_retake snapshots=channel_members` を誘発し、過去に開いた全チャンネルのメンバーを直列に再取得してしまう問題を修正しました。現在は現在表示・監視中のチャンネルのみ即座に再取得し、非表示のキャッシュは次回開いた際に最新取得されるよう無効化し、失敗時の再試行も失敗したチャンネル ID のみに限定します。
