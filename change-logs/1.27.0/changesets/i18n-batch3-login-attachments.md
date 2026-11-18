---
type: fixed
area: i18n
platforms:
  - android
  - ios
  - desktop
pull_request: 5008
rollout: full
---

## zh-CN

登录和附件相关的几处提示不再显示英文，会跟随你的界面语言：
- 打开 Raft 时恢复登录失败、网络不可用，或者本机保存的登录信息读不出来时的提示。
- 登录、选择或创建服务器、完善个人资料、重发验证邮件失败时的提示，比如“邮箱或密码不正确”“请输入服务器名称”“找不到这个服务器”。登录成功但后续设置没完成时，提示只说“没能完成设置，请重试”，不再带一串内部代号。
- 在 iPhone 和 iPad 上关联 Apple、Google、GitHub 账号时的各种结果提示（已关联、已取消、请求已过期、请重新登录等），以及弹窗里的“确定”按钮。Android 上打不开账号关联页面时的提示也一样。
- Android 应用内更新：下载中、已下载、正在打开安装程序、需要允许安装未知应用、安装失败时，版本号下面的说明文字。
- 保存或下载附件失败时，不再显示系统给出的英文原因，而是显示“下载失败”“存储空间不可用”“无法保存到文件”这类提示。下载列表里失败的条目也一样。

## en

A few sign-in and attachment messages no longer show English and follow your language:
- The message shown when Raft can't restore your sign-in on launch, when the network is unavailable, or when the sign-in saved on this device can't be read.
- The messages shown when signing in, choosing or creating a server, finishing your profile or resending the verification email fails, such as "Incorrect email or password", "Enter a server name" and "This server could not be found". When you're signed in but setup can't finish, the message now just says so, without an internal step name.
- On iPhone and iPad, the messages shown when you link an Apple, Google or GitHub account (linked, cancelled, request expired, sign in again and so on), and the OK button in that alert. On Android, the message shown when the account linking page can't be opened.
- Android in-app updates: the text under the version while the update downloads, once it's downloaded, when the installer opens, when Raft needs permission to install apps, and when the install fails.
- When saving or downloading an attachment fails, you see a message such as "Download failed", "Storage unavailable" or "Unable to save to Files" instead of the system's English reason. Failed rows in the downloads list do the same.

## ja

ログインと添付ファイルに関するいくつかのメッセージが、英語ではなく表示言語で表示されるようになりました：
- Raft を開いたときにログイン状態を復元できなかったとき、ネットワークを使えないとき、このデバイスに保存されたログイン情報を読み込めなかったときのメッセージ。
- ログイン、サーバーの選択や作成、プロフィールの設定、確認メールの再送信に失敗したときのメッセージ（「メールアドレスまたはパスワードが正しくありません」「サーバー名を入力してください」「このサーバーが見つかりませんでした」など）。ログインできたのにセットアップを完了できなかったときは、内部の段階名を含まずにその旨だけを表示します。
- iPhone と iPad で Apple、Google、GitHub アカウントを連携したときの結果のメッセージ（連携しました、キャンセルしました、リクエストの期限切れ、もう一度ログインしてください など）と、そのアラートの「OK」ボタン。Android でアカウント連携ページを開けなかったときのメッセージも同様です。
- Android のアプリ内アップデート：ダウンロード中、ダウンロード完了、インストーラーを開いたとき、不明なアプリのインストールの許可が必要なとき、インストールに失敗したときにバージョンの下に表示される説明。
- 添付ファイルの保存やダウンロードに失敗したとき、システムの英語の理由ではなく「ダウンロードに失敗しました」「ストレージを利用できません」などのメッセージを表示します。ダウンロード一覧の失敗した項目も同様です。
