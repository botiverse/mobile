---
type: changed
area: navigation
platforms:
  - android
  - ios
pull_request: 3452
rollout: full
---

## zh-CN

平板、折叠屏和横屏（窗口宽 840dp 起，桌面模式开着）：左侧导航栏的入口顺序改为和手机底栏一致（主页、动态、任务、搜索，然后是成员）；半展开的折叠屏够宽时也显示双栏，不再出现"有左侧导航栏却只有一栏、点会话整页推入"的情况；平板转到竖屏后，正在看的会话保留并单独显示，带返回、不再同时露出底栏；右侧没打开会话时显示统一的空状态；动态列表在右侧会话关闭后不再保留选中高亮。频道排序等多字段/长列表弹窗在平板（窗口宽 600dp 起）上改为居中弹窗，底部有"取消"和确认按钮，手机上仍是 90% 高的底部弹窗。

## en

Tablets, foldables and landscape (windows from 840dp with desktop mode on): the left rail lists its entries in the phone tab bar's order (Home, Activity, Tasks, Search, then Members). A half-open foldable that is wide enough shows the two panes too, instead of the rail beside a single list that opened every conversation as a full page. Turning a tablet to portrait keeps the open conversation and shows it alone with Back, without the tab bar under it. The detail pane shows a proper empty state when nothing is open, and the Activity list drops its row highlight once the conversation beside it closes. Dialogs with a long list or several fields (such as channel sorting) are centred on tablets (windows from 600dp) with Cancel and the confirm button at the bottom; phones keep the 90%-tall sheet.
