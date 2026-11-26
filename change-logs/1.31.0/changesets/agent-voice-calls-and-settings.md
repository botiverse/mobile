---
type: added
area: agents
platforms:
  - android
  - ios
  - desktop
rollout: flag
---

## zh-CN

在 `agent_voice_v1` 灰度开关下新增 Agent 语音设置与实时语音通话体验：
- Agent 资料页新增「语音」设置区块，支持即时切换「可以被询问」与「可以被呼叫」开关，并在下方查看最近「谁问过」记录（最多展示 10 条）。
- 符合条件的 Agent 私聊页顶栏与 Agent 资料页顶栏新增「通话」入口，点击后发起实时语音通话并保持应用级悬浮通话卡片（支持说话状态外圈、实时双行字幕、静音/扬声器切换、麦克风异常提示与挂断/关闭/重试），跨页面与跨服务器切换不断线。

## en

Added Agent Voice settings and live voice call UI behind the `agent_voice_v1` feature flag:
- Added a **Voice** section in the Agent Profile tab with immediate-save **Can be asked** and **Can be called** switches (with optimistic rollback and inline error reporting) plus the **Who asked** recent side-questions list (up to 10 entries).
- Wired Call entry points into eligible Agent DM headers and Agent Profile headers, backed by an app-wide floating call card with speaking ring, live 2-line captions, ticking call timer, mute/speaker/hang-up controls, mic warning banner, and 15-minute call limit handling that persists across navigation and server switches.

## ja

`agent_voice_v1` フラグ配下で Agent 音声設定とリアルタイム音声通話 UI を追加しました：
- エージェントのプロフィールに「音声」セクションを追加し、「質問を受け付ける」「通話を受け付ける」スイッチ（即時保存・失敗時ロールバック付き）と「質問した人」履歴（最大 10 件）を表示します。
- 対象エージェントとの DM ヘッダーおよびプロフィールヘッダーに「通話」ボタンを追加し、画面遷移やサーバー切り替えをまたいで維持されるフローティング通話カード（発話リング、最新 2 行の字幕、ミュート／スピーカー／通話終了操作、マイク警告バナー）を実装しました。
