---
type: fixed
area: agents
platforms:
  - android
  - ios
  - desktop
pull_request: 4649
rollout: full
---

## zh-CN

编辑推理强度为「默认」的智能体时，推理强度会保持显示「默认」，不再显示为模型的默认档位（例如「中」）；不做修改直接保存也会保留「默认」。只有在更换模型后，才会切换为新模型的默认推理强度；创建智能体时仍从模型的默认推理强度开始。

## en

Editing an agent whose reasoning effort is Default now keeps it on Default instead of showing the model's default level (such as Medium), and saving without changes keeps Default. The new model's default effort applies only after you change the model; creating an agent still starts at the model's default effort.

## ja

推論の強さが「デフォルト」のエージェントを編集するとき、モデルの既定レベル（「中」など）ではなく「デフォルト」のまま表示され、変更せずに保存しても「デフォルト」が保たれるようになりました。新しいモデルの既定の推論の強さは、モデルを変更したときにのみ適用されます。エージェントの作成時は、これまでどおりモデルの既定の推論の強さから始まります。
