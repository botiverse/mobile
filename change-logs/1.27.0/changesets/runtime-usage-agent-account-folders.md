---
type: fixed
area: computers
platforms:
  - android
  - ios
  - desktop
pull_request: 0
rollout: full
---

## zh-CN

计算机上的 Agent 用自己的账号目录（CODEX_HOME / CLAUDE_CONFIG_DIR）时，计算机的用量摘要、限额重置和 Claude 5 小时限额只看这台计算机的默认账号，不再被 Agent 自己的账号干扰（和网页一致）。

## en

When an agent on a computer uses its own account folder (CODEX_HOME / CLAUDE_CONFIG_DIR), the computer's usage summary, limit resets and the Claude 5-hour limit read only the computer's default account, as on the web.

## ja

コンピューター上のエージェントが自分のアカウントフォルダー（CODEX_HOME / CLAUDE_CONFIG_DIR）を使っている場合、コンピューターの使用量の要約、制限リセット、Claude の 5 時間制限は、そのコンピューターのデフォルトアカウントだけを見るようになりました（Web と同じ）。
