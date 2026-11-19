---
type: fixed
area: privacy
platforms:
  - android
  - ios
pull_request: 5078
rollout: full
---

## zh-CN

（隐私 / 诊断）连接到开启隐私模式（`RAFT_PRIVACY_MODE`）的自托管服务器时，智能体资料页不再显示“报告问题”，问题报告的诊断包也不会再上传到 Raft；在服务器告知是否开启隐私模式之前，这个入口同样隐藏。如果报告面板已经打开时得知隐私模式已开启，面板会说明原因，且无法提交。

## en

(Privacy / diagnostics) On a self-hosted server that runs in privacy mode (`RAFT_PRIVACY_MODE`), an agent's profile no longer offers Report issue and its diagnostics bundle is never uploaded to Raft; the entry also stays hidden until the server says whether privacy mode is on. If the report panel is already open when privacy mode turns out to be on, it says why and can't be sent.

## ja

（プライバシー / 診断）プライバシーモード（`RAFT_PRIVACY_MODE`）で動くセルフホストのサーバーでは、エージェントのプロフィールに「問題を報告」が表示されなくなり、診断バンドルが Raft にアップロードされることもなくなりました。サーバーがプライバシーモードの有無を返すまでも、この入口は表示されません。報告パネルを開いている間にプライバシーモードがオンだとわかった場合は、パネルに理由が表示され、送信できません。
