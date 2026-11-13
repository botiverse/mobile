---
type: changed
area: settings
platforms:
  - android
  - ios
pull_request: 4722
rollout: full
---

## zh-CN

提交反馈时附带的诊断包更小了：滚动帧耗时、输入框按键、已取消的网络请求等高频记录改为每分钟汇总一行（含次数和最小/最大/最近值），附带的日志上限从 1 MiB 降到 768 KiB，并且不再附带 6 小时前的旧日志。提交前最后几分钟仍会完整附带，崩溃、错误、页面打开和应用前后台切换等记录照常保留。开发者仍可在「设置 › 开发者 › 详细诊断日志」中临时打开完整记录。

## en

Feedback reports now attach a smaller diagnostics bundle: high-frequency entries such as scroll frame timings, composer keystrokes and cancelled network requests are summarized once a minute (with counts and min/max/last values), the attached logs are capped at 768 KiB instead of 1 MiB, and logs older than 6 hours are left out. The last few minutes before you send feedback are still attached in full, and crashes, errors, page opens and app foreground/background changes are kept as before. Developers can still temporarily turn full logging on under Settings › Developer › Verbose diagnostics.

## ja

フィードバックに添付される診断データが小さくなりました。スクロール時のフレーム計測、入力欄のキー入力、キャンセルされた通信など頻繁に出る記録は 1 分ごとの要約（回数と最小・最大・最新の値）にまとめ、添付するログの上限を 1 MiB から 768 KiB に下げ、6 時間より前のログは添付しなくなりました。送信直前の数分間は引き続きすべて添付され、クラッシュ、エラー、画面の表示、アプリの前面・背面の切り替えなどの記録もこれまでどおり残ります。開発者は「設定 › 開発者 › 詳細な診断ログ」で、一時的に完全な記録をオンにできます。
