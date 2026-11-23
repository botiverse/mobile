---
type: fixed
area: computers
platforms: [android, ios, desktop]
rollout: full
---

## zh-CN
- 修复在电脑页执行运行环境登录、登出、供应商连接、安装/升级、配额重置以及电脑远程升级/重启被服务端拒绝时，只显示泛化错误或缺少 Trace ID 的问题，在保留已知错误本地化文案与配额重置安全提示的前提下透出服务端具体拒绝原因。

## en
- Fixed runtime sign-in, sign-out, provider connection, install/upgrade, limit reset, and remote Computer upgrade or restart refusals showing only a generic error or omitting the Trace ID, surfacing specific server refusal reasons while preserving localized known-error copy and warnings when a limit reset outcome is uncertain.

## ja
- コンピューター画面でランタイムのサインイン・サインアウト・プロバイダー接続・インストール/アップグレード・上限リセットや、コンピューターのリモートアップグレード/再起動がサーバーに拒否された際、汎用エラーのみが表示されたり Trace ID が欠落する不具合を修正し、既知エラーのローカライズ文言と上限リセットの安全性警告を保ったまま具体的な拒否理由を表示するようにしました。
