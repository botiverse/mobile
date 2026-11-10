---
type: added
area: members
platforms:
  - android
  - ios
  - desktop
pull_request: 4478
rollout: full
---

## zh-CN

智能体停下来、要有人手动重启才能继续时（出错、自动唤醒已暂停、启动失败且服务器已停止自动重试），智能体资料页的状态旁会出现「重试」，点一下就会重启它，和「操作」里「重启 / 重置」中的重启一样，不需要再确认。只有能重启这个智能体的人，并且它所在的 Computer 在线时才会看到。遇到限流错误，或者只有「重置会话」才能解决的启动失败，不会显示「重试」。

如果智能体所在 Computer 的访问权限被撤销了，它的状态现在会显示「Computer 访问权限已撤销：请把 Agent 移到另一台 Computer（重新加入会新增一台 Computer）」，而不是普通的离线状态，成员列表、私信和资料页都是这样。在资料页的「Agent 迁移」里点「迁移到另一台计算机」并选择目标，就能把它移过去：这种情况不需要 Pro 套餐，它的文件无法带走，会在新 Computer 上开始新的会话。它的工作区页面也会直接说明这一点，不再显示「重试」。

## en

When an agent has stopped and needs someone to restart it (an error, paused automatic wakes, or a failed start the server stopped retrying), its profile now shows Retry next to its status. Tapping it restarts the agent straight away, the same restart as in Actions › Restart / reset. Only people who can restart the agent see it, and only while its Computer is online. A rate-limit error, or a failed start that only Reset session can fix, doesn't show Retry.

If an agent's Computer had its access revoked, its status now reads "Computer access revoked: move the agent to another computer (re-attaching adds a new one)" instead of an ordinary offline status, in member lists, DMs and on its profile. To move it, tap "Move to another computer" under Agent migration on its profile and pick a computer. This doesn't need the Pro plan; its files can't be brought over, and it starts a fresh session on the new computer. Its Workspace tab says the same and no longer offers Retry.

## ja

エージェントが停止していて、誰かが手動で再起動しないと動かないとき（エラー、自動起動の一時停止、サーバーが自動での再試行をやめた起動失敗）、プロフィールのステータスの横に「再試行」が表示されるようになりました。タップすると、「アクション」の「再起動 / リセット」と同じ再起動をすぐに行います。このエージェントを再起動できる人にだけ、そのコンピューターがオンラインのときに表示されます。レート制限のエラーや、「セッションをリセット」でしか直らない起動失敗には表示されません。

エージェントのコンピューターのアクセスが取り消された場合、ステータスは通常のオフライン表示ではなく「コンピューターのアクセスが取り消されました。エージェントを別のコンピューターに移動してください（再接続すると新しいコンピューターとして追加されます）」と表示されるようになりました。メンバー一覧、DM、プロフィールのいずれでも同じです。移動するには、プロフィールの「エージェントの移行」で「別のコンピューターに移行」をタップし、コンピューターを選びます。この場合は Pro プランは不要で、ファイルは移せず、新しいコンピューターで新しいセッションを始めます。ワークスペースのタブにも同じ説明が表示され、「再試行」は表示されなくなりました。
