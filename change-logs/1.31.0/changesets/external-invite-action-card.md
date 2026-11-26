---
type: added
area: channels
platforms:
  - android
  - ios
  - desktop
rollout: full
---

## zh-CN

原生支持由 Agent 发起的外部邀请操作卡片（`channel:invite_external`）：服务器所有者或管理员可点击「邀请」预填邮箱并打开外部邀请弹窗，在卡片无邮箱时点击「连接服务器」直接打开连接自有服务器弹窗（无可用服务器时显示清晰提示），也可点击「拒绝」关闭卡片；发送邀请或连接服务器后自动将卡片标记为已处理，其余成员与跨服务器视角按只读态展示。

## en

Added native support for agent-prepared external invite action cards (`channel:invite_external`): Server owners and admins can tap **Invite people** to fetch the card's email addresses on click and open the prefilled external invite sheet, tap **Connect servers** on zero-address cards to open **Connect your servers** directly (with a clear empty state when no connectable server is available), or **Decline** the card. Completing an invite or connecting a server marks the card handled automatically, while non-permitted and cross-server viewers see the view-only state.

## ja

エージェントが作成した外部招待アクションカード（`channel:invite_external`）にネイティブ対応しました。サーバーのオーナーまたは管理者は「招待」からタップ時にメールアドレスを取得して入力済みの外部招待シートを開けるほか、メールアドレスが 0 件のカードでは「サーバーを接続」から直接「所属しているサーバーを接続」シートを開けます（接続可能なサーバーがない場合は案内を表示）。また「拒否」でカードを閉じることができ、招待の送信またはサーバーの接続が完了するとカードは自動的に対応済みになります。権限のないメンバーや他サーバーからは閲覧専用として表示されます。
