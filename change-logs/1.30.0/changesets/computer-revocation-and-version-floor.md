---
type: improved
area: settings
platforms:
  - android
  - ios
  - desktop
rollout: full
---

## zh-CN

被撤销密钥的 Computer 详情页现显示「密钥已撤销」状态、断开日期与撤销原因（包括退出/移出服务器、账号删除、手动撤销、邮件撤销链接、本机断开、登录退出、批准登录安全退出及服务器重置等原因）、受影响的离线 Agent 数量以及带 `--machine` 的重新加入与迁走 Agent 恢复步骤，Agent 详情页的「Computer 访问权限已撤销」状态下方也同步展示对应的原因与恢复说明；同时将内置 Computer 版本下限对齐至 `1.0.53`，对于低于 `1.0.53` 在连接时被拒的离线 Computer，详情页版本提示新增 npm / Homebrew 先行卸载提醒并保留安装命令，通知中心也会对在线或 7 天内被拒的机器展示一次性重新安装/迁移提醒。

## en

Revoked Computers now display a "Key revoked" status on the Computer detail page with a warning banner showing when and why the key was revoked, how many agents are offline, and recovery steps to reattach the same Computer with `--machine` or move its agents, with the matching cause and recovery note shown underneath the updated "Computer access revoked" status on each affected agent's page. Aligned the built-in Computer version floor to `1.0.53`: the version notice on offline/below-floor Computers now explains that re-running the install command keeps the same Computer and its agents (including the npm / Homebrew uninstall-first note), and the Home notification center surfaces one-time reinstall/migration notices for machines that are online or were refused at connect within the last 7 days.

## ja

キーが取り消された Computer の詳細画面に「キー取り消し済み」ステータスと切断日・取り消し理由・オフラインのエージェント数、`--machine` 付きの再接続手順およびエージェント移動案内を表示し、各エージェント詳細画面の「コンピューターのアクセスが取り消されました」表示の下にも同じ理由と復旧案内を追加しました。また、組み込みの Computer バージョン下限を `1.0.53` に合わせ、下限未満で接続拒否されたオフライン Computer でもインストールコマンド再実行案内（npm / Homebrew の事前アンインストール注意を含む）を表示し、オンラインまたは直近 7 日以内に接続拒否されたマシンを通知センターの再インストール／移行通知に表示するようにしました。
