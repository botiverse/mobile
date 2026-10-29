#!/usr/bin/env python3
"""Compose the Raft Play / App Store screenshots from raw app renders, via headless Chromium.

Raw renders come from StoreScreenshotsTest (see README.md): <raw>/<profile>/<lang>/<scene>.png.
Output: <out>/<store>/<device>/<lang>/<scene>.png, plus the Play feature graphic at
<out>/play/feature-graphic/<lang>/feature-graphic.png. Every PNG is flattened to RGB (no alpha,
as App Store Connect requires) and checked against its exact pixel size.

Usage:
  frames.py --raw DIR --out DIR --fonts DIR [--style A|B] [--devices phone,ipad-13] [--langs en,ja] [--jobs 4]

--fonts is a node_modules/@fontsource directory holding noto-sans-sc and noto-sans-jp
(`npm install @fontsource/noto-sans-sc @fontsource/noto-sans-jp`): Space Grotesk has no CJK.
Needs `chromium` on PATH and Pillow.
"""
import argparse
import concurrent.futures
import hashlib
import html
import os
import subprocess
import sys
import tempfile

from PIL import Image

REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
FONT_DIR = f"{REPO}/compose/designsystem/src/commonMain/composeResources/font"
LOGO = f"{REPO}/scripts/assets/brand/raft-logo.svg"

INK = "#141111"
CREAM = "#FFFAEF"
YELLOW = "#FFD441"
PINK = "#FE7DA8"
CYAN = "#28CCF3"
LIME = "#A9D877"
LAVENDER = "#BBAFE6"
ORANGE = "#F8A16F"

# (store, device, raw profile, width, height). Profiles and their dp sizes: StoreScreenshotsTest.
TARGETS = [
    ("play", "phone", "phone", 1080, 1920),
    ("play", "tablet-7", "tablet", 1920, 1080),
    ("play", "tablet-10", "tablet", 2560, 1440),
    ("appstore", "iphone-6.9", "iphone", 1320, 2868),
    ("appstore", "iphone-6.5", "iphone", 1284, 2778),
    ("appstore", "ipad-13", "ipad", 2064, 2752),
    ("appstore", "duo-inner-portrait", "duo-inner-portrait", 2007, 2853),
    ("appstore", "duo-inner-landscape", "duo-inner-landscape", 2853, 2007),
    ("appstore", "duo-outer", "duo-outer", 1398, 2034),
]
# Window width in dp per raw profile (the raw PNG width / this = px per dp).
PROFILE_DP_WIDTH = {
    "phone": 390,
    "iphone": 430,
    "tablet": 1024,
    "ipad": 1032,
    "duo-inner-portrait": 669,
    "duo-inner-landscape": 951,
    "duo-outer": 466,
}
TABLET_PROFILES = {"tablet", "ipad", "duo-inner-portrait", "duo-inner-landscape"}
LANGS = ["en", "zh-Hans", "ja"]

# scene key, accent, {lang: (headline with [highlight], sub)}. " " keeps words together.
SCENES = [
    ("01-chat", YELLOW, {
        "en": ("Your [AI teammates], in every channel", "Mention an agent and it gets to work, right in the conversation."),
        "zh-Hans": ("[AI 队友]，|就在你的频道里", "@ 一下 Agent，|它就在对话里开工。"),
        "ja": ("[AI チームメイト]が、|どのチャンネルにも", "エージェントに|メンションすれば、|会話の中で|すぐ動き出します。"),
    }),
    ("02-activity", PINK, {
        "en": ("Never miss what [needs you]", "Mentions, replies and task updates in one Activity inbox."),
        "zh-Hans": ("需要你的，|[都在动态里]", "提及、|回复和任务进展，|一处看完。"),
        "ja": ("[あなた宛て]を|見逃さない", "メンション、|返信、|タスクの進捗を|アクティビティに|まとめて。"),
    }),
    ("03-agents", CYAN, {
        "en": ("Your [agent team], at a glance", "Each agent's role and live status, grouped by the computer it runs on."),
        "zh-Hans": ("你的 [Agent 团队]，|一目了然", "每个 Agent 的|分工和实时状态，|按所在计算机分组。"),
        "ja": ("[エージェントチーム]を|ひと目で", "役割と|リアルタイムの状態を、|動いている|コンピューターごとに。"),
    }),
    ("04-tasks", LIME, {
        "en": ("Tasks your agents [pick up and finish]", "To do, in progress, in review, done, and who is on each one."),
        "zh-Hans": ("任务交给 Agent，|[认领到完成]", "待办、|进行中、|审核中、|已完成，|谁在做一清二楚。"),
        "ja": ("エージェントが|[引き受けて、やり遂げる]", "未着手・|進行中・|レビュー中・|完了。|担当者も|ひと目で。"),
    }),
    ("05-computers", LAVENDER, {
        "en": ("Run agents on [your own computers]", "Connect your machines; your agents run right there."),
        "zh-Hans": ("Agent 跑在|[你自己的计算机]上", "连上你自己的机器，|Agent 就在上面运行。"),
        "ja": ("エージェントは|[自分のコンピューター]で", "手元のマシンを|つなげば、|エージェントは|そこで動きます。"),
    }),
    ("06-home", ORANGE, {
        "en": ("Your team and its agents, [in your pocket]", "Channels, DMs and threads, wherever you are."),
        "zh-Hans": ("团队和 Agent，|[随身带着]", "频道、|私信、|讨论串，|随时跟进。"),
        "ja": ("チームと|エージェントを|[ポケットに]", "チャンネル、|DM、|スレッドを|どこからでも。"),
    }),
]

