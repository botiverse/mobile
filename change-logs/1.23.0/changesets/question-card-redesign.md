---
type: changed
area: thread
platforms:
  - ios
  - android
pull_request: null
rollout: full
---

## zh-CN

agent 的提问卡片换了新样子，和网页一致。只有一个单选题、选项又少又短时，不再显示卡片：问题作为消息的最后一行加粗显示，下面是一排选项按钮和「其他」，点一下就提交（仍可在 1.5 秒内撤销）；按钮下方的小字写着「Fixer 在等你回答 · 还剩 7 天」。被问的不是你时，按钮不可点，小字显示「等待 Maya 回答」；回答后按钮换成一行「✓ Maya 回答了：…」。其他提问卡片变得更简洁：去掉了图标和「回答问题」标题，只有一个问题时问题本身就是标题，有多个问题时标题为「Fixer 有 3 个问题」，「问」一栏改为标题下的一行说明（「Fixer 在问你 · 还剩 7 天」或「等待 Maya」），选项直接放在卡片里，单个问题不再显示必填星号；回答后收起为一行「Maya：…」，右侧显示绿色的「已回答」。过期或被撤回的提问卡片保持提问时的样子，不再显示任何按钮或选项，原来的位置只显示一行状态，例如「Maya 在 3 天内没有回答，已告知 Fixer。」或「Fixer 撤回了问题。」。「其他…」改为「其他」。

## en

Agents' question cards have a new look, matching the web. A card with one single-choice question and a few short options is no longer a card: the question is the last line of the message in semibold, followed by a row of option buttons and "Other". Tapping one sends it (you can still undo for 1.5 seconds), and a small line underneath reads "Fixer is waiting for your answer · 7 days left". If the question is for someone else, the buttons are disabled and the line reads "Waiting for Maya to answer"; once answered, the buttons are replaced by "✓ Maya answered …". Every other question card is slimmer: no icon and no "Answer Question" title, a single question is the title itself (several read "Fixer has 3 questions"), the "For" row becomes a line under it ("Fixer is asking you · 7 days left" or "Waiting for Maya"), the options sit directly in the card, and a single question has no required asterisk. Once answered, it collapses to one line, "Maya: …", with a green "Answered". Expired and withdrawn question cards keep the shape they had when asked, with no buttons or options: one status line says what happened, such as "Maya didn't answer within 3 days. Fixer was told." or "Fixer withdrew this question." "Other…" now reads "Other".

## ja

エージェントの質問カードのデザインを Web に合わせて一新しました。単一選択の質問が 1 つだけで、選択肢が少なく短い場合はカードではなくなり、質問がメッセージの最後の行として太字で表示され、その下に選択肢のボタンと「その他」が並びます。ボタンを押すとすぐに送信されます（1.5 秒以内なら取り消せます）。ボタンの下には「Fixer があなたの回答を待っています · 残り 7 日」と表示されます。質問の相手が自分でない場合はボタンを押せず、「Maya の回答を待っています」と表示されます。回答後はボタンの代わりに「✓ Maya の回答：…」の 1 行になります。それ以外の質問カードはよりシンプルになり、アイコンと「質問に回答」のタイトルがなくなりました。質問が 1 つならその質問が、複数なら「Fixer から 3 件の質問」がタイトルになり、「宛先」の行はタイトルの下の説明（「Fixer があなたに質問しています · 残り 7 日」または「Maya の回答待ち」）に変わります。選択肢はカードの中に直接並び、質問が 1 つのときは必須のアスタリスクを表示しません。回答後は「Maya：…」の 1 行に折りたたまれ、右側に緑色の「回答済み」が表示されます。期限切れや取り下げになった質問カードは質問時の形のまま、ボタンや選択肢は表示されず、「Maya は 3 日 以内に回答しませんでした。Fixer に伝えました。」や「Fixer が質問を取り下げました。」のような 1 行の状態表示になります。「その他…」は「その他」になりました。
