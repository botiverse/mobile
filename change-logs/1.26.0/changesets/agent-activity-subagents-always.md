---
type: changed
area: agents
platforms:
  - android
  - ios
  - desktop
pull_request: 4827
rollout: full
---

## zh-CN

Agent 的「动态」页里，使用能启动子 Agent 的运行时（Claude Code、Codex、Kimi Code、Qwen Code、Qoder）的 Agent 会一直显示「子 Agent」视图和数量，还没有子 Agent 时显示 0。其他运行时仍然在出现第一个子 Agent 后才显示。

## en

On an agent's Activity page, agents on a runtime that can start sub-agents (Claude Code, Codex, Kimi Code, Qwen Code, Qoder) now always show the Subagents view with its count, 0 before the first one. Other runtimes still show it once a sub-agent has appeared.

## ja

エージェントの「アクティビティ」ページで、サブエージェントを起動できるランタイム（Claude Code、Codex、Kimi Code、Qwen Code、Qoder）のエージェントは、「サブエージェント」の表示と件数を常に表示するようになりました。まだサブエージェントがない場合は 0 と表示されます。その他のランタイムでは、これまでどおり最初のサブエージェントが現れてから表示されます。
