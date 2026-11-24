---
type: improved
area: billing
platforms:
  - android
  - ios
  - desktop
rollout: full
---

## zh-CN

适配服务端下发的按成员计费（`person_v2`）Pro 方案：账单页与席位明细会按当前服务器的计费规则隐藏不再占席位的 Agent 项，升级弹窗从服务端读取真实定价与说明，创建 Agent 时在按成员计费下不再受席位占用拦截，处理席位申请卡片时会先按对应服务器的计费规则核查缺口并在无需加购时直接完成。

## en

Added support for server-resolved per-person Pro pricing (`person_v2`) across Plan & Billing, the upgrade dialog, agent creation, and seat-request action cards: billing breakdowns omit agents when they take no seats, the upgrade dialog loads pricing from the server instead of guessing, agent creation no longer blocks on universal seats under per-person pricing, and seat-request cards check the card's server pricing before opening checkout (completing immediately when no seats are needed).

## ja

サーバー側で決定される 1 人あたり課金（`person_v2`）の Pro プランに対応しました。プランと請求画面では席を消費しないエージェントの内訳を非表示にし、アップグレード画面は推測せずサーバーから最新の料金と説明を取得して表示します。また、1 人あたり課金ではエージェント作成が席数上限でブロックされず、シート追加リクエストカードは対象サーバーの料金ルールで不足数を確認し、追加不要な場合はそのまま完了できるようになりました。
