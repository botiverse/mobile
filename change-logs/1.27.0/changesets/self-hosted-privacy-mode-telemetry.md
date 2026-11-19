---
type: fixed
area: diagnostics
platforms:
  - android
  - ios
pull_request: 5064
rollout: full
---

## zh-CN

（内部诊断）连接到开启隐私模式（`RAFT_PRIVACY_MODE`）的自托管服务器时，App 不再向 Raft 发送任何诊断追踪数据；在服务器告知是否开启之前，追踪数据只留在本机、不会发出。

## en

(Internal diagnostics) On a self-hosted server that runs in privacy mode (`RAFT_PRIVACY_MODE`), the app no longer sends any diagnostic traces to Raft; until the server says whether privacy mode is on, traces stay on the device and nothing is sent.

## ja

（内部診断）プライバシーモード（`RAFT_PRIVACY_MODE`）で動くセルフホストのサーバーでは、App が Raft に診断トレースを送らなくなりました。サーバーがプライバシーモードの有無を返すまで、トレースは端末内にとどまり送信されません。
