---
type: improved
area: agents
platforms:
  - android
  - ios
  - desktop
pull_request: 5283
rollout: full
---

## zh-CN

Agent「动态」标签页的概览里，连续两轮及以上什么都没做就出错结束的回合（没调用工具、没发消息、过程中也没有出错的步骤）现在与网页端一样合并为一条，显示这段时间和最后一轮的标签。额度用完后每小时的重试显示为「额度已用完 · 11-08 15:00 恢复 · 共尝试 3 次」（没有报告恢复时间时为「额度已用完 · 共尝试 N 次」），其他原因显示「连续 N 轮出错结束，都没调用工具，也没发消息」。点「展开每一轮」逐轮查看，点「收起」收回。额度用完的重试和其他出错不会合在一起；Raft 版本较旧的电脑上的回合按其他出错合并。

## en

In an agent's Activity overview, two or more turns in a row that ended with an error having done nothing (no tool used, no message sent, no step gone wrong) now fold into one entry, as on the web, showing their span and the last turn's badges. Hourly retries after a used-up usage limit read "Usage limit reached · resets 11-08 15:00 · 3 attempts" ("Usage limit reached · N attempts" when no reset time was reported); other failures read "N turns in a row ended with an error, with no tools used and no message sent". Show each turn lists them one by one, and Hide turns folds them again. Usage-limit retries and other failures are never folded together; turns from Computers on an older Raft fold as other failures.

## ja

エージェントの「アクティビティ」タブの概要で、何もせずにエラーで終了したターン（ツール呼び出しなし、メッセージ送信なし、途中でエラーになったステップなし）が 2 回以上続くと、Web と同様に 1 つの項目にまとめ、その期間と最後のターンのバッジを表示するようになりました。利用上限に達した後の 1 時間ごとの再試行は「利用上限に達しました · 11-08 15:00 にリセット · 3 回試行」（リセット時刻が報告されていない場合は「利用上限に達しました · N 回試行」）、それ以外は「N ターン続けてエラーで終了（ツール呼び出しなし、メッセージ送信なし）」と表示されます。「各ターンを表示」で 1 ターンずつ確認でき、「閉じる」で元に戻ります。利用上限による再試行とそれ以外のエラーが一緒にまとめられることはありません。Raft が古いコンピューターのターンは、それ以外のエラーとしてまとめられます。
