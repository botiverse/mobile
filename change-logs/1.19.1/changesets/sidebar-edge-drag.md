---
type: improved
area: navigation
platforms:
  - android
  - ios
  - desktop
pull_request: https://github.com/botiverse/mobile/pull/4186
rollout: full
---

## zh-CN

在平板和 Compose 版的宽屏布局中，侧栏（频道列表、设置列表）和动态列表的右边缘与网页版一致：拖动边缘可调整宽度（侧栏 200–400，默认 240），宽度保存在本设备；拖到最窄处再往左超出 40 时，列表会像“收起侧栏”一样收起，按住时拖回即可恢复，之后点“展开侧栏”会回到原来的宽度。双击边缘恢复默认宽度；用键盘时，方向键每次调整 16（按住 Shift 为 64），Home / End 到最窄 / 最宽，回车收起。鼠标移到边缘上会显示调整宽度的指针。手机布局不变。

## en

On tablets and the Compose desktop app's wide layout, the edge of the sidebar (channels, the Settings list) and of the Activity list now works as on the web: drag it to change the width (the sidebar goes from 200 to 400, 240 by default), remembered on this device. Dragging more than 40 past the narrowest width collapses the list, just like Collapse Sidebar; drag back while still holding to undo, and Expand Sidebar later brings back the width it had. Double-click the edge to restore the default width; with a keyboard, the arrow keys move it by 16 (64 with Shift), Home / End go to the narrowest / widest, and Enter collapses it. A mouse over the edge shows the resize pointer. Phone layouts are unchanged.

## ja

タブレットと Compose 版のワイドレイアウトで、サイドバー（チャンネル一覧、設定の一覧）とアクティビティ一覧の右端が Web 版と同じように動作するようになりました。端をドラッグすると幅を変えられ（サイドバーは 200〜400、既定 240）、この端末に保存されます。最小幅からさらに 40 を超えてドラッグすると「サイドバーを折りたたむ」と同じように一覧が折りたたまれ、押したまま戻せば元に戻ります。あとで「サイドバーを展開」を押すと元の幅で表示されます。端をダブルクリックすると既定の幅に戻ります。キーボードでは矢印キーで 16（Shift 併用で 64）ずつ、Home / End で最小 / 最大に移動し、Enter で折りたたみます。マウスを端に重ねると幅変更用のポインターが表示されます。スマートフォンのレイアウトは変わりません。
