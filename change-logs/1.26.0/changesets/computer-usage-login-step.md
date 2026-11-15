---
type: changed
area: computers
platforms:
  - android
  - ios
  - desktop
pull_request: 4839
rollout: full
---

## zh-CN

与网页版一致：当某台计算机上某个运行时的登录需要验证或重新登录、且有 Agent 在用它时，「计算机」卡片和列表里该运行时的用量格会显示「验证」或「登录」（橙色，不显示用量条），而不是用量。旧版服务器不受影响。各选择器里的用量说明也改为与网页一致的「已用完 · …重置」，重置时间的算法与用量卡片相同。

## en

Matching the web: when a runtime's sign-in on a Computer needs verifying or signing in again and agents use it, that runtime's usage cell on the Computers cards and list says "Verify" or "Sign in" (in orange, no bar) instead of the usage. Older servers are unaffected. The pickers' usage line now reads "used up · resets …" like the web, counting the reset as the usage card does.

## ja

ウェブ版と同じく、コンピューター上のランタイムのログインに確認や再ログインが必要で、エージェントがそれを使っている場合、コンピューターのカードと一覧にあるそのランタイムの使用量欄に、使用量ではなく「確認」または「ログイン」が表示されます（オレンジ、バーなし）。古いサーバーでは変わりません。各ピッカーの使用量の行も、ウェブ版と同じ「上限到達 · リセット：…」になり、リセット時刻は使用量カードと同じ方法で数えます。
