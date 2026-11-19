---
type: fixed
area: agents
platforms:
  - android
  - ios
  - desktop
pull_request: 5044
rollout: full
---

## zh-CN

创建和编辑 Agent 时，运行时设置表单与网页版一致：错误提示改用网页版的文字（如「此项为必填。」「Base URL 必须以 http:// 或 https:// 开头。」）；本版本无法编辑的必填项只显示一次「请升级 Raft 后再编辑此项。」，不再另显示红色错误；可搜索的选择框出错时边框不再变红；所选项的说明只在列表里显示；已保存但当前不可用的值会提示它不在当前列表中；计算机未及时响应时，会说明选项可能不是最新的。

## en

The runtime settings form in Create Agent and agent settings now matches the web: error messages use the web's wording (such as "This field is required." and "Base URL must start with http:// or https://."); a required setting this version can't edit shows "Update Raft to edit this setting." once, without an extra red error; searchable pickers no longer turn red on an error; a choice's description shows only in the list; a saved value that is no longer available says it is not in the current list; and when the Computer did not answer in time, the form says the options may be out of date.

## ja

エージェントの作成・設定にあるランタイム設定フォームがウェブ版と同じになりました。エラーメッセージはウェブ版の文言（「この項目は必須です。」「ベース URL は http:// または https:// で始まる必要があります。」など）になり、このバージョンで編集できない必須項目には「この設定を編集するには Raft をアップデートしてください。」が一度だけ表示され、赤いエラーは重ねて表示されません。検索できる選択欄はエラー時に赤くならず、選択肢の説明は一覧にだけ表示されます。保存済みでも利用できなくなった値は現在の一覧にないことが表示され、コンピューターが時間内に応答しなかった場合は選択肢が最新でない可能性があると表示されます。
