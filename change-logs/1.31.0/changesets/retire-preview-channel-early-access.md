---
type: changed
area: settings
platforms:
  - android
  - ios
rollout: full
---

## zh-CN

停用旧的 `preview` 更新通道：原 `preview` 通道用户在升级到 1.31.0 后会自动并入正式通道（`main`）并继续接收应用内增量更新；同时在「设置 › 关于」中通过服务端长期功能开关控制抢先体验入口可见性与默认值（Android 直装可运行期免重装切换 `alpha` / `main` 通道，Google Play / iOS 直达对应测试计划）。

## en

Retired the legacy `preview` update channel: existing `preview` installs automatically transition to the `main` release channel on 1.31.0 with in-app delta updates, and Settings › About now surfaces Early access enrollment via long-lived server feature flags controlling visibility and default channel (runtime `alpha` / `main` toggle without reinstalling on direct Android installs, plus Google Play beta and iOS TestFlight links).

## ja

旧 `preview` アップデートチャネルを廃止し、1.31.0 へ更新した既存 `preview` ユーザーは自動的に正式チャネル（`main`）へ移行して差分アップデートを継続できるようになりました。あわせて「設定 › このアプリについて」の早期アクセス導線の表示と既定チャネルをサーバー側の長期フィーチャーフラグで制御できるようにし、Android 直インストール版では再インストールなしで `alpha` / `main` を切り替え可能、Google Play / iOS 版ではベータ・TestFlight 参加リンクから案内します。
