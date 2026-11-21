---
type: added
area: computers
platforms:
  - android
  - ios
  - desktop
pull_request: 0
rollout: full
---

## zh-CN

「添加计算机」的连接命令下方新增「终端显示了一个代码？在这里输入。」：通过 SSH（或在没有浏览器的地方）运行 raft-computer setup、终端显示设备代码时，可以直接在 App 里输入并批准。批准前会显示这台计算机自报的名称、主机名和系统、发起时间和来源，以及要添加到的服务器；开启两步验证时会先确认是你本人。用于登录、Agent 密钥或其他服务器的代码不会在这里批准，会改为提供网页上的设备登录页面。

## en

Add Computer now has "The terminal shows a code? Enter it here." under the connect command: when raft-computer setup runs over SSH (or anywhere without a browser) and shows a device code, you can approve it in the app. Before you approve, it shows the computer's own name, hostname and OS, when and where the login started, and the server it will be added to; with two-step verification on, you verify it's you first. A code for a sign-in, an agent key or another server isn't approved here: the device login page on the web is offered instead.

## ja

「コンピューターを追加」の接続コマンドの下に「ターミナルにコードが表示されましたか？ここに入力してください。」が追加されました。SSH 経由（またはブラウザーのない環境）で raft-computer setup を実行し、ターミナルにデバイスコードが表示された場合、アプリでそのまま承認できます。承認前に、コンピューターが自己申告した名前、ホスト名と OS、ログインを開始した時刻と接続元、追加先のサーバーが表示されます。2 段階認証をオンにしている場合は、先に本人確認を行います。ログイン用、エージェントのキー用、または別のサーバー用のコードはここでは承認されず、代わりにウェブのデバイスのログインページが案内されます。
