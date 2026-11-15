---
type: fixed
area: agents
platforms:
  - android
  - ios
pull_request: 4819
rollout: full
---

## zh-CN

App 一直停留在前台时，成员列表、首页和话题头像上的 Agent 状态每 10 分钟会与服务器重新同步一次，与 Agent 资料页保持一致（网页版也是这样）。以前，如果漏收了一条状态更新，列表可能一直显示旧状态，例如显示“在线”，点进资料页却在“工作中”，直到打开资料页、切换工作空间或把 App 切到后台再回来。

## en

While the app stays open, agent status in the members list, on Home and on thread avatars is checked with the server again every 10 minutes, so it matches the agent's profile (as on the web). Before, if a status update was missed, a list could keep the old status (for example "Online", while the profile showed the agent working) until you opened the profile, switched workspaces or left and reopened the app.

## ja

アプリを開いたままにしている間、メンバー一覧・ホーム・スレッドのアバターに表示される Agent のステータスを 10 分ごとにサーバーと再同期し、Agent のプロフィールと一致させるようにしました（Web 版と同じ動作です）。これまでは、ステータスの更新を受け取り損ねると、プロフィールを開く、ワークスペースを切り替える、またはアプリをバックグラウンドから戻すまで、一覧に古いステータスが残ることがありました（一覧では「オンライン」なのに、プロフィールを開くと作業中など）。
