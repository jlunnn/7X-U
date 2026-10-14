#!/usr/bin/env python3
"""出「7X=U」設計頁：app icon 設計 ＋ app UI 設計。

產出（預設 /tmp/7xu-design）：
  index.html          設計頁（icon 大圖／4× 放大／各密度／色板 ＋ UI 截圖 ＋ 規格表 ＋ 31 段頻率表）
  icon.svg            向量 icon（可 import Figma，圖層分開）
  icon-round.svg      圓形版
  tokens.json         W3C design tokens（顏色／圓角／字級／行高）
  icon-192.png …      各密度 icon
  ui-1.png ui-2.png   實機截圖（EQ 開／關）
用法：python3 tools/make_design_page.py [輸出目錄]
"""
import base64
import json
import os
import shutil
import sys

from PIL import Image

SRC = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(SRC)
RES = os.path.join(ROOT, "app/src/main/res")
OUT = sys.argv[1] if len(sys.argv) > 1 else "/tmp/7xu-design"

# ---- 設計 token（同 Ui.java / SysEq 一致）----
TOKENS = {
    "color": {
        "bg": "#0E0F12", "bgTop": "#141821", "surface": "#171A20", "surface2": "#1F232B",
        "stroke": "#2C3138", "text": "#F4F6F8", "textDim": "#A9AEB6",
        "accent": "#4DA3FF", "onAccent": "#07131F", "cut": "#FF8A5B",
        "ok": "#3DD68C", "warn": "#FFC24B", "danger": "#E5484D",
    },
    "radiusDp": {"card": 14, "button": 10},
    "fontSp": {"title": 20, "big": 19, "button": 18, "segment": 15, "body": 13, "label": 12},
    "rowDp": {"top": 50, "presets": 44, "controls": 46, "sliders": 48, "status": 40},
    "icon": {"sliders": 5, "knobPositions": [0.66, 0.34, 0.78, 0.28, 0.55],
             "trackWidthRatio": 0.30, "knobWidthRatio": 0.76, "knobHeightRatio": 0.085},
}
KNOBS = TOKENS["icon"]["knobPositions"]


def icon_svg(size=512, round_icon=False, for_figma=True):
    """向量 icon：圖層分開（背景／光暈／每條推桿分組），可以直接 import Figma 再改。"""
    c = TOKENS["color"]
    W = size
    m = W * 0.15
    n = len(KNOBS)
    cw = (W - 2 * m) / n
    top, bot = W * 0.14, W * 0.86
    tw = cw * TOKENS["icon"]["trackWidthRatio"]
    kw = cw * TOKENS["icon"]["knobWidthRatio"]
    kh = W * TOKENS["icon"]["knobHeightRatio"]
    r = W * 0.22
    out = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{W}" '
           f'viewBox="0 0 {W} {W}">']
    out.append('  <defs><linearGradient id="bgGrad" x1="0" y1="0" x2="0" y2="1">'
               f'<stop offset="0" stop-color="{c["bgTop"]}"/>'
               f'<stop offset="1" stop-color="{c["bg"]}"/></linearGradient>'
               '<radialGradient id="glow" cx="50%" cy="50%" r="50%">'
               f'<stop offset="0" stop-color="{c["accent"]}" stop-opacity="0.30"/>'
               f'<stop offset="1" stop-color="{c["accent"]}" stop-opacity="0"/></radialGradient>'
               '</defs>')
    if round_icon:
        out.append(f'  <circle id="bg" cx="{W/2}" cy="{W/2}" r="{W/2}" fill="url(#bgGrad)"/>')
    else:
        out.append(f'  <rect id="bg" width="{W}" height="{W}" rx="{r}" fill="url(#bgGrad)"/>')
    out.append(f'  <g id="glow"><ellipse cx="{W/2}" cy="{W*0.5}" rx="{W*0.34}" ry="{W*0.30}" '
               'fill="url(#glow)"/></g>')
    out.append('  <g id="sliders">')
    for i, pos in enumerate(KNOBS):
        cx = m + (i + 0.5) * cw
        ky = bot - (bot - top) * pos
        out.append(f'    <g id="slider-{i + 1}">')
        out.append(f'      <rect x="{cx - tw/2:.1f}" y="{top:.1f}" width="{tw:.1f}" '
                   f'height="{bot - top:.1f}" rx="{tw/2:.1f}" fill="#3C434E"/>')
        out.append(f'      <rect x="{cx - tw/2:.1f}" y="{ky:.1f}" width="{tw:.1f}" '
                   f'height="{bot - ky:.1f}" rx="{tw/2:.1f}" fill="#2A5C93"/>')
        out.append(f'      <rect x="{cx - kw/2:.1f}" y="{ky - kh/2:.1f}" width="{kw:.1f}" '
                   f'height="{kh:.1f}" rx="{kh*0.34:.1f}" fill="{c["accent"]}"/>')
        out.append(f'      <rect x="{cx - kw/2 + kh*0.12:.1f}" y="{ky - kh/2 + kh*0.14:.1f}" '
                   f'width="{kw - kh*0.24:.1f}" height="{kh*0.38:.1f}" '
                   f'rx="{kh*0.20:.1f}" fill="#F4F6F8"/>')
        out.append('    </g>')
    out.append('  </g>')
    if not round_icon:
        out.append(f'  <rect x="3" y="3" width="{W-6}" height="{W-6}" rx="{r-2}" '
                   'fill="none" stroke="#5A626E" stroke-width="6"/>')
    out.append('</svg>')
    return "\n".join(out)


