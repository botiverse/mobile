---
type: fixed
area: composer
platforms:
  - android
  - ios
  - desktop
pull_request: 4785
rollout: full
---

## zh-CN

修复了应用启动时无法读取已保存的登录信息后，重新登录同一账号仍会删除尚未发出的消息的问题。现在重新登录同一账号会保留这些消息和草稿；登录其他账号时才会清除。另外，草稿页在账号准备好之前不再短暂显示“没有草稿”，无法读取登录信息时电脑列表也不会被清空。

## en

Fixed unsent messages that were still deleted when you signed in to the same account again after the app could not read the saved sign-in at startup. Signing in to the same account now keeps them, and your drafts; they are cleared only when a different account signs in. Also, the Drafts page no longer shows "No drafts" for a moment before your account is ready, and the computer list is no longer cleared when the sign-in cannot be read.

## ja

起動時に保存されたサインイン情報を読み取れなかった後、同じアカウントで再度サインインしても未送信のメッセージが削除されていた問題を修正しました。同じアカウントでサインインし直すと、これらのメッセージと下書きは保持されます。別のアカウントでサインインした場合にのみ消去されます。また、アカウントの準備ができる前に下書きページに一瞬「下書きはありません」と表示されることがなくなり、サインイン情報を読み取れないときにコンピューターの一覧が消去されることもなくなりました。