# The 8-shot Play phone set (StorePlay8ScreenshotsTest; Ginny's final copy 2026-10-28, msg a8fcfa93).
# zh-Hant serves Play zh-TW and zh-HK; Ginny's copy fixes 10-28 (03, 08 zh-Hant; 04 en).
PLAY8_LANGS = ["en", "zh-Hans", "zh-Hant", "ja"]
PLAY8_SCENES = [
    ("01-chat", YELLOW, {
        "en": ("Team chat with [AI agents]", "Humans and AI teammates, in one place"),
        "zh-Hans": ("有 [AI Agent]|一起协作的|团队聊天", "人类和 AI 队友，|在同一个地方"),
        "zh-Hant": ("有 [AI Agent]|一起協作的|團隊聊天", "人類和 AI 隊友，|在同一個地方"),
        "ja": ("[AIエージェント]と|働く|チームチャット", "人とAIのチームメイトが、|ひとつの場所に"),
    }),
    ("02-mention", PINK, {
        "en": ("[@mention] an AI agent", "Ask for help like you would a teammate"),
        "zh-Hans": ("[@ 一下]|AI Agent", "像找同事一样|请它帮忙"),
        "zh-Hant": ("[@ 一下]|AI Agent", "像找同事一樣|請它幫忙"),
        "ja": ("[AIエージェント]を|@メンション", "同僚に頼むように、|気軽にお願い"),
    }),
    ("03-thread", CYAN, {
        "en": ("Agents work together [in threads]", "They hand off, review and solve it"),
        "zh-Hans": ("多个 Agent|[在消息列里协作]", "交接、|审阅，|一起把问题解决"),
        "zh-Hant": ("多個 Agent|[一起協作]", "交接、|審閱，|一起把問題解決"),
        "ja": ("スレッドで|[エージェント同士が協力]", "引き継ぎ、|レビューして、|解決まで"),
    }),
    ("04-tasks", LIME, {
        "en": ("Assign tasks, [watch them get done]", "Track every task from Todo to Done"),
        "zh-Hans": ("分配任务，|[看着它完成]", "从待办到已完成，|全程可追踪"),
        "zh-Hant": ("指派任務，|[看著它完成]", "從待辦到已完成，|全程可追蹤"),
        "ja": ("タスクを任せて、|[完了を見届ける]", "未着手から完了まで、|すべて追跡"),
    }),
    ("05-agent", LAVENDER, {
        "en": ("AI teammates [that remember]", "Persistent identity and memory"),
        "zh-Hans": ("[记性好的]|AI 队友", "身份不变，|记忆一直都在"),
        "zh-Hant": ("[記性好的]|AI 隊友", "身分不變，|記憶一直都在"),
        "ja": ("[覚えてくれる]|AIチームメイト", "名前も記憶も、|ずっと引き継がれる"),
    }),
    ("06-activity", ORANGE, {
        "en": ("Never miss [what needs you]", "Jump straight to the conversation"),
        "zh-Hans": ("不错过|[任何需要你的事]", "一点直达|那条对话"),
        "zh-Hant": ("不錯過|[任何需要你的事]", "一點直達|那則對話"),
        "ja": ("[あなたの出番]を|見逃さない", "ワンタップで|その会話へ"),
    }),
    ("07-swipe", CYAN, {
        "en": ("Built for [your phone]", "Swipe to clear, reply on the go"),
        "zh-Hans": ("[为手机而生]", "左滑处理，|随时回复"),
        "zh-Hant": ("[為手機而生]", "左滑處理，|隨時回覆"),
        "ja": ("[スマホのための]|デザイン", "スワイプで処理、|外出先でも返信"),
    }),
    ("08-home", YELLOW, {
        "en": ("Your whole AI team, [organized]", "Channels, DMs and tasks at a glance"),
        "zh-Hans": ("你的整个 AI 团队，|[井井有条]", "频道、|私信、|任务一目了然"),
        "zh-Hant": ("你的整個 AI 團隊，|[井然有序]", "頻道、|私信、|任務一目了然"),
        "ja": ("AIチーム全体を、|[すっきり整理]", "チャンネル・DM・タスクを|ひと目で"),
    }),
]
PLAY8_HEADLINE_SCALE = 1.12
# Every Play shot reserves two headline lines and one subline, so the phone sits at the same place
# and size whether the headline takes one line or two (review 10-28: it moved ~100px).
PLAY8_TEXT_LINES = (2, 1)


# The App Store 8-shot set (`--set appstore8`, StorePlay8AppStoreScreenshotsTest): the Play 8 scenes
# and copy at iPhone size with the iPhone's own chrome, plus iPhone Duo. (device, raw profile, w, h,
# scenes). Duo takes the scenes that read on its shape: the outer screen's pushed pages; the inner
# screen's list + detail scenes.
APPSTORE8_DEVICES = [
    ("iphone-6.9", "iphone", 1320, 2868, [k for k, _, _ in PLAY8_SCENES]),
    ("iphone-6.5", "iphone", 1284, 2778, [k for k, _, _ in PLAY8_SCENES]),
    ("duo-outer", "duo-outer", 1398, 2034, ["01-chat", "02-mention", "03-thread", "05-agent"]),
    ("duo-inner-portrait", "duo-inner-portrait", 2007, 2853, ["01-chat", "02-mention", "03-thread", "04-tasks", "06-activity"]),
    ("duo-inner-landscape", "duo-inner-landscape", 2853, 2007, ["01-chat", "02-mention", "03-thread", "04-tasks", "06-activity"]),
]
# The iOS tab bar's selected tab per scene; the other scenes are pushed pages with no bar.
APPSTORE8_TAB_SCENES = {"04-tasks": "tasks", "06-activity": "activity", "07-swipe": "activity", "08-home": "home"}
# zh-Hant shows the English UI (StorePlay8ScreenshotsTest): its raw renders are en's.
APPSTORE8_RAW_LANG = {"zh-Hant": "en"}
# Raw content size per profile in dp (rendered at 3x): the frame's chrome is drawn around it.
APPSTORE8_RAW_DP = {"iphone": (430, 836), "duo-outer": (466, 678), "duo-inner-portrait": (669, 907), "duo-inner-landscape": (951, 669)}
RES_DIR = f"{REPO}/compose/shared/src/commonMain/composeResources"
ICON_DIR = f"{REPO}/compose/iosApp/iosApp/Assets.xcassets"


