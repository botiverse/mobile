---
type: improved
area: auth
platforms:
  - android
  - ios
  - desktop
rollout: full
---

## zh-CN

用户名与 Agent 名称的实时校验现已与网页端一致：支持 UTS #39 单文字体系限制（禁止混用不同文字体系，允许拉丁字母与中/日/韩文组合）、按用户/Agent 分层的保留名称及相似字符（confusable）拦截，并补齐服务端相似名称拒收时的本地化提示文案。

## en

Inline username and agent name validation now matches web: enforces UTS #39 Highly Restrictive single-script rules (allowing Latin combined with CJK), checks two-tier reserved handles including confusable look-alikes, and localizes server-side confusable handle refusals.

## ja

ユーザー名およびエージェント名のリアルタイム検証をウェブ版と揃え、UTS #39 単一文字体系ルール（英字と日中韓文字の組み合わせは許可）、階層別予約名・類似文字（confusable）チェック、およびサーバー側の類似名拒否エラーの多言語表示を追加しました。
