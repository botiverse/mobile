---
type: fixed
area: navigation
platforms:
  - android
  - ios
  - desktop
pull_request: 4783
rollout: full
---

## zh-CN

连续点同一条通知，或者在页面正在滑入时收到并点开通知，不再会把同一个讨论串打开两次；iOS 的原生导航（Beta）和 Android 的多窗口模式也是如此。页面滑入期间点了另一个页面时，会在滑入真正结束后马上打开最后点的那个。在平板宽屏布局和桌面端，打开页面没有滑入动画，紧接着点另一个页面会立即打开，不再等待。

## en

Tapping the same notification twice, or opening a notification while a page is still sliding in, no longer opens the same thread twice, including with the iOS native navigation (Beta) and Android's multi-window mode. If you tap a different page while one is sliding in, the last one you tapped opens as soon as the slide actually finishes. On tablet wide layouts and on desktop, where pages open without a slide, the next page you tap opens right away instead of after a short wait.

## ja

同じ通知を続けてタップしたり、ページがスライドして表示されている途中で通知を開いたりしても、同じスレッドが 2 回開かれなくなりました。iOS のネイティブナビゲーション（ベータ）や Android のマルチウィンドウでも同様です。スライド中に別のページをタップした場合は、スライドが実際に終わった時点で最後にタップしたページが開きます。タブレットの横長レイアウトやデスクトップではページがスライドせずに開くため、続けてタップした別のページも待たずにすぐ開きます。
