---
type: improved
area: computers
platforms:
  - android
  - ios
  - desktop
rollout: full
---

## zh-CN

补充了三项与 Web 及服务端对齐的细节优化：
- Antigravity 用量查询返回暂不支持时，用量卡片和弹窗现在会显示「根据条款暂不支持查询」提示，且不再提供「再查一次」按钮。
- 账户设置中的「用 Raft 登录过的 App」现在会区分通过卡片互动认识你的应用，在行说明和撤销确认弹窗中显示对应卡片文案。
- `pi-sdk` 智能体的只读配置详情现在会正确解析并显示 Provider 行。

## en

Added three Web/backend parity polish improvements:
- Antigravity usage cards and sheets now show the localized unavailable-under-terms notice (with no Check again button) when usage lookup returns `unsupported` while signed in.
- Settings › Account › App Logins now distinguishes apps known through a card (`kind: "card"`), showing card-specific row descriptions and revoke confirmation copy across all 8 locales.
- Read-only agent profile runtime details now recognize `pi-sdk` alongside `pi` when parsing and displaying the `pi-builtin` Provider row.

## ja

Web・バックエンドとの整合性を高める 3 つの改善を追加しました。
- Antigravity の使用量照会が `unsupported` を返す場合、使用量カードおよびシートに利用規約による非対応メッセージを表示し、「もう一度確認」ボタンを非表示にしました。
- アカウント設定の「Raft でログインしたアプリ」で、カード利用によって認識されたアプリ（`kind: "card"`）に対してカード専用の行説明と取り消し確認文言を表示するようにしました。
- エージェント詳細の読み取り専用ランタイム設定で、`pi-sdk` ランタイムでも `pi-builtin` の Provider 行を正しく解析・表示するようにしました。
