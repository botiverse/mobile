---
type: improved
area: agents
platforms:
  - android
  - ios
  - desktop
pull_request: 4614
rollout: full
---

## zh-CN

创建和编辑智能体时的运行时设置与网页版一致：选择已保存的提供方后，由它提供的字段（如 API Key、网关地址）会直接隐藏，不再显示为不可编辑。推理强度随所选模型变化，「默认」是列表中的一个选项；没有推理强度可选的模型（包括手动输入的模型）不再显示这一项，编辑时保留原来的设置。暂时不可用的选项（例如已停用的已保存提供方，或这台电脑上不可用的模型）显示在列表末尾，带有原因，不能选择；如果当前选中的就是这样的选项，会保持原样显示并提示重新选择，之后才能保存。刷新模型列表时，推理强度选项会一起更新。

## en

Runtime settings when you create or edit an agent now work as on the web. When you pick a saved provider, the fields it provides (such as the API key or gateway URL) are hidden instead of shown disabled. Reasoning effort follows the selected model, with Default as one of its options; a model with no effort choices (including a model you typed) no longer shows the field, and editing keeps the stored setting. Options that are not available right now, such as a disabled saved provider or a model this computer can't use, appear at the end of the list with the reason and can't be picked; if one of them is the current selection it stays as it is and asks you to choose another before saving. Refreshing the model list also refreshes the reasoning effort choices.

## ja

エージェントの作成・編集時のランタイム設定がウェブ版と同じ動きになりました。保存済みプロバイダーを選ぶと、そのプロバイダーが提供する項目（API キーやゲートウェイ URL など）は無効表示ではなく非表示になります。推論の強さは選んだモデルに合わせて変わり、「デフォルト」も選択肢の一つです。推論の強さを選べないモデル（手入力したモデルを含む）ではこの項目は表示されず、編集時は保存済みの設定がそのまま保たれます。いま利用できない選択肢（無効になった保存済みプロバイダーや、このコンピューターでは使えないモデルなど）はリストの最後に理由付きで表示され、選択できません。現在の選択がそれに当たる場合はそのまま表示され、別の選択肢を選ぶまで保存できません。モデル一覧を更新すると、推論の強さの選択肢も更新されます。
