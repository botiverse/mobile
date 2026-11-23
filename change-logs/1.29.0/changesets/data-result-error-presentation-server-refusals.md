---
type: fixed
area: errors
platforms: [android, ios, desktop]
rollout: full
---

## zh-CN
- 修复操作被服务端拒绝时具体原因有时被通用「无权限」或「未找到」文案覆盖的问题：当服务端给出明确原因或已知限制时优先准确展示，同时继续屏蔽服务端内部故障与异常原始页面内容。

## en
- Fixed server refusal reasons sometimes being hidden behind generic permission or not-found messages: specific refusal explanations and known limits now surface accurately, while internal server faults and raw error pages stay hidden.

## ja
- 操作がサーバーに拒否された際、具体的な理由が汎用の「権限がありません」「見つかりません」メッセージで隠れてしまう不具合を修正しました（サーバー内部エラーや生のエラー画面は引き続き非表示にします）。
