---
type: fixed
area: artifacts
platforms: [android, ios]
rollout: full
---

## zh-CN
修复了在 Artifact 页面点击左上角返回箭头关闭时，刚进入多槽保活池的 WebView 立即被销毁导致两个 Artifact 之间来回切换每次都重新加载并闪现加载页的问题；正常返回现在与系统返回键一致，保留已加载页面与滚动位置，登出/切服或异常跳离页面仍会按原规则清理缓存槽位。

## en
Fixed an issue where tapping the top-left Back arrow inside an Artifact viewer immediately evicted the newly parked multi-slot keep-alive WebView, forcing a full reload and splash flash when switching back and forth between two Artifacts. Normal back-arrow closes now preserve the warm slot and scroll position just like system Back, while sign-out, server switches, and unexpected off-Artifact navigations still evict slots as before.

## ja
Artifact ビューア左上の戻るボタンで閉じた際に、マルチスロット keep-alive に退避された直後の WebView が破棄され、2 つの Artifact を行き来するたびに再読み込みとスプラッシュ表示が発生する不具合を修正しました。通常の戻る操作ではシステムバックと同様に読み込み済みページとスクロール位置を保持し、サインアウト・サーバー切替・想定外の遷移時は従来どおりスロットを破棄します。
