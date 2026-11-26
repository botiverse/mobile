---
type: added
area: browser
platforms: [android, ios]
rollout: full
---

## zh-CN
- 内置浏览器拦截网页无用户手势自动拉起外部应用，并对合法的非 `http/https` 外部应用链接增加二次确认弹窗（`javascript:`、`intent:`、`file:`、`data:` 等危险协议保持直接拦截）。
- 「设置 › 存储空间」新增「内置浏览器」缓存与 Cookie 清除入口，不会影响账号与服务器数据。
- 退出登录或切换账号时一并清空内置浏览器的第三方独立浏览数据。

## en
- Blocked automatic non-`http/https` external app launches without a user gesture in the in-app browser, and added a confirmation dialog before opening allowed external app links (while continuing to hard-deny dangerous schemes such as `javascript:`, `intent:`, `file:`, and `data:`).
- Added an "In-app browser" cache and cookies clear entry under Settings › Storage without affecting your account or server data.
- Sign-out and account switching now also clear isolated third-party in-app browser data.

## ja
- アプリ内ブラウザでユーザー操作なしに外部アプリを起動する遷移をブロックし、許可された外部アプリリンクを開く前に確認ダイアログを表示するようにしました（`javascript:`・`intent:`・`file:`・`data:` などの危険なスキームは引き続き拒否）。
- 「設定 › ストレージ」に、アカウントやサーバーデータに影響を与えずにアプリ内ブラウザのキャッシュと Cookie を削除できる項目を追加しました。
- サインアウトやアカウント切り替え時に、分離されたサードパーティ用ブラウザデータも併せて消去するようにしました。
