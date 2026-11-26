---
type: improved
area: computers
platforms:
  - android
  - ios
  - desktop
rollout: full
---

## zh-CN

对齐 Web 端 Computers 页面「需要处理」顶部摘要与就绪状态（`attention` / `readiness`）：
- Computers 列表上方新增「需要处理」摘要条：单台单事项展示原因、所在 Computer、受影响 Agent 数量与跳转操作（成员展示可处理人，访客仅展示角色不泄露所有者姓名）；多项或多台展示汇总计数并支持一键筛选「需要处理」；存在额度/重启等待的 Agent 时展示等待数量与最早恢复时间。
- 页面头部计数在服务端返回 `attention` 时追加「N 台需要处理」，「需要处理」状态筛选优先遵循服务端 `attention.count` 判定并在旧服务端上保持兼容回退。
- 列表状态灯支持以橙色展示需处理项列表提示，重启中/更新中窗口展示黄色状态灯与「重启中… / 更新中…」文案，并在运行时用量卡片/面板顶部展示运行时就绪提示与固定 Anthropic 验证门户链接。

## en

Brings Computers page needs-attention summary and readiness (`attention` / `readiness`) parity with web:
- Adds the top needs-attention summary strip above the Computers list: single-item issues show the reason, Computer, affected agent count, and action link for owners/admins (or who can act for members, and role-only copy without owner names for guests); multiple items or Computers summarize the count and filter to Needs attention on tap; waiting agents display a separate waiting count and earliest recovery time.
- Header counts append `{count} need attention` when the server supplies `attention`, and the Status: Needs attention filter follows the server verdict while falling back cleanly on older servers.
- Computer row status lights show orange with attention item tooltips when action is required, yellow for Restarting… / Updating… windows, and runtime usage sheets/cards lead with a readiness notice (including the fixed Anthropic Verification Portal link when verification is required).

## ja

Web 版に合わせて、コンピューター一覧の「要対応」サマリーと稼働状態（`attention` / `readiness`）に対応しました：
- コンピューター一覧の上部に要対応サマリーを追加しました。単一項目では理由・対象コンピューター・影響エージェント数・アクションを表示し（メンバーには対応可能な担当者名、ゲストには所有者名を伏せた役割表現を表示）、複数件では件数からワンタップで「要対応」フィルターを適用できます。待機中のエージェントがいる場合は待機数と最短の回復予定時刻も表示します。
- サーバーが `attention` を返す場合、ヘッダー件数に「N 台要対応」を追加し、「要対応」フィルターもサーバー判定に従いつつ旧サーバーでは従来のルールにフォールバックします。
- コンピューター行のステータスライトは要対応項目をオレンジでツールチップ表示し、再起動中／更新中ウィンドウでは黄色のライトと状態文言を表示します。ランタイム使用量カード／シート上部にはランタイムの稼働状態と固定の Anthropic 認証ポータルリンクを表示します。
