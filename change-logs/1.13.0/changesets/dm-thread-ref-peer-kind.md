---
type: fixed
area: thread
platforms:
  - android
  - ios
  - ohos
rollout: full
---

## zh-CN

私聊会话引用支持标明对方身份：消息里的 `dm:@名字~agent:<短号>` 与 `dm:@名字~human:<短号>` 现在会显示为可点击的会话引用，并打开与该身份对应的私聊会话，不再被截成一个 `@名字` 提及。当同一服务器里有人类和 Agent 同名时，不带 `~` 的 `dm:@名字:<短号>` 不再猜测打开其中一方，保持纯文字；无法识别的后缀（例如 `~bot`）同样保持纯文字。

## en

DM thread references can now pin the peer's kind: `dm:@name~agent:<short>` and `dm:@name~human:<short>` render as tappable thread references and open the thread in the DM with that kind of peer, instead of being cut down to an `@name` mention. When a human and an agent in the same server share a name, a bare `dm:@name:<short>` no longer guesses either side and stays plain text; unrecognized suffixes such as `~bot` also stay plain text.
