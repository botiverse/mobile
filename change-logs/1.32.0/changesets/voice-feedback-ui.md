---
type: added
area: composer
platforms: [android, ios]
rollout: full
---

## zh-CN
接入了语音听写与智能体语音通话反馈界面，并打通输入框发送后的隐式编辑指标上报：在开启 `voice_feedback_sample_v0` 且关闭隐私模式的内部 Server 上，语音听写结束后显示可关闭的「报告问题」轻量反馈条（支持可选标签、备注及默认不勾选的样本上报授权）；在非隐私模式下，智能体语音通话结束后可在悬浮通话卡内一键点赞/点踩并按需补充问题标签与备注，支持重复提交覆盖及本地化错误提示。

## en
Added mobile UI wiring and composer outcome reporting for voice dictation and Agent Voice call feedback. Sending a dictated message now reports zero-text implicit edit metrics (`sentAsIs`, `editRatio`, `sentBeforeRefineDone`) under strict `RAFT_PRIVACY_MODE` gating; internal servers with `voice_feedback_sample_v0` enabled show a dismissible post-dictation "Report issue" strip with unchecked-by-default sample consent; and ended Agent Voice call cards show 1-tap Thumbs Up / Thumbs Down feedback with idempotent overwrite and optional tags/note when privacy mode is off.

## ja
音声ディクテーションおよびエージェント音声通話のフィードバック UI と、コンポーザー送信時の編集指標レポートを接続しました。ディクテーション後にメッセージを送信すると、プライバシーモードがオフの場合のみ本文を含まない編集指標を送信します。`voice_feedback_sample_v0` が有効かつプライバシーモードがオフの社内サーバーでは、ディクテーション完了後に「問題を報告」バー（デフォルト未チェックのサンプル添付同意付き）を表示し、エージェント音声通話終了後のカードではワンタップの評価と任意のタグ・メモによる上書き送信に対応しました。
