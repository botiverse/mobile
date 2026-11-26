---
type: fixed
area: agents
platforms:
  - android
  - ios
  - desktop
rollout: full
---

## zh-CN

修复了需要人工处理的 Agent 在各状态点与详情徽标上的展示一致性，并补齐了模型移除就绪状态：当 Agent 因就绪状态受阻（Computer 离线除外）或因不可自动重试的启动失败（如模型不支持、需登录、需重置会话）处于离线且未手动停止时，侧边栏、成员列表、私聊页头、资料卡片与 Computer 页面都会统一亮起橙色错误灯并展示简短状态词与完整说明；当已配置的模型不再由该 Computer 的运行时提供时，支持区分「模型不可用」阻塞态与热会话存活期间的仅所有者提示态，并提供「更换模型」入口，同时避免在需重置会话时误显示「重试」按钮。

## en

Aligned agent attention lights and status labels across every surface and added `model_unavailable` readiness handling: agents blocked by readiness (except `computer_offline`) or offline after a non-retryable start failure (such as unsupported model, sign-in required, or session reset needed) now show the orange error light with concise status titles and full detail copy across the sidebar, member lists, DM header, profile preview, agent profile, and Computer rows; agents whose configured model left their Computer's catalog now display `Model unavailable` (blocked) or an owner-only advisory note while a warm session is live with a "Change model" action; missing readiness resource keys are guarded safely, and `session_needs_reset` no longer shows a spurious Retry link.

## ja

人の対応が必要なエージェントのステータスドットと詳細バッジの表示を全画面で統一し、`model_unavailable` レディネス対応を追加しました。レディネスがブロック状態（Computer オフラインを除く）または自動再試行できない起動失敗（非対応モデル、サインイン必須、セッションリセット必須など）によりオフラインのエージェントは、サイドバー・メンバー一覧・DM ヘッダー・プロフィール・Computer 一覧のすべてでオレンジ色のエラーライトと短いステータス語・詳細説明を表示します。設定済みモデルがその Computer のランタイムから削除された場合は、ブロック時の「モデルを利用できません」およびライブセッション中のオーナー向け案内と「モデルを変更」リンクを表示し、セッションリセットが必要な場合に誤って「再試行」リンクが出ないよう修正しました。
