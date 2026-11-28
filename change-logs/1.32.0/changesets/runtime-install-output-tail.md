---
type: improved
area: agents
platforms:
  - android
  - ios
  - desktop
rollout: full
---

## zh-CN
当在 Computer 页面安装运行时失败且服务端返回了脱敏后的安装输出末尾行（`outputTail`）时，失败提示下方现在会显示「安装输出的最后几行：」及可滚动的等宽代码块，方便直接排查失败原因；未返回输出末尾时保持原有提示不变。

## en
When a runtime install fails on a Computer and the server returns the redacted trailing installer output (`outputTail`), the failure outcome now displays "Last lines of the install output:" with a scrollable monospace code block directly under the failure message; failures without `outputTail` remain unchanged.

## ja
Computer ページでランタイムのインストールが失敗し、サーバーからマスキング済みの末尾出力（`outputTail`）が返された場合、失敗メッセージの直下に「インストール出力の最後の数行：」とスクロール可能な等幅コードブロックを表示するようになりました。`outputTail` がない場合はこれまでどおり表示されます。
