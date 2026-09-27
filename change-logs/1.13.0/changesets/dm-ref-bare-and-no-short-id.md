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

私聊引用与网页一致：消息里的 `dm:@名字`、`dm:@名字~agent`、`dm:@名字~human`（不带会话短号）现在会显示为可点击的引用，点开进入对应的私聊；带 `~agent` / `~human` 时只打开该身份的私聊。当人类和 Agent 同名时，不带 `~` 的 `dm:@名字` 与 `dm:@名字:<短号>` 也可点击，按私聊列表顺序打开第一个匹配的私聊。短号格式不对时（例如 `dm:@名字:12345`），前面的 `dm:@名字` 仍可点击，后缀保持纯文字。

## en

DM references now match the web app: `dm:@name`, `dm:@name~agent` and `dm:@name~human` without a thread short id render as tappable references that open the DM, and a `~agent` / `~human` suffix opens only the DM with that kind of peer. When a human and an agent share a name, a bare `dm:@name` or `dm:@name:<short>` is tappable too and opens the first matching DM in DM-list order. If the short id is malformed (for example `dm:@name:12345`), the leading `dm:@name` still links and the suffix stays plain text.
