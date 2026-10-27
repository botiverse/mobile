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

FEATURE = {
    "en": ("Your [AI teammates], in every channel", "Chat, tasks and agents in one place"),
    "zh-Hans": ("[AI 队友]，|就在你的频道里", "频道、|任务和 Agent，|都在一处"),
    "ja": ("[AI チームメイト]が、|どのチャンネルにも", "チャット、|タスク、|エージェントを|ひとつに"),
}


def fonts_css(fonts):
    css = []
    for pkg in ("noto-sans-sc", "noto-sans-jp"):
        base = os.path.join(fonts, pkg)
        for w in (400, 500, 700):
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
    cjk = "'Noto Sans JP','Noto Sans SC'" if lang == "ja" else "'Noto Sans SC','Noto Sans JP'"
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


def page(style, shot, profile, accent, headline, sub, idx, lang, w, h):
    landscape = w > h
    tablet = profile in TABLET_PROFILES
    raw = Image.open(shot)
    s = (h / 1080 * 0.92) if landscape else (w / 1080)
    cjk = lang != "en"
    hl = (66 if cjk else 72) * s if landscape else (68 if cjk else 74) * s
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
    top_color, bottom_color = edge_color(shot), edge_color(shot, bottom=True)
    if style == "A":
        bg = (f"background:{CREAM};background-image:radial-gradient(rgba(20,17,17,.13) {2*s:.1f}px,transparent {2.5*s:.1f}px);"
              f"background-size:{36*s:.0f}px {36*s:.0f}px;background-position:{18*s:.0f}px {18*s:.0f}px")
        hl_mark = (f".m{{background:{accent};border:{5*s:.1f}px solid {INK};border-radius:{14*s:.0f}px;padding:0 {12*s:.0f}px;"
                   f"box-shadow:{7*s:.1f}px {7*s:.1f}px 0 {INK};white-space:nowrap;line-height:1.3;"
                   "-webkit-box-decoration-break:clone;box-decoration-break:clone}")
        device_css = (f"border:{6*s:.1f}px solid {INK};box-shadow:var(--sh) var(--sh) 0 {accent},var(--sh) var(--sh) 0 {6*s:.1f}px {INK}")
        kicker = f'<span class="kicker">RAFT · {idx:02d}</span>'
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
.bat:after{{content:'';position:absolute;left:calc(var(--u)*1.5);top:calc(var(--u)*1.5);bottom:calc(var(--u)*1.5);width:65%;background:currentColor;border-radius:1px}}
.hb{{height:calc(var(--u)*{fit['hbDp']});flex:none;display:flex;align-items:center;justify-content:center}}
.hb i{{display:block;width:calc(var(--u)*{200 if tablet else 134});height:calc(var(--u)*5);border-radius:calc(var(--u)*3);background:{INK}}}
</style></head><body>
<div class="top">{kicker}<h1>{mark(headline, cls)}</h1><p>{mark(sub, 'm')}</p></div>
<div class="stage"><div class="device" data-fit='{__import__("json").dumps(fit)}'><div class="screen">
<div class="sb" style="background:{top_color}"><span>9:41</span><span class="sbi"><i class="sig"></i><i class="wifi"></i><i class="bat"></i></span></div>
<img src="file://{shot}">
<div class="hb" style="background:{bottom_color}"><i></i></div></div></div></div>
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
    a = ap.parse_args()
    raw, out = os.path.abspath(a.raw), os.path.abspath(a.out)
    FONTS_CSS = fonts_css(os.path.abspath(a.fonts))
    PAGES = tempfile.mkdtemp(prefix="raft-store-frames-")
    devices = set(a.devices.split(",")) if a.devices else None
    langs = a.langs.split(",") if a.langs else LANGS
    scenes = set(a.scenes.split(",")) if a.scenes else None
    jobs = []
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
