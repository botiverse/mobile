---
type: improved
area: external-invites
platforms: [android, ios, desktop]
rollout: full
---

## zh-CN
- 频道成员页与「连接你的服务器」面板对齐网页交互：成员页底部新增直达「邀请外部人员」按钮，且关闭外部邀请弹窗后保留「添加成员」视图；搜索成员时能直接搜出待接受的外部邀请。
- 「连接你的服务器」会显示各服务器的免费版外部频道名额状态，对已满名额的服务器禁用勾选并按角色显示「升级」或「请所有者升级」按钮（iOS 按应用商店规则隐藏外链升级入口），连接成功后立即刷新频道与首页列表。
- 打开外部邀请与新建外部频道入口时更快响应免费版名额校验，升级提示中会直接说明当前占用名额的频道名；外部邀请确认页提交期间保留「上一步」按钮为禁用状态。

## en
- Aligned channel external-invite and Connect your servers flows with web: added a direct "Invite people outside {server}" button to the Members page, kept Add Member open behind the invite sheet, and made member search surface matching open invites.
- Connect your servers now checks Free-plan external-channel limits, disables at-limit servers with inline "Upgrade" (owner) or "Ask an owner to upgrade" (admin) actions for that server (omitted on iOS when upgrade offers are disabled), and refreshes channel and home lists immediately after connecting.
- External invite and new external channel entry points now respond faster when Free external-channel slots are available, name the channel holding the slot in the upgrade prompt, and keep the review step's Back button visible and disabled while sending.

## ja
- チャンネルメンバー画面および「サーバーを接続」パネルの操作を Web に合わせました：メンバー画面下部に外部招待ボタンを追加し、招待シートを閉じても「メンバーを追加」画面を維持し、検索で保留中の外部招待も表示されるようにしました。
- 「サーバーを接続」で無料プランの外部チャンネル枠制限を確認し、上限に達したサーバーを選択不可にして所有者向け「アップグレード」／管理者向け「オーナーにアップグレードを依頼」ボタンを表示し（iOS の課金ルール非対象時は非表示）、接続後にチャンネルとホーム一覧を即時更新します。
- 外部招待・外部チャンネル作成の開始時に無料枠の確認を高速化し、アップグレード案内に枠を使用中のチャンネル名を表示するようにしました。また、送信中は確認画面の「戻る」ボタンを無効状態で維持します。
