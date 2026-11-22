---
type: fixed
area: thread
platforms: [android, ios, desktop]
rollout: full
---

## zh-CN

优化定位到指定消息打开已缓存频道与讨论串的首屏体验：内存已有该会话且动态列表已有目标新消息时，首帧直接展示内容而不闪加载提示；读取本地缓存中的目标消息附近记录时完整保留会话标题、原消息与输入框状态，避免界面闪烁。

## en

Eliminate loading and composer flashes when jumping to a specific message in a cached channel or thread: conversations now display immediately on the first frame when Activity already has the target message, and local cached message lookups preserve conversation details, parent messages, and composer state while refreshing.

## ja

キャッシュ済みのチャンネルやスレッドの特定メッセージへ移動して開く際のちらつきを改善しました。アクティビティに対象メッセージがある場合は最初のフレームから即座に表示し、ローカル履歴から対象メッセージ付近を読み込む際も会話ヘッダーや親メッセージ、入力欄の状態を保持します。
