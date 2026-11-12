---
type: fixed
area: agents
platforms:
  - android
  - ios
  - desktop
pull_request: PENDING
rollout: full
---

## zh-CN

创建智能体和切换运行时时，可选的运行时以服务器为准：服务器允许的运行时（包括重新开放的运行时，例如开启后的 Antigravity）可以直接选择，不再被应用内置的列表挡住；服务器不允许的不会出现在列表中。智能体资料中的「（已弃用）」标记和提示也以服务器对该智能体当前运行时的判断为准；切换运行时后会重新获取，不会沿用切换前的结果。只有在无法从服务器读取运行时列表时，才会使用应用内置的列表。

## en

When you create an agent or switch its runtime, the runtimes you can pick now come from the server: a runtime the server allows, including one it offers again (such as Antigravity where it is turned on), can be picked instead of being held back by the app's built-in list, and a runtime the server does not allow is not listed. The "(deprecated)" label and warning on an agent's profile also follow the server's answer for the agent's current runtime, and are read again after a switch. The app's built-in list is used only when the server's runtime list cannot be read.

## ja

エージェントの作成時やランタイムの切り替え時に選べるランタイムは、サーバーの判断に従うようになりました。サーバーが許可するランタイム（有効化された Antigravity のように再び提供されるものを含む）はアプリ内蔵のリストに妨げられずに選択でき、許可されていないものは表示されません。エージェントのプロフィールの「（非推奨）」表示と警告も、そのエージェントの現在のランタイムに対するサーバーの判断に従い、切り替え後は改めて読み込まれます。アプリ内蔵のリストは、サーバーからランタイムの一覧を読み込めない場合にのみ使われます。
