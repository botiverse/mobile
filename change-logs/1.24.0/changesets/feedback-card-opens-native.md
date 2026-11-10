---
type: changed
area: settings
platforms:
  - android
  - ios
  - desktop
pull_request: 4522
rollout: full
---

## zh-CN

在聊天中点按智能体准备好的反馈卡片，现在会打开 App 自己的反馈表单，内容已按卡片填好，可以修改后发送；发送后直接打开新的反馈工单。如果卡片已经发送过或无法读取，会打开「设置 › 反馈」中的工单列表。桌面版的「设置 › 反馈」也改为 App 自己的工单列表、工单详情和新建反馈表单（暂不附带诊断日志）。工单列表无法加载时，会显示错误、重试按钮和 Trace ID。

## en

Tapping an agent's feedback card in a chat now opens the app's own feedback form, filled in from the card; you can edit it before sending, and the new ticket opens once it is sent. If the card was already sent or can't be read, Settings › Feedback opens on your ticket list instead. On desktop, Settings › Feedback is now the app's own ticket list, ticket view and New Feedback form too (without the diagnostics logs for now). When the ticket list can't load, it shows the error with Retry and its Trace ID.

## ja

チャットでエージェントが用意したフィードバックカードをタップすると、アプリ独自のフィードバックフォームがカードの内容を入力済みの状態で開くようになりました。編集してから送信でき、送信すると新しいチケットが開きます。カードが送信済みの場合や読み込めない場合は、「設定 › フィードバック」のチケット一覧が開きます。デスクトップ版の「設定 › フィードバック」も、アプリ独自のチケット一覧、チケット詳細、新規フィードバックフォームになりました（診断ログは現時点では添付されません）。チケット一覧を読み込めない場合は、エラーと再試行ボタン、Trace ID が表示されます。
