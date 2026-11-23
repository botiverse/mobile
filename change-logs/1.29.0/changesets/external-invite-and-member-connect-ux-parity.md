---
type: improved
area: external-invites
platforms: [android, ios, desktop]
rollout: full
---

## zh-CN
- 频道成员页与「连接你的服务器」面板对齐网页交互：成员页底部新增直达「邀请外部人员」按钮，且取消外部邀请弹窗后保留「添加成员」视图；搜索成员时能直接搜出待接受的外部邀请。
- 「连接你的服务器」会先拉取免费版外部频道名额状态，对已满名额的服务器禁用勾选并按角色显示「升级」或「请所有者升级」按钮（iOS 遵循应用商店计费规则隐藏外链升级入口），连接成功后立即刷新频道与首页状态。
- 预取免费版外部频道名额并在升级提示中标明当前占用的频道名；外部邀请确认页提交期间保留「上一步」按钮为禁用状态。

## en
- Aligned channel external-invite and Connect your servers flows with web: added the direct "Invite people outside {server}" button to the Members page, kept Add Member open behind the invite sheet, and made member search surface matching open invites.
- Connect your servers now loads Free-plan external-channel limits, disables at-limit servers with inline "Upgrade" (owner) or "Ask an owner to upgrade" (admin) actions targeting that server (omitted on iOS when upgrade offers are off), and refreshes channel/home projections immediately after connecting.
- Added Free external-channel slot prefetch with channel-named upgrade context, and kept the review step's Back control visible and disabled while submitting.

## ja
- チャンネルメンバー画面および「サーバーを接続」パネルの操作を Web に合わせました：メンバー画面下部に外部招待ボタンを追加し、招待シートを閉じても「メンバーを追加」画面を維持し、検索で保留中の外部招待も表示されるようにしました。
- 「サーバーを接続」で無料プランの外部チャンネル枠制限を取得し、上限に達したサーバーを選択不可にして所有者向け「アップグレード」／管理者向け「オーナーにアップグレードを依頼」ボタンを表示し（iOS の課金ルール非対象時は非表示）、接続後にチャンネルとホーム状態を即時更新します。
- 無料枠の事前取得と対象チャンネル名付きアップグレード案内を追加し、送信中は確認画面の「戻る」ボタンを無効状態で維持するようにしました。
