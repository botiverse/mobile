---
type: fixed
area: network
platforms: [android, ios]
rollout: all
---

## zh-CN
修复了在两个服务器之间快速切换时，排队中或触发 401 刷新重试的请求会带上新服务器 `X-Server-Id`、导致接口误报「不在此频道/无权限」的问题：现在网络层会在请求发起时捕获当前账号与服务器，并在调度排队与 401 刷新重试期间沿用同一个 `X-Server-Id`，同时保留显式 `serverIdOverride` 覆盖与原有 `expectedAuthority` 切服拦截语义。

## en
Fixed an issue where rapidly switching servers could cause queued requests or 401 token-refresh retries to pick up the newly selected server's `X-Server-Id` and fail with false channel-membership errors: the mobile network layer now captures the originating server scope at request initiation and preserves it across scheduler queuing and 401 refresh retries, while preserving explicit `serverIdOverride` precedence and existing `expectedAuthority` switch rejection.

## ja
サーバーを素早く切り替えた際、キュー待機中または 401 トークン更新リトライ中のリクエストが新しいサーバーの `X-Server-Id` を送信し、誤ってチャンネル未所属エラーになる問題を修正しました。ネットワーク層でリクエスト開始時のサーバーを固定し、キュー待機や 401 リトライでも同じ `X-Server-Id` を維持します（明示的な `serverIdOverride` と既存の `expectedAuthority` チェックは従来どおり維持されます）。