def b64(path):
    with open(path, "rb") as f:
        return base64.b64encode(f.read()).decode()


def main():
    os.makedirs(OUT, exist_ok=True)
    # ---- 圖檔 ----
    icon_prev = "/tmp/eq_icon_preview.png"
    if not os.path.exists(icon_prev):
        os.system("cd %s && python3 tools/make_eq_icon.py >/dev/null" % ROOT)
    shutil.copy(icon_prev, os.path.join(OUT, "icon-384.png"))
    for den, size in (("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)):
        shutil.copy(os.path.join(RES, "mipmap-" + den, "ic_launcher.png"),
                    os.path.join(OUT, "icon-%d.png" % size))
    shutil.copy(os.path.join(RES, "mipmap-xxxhdpi", "ic_launcher_round.png"),
                os.path.join(OUT, "icon-round-192.png"))
    # 4× 放大：中間兩條推桿
    im = Image.open(icon_prev)
    w, h = im.size
    im.crop((int(w * 0.28), int(h * 0.20), int(w * 0.72), int(h * 0.72))) \
        .resize((int(w * 0.44 * 3), int(h * 0.52 * 3)), Image.LANCZOS) \
        .save(os.path.join(OUT, "icon-zoom.png"))
    shots = []
    for src, dst in (("eq_100_on_a.png", "ui-1.png"),
                     ("eq_102_off.png", "ui-2.png"),
                     ("eq_150_settings.png", "ui-3.png")):
        if os.path.exists(src):
            shutil.copy(src, os.path.join(OUT, dst))
            shots.append(dst)
    # ---- 向量 ＋ tokens ----
    open(os.path.join(OUT, "icon.svg"), "w", encoding="utf-8").write(icon_svg(512, False))
    open(os.path.join(OUT, "icon-round.svg"), "w", encoding="utf-8").write(icon_svg(512, True))
    json.dump(TOKENS, open(os.path.join(OUT, "tokens.json"), "w", encoding="utf-8"),
              ensure_ascii=False, indent=2)

    freq = [20, 25, 31.5, 40, 50, 63, 80, 100, 125, 160, 200, 250, 315, 400, 500, 630, 800,
            1000, 1250, 1600, 2000, 2500, 3150, 4000, 5000, 6300, 8000, 10000, 12500, 16000, 20000]

    def lab(f):
        if f >= 10000:
            return "%dk" % (f // 1000)
        if f >= 1000:
            return ("%g" % (f / 1000.0)) + "k"
        return "%g" % f

    freq_rows = "".join(
        "<tr><td>%d</td><td>%g Hz</td><td>%s</td></tr>" % (i, f, lab(f))
        for i, f in enumerate(freq))

    def swatch(name):
        return ('<div class="sw"><div class="chip" style="background:%s"></div>'
                '<code>%s</code><span>%s</span></div>' % (TOKENS["color"][name], TOKENS["color"][name], name))

    html = f"""<!DOCTYPE html>
<html lang="zh-HK"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>7X=U · 設計頁（App icon ＋ UI）</title>
<style>
  :root{{--bg:{TOKENS['color']['bg']};--surf:{TOKENS['color']['surface']};--s2:{TOKENS['color']['surface2']};
        --stroke:{TOKENS['color']['stroke']};--text:{TOKENS['color']['text']};--dim:{TOKENS['color']['textDim']};
        --acc:{TOKENS['color']['accent']};--ok:{TOKENS['color']['ok']};--warn:{TOKENS['color']['warn']};}}
  *{{box-sizing:border-box}}
  body{{margin:0;background:var(--bg);color:var(--text);
       font-family:-apple-system,"Noto Sans CJK TC","PingFang TC",sans-serif;line-height:1.6}}
  .wrap{{max-width:1100px;margin:0 auto;padding:28px 20px 80px}}
  h1{{font-size:30px;margin:0 0 6px}} h1 small{{color:var(--dim);font-size:15px;font-weight:400}}
  h2{{font-size:21px;margin:36px 0 12px;padding-bottom:8px;border-bottom:1px solid var(--stroke)}}
  h3{{font-size:16px;margin:22px 0 8px;color:var(--acc)}}
  .card{{background:var(--surf);border:1px solid var(--stroke);border-radius:14px;padding:16px;margin:14px 0}}
  .row{{display:flex;gap:18px;flex-wrap:wrap;align-items:flex-start}}
  .legend{{color:var(--dim);font-size:13px}}
  img.shot{{width:100%;border-radius:12px;border:1px solid var(--stroke);display:block}}
  .grid{{display:flex;gap:16px;flex-wrap:wrap;align-items:flex-end}}
  .grid figure{{margin:0;text-align:center}} .grid figcaption{{font-size:12px;color:var(--dim);margin-top:6px}}
  .sw{{display:flex;align-items:center;gap:8px;margin:4px 0;font-size:13px}}
  .chip{{width:26px;height:26px;border-radius:7px;border:1px solid var(--stroke)}}
  code{{background:var(--s2);padding:1px 6px;border-radius:5px;font-size:12px}}
  table{{border-collapse:collapse;width:100%;font-size:13px}}
  th,td{{border-bottom:1px solid var(--stroke);padding:6px 8px;text-align:left}}
  th{{color:var(--dim);font-weight:500}}
  .pill{{display:inline-block;background:var(--s2);border:1px solid var(--stroke);border-radius:999px;
        padding:3px 12px;font-size:13px;margin:0 6px 6px 0}}
  .acc{{color:var(--ok)}} .warnc{{color:var(--warn)}}
  a{{color:var(--acc)}}
  .two{{display:grid;grid-template-columns:1fr 1fr;gap:18px}}
  @media(max-width:760px){{.two{{grid-template-columns:1fr}}}}
</style></head><body><div class="wrap">
<h1>7X=U <small>· 車機 車機 31 段 EQ · 設計頁（app icon ＋ UI）</small></h1>
<div class="row">
  <span class="pill">package com.sevenxu.eq</span>
  <span class="pill">v1.8 (versionCode 9)</span>
  <span class="pill">arm64-v8a · Android 9+</span>
  <span class="pill">離線 · 冇 INTERNET 權限</span>
</div>

<h2>① App icon（EQ 調音推桿）</h2>
<div class="two">
  <div class="card"><img class="shot" src="icon-384.png" alt="icon">
    <div class="legend" style="margin-top:8px">主圖 384 px（圓角 22%）</div></div>
  <div class="card"><img class="shot" src="icon-zoom.png" alt="zoom">
    <div class="legend" style="margin-top:8px">3× 放大：推鈕（藍色手柄＋白高光）／軌道／已推藍色段</div></div>
</div>
<div class="card"><h3>各密度（同一張圖縮放，MIPMAP）</h3>
  <div class="grid">
    <figure><img src="icon-48.png" width="48"><figcaption>48 mdpi</figcaption></figure>
    <figure><img src="icon-72.png" width="72"><figcaption>72 hdpi</figcaption></figure>
    <figure><img src="icon-96.png" width="96"><figcaption>96 xhdpi</figcaption></figure>
    <figure><img src="icon-144.png" width="144"><figcaption>144 xxhdpi</figcaption></figure>
    <figure><img src="icon-192.png" width="192"><figcaption>192 xxxhdpi</figcaption></figure>
    <figure><img src="icon-round-192.png" width="120"><figcaption>圓形版</figcaption></figure>
  </div>
  <div class="legend" style="margin-top:10px">下載可編輯向量圖：<a href="icon.svg" download>icon.svg</a> ·
    <a href="icon-round.svg" download>icon-round.svg</a>（每個推桿係獨立 <code>&lt;g&gt;</code> 圖層，可直接 import Figma 改色／改推桿數）</div>
</div>
<div class="card"><h3>Icon 規格</h3>
  <table>
    <tr><th>項目</th><th>值</th></tr>
    <tr><td>形狀</td><td>圓角方（radius = 22% 邊長）＋ 圓形版各一</td></tr>
    <tr><td>底色</td><td>{TOKENS['color']['bgTop']} → {TOKENS['color']['bg']}（上→下漸變）＋ 中心藍色光暈</td></tr>
    <tr><td>推桿數</td><td>{TOKENS['icon']['sliders']} 條（豎向）</td></tr>
    <tr><td>推鈕位置</td><td>{", ".join(str(k) for k in KNOBS)}（0=底、1=頂，高低錯落）</td></tr>
    <tr><td>軌道／推鈕闊</td><td>軌道 30% 欄闊 · 推鈕 76% 欄闊 · 推鈕高 8.5% 邊長</td></tr>
    <tr><td>主要色</td><td>推鈕 {TOKENS['color']['accent']} · 已推段 #2A5C93 · 軌道 #3C434E · 高光 #F4F6F8</td></tr>
    <tr><td>外框</td><td>1.4% 邊長幼線 #5A626E（深色桌布上都有輪廓）</td></tr>
    <tr><td>生成腳本</td><td><code>tools/make_eq_icon.py</code>（PIL，改一行改色／推桿數，5 個密度一次出齊）</td></tr>
  </table>
</div>

<h2>② App UI（實機截圖 · 1920×1080 車機版面）</h2>
<div class="card"><img class="shot" src="ui-1.png" alt="UI 1">
  <div class="legend" style="margin-top:8px">EQ 開關 = 「EQ 開」（**粗線框變動態黃**）：頂欄綠字 <span class="acc">✅ 已開 ｜ 自訂 31 段（20 Hz–20 kHz）· 生效中</span></div></div>
<div class="card"><img class="shot" src="ui-2.png" alt="UI 2">
  <div class="legend" style="margin-top:8px">EQ 開關 = 「EQ 關」：粗線框**還原做原有灰粗線**，頂欄轉橙字 <span class="warnc">⏸ 已關（原聲）</span></div></div>

<div class="card"><img class="shot" src="ui-3.png" alt="UI 3">
  <div class="legend" style="margin-top:8px">設定頁（撳 ⚙️ 設定）：開機自動打開 EQ ／ 開 app 自動設 EQ 為開 ／ EQ 引擎，右邊 Switch 顯示開／關；下面「現況」顯示引擎、段數、EQ 狀態</div></div>

<div class="card"><h3>版面結構（由上到下 5 行）</h3>
  <table>
    <tr><th>行</th><th>內容</th><th>高</th></tr>
    <tr><td>1</td><td><b>7X=U</b> ＋ <b>EQ 開／關大掣</b> ＋ <b>⚙️ 設定</b>（入設定頁）（粗線框：開＝動態黃脈動＋黃光暈，關＝灰粗線；固定喺標題右邊）＋ 媒體音樂 EQ 狀態字（綠＝開、橙＝關、紅＝未掛上）</td><td>{TOKENS['rowDp']['top']} dp</td></tr>
    <tr><td>2</td><td>EQ 曲線 chips（平坦／低音+／低音−／人聲／搖滾／流行／古典／爵士／響度／V 型／車廂補償／重低音／★自訂）＋ 💾 儲存（可橫向捲動）</td><td>{TOKENS['rowDp']['presets']} dp</td></tr>
    <tr><td>3</td><td><b>31 段圖形 EQ</b>：31 條豎柱可拖（雙指可同時拖兩段）、綠色曲線＝實際響應、橙色虛線＝車機真正生效嘅段位置</td><td>佔滿剩餘</td></tr>
    <tr><td>4</td><td>測試音（單頻／粉紅噪音／白噪音／掃頻，撳一下播、再撳停）｜<b>1K Tone</b>｜🔄 重新掛上｜引擎：自訂／車機原生｜<b>開機自動開：開／關</b></td><td>{TOKENS['rowDp']['controls']} dp</td></tr>
    <tr><td>5</td><td>前級 slider ／ 音量 slider ／ ↺ 全段歸零 ＋ 狀態行（2 行，封頂唔遮控制區）</td><td>{TOKENS['rowDp']['sliders']}+{TOKENS['rowDp']['status']} dp</td></tr>
  </table>
</div>

<div class="card"><h3>色板（Design tokens）</h3>
  <div class="two"><div>{''.join(swatch(k) for k in ["bg", "bgTop", "surface", "surface2", "stroke", "text", "textDim"])}</div>
  <div>{''.join(swatch(k) for k in ["accent", "onAccent", "cut", "ok", "warn", "danger"])}</div></div>
  <div class="legend" style="margin-top:8px">完整 token（顏色／圓角／字級／行高）：<a href="tokens.json" download>tokens.json</a></div>
</div>

<div class="card"><h3>字級（車機遠距離睇：掣大字）</h3>
  <table><tr><th>用途</th><th>字級</th></tr>
    <tr><td>標題</td><td>{TOKENS['fontSp']['title']} sp</td></tr>
    <tr><td>正在調節大字</td><td>{TOKENS['fontSp']['big']} sp</td></tr>
    <tr><td>按鈕</td><td>{TOKENS['fontSp']['button']} sp</td></tr>
    <tr><td>分段掣</td><td>{TOKENS['fontSp']['segment']} sp</td></tr>
    <tr><td>內文／狀態</td><td>{TOKENS['fontSp']['body']} sp</td></tr>
  </table>
</div>

<h2>③ 31 段頻率（ISO 1/3 八度）</h2>
<div class="card"><table><tr><th>#</th><th>頻率</th><th>標籤</th></tr>{freq_rows}</table>
  <div class="legend" style="margin-top:8px">每段 ±12 dB、Q = 4.3185（1/3 八度）；DSP = 31 個串聯 peaking biquad ＋ 軟限幅。</div>
</div>

<h2>④ 檔案</h2>
<div class="card legend">
  APK：<a href="7XU-1.8-arm64.apk">7XU-1.8-arm64.apk</a>（v1.8）·
  icon 向量：<a href="icon.svg">icon.svg</a> · <a href="icon-round.svg">icon-round.svg</a> ·
  tokens：<a href="tokens.json">tokens.json</a> ·
  生成腳本：<code>車機-eq/tools/make_eq_icon.py</code>、<code>車機-eq/tools/make_design_page.py</code>
</div>
</div></body></html>"""
    open(os.path.join(OUT, "index.html"), "w", encoding="utf-8").write(html)
    print("寫好：", OUT)
    for f in sorted(os.listdir(OUT)):
        print("  ", f, os.path.getsize(os.path.join(OUT, f)), "B")


if __name__ == "__main__":
    main()
