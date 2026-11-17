---
type: fixed
area: agents
platforms:
  - android
  - ios
  - desktop
pull_request: null
rollout: full
---

## zh-CN

在智能体的运行时设置中，当计算机报告所选模型不支持推理强度，或读取不到它的推理强度级别时，「推理强度」不再消失：它保持显示为「默认」且不能更改，下方说明「此模型不支持调整推理强度。」或「无法读取推理强度级别：」加上运行时给出的原因，与网页版一致。较旧的计算机没有报告这些信息时，仍和以前一样不显示。

## en

In an agent's runtime settings, Reasoning effort no longer disappears when the Computer reports that the selected model takes no effort or that its effort levels could not be read: it stays visible at Default and can't be changed, with "This model doesn't support adjusting effort." or "Can't read the effort levels:" followed by the runtime's reason underneath, as on the web. When an older Computer reports neither, it stays hidden as before.

## ja

エージェントのランタイム設定で、選んだモデルが推論の強度を使わない、またはその強度レベルを読み取れないとコンピューターが報告した場合も、「推論の強度」が消えなくなりました。「デフォルト」のまま変更できない状態で表示され、その下に「このモデルは推論の強度を調整できません。」または「推論の強度を読み取れません：」とランタイムが示した理由が表示されます。ウェブ版と同じ動作です。古いコンピューターがどちらも報告しない場合は、これまでどおり表示されません。
