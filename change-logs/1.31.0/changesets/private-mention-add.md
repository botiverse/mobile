---
type: added
area: chat
platforms: [android, ios, desktop]
rollout: flag
---

## zh-CN
在 `private_mention_add_v0` 功能开关开启的服务器中，具备添加成员权限的用户在未归档的私有频道或联合频道输入 `@` 时，候选列表会补充展示同服未进频道成员（「不在此频道」分组，不含频道外访客）；发送后若返回仅可添加的待处理提及条，会提示对方在加入频道后才能看到这条消息。

## en
On servers with `private_mention_add_v0` enabled, members who can add members to an unarchived private or joint channel now see same-server non-members (excluding outsider guests) under "Not in this channel" in the `@mention` picker; add-only pending mention strips clarify that the target cannot see the message until added.

## ja
`private_mention_add_v0` が有効なサーバーでは、アーカイブされていないプライベートチャンネルまたはジョイントチャンネルでメンバー追加権限を持つユーザーが `@` を入力すると、同じサーバー内の未参加メンバー（外部ゲストを除く）も「このチャンネルにいません」に表示されます。追加のみ可能な未処理メンションバーでは、追加されるまでメッセージを見られない旨を表示します。
