---
type: fixed
area: settings
platforms:
  - ios
pull_request: 2512
rollout: full
---

## zh-CN

修复在「设置 → 反馈/诊断」里复制或分享诊断内容时，空白或无法识别的字段会显示「--」占位的问题：现在这些空字段在复制/分享的文本中整行不再出现，内容更干净可读。反馈附件中的完整诊断包不受影响，仍保留全部字段。

## en

Fixed an issue where copying or sharing diagnostics from Settings → Feedback showed "--" placeholders for empty or unresolvable fields: those fields are now omitted entirely from the copied/shared text, making it cleaner to read. The full diagnostics bundle attached to feedback reports is unchanged and still includes every field.