def app_strings(lang, keys):
    """The app's own strings for [keys] in [lang]'s UI (zh-Hant: the English UI)."""
    import re
    folder = {"zh-Hans": "values-zh-rCN", "ja": "values-ja"}.get(lang, "values")
    text = open(f"{RES_DIR}/{folder}/strings.xml", encoding="utf-8").read()
    out = {}
    for k in keys:
        m = re.search(rf'<string name="{k}">(.*?)</string>', text)
        out[k] = html.unescape(m.group(1)) if m else k
    return out


def lucide(name, size_dp, color="currentColor", stroke=2):
    """The app's Lucide tab glyph (the iOS asset catalog's SVG), [size_dp] square."""
    import re
    svg = open(f"{ICON_DIR}/lucide-{name}.imageset/{name}.svg").read()
    body = re.search(r">\s*(.*)</svg>", svg, re.S).group(1)
    return (f'<svg viewBox="0 0 24 24" style="width:calc(var(--u)*{size_dp});height:calc(var(--u)*{size_dp})" fill="none" '
            f'stroke="{color}" stroke-width="{stroke}" stroke-linecap="round" stroke-linejoin="round">{body}</svg>')


IOS_FONT = "'Noto Sans SC','Noto Sans JP',sans-serif"
IOS_CSS = """
.ios-sb{position:relative;flex:none;color:#000}
.ios-sb .t{position:absolute;top:0;bottom:0;display:flex;align-items:center;justify-content:center;
 font-family:%(font)s;font-weight:600;letter-spacing:calc(var(--u)*-0.2)}
.ios-sb .r{position:absolute;top:0;bottom:0;display:flex;align-items:center;justify-content:center;gap:calc(var(--u)*5)}
.ios-sb .island{position:absolute;left:50%%;transform:translateX(-50%%);top:calc(var(--u)*11);width:calc(var(--u)*126);
 height:calc(var(--u)*37);border-radius:calc(var(--u)*19);background:#000}
.ios-hi{position:absolute;left:50%%;transform:translateX(-50%%);bottom:calc(var(--u)*8);width:calc(var(--u)*144);
 height:calc(var(--u)*5);border-radius:calc(var(--u)*3);background:#000}
.glass{background:rgba(250,250,250,.62);-webkit-backdrop-filter:blur(calc(var(--u)*10)) saturate(1.8);backdrop-filter:blur(calc(var(--u)*10)) saturate(1.8);
 box-shadow:0 calc(var(--u)*6) calc(var(--u)*24) rgba(0,0,0,.13),0 0 0 calc(var(--u)*.5) rgba(0,0,0,.07),
 inset 0 calc(var(--u)*1) calc(var(--u)*1) rgba(255,255,255,.95),inset 0 calc(var(--u)*-1) calc(var(--u)*1) rgba(255,255,255,.5)}
.tabbar{position:absolute;left:calc(var(--u)*21);right:calc(var(--u)*21);bottom:calc(var(--u)*21);height:calc(var(--u)*62);
 display:flex;gap:calc(var(--u)*10)}
.tabbar .cap{flex:1;border-radius:calc(var(--u)*31);display:flex;align-items:stretch;padding:calc(var(--u)*4)}
.tabbar .it{flex:1;position:relative;display:flex;flex-direction:column;align-items:center;justify-content:center;
 gap:calc(var(--u)*1);color:#000;border-radius:calc(var(--u)*27)}
.tabbar .it.sel{background:rgba(0,0,0,.075)}
.tabbar .it span{font-family:%(font)s;font-size:calc(var(--u)*10);font-weight:600;line-height:1.2;white-space:nowrap}
.tabbar .badge{position:absolute;top:calc(var(--u)*3);left:calc(50%% + var(--u)*6);min-width:calc(var(--u)*18);
 height:calc(var(--u)*18);padding:0 calc(var(--u)*5);border-radius:calc(var(--u)*9);background:#FF3B30;color:#fff;display:flex;
 align-items:center;justify-content:center;font-family:%(font)s;font-size:calc(var(--u)*12);font-weight:600}
.tabbar .srv{width:calc(var(--u)*62);flex:none;border-radius:50%%;display:flex;align-items:center;justify-content:center}
.tabbar .av{width:calc(var(--u)*40);height:calc(var(--u)*40);border-radius:50%%;background:#141111;color:#FFD441;display:flex;
 align-items:center;justify-content:center;font-family:'Space Mono',monospace;font-weight:700;font-size:calc(var(--u)*18)}
.cam{position:absolute;border-radius:50%%;background:radial-gradient(circle at 40%% 40%%,#2a2f3a 0,#0b0b0d 55%%);
 box-shadow:0 0 0 calc(var(--u)*1.5) #1d1d1f}
""" % {"font": IOS_FONT}


def ios_status_icons():
    """Cellular bars, Wi-Fi and the battery, drawn as iOS draws them (black on a light bar)."""
    u = "var(--u)"
    bars = "".join(f'<rect x="{i * 4.6:.1f}" y="{11 - h}" width="3.2" height="{h}" rx="1"/>' for i, h in enumerate((4.2, 6.4, 8.7, 11)))
    cell = f'<svg viewBox="0 0 17 11" style="width:calc({u}*17);height:calc({u}*11)" fill="#000">{bars}</svg>'
    wifi = (f'<svg viewBox="0 0 16 12" style="width:calc({u}*15.5);height:calc({u}*11.5)" fill="none" stroke="#000" stroke-linecap="round">'
            '<path d="M1.6 4.6 A9.2 9.2 0 0 1 14.4 4.6" stroke-width="2.3"/><path d="M4.4 7.5 A5.2 5.2 0 0 1 11.6 7.5" stroke-width="2.3"/>'
            '<path d="M8 11.2 L6.6 9.9 A2 2 0 0 1 9.4 9.9 Z" fill="#000" stroke-width="1.2" stroke-linejoin="round"/></svg>')
    bat = (f'<svg viewBox="0 0 28 13" style="width:calc({u}*27.3);height:calc({u}*13)">'
           '<rect x=".5" y=".5" width="24" height="12" rx="3.8" fill="none" stroke="#000" stroke-opacity=".35"/>'
           '<rect x="2.5" y="2.5" width="20" height="8" rx="2.2" fill="#000"/>'
           '<path d="M26 4.4 v4.2 a2 2 0 0 0 0 -4.2z" fill="#000" fill-opacity=".4"/></svg>')
    return cell + wifi + bat


