---
type: fixed
area: agents
platforms:
  - android
  - ios
  - desktop
pull_request: 4697
rollout: full
---

## zh-CN

Agent 的 Claude Code 因为以 root 或 sudo 权限运行而无法启动时，Agent 页面和动态记录会说明原因，并提示改用非 root 用户运行 Raft Computer，不再只显示「离线」。频道里还有访客时，断开共享频道会提示先移除访客或归档这个频道，不再显示服务器的英文报错。在没有配置 RisingWave 的自托管服务器上，「动态」和未读数会显示「暂时不可用」，不再显示笼统的加载失败。

## en

When an agent's Claude Code can't start because it runs with root or sudo privileges, the agent page and its activity now explain why and tell you to run Raft Computer as a non-root user. Before, they only showed "Offline". If you disconnect a shared channel that still has guests, the app now tells you to remove the guests first or archive the channel, instead of showing the server's English error. On self-hosted servers without RisingWave, Activity and unread counts now say they're temporarily unavailable instead of showing a generic loading failure.

## ja

エージェントの Claude Code が root または sudo 権限で実行されていて起動できない場合、エージェントのページとアクティビティに理由が表示され、root 以外のユーザーで Raft Computer を実行するよう案内されるようになりました。以前は「オフライン」とだけ表示されていました。ゲストがいる共有チャンネルの接続を解除しようとすると、先にゲストを削除するかチャンネルをアーカイブするよう案内されるようになりました。サーバーの英語のエラーは表示されなくなりました。RisingWave を設定していないセルフホストのサーバーでは、アクティビティと未読数が「一時的に利用できません」と表示されるようになりました。汎用的な読み込み失敗は表示されなくなりました。
