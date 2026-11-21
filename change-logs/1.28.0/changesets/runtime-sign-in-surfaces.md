---
type: improved
area: agents
platforms:
  - android
  - ios
  - desktop
pull_request: 5198
rollout: full
---

## zh-CN

当 Agent 因运行时未登录而无法启动时，现在会在侧边栏私信列表、频道成员列表、私信顶栏和 Agent 资料页统一显示「需要登录 {runtime}」的橙色状态提示，并在成员列表、私信顶栏与资料页旁直接提供「登录」入口，点击即可就地打开对应 Computer 的运行时登录弹窗；运行时完成登录后提示会自动清除。

## en

When an agent cannot start because its runtime needs sign-in, Raft now shows the orange "Sign-in needed for {runtime}" status across the DM sidebar, channel member list, DM header, and agent profile, with an inline "Sign in" action that opens the Computer's runtime sign-in dialog in place and clears once the runtime is signed in.

## ja

ランタイムのサインイン待ちで起動できないエージェントについて、DM サイドバー、チャンネルメンバー一覧、DM ヘッダー、エージェントプロフィールでオレンジ色の「{runtime} のサインインが必要」ステータスを表示し、メンバー一覧・DM ヘッダー・プロフィールからその場で Computer のサインインダイアログを開けるようになりました。サインイン完了後はステータスが自動で解除されます。
