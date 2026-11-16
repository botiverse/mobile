---
type: added
area: search
platforms:
  - android
  - ios
  - desktop
pull_request: 4888
rollout: full
---

## zh-CN

消息列和任务里的「搜索消息列」/「搜索任务」现在会搜索整个对话，而不只是已加载的消息：输入时会读取更早和更新的消息，期间计数显示「加载中」，之后显示「当前/总数」（没有结果时为 0/0）。上一个 / 下一个会循环跳转，跳到尚未加载的消息时会先加载它周围的消息，当前匹配的消息会高亮并标出匹配的词。新增关闭按钮，按 Esc 或返回也可关闭，再次打开时保留上次的搜索词，与网页版一致。读取失败时会提示错误并附带 Trace ID。

## en

Search in thread (and Search in task) now searches the whole conversation, not only the messages already loaded: as you type it reads the older and newer messages, the count says "Loading" meanwhile, then "n/N" (0/0 with no match). Previous and Next wrap around, and a match that isn't loaded yet is loaded with the messages around it before the view jumps there; the current match is highlighted with its words marked. The bar has a Close button, Esc or Back also close it, and opening it again keeps your words, as on the web. If reading the conversation fails, an error with its trace ID says so.

## ja

スレッド内検索（タスク内検索）が、読み込み済みのメッセージだけでなく会話全体を検索するようになりました。入力すると以前と以降のメッセージを読み込み、その間カウントは「読み込み中」、その後は「n/N」（一致なしは 0/0）と表示されます。前へ / 次へは端で折り返し、まだ読み込まれていない一致はその周辺のメッセージを読み込んでから移動します。現在の一致はハイライトされ、一致した語に印が付きます。閉じるボタンを追加し、Esc や戻る操作でも閉じられます。再度開くと前回の検索語が残ります（ウェブ版と同じ）。読み込みに失敗した場合は Trace ID 付きのエラーが表示されます。
