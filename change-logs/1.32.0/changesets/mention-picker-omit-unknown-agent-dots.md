---
type: fixed
area: composer
platforms: [android, ios]
rollout: full
---

## zh-CN
`@` 提及选择器（以及成员/指派选择器）在 Agent 的在线状态尚未可知时不再显示灰色离线圆点或空角标占位，也不再把跨服/受限视图中缺少实时活动数据的 Agent 误判为「可能在线」；已知在线、工作中、离线或外部 Agent 仍正常显示对应状态圆点。

## en
The `@` mention picker (and member/assignee pickers) now omits the status dot and badge shell for agents whose presence is unknown instead of painting a gray offline dot, and stops guessing "Likely online" for cross-server or restricted channel-member rows without live activity.

## ja
`@` メンションピッカー（およびメンバー／担当者ピッカー）で、オンライン状態が未確定の Agent に灰色のオフラインドットや空バッジを表示しないようにし、リアルタイム活動情報のない他サーバー／制限ビューの Agent を「オンラインの可能性」と誤判定しないようにしました。
