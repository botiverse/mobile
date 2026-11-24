---
type: fixed
area: messages
platforms:
  - android
  - ios
  - desktop
rollout: full
---

## zh-CN

修复中文句子中出现两个 `$` 符号（如价格区间）时被误渲染为数学公式的问题；包含 LaTeX 命令的公式和 `$$…$$` 块公式不受影响。

## en

Keep single-dollar spans containing CJK prose without a LaTeX command as plain text instead of rendering them as inline math (matching web #16343); formulas with LaTeX commands and `$$…$$` math are unaffected.

## ja

日本語・中国語などの文中に `$` が 2 つ含まれる場合（価格表記など）に数式として誤変換されないよう修正しました。LaTeX コマンドを含む数式や `$$…$$` 数式は従来通り表示されます。
