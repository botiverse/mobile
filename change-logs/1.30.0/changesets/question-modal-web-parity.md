---
type: improved
area: thread
platforms:
  - android
  - ios
  - desktop
rollout: full
---

## zh-CN

对齐网页端问题卡片（`question:ask`）弹窗表单交互：单选题目点选选项后高亮 150 ms 自动进入下一题或提交，选择「其他」后聚焦输入框并支持按完成键推进；多选保持手动点选与「下一步」推进；翻页加入 500 ms 防误触保护；提交进入 1.5 秒「已提交 · 撤销」窗口，撤销可继续编辑、中途关闭会立即发送；单页单选隐藏冗余提交按钮；支持按步骤草稿与页码恢复，以及无草稿时的放弃修改确认。

## en

Aligned the `question:ask` modal form sheet with the web interaction model: tapping a single-choice option highlights it for 150 ms before advancing to the next question or submitting, choosing "Other" focuses its input and advances on Done, multiple-choice waits for Next/Submit, page turns apply a 500 ms settle guard against stray double-taps, submitting enters a 1.5 s "Submitted · Undo" window (cancelling cleanly on Undo and flushing immediately if closed), lone single-choice forms hide the redundant commit button until "Other" is chosen, and mid-way drafts/pages persist per card and form with a discard prompt when unsaved.

## ja

質問カード（`question:ask`）のモーダルフォームをウェブ版の操作モデルに揃えました。単一選択は選択後に 150 ms ハイライトしてから次の質問または送信へ自動で進み、「その他」を選ぶと入力欄にフォーカスして完了キーで進めます。複数選択は「次へ」/送信ボタンで進み、ページ切り替え時には 500 ms の誤タップ防止ガードを適用します。送信時は 1.5 秒の「送信済み · 元に戻す」待機に入り、元に戻すと編集を再開でき、待機中に閉じると即座に送信されます。単一選択のみのフォームでは「その他」を選ぶまで送信ボタンを非表示にし、途中入力とページ位置の下書き保持および未保存時の破棄確認も備えました。
