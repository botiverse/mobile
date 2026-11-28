---
type: fixed
area: composer
platforms: [android, ios]
rollout: full
---

## zh-CN
修复了手机端语音输入时麦克风外圈光晕在说话时几乎静止的问题。Android 与 iOS 原生录音为保证转写质量关闭了自动增益（AGC），手持说话的原始音量低于 Web 端 AGC 阈值；现为视觉音量计引入适配无 AGC 麦克风的噪声底噪与自适应峰值上限，并采用线性帧间过渡使光晕随人声自然起伏，同时保持发送给识别服务的原始 PCM 音频字节完全不变。

## en
Fixed the composer voice dictation mic halo looking nearly static while speaking on Android and iOS. Because native mobile capture intentionally disables AGC (`VOICE_RECOGNITION` on Android and `.measurement` on iOS) to keep speech clean for transcription, normal handheld speech arrived below the Web-AGC noise floor; the visual audio-level meter now uses an un-AGC'd noise floor, an adaptive ceiling that preserves syllable modulation for both quiet handheld and close-up loud speech, and linear 100 ms halo interpolation, without modifying any raw PCM bytes sent to ASR.

## ja
Android および iOS の音声入力時にマイク外周のハローが発話中もほとんど動かない問題を修正しました。モバイルのネイティブ録音では文字起こし品質のため AGC を無効化しており、手持ち発話の音量が Web 版 AGC 基準を下回っていました。視覚用レベルメーターに非 AGC 向けのノイズフロアと適応型上限、および線形補間を導入し、ASR へ送信する生 PCM 音声データは一切変更せずに発話に合わせて滑らかに反応するようにしました。
