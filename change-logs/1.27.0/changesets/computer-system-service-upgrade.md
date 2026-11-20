---
type: fixed
area: computers
platforms:
  - android
  - ios
  - desktop
pull_request: 5098
rollout: full
---

## zh-CN

作为系统服务运行、无法从网页升级的 Computer，不再显示「升级」按钮，而是说明原因，并给出在那台机器上的步骤：先停止它的 systemd 服务，运行安装命令，再重新启动服务。

## en

A Computer that runs as a system service and can't be upgraded from the web no longer offers Upgrade. It says why and gives the steps on that machine: stop its systemd service, run the install command, then start the service again.

## ja

システムサービスとして実行されていてウェブからアップグレードできないコンピューターには「アップグレード」を表示せず、理由と、そのコンピューターでの手順（systemd サービスを停止し、インストールコマンドを実行してから、サービスを再び起動する）を表示するようになりました。
