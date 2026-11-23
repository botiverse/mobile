---
type: fixed
area: layout
platforms:
  - android
  - ios
  - desktop
pull_request: 5531
rollout: full
---

## zh-CN

在平板和桌面端的宽屏分栏视图中，所有并排显示的分栏（会话、回复串、任务与文件、Artifact、成员资料与频道详情）顶部第一条水平分隔线现已统一与会话顶栏底边对齐（触控 56dp、鼠标或触控板 44dp）：

- 当任务与文件分栏在较矮窗口中切换为单卡切换条模式时，顶部的“任务 / 文件”切换条与旁边会话顶栏保持同一高度和底边线。
- 当宽屏窗口较窄、多个分栏折叠为页签切换时，会话、回复串、成员资料和频道详情均在各自顶栏内直接显示分栏页签，不再在顶栏上方额外叠加一条高度不齐的页签条；Artifact 分栏上方的独立页签条也统一为与顶栏一致的高度，加载中与加载完成保持同一布局。

## en

In the wide split view on tablets and desktops, the first horizontal divider at the top of every side-by-side column (conversation, reply thread, Tasks and Files, Artifact, member profile, and channel details) now aligns with the bottom line of the conversation header under both touch and mouse/trackpad input:

- When the Tasks and Files column switches to its single-card switcher in a short window, the top Tasks / Files bar now matches the conversation header's height and bottom line.
- When a crowded split view folds columns into row tabs, the conversation, reply thread, profile, and channel details headers now display those tabs inline inside the header instead of stacking an extra tab strip above it; the standalone tab strip above the Artifact column also matches the header height so loading and loaded states share one layout.

## ja

タブレットおよびデスクトップのワイド分割表示で、横に並ぶすべてのカラム（会話、返信スレッド、タスクとファイル、Artifact、メンバープロフィール、チャンネル詳細）の最上部の水平区切り線が、タッチ操作でもマウス／トラックパッド操作でも会話ヘッダーの下端と揃うようになりました。

- ウィンドウの高さが低いときにタスクとファイルカラムが単一カード切替モードへ移行した場合、上部の「タスク / ファイル」切替バーが隣の会話ヘッダーと同じ高さ・下端ラインに揃います。
- 分割表示が狭くなりカラムがタブに折りたたまれる場合、会話・返信スレッド・プロフィール・チャンネル詳細ではヘッダー内に直接タブを表示し、ヘッダーの上に別のタブバーを重ねなくなりました。Artifact カラム上部の独立したタブバーもヘッダーと同じ高さに揃い、読み込み中と読み込み後で同じレイアウトを保ちます。