def ios_status(width_dp, height_dp, island, time_size=17):
    """The iOS status bar over [top_color]: 9:41 in the left ear, the icons in the right one; the
    Dynamic Island between them on an iPhone."""
    ear = (width_dp - 126) / 2 if island else 120

    def build(top_color):
        right = f"left:calc(var(--u)*{width_dp - ear})" if island else f"right:calc(var(--u)*18)"
        return (f'<div class="ios-sb" style="height:calc(var(--u)*{height_dp});background:{top_color}">'
                f'<span class="t" style="left:calc(var(--u)*{12 if island else 18});width:calc(var(--u)*{ear - 6:.1f});font-size:calc(var(--u)*{time_size})'
                f'{"" if island else ";justify-content:flex-start"}">9:41</span>'
                + ('<i class="island"></i>' if island else "")
                + f'<span class="r" style="{right};{f"width:calc(var(--u)*{ear - 12:.1f})" if island else ""}">{ios_status_icons()}</span></div>')
    return build


def ios_tab_bar(lang, selected):
    """iOS 26's tab bar as the app's RaftIosTabBarController lays it out: a Liquid Glass capsule
    with Home, Activity, Tasks and Search (the app's Lucide glyphs, tinted `.label`, the selected
    one on the glass's platter, Activity's unread count as UIKit's red badge), and the current
    server as its own round glass button (its initial tile: ink, yellow initial)."""
    labels = app_strings(lang, ["tab_home", "tab_activity", "tab_tasks", "home_nav_search"])
    tabs = [("home", "house", labels["tab_home"]), ("activity", "activity", labels["tab_activity"]),
            ("tasks", "square-check-big", labels["tab_tasks"]), ("search", "search", labels["home_nav_search"])]
    items = []
    for key, icon, label in tabs:
        badge = '<b class="badge">4</b>' if key == "activity" and selected != "activity" else ""
        items.append(f'<div class="it{" sel" if key == selected else ""}">{lucide(icon, 24, stroke=1.9)}<span>{html.escape(label)}</span>{badge}</div>')
    return (f'<div class="tabbar"><div class="cap glass">{"".join(items)}</div>'
            f'<div class="srv glass"><div class="av">N</div></div></div>')


def appstore8_index(out):
    """<out>/index.html: every device's contact sheet, then its shots per language, as found on disk."""
    parts = ["<!doctype html><meta charset='utf-8'><title>Raft App Store screenshots</title>",
             "<style>body{font-family:sans-serif;margin:24px;background:#f6f4ee}h2{margin-top:40px}"
             "figure{display:inline-block;margin:0 12px 16px 0;vertical-align:top}figure img{height:360px;border:1px solid #ccc}"
             "figcaption{font:12px monospace}.sheet{max-width:100%;border:1px solid #ccc}</style>",
             "<h1>Raft App Store screenshots (8-shot set)</h1>"]
    for device, _, w, h, _ in APPSTORE8_DEVICES:
        ddir = os.path.join(out, device)
        if not os.path.isdir(ddir):
            continue
        parts.append(f"<h2>{device} ({w}&times;{h})</h2>")
        if os.path.exists(os.path.join(out, f"contact-{device}.png")):
            parts.append(f"<p><a href='contact-{device}.png'>contact sheet</a></p>")
        for lang in PLAY8_LANGS:
            ldir = os.path.join(ddir, lang)
            if not os.path.isdir(ldir):
                continue
            parts.append(f"<h3>{lang}</h3><div>")
            for f in sorted(os.listdir(ldir)):
                rel = f"{device}/{lang}/{f}"
                parts.append(f"<figure><a href='{rel}'><img src='{rel}' loading='lazy'></a><figcaption>{rel}</figcaption></figure>")
            parts.append("</div>")
    path = os.path.join(out, "index.html")
    with open(path, "w", encoding="utf-8") as fh:
        fh.write("\n".join(parts))
    return path


def appstore8_chrome(device, scene, lang):
    """Frame metrics and the drawn system chrome per App Store device."""
    if device.startswith("iphone"):
        overlay = '<i class="ios-hi"></i>'
        if scene in APPSTORE8_TAB_SCENES:
            overlay = ios_tab_bar(lang, APPSTORE8_TAB_SCENES[scene]) + overlay
        # 430pt wide: the 6.9" iPhone's 62pt status bar (Dynamic Island) and 34pt home indicator.
        return {"sbDp": 62, "hbDp": 34, "bezel": 0.034, "radiusDp": 58, "css": IOS_CSS,
                "status": ios_status(430, 62, island=True), "overlay": overlay}
    if device == "duo-inner-portrait":
        return {"sbDp": 24, "hbDp": 20, "bezel": 0.024, "radiusDp": 24, "css": IOS_CSS,
                "status": ios_status(669, 24, island=False, time_size=14)}
    # Outer screen and the inner one held sideways: the side column holds the camera and the
    # system's status, so the page runs from the top; the camera in the column's top band.
    band, radius = (48, 34) if device == "duo-outer" else (40, 24)
    width = 466 if device == "duo-outer" else 951
    cam = 12
    overlay = (f'<i class="cam" style="left:calc(var(--u)*{width - 28 - cam / 2});top:calc(var(--u)*{band / 2 - cam / 2});'
               f'width:calc(var(--u)*{cam});height:calc(var(--u)*{cam})"></i>')
    return {"sbDp": 0, "hbDp": 0, "bezel": 0.03 if device == "duo-outer" else 0.022, "radiusDp": radius, "css": IOS_CSS,
            "status": lambda _c: "", "overlay": overlay}


