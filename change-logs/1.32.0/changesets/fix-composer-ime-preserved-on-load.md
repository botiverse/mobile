---
type: fixed
area: composer
platforms: [android, ios, desktop]
rollout: full
---

## zh-CN
修复进入仍在加载的频道、私聊、回复线程或任务对话时，先唤起输入法再等待消息刷新完成会导致输入法被意外关闭的问题：保持加载态与正式界面之间的输入框实例 ID、发送键配置与焦点监听节点稳定，消除草稿 key 细化时的单帧占位闪烁，并在权限尚未最终确认的过渡窗口内保留已聚焦的输入框与输入草稿。

## en
Fixed an issue where opening the keyboard while entering a loading channel, DM, reply thread, or task conversation could dismiss the IME once messages finished loading or refreshing. Keeps composer instance identity, send-key IME action, keyboard inset state, and focus-reporting modifier nodes stable across `LoadingShell` to `FeatureSurfaces` transitions, eliminates single-frame draft-key refinement placeholder flashes, and preserves the mounted composer across unresolved non-reply access windows without misdirecting sends or uploads.

## ja
読み込み中のチャンネル・DM・返信スレッド・タスク会話に入って先にキーボードを開いた際、メッセージの読み込み完了時に IME が意図せず閉じてしまう不具合を修正しました。読み込み画面から通常表示への遷移やドラフトキー確定時に、入力欄のインスタンス ID・送信キー設定・フォーカス監視ノードを安定して維持し、入力中の下書きとキーボード表示を途切れさせないようにしました。
