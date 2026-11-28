---
type: fixed
area: navigation
platforms:
  - android
  - ios
rollout: behind-flag
---

## zh-CN
将手机端底部服务器面板（`tab_bar_places_v0`）中的「最近使用」分组移至面板最顶部，位于服务器列表与「更多」分组之前（为空时不显示且不出现多余分隔线）；同时按规范的 Artifact ID（将 22 位 Base58 短码与 `pt_<uuid>` 统一归一化）和 App clientId 对最近使用记录去重，并在读取历史缓存时自动清理重复条目。

## en
Moved the "Recently used" section to the very top of the phone Server tab popover (`tab_bar_places_v0`), above the server list and "More" section (staying omitted with no leading divider when empty), and fixed duplicate Artifact entries by canonicalizing Base58 short IDs and `pt_<uuid>` IDs (as well as App client IDs) on both write and persisted-storage decode so existing duplicates self-heal automatically.

## ja
モバイルのサーバータブポップオーバー（`tab_bar_places_v0`）で「最近使った項目」を最上部（サーバー一覧と「その他」の上、空の場合は区切り線ごと非表示）へ移動し、Artifact の 22 文字 Base58 短縮 ID と `pt_<uuid>`（および App の clientId）を保存時・復元時の両方で正規化して重複エントリを解消しました。
