---
type: fixed
area: browser
platforms: [android, ios, desktop]
rollout: full
---

## zh-CN
修复了在应用内浏览器（如 Stamp 登录页）加载完成后点击同源登录或授权链接时，因重新发起请求导致 `Sec-Fetch-Site` 降级且 `Referer` 丢失、页面反复弹回登录页的问题；在首次加载前的合法同源重定向补头路径上也会保留原请求的 `Referer`，跨站跳转继续自动剥离宿主请求头。

## en
Fixed an issue in the in-app browser where clicking a same-origin sign-in or consent link on a loaded page (such as Stamp's sign-in page) was re-issued by the host, downgrading `Sec-Fetch-Site` from `same-origin` to `none` and dropping `Referer` into a sign-in redirect loop. Post-load same-origin links now navigate natively to preserve CSRF and `Referer` headers, while legitimate pre-commit same-origin redirects preserve any browser-supplied `Referer` across Android, iOS, and macOS without affecting cross-origin header stripping.

## ja
アプリ内ブラウザで読み込み済みのページ（Stamp のサインイン画面など）から同一オリジンのログイン・同意リンクを開いた際、ホスト側の再発行によって `Sec-Fetch-Site` が `none` に降格され `Referer` も失われてサインイン画面に戻される不具合を修正しました。読み込み後の同一オリジン遷移はネイティブのまま実行して CSRF ヘッダーと `Referer` を保持し、正当な読み込み前の同一オリジンリダイレクトでもブラウザ付与の `Referer` を引き継ぎます。
