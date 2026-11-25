---
type: fixed
area: diagnostics
platforms:
  - android
rollout: full
---

## zh-CN

将 Android 端 Hands SDK 升级至 0.15.2：在发生 OOM 或剩余堆内存低于 16 MB 时，崩溃采集会跳过文件描述符遍历、logcat 读取与全线程栈导出，避免内存耗尽期间引发次生 ANR。

## en

Upgrades the Hands Android SDK to 0.15.2 so crash collection skips file-descriptor enumeration, logcat capture, and full-thread dumps during OOM or when less than 16 MB of heap remains, preventing secondary ANRs under memory exhaustion.

## ja

Android 版 Hands SDK を 0.15.2 に更新しました。OOM 発生時や空きヒープが 16 MB 未満の状態でクラッシュ収集がファイル記述子の一覧取得・logcat 読み取り・全スレッドダンプをスキップし、メモリ枯渇時の二次的な ANR を防ぎます。
