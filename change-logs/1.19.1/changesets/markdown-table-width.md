---
type: fixed
area: thread
platforms:
  - android
  - ios
  - desktop
pull_request: https://github.com/botiverse/mobile/pull/4195
rollout: full
---

## zh-CN

消息里的 Markdown 表格与网页版一致：表格按内容宽度显示，最宽到消息区域的宽度。之前即使旁边空间很多，每列最宽也只有约 160 像素，长单元格被折成好几行；现在只有内容放不下时单元格才换行，连最窄也放不下时可左右滑动表格。表头不换行。

## en

Markdown tables in messages now size like on the web: a table takes the width of its content, up to the width of the message area. Before, every column was capped at about 160 pixels, so long cells wrapped into several lines even with plenty of room beside them; now cells wrap only when the content does not fit, and a table that cannot fit even then scrolls sideways. Header cells stay on one line.

## ja

メッセージ内の Markdown の表が Web 版と同じ幅で表示されるようになりました。表は内容に合わせた幅になり、最大でメッセージ領域の幅まで広がります。これまでは横に十分な余白があっても各列が約 160 ピクセルに制限され、長いセルが何行にも折り返されていました。今後は内容が収まらない場合にだけセルが折り返され、それでも収まらない表は横にスクロールできます。見出しのセルは折り返されません。
