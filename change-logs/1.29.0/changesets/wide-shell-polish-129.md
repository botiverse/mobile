---
type: fixed
area: layout
platforms:
  - android
  - ios
  - desktop
pull_request: 5573
rollout: full
---

## zh-CN

修复了大屏宽屏模式下的几处界面细节：宽屏「任务」页顶栏改为白底、不再与顶部黄色导航栏叠成双层黄条；宽屏「成员」分栏右侧详情去掉多余的手机返回箭头；宽屏「制品」页标题和数量对齐到同一行；平板竖屏电脑卡片不再把系统名截成「Ubuntu 2…」；频道右侧「详情」里的「成员」标题不再被截断；从宽屏聊天框切到其它左侧栏标签或返回历史页面时自动收起软键盘。

## en

Fixed several wide-screen layout and interaction issues: the wide Tasks tab header now sits on the canvas surface instead of stacking a second yellow band under the top bar; the wide Members split detail pane no longer shows a phone Back arrow beside the member list; the wide Artifacts header places its title and count inline on one line; medium Computers cards wrap update notices onto a second line instead of truncating OS names like "Ubuntu 2…"; the channel Details Members row no longer truncates its title in narrow side panes; and switching wide rail tabs or navigating wide history now dismisses an open keyboard.

## ja

ワイド画面でのレイアウトと操作の細部を修正しました。ワイド表示の「タスク」タブヘッダーをキャンバス背景に統一して黄色の二重バーを解消し、「メンバー」分割表示の詳細ヘッダーから不要な戻る矢印を削除しました。また、「アーティファクト」ヘッダーのタイトルと件数を 1 行にそろえ、中幅のコンピューターカードで OS 名が「Ubuntu 2…」のように途切れないよう更新通知を 2 行目に折り返し、チャンネル詳細の「メンバー」見出しが狭いサイドパネルで途切れないようにしました。さらに、サイドレールのタブ切替や履歴移動時に開いているキーボードを自動で閉じるようにしました。
