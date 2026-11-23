---
type: fixed
area: errors
platforms: [android, ios, desktop]
rollout: full
---

## zh-CN
- 修复 403、404 等业务拒绝提示有时被通用文案覆盖的问题：当服务端返回具体原因或明确业务错误码时优先准确展示，同时继续屏蔽 5xx 内部报错、HTML 报错页与堆栈内容。

## en
- Fixed 403 and 404 server refusal reasons sometimes being hidden behind generic permission or not-found text: specific refusal explanations and known refusal codes now surface accurately, while 5xx faults, HTML error pages, and stack traces remain hidden.

## ja
- 403・404 のサーバー拒否理由が汎用メッセージで隠れてしまう不具合を修正し、具体的な拒否理由や既知のエラー種別を正確に表示するようにしました（5xx エラーや HTML エラーページ、スタックトレースは引き続き非表示にします）。
