---
type: fixed
area: composer
platforms:
  - android
  - ios
  - desktop
pull_request: 5298
rollout: full
---

## zh-CN

优化聊天输入框在提及、引用、发送和附带编辑图片时的聚焦行为：打开弹窗期间输入框请求不再抢占焦点；准备并上传编辑后的图片期间，如果你已把焦点移到搜索框、其他按钮或弹窗，图片附带完成后不再把焦点抢回输入框，也不会在触屏设备上自动弹出软键盘。

## en

Improved composer focus handling for mentions, quotes, sending, and attaching edited images: composer focus requests now yield while a dialog is open, and if you move focus to search, another control, or a dialog while an edited image is being prepared and uploaded, finishing the attachment no longer steals focus back to the composer or pops up the soft keyboard on touch devices.

## ja

メンション・引用・送信・編集済み画像の添付時の入力欄フォーカス動作を改善しました。ダイアログが開いている間は入力欄へのフォーカス要求がフォーカスを奪わず、編集済み画像の準備・アップロード中に検索欄や別コントロール、ダイアログへフォーカスを移した場合も、添付完了後に入力欄へフォーカスを奪い返したり、タッチ端末でソフトキーボードを勝手に表示したりしないようになりました。
