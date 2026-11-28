---
type: fixed
area: chat
platforms: [android, ios]
rollout: full
---

## zh-CN
修复了以 UTF-8 BOM（`U+FEFF`）开头的消息（例如 `@提及` 前混入不可见 BOM 字符的中文或英文消息）在移动端被误判为从右向左（RTL）排版、导致整段右对齐及句末标点错位的问题。

## en
Fixed messages prefixed with a UTF-8 BOM (`U+FEFF`, such as Chinese or English messages with an invisible BOM before a leading `@mention`) being misclassified as right-to-left (RTL) on mobile, which caused right alignment and misplaced trailing punctuation.

## ja
UTF-8 BOM（`U+FEFF`）で始まるメッセージ（先頭の `@メンション` の前に不可視の BOM が混入した日本語・中国語・英語メッセージなど）がモバイルで右から左（RTL）と誤判定され、右揃えや末尾の句読点ずれが発生する問題を修正しました。