def contact_sheet(paths, out_png, cols=4, scale=3, gap=24, labels=os.path.basename):
    """The set at 1/[scale] size in a [cols]-wide grid on white, each shot labelled with its file name."""
    from PIL import ImageDraw
    thumbs = [Image.open(p).convert("RGB") for p in paths]
    tw, th = thumbs[0].width // scale, thumbs[0].height // scale
    rows = (len(thumbs) + cols - 1) // cols
    label = 34
    sheet = Image.new("RGB", (cols * tw + (cols + 1) * gap, rows * (th + label) + (rows + 1) * gap), "white")
    draw = ImageDraw.Draw(sheet)
    for i, (im, p) in enumerate(zip(thumbs, paths)):
        x, y = gap + (i % cols) * (tw + gap), gap + (i // cols) * (th + label + gap)
        sheet.paste(im.resize((tw, th), Image.LANCZOS), (x, y))
        draw.text((x, y + th + 8), labels(p), fill=(20, 17, 17))
    sheet.save(out_png, "PNG", optimize=True)
    return out_png


FEATURE = {
    "en": ("Your [AI teammates], in every channel", "Chat, tasks and agents in one place"),
    "zh-Hans": ("[AI 队友]，|就在你的频道里", "频道、|任务和 Agent，|都在一处"),
    "ja": ("[AI チームメイト]が、|どのチャンネルにも", "チャット、|タスク、|エージェントを|ひとつに"),
}


def fonts_css(fonts):
    css = []
    for pkg in ("noto-sans-sc", "noto-sans-jp", "noto-sans-tc"):
        base = os.path.join(fonts, pkg)
        if pkg == "noto-sans-tc" and not os.path.isdir(base):
            continue  # only the zh-Hant set needs it
        for w in (400, 500, 600, 700):
            css.append(open(os.path.join(base, f"{w}.css")).read().replace("url(./files/", f"url(file://{base}/files/"))
    faces = [
        ("Space Grotesk", 400, "space_grotesk_regular.ttf"),
        ("Space Grotesk", 500, "space_grotesk_medium.ttf"),
        ("Space Grotesk", 700, "space_grotesk_bold.ttf"),
        ("Space Mono", 400, "space_mono_regular.ttf"),
        ("Space Mono", 700, "space_mono_bold.ttf"),
    ]
    css += [f"@font-face{{font-family:'{f}';font-weight:{w};src:url('file://{FONT_DIR}/{n}')}}" for f, w, n in faces]
    return "\n".join(css)


def sans(lang):
    cjk = {
        "ja": "'Noto Sans JP','Noto Sans SC'",
        "zh-Hant": "'Noto Sans TC','Noto Sans SC','Noto Sans JP'",
    }.get(lang, "'Noto Sans SC','Noto Sans JP'")
    return f"'Space Grotesk',{cjk},sans-serif"


def mono(lang):
    return f"'Space Mono',{sans(lang)}"


def mark(text, cls):
    """[x] is the highlight. "|" separates CJK phrases: each phrase is unbreakable (nowrap) and
    the only line-break opportunities are between phrases (<wbr>), so a CJK line never breaks
    inside a phrase. Text without "|" (English) wraps normally."""
    def one(seg):
        return html.escape(seg).replace("[", f'<span class="{cls}">').replace("]", "</span>")
    if "|" not in text:
        return one(text)
    return "<wbr>".join(f'<span class="ph">{one(seg)}</span>' for seg in text.split("|"))


def edge_color(png, bottom=False):
    im = Image.open(png).convert("RGB")
    r, g, b = im.getpixel((20, im.height - 4 if bottom else 6))
    return f"#{r:02X}{g:02X}{b:02X}"


# Lays the device out in .stage: the largest screen that fits, keeping the raw render's aspect plus
# the drawn status bar and home indicator; all device metrics scale with --u (px per app dp).
FIT_JS = """
<script>
document.fonts.ready.then(function(){
  // A highlight never wraps: shrink the headline until no line sticks out of its column.
  const h=document.querySelector('h1');
  for(let i=0;i<30&&h.scrollWidth>h.clientWidth+1;i++){h.style.fontSize=(parseFloat(getComputedStyle(h).fontSize)*0.95)+'px';}

  const d=document.querySelector('.device'), st=document.querySelector('.stage');
  const c=JSON.parse(d.dataset.fit);
  const r=st.getBoundingClientRect();
  const aw=r.width-c.padX*2-c.shadow, ah=r.height-c.padTop-c.padBottom-c.shadow;
  const ratio=c.rawH/c.rawW+(c.sbDp+c.hbDp)/c.dpW;     // screen height / width
  const b=c.bezel;                                      // bezel / screen width
  let sw=Math.min(aw/(1+2*b), ah/(ratio+2*b), c.maxW/(1+2*b));
  const u=sw/c.dpW;
  d.style.width=(sw*(1+2*b))+'px'; d.style.height=(sw*(ratio+2*b))+'px';
  d.style.padding=(sw*b)+'px';
  d.style.left=(c.padX+(aw-sw*(1+2*b))/2)+'px';
  d.style.top=(c.padTop+(ah-sw*(ratio+2*b))*c.vAlign)+'px';
  d.style.borderRadius=(sw*b+c.radiusDp*u)+'px';
  d.querySelector('.screen').style.borderRadius=(c.radiusDp*u)+'px';
  document.documentElement.style.setProperty('--u', u+'px');
  document.documentElement.style.setProperty('--sh', c.shadow+'px');
});
</script>"""


def page(style, shot, profile, accent, headline, sub, idx, lang, w, h, hl_scale=1.0, text_lines=None, chrome=None):
    landscape = w > h
    tablet = profile in TABLET_PROFILES
    raw = Image.open(shot)
    chrome = chrome or {}
    s = (h / 1080 * 0.92) if landscape else (w / 1080)
    cjk = lang != "en"
    hl = ((66 if cjk else 72) * s if landscape else (68 if cjk else 74) * s) * hl_scale
    fit = {
        "rawW": raw.width,
        "rawH": raw.height,
        "dpW": PROFILE_DP_WIDTH[profile],
        "sbDp": 24 if tablet else 30,
        "hbDp": 20 if tablet else 24,
        "bezel": 0.022 if tablet else 0.03,
        "radiusDp": 18 if tablet else 44,
        "shadow": round(22 * s),
        "padX": round((40 if landscape else 72) * s),
        "padTop": round((70 if landscape else 64) * s),
        "padBottom": round((70 if landscape else 90) * s),
        "maxW": w * (0.66 if landscape else (0.90 if tablet else 0.76)),
        "vAlign": 0.5 if landscape else 0.0,
    }
    for k in ("sbDp", "hbDp", "bezel", "radiusDp"):
        if k in chrome:
            fit[k] = chrome[k]
    top_color, bottom_color = edge_color(shot), edge_color(shot, bottom=True)
    if style == "A":
        bg = (f"background:{CREAM};background-image:radial-gradient(rgba(20,17,17,.13) {2*s:.1f}px,transparent {2.5*s:.1f}px);"
              f"background-size:{36*s:.0f}px {36*s:.0f}px;background-position:{18*s:.0f}px {18*s:.0f}px")
        hl_mark = (f".m{{background:{accent};border:{5*s:.1f}px solid {INK};border-radius:{14*s:.0f}px;padding:0 {12*s:.0f}px;"
                   f"box-shadow:{7*s:.1f}px {7*s:.1f}px 0 {INK};white-space:nowrap;line-height:1.3;"
                   "-webkit-box-decoration-break:clone;box-decoration-break:clone}")
        device_css = (f"border:{6*s:.1f}px solid {INK};box-shadow:var(--sh) var(--sh) 0 {accent},var(--sh) var(--sh) 0 {6*s:.1f}px {INK}")
        kicker = f'<span class="kicker">RAFT · {idx:02d}</span>'
        if cjk:  # no word spaces in CJK: keep the sticker clear of the characters beside it; the
            # right side also clears its offset shadow, so a gap shows before the next character
            hl_mark += f"h1 .m{{margin:0 {(8+7+8)*s:.0f}px 0 {14*s:.0f}px}}"
        sub_color = "#3b3535"
        align = "left"
        cls = "m"
    else:
        bg = f"background:{YELLOW}"
        hl_mark = (f".u{{white-space:nowrap;text-decoration:underline;text-decoration-thickness:{8*s:.0f}px;"
                   f"text-underline-offset:{10*s:.0f}px;text-decoration-color:{INK}}}")
        device_css = f"box-shadow:0 {30*s:.0f}px {80*s:.0f}px rgba(60,45,0,.30)"
        kicker = ""
        sub_color = "#4a3f10"
        align = "left" if landscape else "center"
        cls = "u"
    reserve = ""
    if text_lines:  # fixed text block: [h1 lines, subline lines] at the starting sizes
        hl_lines, sub_lines = text_lines
        # 1.3: a line holding the highlight is that tall in English too (.m line-height)
        reserve = (f"h1{{min-height:{hl_lines * hl * 1.3:.0f}px}}"
                   f"p{{min-height:{sub_lines * 33 * s * 1.4:.0f}px}}")
    if "status" in chrome:
        status_html = chrome["status"](top_color)
    else:
        status_html = (f'<div class="sb" style="background:{top_color}"><span>9:41</span>'
                       '<span class="sbi"><i class="sig"></i><i class="wifi"></i><i class="bat"></i></span></div>')
    hb_html = f'<div class="hb" style="background:{bottom_color}"><i></i></div>' if fit["hbDp"] else ""
    if landscape:
        layout = f"""body{{flex-direction:row}}
.top{{width:{0.36*w:.0f}px;flex:none;display:flex;flex-direction:column;justify-content:center;padding:0 {24*s:.0f}px 0 {80*s:.0f}px}}"""
    else:
        layout = f".top{{padding:{92*s:.0f}px {72*s:.0f}px 0}}"
    return f"""<!doctype html><html lang="{lang}"><head><meta charset="utf-8"><style>
{FONTS_CSS}
*{{box-sizing:border-box;margin:0;padding:0}}
html,body{{width:{w}px;height:{h}px;overflow:hidden}}
body{{font-family:{sans(lang)};color:{INK};display:flex;flex-direction:column;{bg}}}
.top{{word-break:{'auto-phrase' if lang == 'ja' else 'normal'}}}
h1{{text-wrap:balance}} p{{text-wrap:pretty}} .ph{{white-space:nowrap}}
{layout}
.top{{text-align:{align}}}
.kicker{{display:inline-block;align-self:flex-start;font-family:{mono(lang)};font-weight:700;font-size:{24*s:.0f}px;letter-spacing:{1*s:.1f}px;
 background:{INK};color:{CREAM};padding:{6*s:.0f}px {14*s:.0f}px;border-radius:{8*s:.0f}px;margin-bottom:{28*s:.0f}px}}
h1{{font-weight:700;font-size:{hl:.0f}px;line-height:{1.3 if cjk else 1.1};letter-spacing:{-1.5*s:.1f}px}}
{hl_mark}
p{{margin-top:{30*s:.0f}px;font-size:{33*s:.0f}px;line-height:1.4;font-weight:500;color:{sub_color}}}
{reserve}
.stage{{flex:1;position:relative;min-height:0;min-width:0}}
.device{{position:absolute;background:{INK};{device_css};display:flex}}
.screen{{flex:1;overflow:hidden;background:#fff;display:flex;flex-direction:column}}
.screen img{{display:block;width:100%}}
.sb{{height:calc(var(--u)*{fit['sbDp']});flex:none;display:flex;align-items:center;justify-content:space-between;
 padding:0 calc(var(--u)*{26 if not tablet else 18});font-family:'Space Grotesk',sans-serif;font-weight:700;font-size:calc(var(--u)*{13 if tablet else 15})}}
.sbi{{display:flex;gap:calc(var(--u)*6);align-items:center}}
.sig{{display:inline-block;width:calc(var(--u)*16);height:calc(var(--u)*10);background:currentColor;clip-path:polygon(0 100%,100% 0,100% 100%)}}
.wifi{{display:inline-block;width:calc(var(--u)*14);height:calc(var(--u)*10);background:currentColor;clip-path:polygon(50% 100%,0 20%,100% 20%)}}
.bat{{display:inline-block;width:calc(var(--u)*22);height:calc(var(--u)*11);border:calc(var(--u)*1.5) solid currentColor;border-radius:calc(var(--u)*3);position:relative}}
.bat:after{{content:'';position:absolute;left:calc(var(--u)*1.5);top:calc(var(--u)*1.5);bottom:calc(var(--u)*1.5);right:calc(var(--u)*1.5);background:currentColor;border-radius:1px}}
.hb{{height:calc(var(--u)*{fit['hbDp']});flex:none;display:flex;align-items:center;justify-content:center}}
.hb i{{display:block;width:calc(var(--u)*{200 if tablet else 134});height:calc(var(--u)*5);border-radius:calc(var(--u)*3);background:{INK}}}
.screen{{position:relative}}
{chrome.get("css", "")}
</style></head><body>
<div class="top">{kicker}<h1>{mark(headline, cls)}</h1><p>{mark(sub, 'm')}</p></div>
<div class="stage"><div class="device" data-fit='{__import__("json").dumps(fit)}'><div class="screen">
{status_html}
<img src="file://{shot}">
{hb_html}{chrome.get("overlay", "")}</div></div></div>
{FIT_JS}
</body></html>"""


def feature_page(style, lang, chat_shot, activity_shot):
    headline, sub = FEATURE[lang]
    cjk = lang != "en"
    if style == "A":
        return f"""<!doctype html><html lang="{lang}"><head><meta charset="utf-8"><style>
{FONTS_CSS}
*{{box-sizing:border-box;margin:0;padding:0}}
html,body{{width:1024px;height:500px;overflow:hidden}}
.ph{{white-space:nowrap}}
body{{font-family:{sans(lang)};color:{INK};background:{CREAM};
 background-image:radial-gradient(rgba(20,17,17,.13) 1.6px,transparent 2px);background-size:26px 26px;position:relative}}
.logo{{position:absolute;left:56px;top:62px;width:300px}}
h1{{position:absolute;left:56px;top:178px;width:{520 if not cjk else 540}px;font-weight:700;font-size:{48 if not cjk else 40}px;line-height:1.18;letter-spacing:-1px}}
.m{{background:{YELLOW};border:4px solid {INK};border-radius:10px;padding:0 8px;box-shadow:5px 5px 0 {INK};white-space:nowrap;line-height:1.35}}
p{{position:absolute;left:56px;top:372px;font-family:{mono(lang)};font-weight:700;font-size:21px;background:{INK};color:{CREAM};padding:6px 12px;border-radius:7px}}
.card{{position:absolute;width:270px;height:520px;border:5px solid {INK};border-radius:30px;overflow:hidden;background:#fff}}
.card img{{width:100%;display:block}}
.c1{{left:610px;top:56px;transform:rotate(-5deg);box-shadow:10px 10px 0 {PINK},10px 10px 0 5px {INK}}}
.c2{{left:770px;top:110px;transform:rotate(6deg);box-shadow:10px 10px 0 {CYAN},10px 10px 0 5px {INK}}}
</style></head><body>
<img class="logo" src="file://{LOGO}"><h1>{mark(headline, 'm')}</h1><p>{mark(sub, 'm')}</p>
<div class="card c1"><img src="file://{activity_shot}"></div><div class="card c2"><img src="file://{chat_shot}"></div>
</body></html>"""
    return f"""<!doctype html><html lang="{lang}"><head><meta charset="utf-8"><style>
{FONTS_CSS}
*{{box-sizing:border-box;margin:0;padding:0}}
html,body{{width:1024px;height:500px;overflow:hidden}}
.ph{{white-space:nowrap}}
body{{font-family:{sans(lang)};color:{INK};background:{YELLOW};position:relative}}
.logo{{position:absolute;left:64px;top:84px;width:280px}}
h1{{position:absolute;left:64px;top:196px;width:{540 if not cjk else 580}px;font-weight:700;font-size:{46 if not cjk else 40}px;line-height:1.15;letter-spacing:-1px}}
.u{{white-space:nowrap;text-decoration:underline;text-decoration-thickness:5px;text-underline-offset:7px}}
p{{position:absolute;left:64px;top:372px;font-size:24px;font-weight:500;color:#4a3f10}}
.phone{{position:absolute;left:680px;top:48px;width:290px;height:600px;background:{INK};border-radius:42px;padding:9px;box-shadow:0 20px 50px rgba(60,45,0,.3)}}
.screen{{width:100%;height:100%;border-radius:34px;overflow:hidden;background:#fff}}
.screen img{{width:100%;display:block}}
</style></head><body>
<img class="logo" src="file://{LOGO}"><h1>{mark(headline, 'u')}</h1><p>{mark(sub, 'm')}</p>
<div class="phone"><div class="screen"><img src="file://{chat_shot}"></div></div>
</body></html>"""


def render(html_text, out_png, w, h):
    os.makedirs(os.path.dirname(out_png), exist_ok=True)
    key = hashlib.sha1(out_png.encode()).hexdigest()[:12]
    page_file = os.path.join(PAGES, f"{key}.html")
    with open(page_file, "w") as f:
        f.write(html_text)
    tmp_png = os.path.join(PAGES, f"{key}.png")
    subprocess.run(
        ["chromium", "--headless=new", "--no-sandbox", "--disable-gpu", "--hide-scrollbars",
         "--allow-file-access-from-files", "--force-device-scale-factor=1", "--default-background-color=FFFFFFFF",
         f"--screenshot={tmp_png}", f"--window-size={w},{h}", "--virtual-time-budget=5000", f"file://{page_file}"],
        check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=180,
    )
    im = Image.open(tmp_png).convert("RGB")
    if im.size != (w, h):
        raise SystemExit(f"{out_png}: rendered {im.size}, expected {(w, h)}")
    im.save(out_png, "PNG", optimize=True)
    os.remove(tmp_png)
    return out_png


def main():
    global FONTS_CSS, PAGES
    ap = argparse.ArgumentParser()
    ap.add_argument("--raw", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--fonts", required=True)
    ap.add_argument("--style", default="A", choices=["A", "B"])
    ap.add_argument("--devices")
    ap.add_argument("--langs")
    ap.add_argument("--scenes")
    ap.add_argument("--jobs", type=int, default=4)
    ap.add_argument("--set", default="store", choices=["store", "play8", "appstore8"],
                    help="play8: the 8 Play phone shots per language, <out>/<lang>/NN-scene.png + contact-<lang>.png")
    a = ap.parse_args()
    raw, out = os.path.abspath(a.raw), os.path.abspath(a.out)
    FONTS_CSS = fonts_css(os.path.abspath(a.fonts))
    PAGES = tempfile.mkdtemp(prefix="raft-store-frames-")
    devices = set(a.devices.split(",")) if a.devices else None
    langs = a.langs.split(",") if a.langs else LANGS
    scenes = set(a.scenes.split(",")) if a.scenes else None
    jobs = []
    if a.set == "appstore8":
        langs = a.langs.split(",") if a.langs else PLAY8_LANGS
        copy_by_key = {key: (accent, copy) for key, accent, copy in PLAY8_SCENES}
        sets = []
        for device, profile, w, h, keys in APPSTORE8_DEVICES:
            if devices and device not in devices:
                continue
            for lang in langs:
                paths = []
                for idx, key in enumerate(keys, start=1):
                    if scenes and key not in scenes:
                        continue
                    shot = os.path.join(raw, profile, APPSTORE8_RAW_LANG.get(lang, lang), key + ".png")
                    if not os.path.exists(shot):
                        raise SystemExit(f"missing raw render {shot}")
                    want = tuple(3 * d for d in APPSTORE8_RAW_DP[profile])
                    if Image.open(shot).size != want:
                        raise SystemExit(f"{shot}: raw render is {Image.open(shot).size}, expected {want}")
                    accent, copy = copy_by_key[key]
                    headline, sub = copy[lang]
                    out_png = f"{out}/{device}/{lang}/{key}.png"
                    jobs.append((page(a.style, shot, profile, accent, headline, sub, idx, lang, w, h, PLAY8_HEADLINE_SCALE,
                                      PLAY8_TEXT_LINES, chrome=appstore8_chrome(device, key, lang)), out_png, w, h))
                    paths.append(out_png)
                sets.append((device, lang, paths))
        with concurrent.futures.ThreadPoolExecutor(a.jobs) as pool:
            for done in pool.map(lambda j: render(*j), jobs):
                print("wrote", done)
        for device in {d for d, _, _ in sets}:
            rows = [p for d, _, paths in sets if d == device for p in paths]
            cols = max(len(paths) for d, _, paths in sets if d == device)
            dw, dh = next((dw, dh) for d, _, dw, dh, _ in APPSTORE8_DEVICES if d == device)
            if rows and all(os.path.exists(p) for p in rows):
                print("wrote", contact_sheet(rows, f"{out}/contact-{device}.png", cols=cols, scale=5 if dw < dh else 6,
                                             labels=lambda p: os.path.relpath(p, out)))
        print("wrote", appstore8_index(out))
        print(len(jobs), "images")
        return
    if a.set == "play8":
        langs = a.langs.split(",") if a.langs else PLAY8_LANGS
        for lang in langs:
            for idx, (key, accent, copy) in enumerate(PLAY8_SCENES, start=1):
                shot = os.path.join(raw, "phone", lang, key + ".png")
                if scenes and key not in scenes:
                    continue
                if not os.path.exists(shot):
                    raise SystemExit(f"missing raw render {shot}")
                headline, sub = copy[lang]
                jobs.append((page(a.style, shot, "phone", accent, headline, sub, idx, lang, 1080, 1920, PLAY8_HEADLINE_SCALE,
                                  PLAY8_TEXT_LINES),
                             f"{out}/{lang}/{key}.png", 1080, 1920))
        with concurrent.futures.ThreadPoolExecutor(a.jobs) as pool:
            for done in pool.map(lambda j: render(*j), jobs):
                print("wrote", done)
        for lang in langs:
            paths = [f"{out}/{lang}/{key}.png" for key, _, _ in PLAY8_SCENES]
            if all(os.path.exists(p) for p in paths):
                print("wrote", contact_sheet(paths, f"{out}/contact-{lang}.png"))
        print(len(jobs), "images")
        return
    for store, device, profile, w, h in TARGETS:
        if devices and device not in devices:
            continue
        for lang in langs:
            for idx, (key, accent, copy) in enumerate(SCENES, start=1):
                shot = os.path.join(raw, profile, lang, key + ".png")
                if not os.path.exists(shot) or (scenes and key not in scenes):
                    continue
                headline, sub = copy[lang]
                jobs.append((page(a.style, shot, profile, accent, headline, sub, idx, lang, w, h), f"{out}/{store}/{device}/{lang}/{key}.png", w, h))
    if not devices or "feature-graphic" in devices:
        for lang in langs:
            chat, act = (os.path.join(raw, "phone", lang, f"{k}.png") for k in ("01-chat", "02-activity"))
            if os.path.exists(chat) and os.path.exists(act):
                jobs.append((feature_page(a.style, lang, chat, act), f"{out}/play/feature-graphic/{lang}/feature-graphic.png", 1024, 500))
    with concurrent.futures.ThreadPoolExecutor(a.jobs) as pool:
        for done in pool.map(lambda j: render(*j), jobs):
            print("wrote", done)
    print(len(jobs), "images")


if __name__ == "__main__":
    sys.exit(main())
