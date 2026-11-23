---
type: fixed
area: performance
platforms:
  - android
  - ios
  - desktop
rollout: full
pull_request: 5412
---

## zh-CN
优化从后台回到应用、退出对话立刻滑动首页时的掉帧问题：将后台收件箱解析与日志写入移出主路径，并在首页滑动期间延后合批后台标签页的动态、成员更新与本地缓存写入。

## en
Reduce Home scroll jank right after returning from background and leaving a conversation by moving background inbox parsing off the main path, consolidating burst sync logging, and deferring background-tab activity/member updates and cache writes until Home finishes scrolling.

## ja
バックグラウンドから復帰して会話からホームへ戻り直ちにスクロールした際のコマ落ちを軽減しました。受信トレイの解析をバックグラウンドへ移行し、ログ出力を集約するとともに、ホームのスクロール中は非表示タブの更新やキャッシュ書き込みをスクロール終了後へ遅延・統合します。
