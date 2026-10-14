#!/usr/bin/env python3
"""7X=U（7X=U）app icon：31 段 EQ 調音推桿圖案。

出 5 個密度嘅 mipmap（ic_launcher.png / ic_launcher_round.png）＋ xxxhdpi 前景。
用法：python3 tools/make_eq_icon.py
"""
import os
from PIL import Image, ImageDraw, ImageFilter

BASE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..",
                    "app/src/main/res")
DENS = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
S = 8                                     # 超取樣倍數（畫大再縮，邊緣才順）

BG_TOP = (0x1A, 0x1F, 0x28, 255)
BG_BOT = (0x08, 0x0A, 0x0E, 255)
TRACK = (0x3C, 0x43, 0x4E, 255)   # 對比高啲，細圖都睇得出軌道
TRACK_DIM = (0x2A, 0x30, 0x38, 255)
ACCENT = (0x4D, 0xA3, 0xFF, 255)
ACCENT_DIM = (0x2A, 0x5C, 0x93, 255)
WHITE = (0xF4, 0xF6, 0xF8, 255)
EDGE = (0x5A, 0x62, 0x6E, 255)         # 外框幼線：深色桌面上都有輪廓
# 5 條推桿嘅位置（0 = 底，1 = 頂）—— 高低錯落，一眼認出係調音台
KNOBS = [0.66, 0.34, 0.78, 0.28, 0.55]


def icon_px(size, round_icon):
    W = size * S
    im = Image.new("RGBA", (W, W), (0, 0, 0, 0))
    dr = ImageDraw.Draw(im)

    # ---- 底：深色（方 = 圓角方；圓 = 圓）＋ 由上而下漸變 ----
    if round_icon:
        dr.ellipse([0, 0, W - 1, W - 1], fill=BG_BOT)
        shape = Image.new("L", (W, W), 0)
        ImageDraw.Draw(shape).ellipse([0, 0, W - 1, W - 1], fill=255)
    else:
        dr.rounded_rectangle([0, 0, W - 1, W - 1], radius=int(W * 0.22), fill=BG_BOT)
        shape = Image.new("L", (W, W), 0)
        ImageDraw.Draw(shape).rounded_rectangle([0, 0, W - 1, W - 1], radius=int(W * 0.22),
                                                fill=255)
    grad = Image.new("RGBA", (W, W))
    gd = grad.load()
    for y in range(W):
        t = y / (W - 1)
        gd_row = tuple(int(BG_TOP[i] + (BG_BOT[i] - BG_TOP[i]) * t) for i in range(4))
        for x in range(W):
            gd[x, y] = gd_row
    im.paste(grad, (0, 0), shape)
    dr = ImageDraw.Draw(im)

    # ---- 藍色光暈（令細圖都睇得出有嘢）----
    glow = Image.new("RGBA", (W, W), (0, 0, 0, 0))
    ImageDraw.Draw(glow).ellipse([W * 0.16, W * 0.20, W * 0.84, W * 0.80],
                                 fill=(0x4D, 0xA3, 0xFF, 70))
    glow = glow.filter(ImageFilter.GaussianBlur(W * 0.10))
    im.alpha_composite(Image.composite(glow, Image.new("RGBA", (W, W), (0, 0, 0, 0)), shape))
    dr = ImageDraw.Draw(im)

    # ---- 5 條推桿 ----
    n = len(KNOBS)
    margin = W * 0.15
    usable = W - 2 * margin
    cw = usable / n
    top, bot = W * 0.14, W * 0.86
    tw = cw * 0.30                      # 軌道闊
    kw = cw * 0.76                      # 手柄闊
    kh = W * 0.085                      # 手柄高
    for i, pos in enumerate(KNOBS):
        cx = margin + (i + 0.5) * cw
        # 軌道
        dr.rounded_rectangle([cx - tw / 2, top, cx + tw / 2, bot],
                             radius=tw / 2, fill=TRACK)
        ky = bot - (bot - top) * pos
        # 由底到手柄嘅「已推」藍色
        dr.rounded_rectangle([cx - tw / 2, ky, cx + tw / 2, bot],
                             radius=tw / 2, fill=ACCENT_DIM)
        # 手柄（藍色推鈕 + 高光）
        dr.rounded_rectangle([cx - kw / 2, ky - kh / 2, cx + kw / 2, ky + kh / 2],
                             radius=kh * 0.34, fill=ACCENT)
        dr.rounded_rectangle([cx - kw / 2 + kh * 0.12, ky - kh / 2 + kh * 0.14,
                              cx + kw / 2 - kh * 0.12, ky - kh * 0.12],
                             radius=kh * 0.22, fill=WHITE)

    # 外框幼線（深色 wall paper 上都有輪廓）
    dr = ImageDraw.Draw(im)
    lw = max(2, int(W * 0.014))
    if round_icon:
        dr.ellipse([lw / 2, lw / 2, W - 1 - lw / 2, W - 1 - lw / 2], outline=EDGE, width=lw)
    else:
        dr.rounded_rectangle([lw / 2, lw / 2, W - 1 - lw / 2, W - 1 - lw / 2],
                             radius=int(W * 0.22), outline=EDGE, width=lw)
    return im.resize((size, size), Image.LANCZOS)


def main():
    for den, size in DENS.items():
        d = os.path.join(BASE, "mipmap-" + den)
        os.makedirs(d, exist_ok=True)
        icon_px(size, False).save(os.path.join(d, "ic_launcher.png"))
        icon_px(size, True).save(os.path.join(d, "ic_launcher_round.png"))
        print("  ", den, size, "→ ic_launcher.png / ic_launcher_round.png")
    # 前景（未來 adaptive icon 用；今日冇 anydpi-v26 所以唔會被用）
    fg = icon_px(432, False)
    fg.save(os.path.join(BASE, "mipmap-xxxhdpi", "ic_launcher_foreground.png"))
    # 預覽（大圖，用嚟睇設計）
    prev = icon_px(384, False)
    prev.save("/tmp/eq_icon_preview.png")
    print("預覽：/tmp/eq_icon_preview.png")


if __name__ == "__main__":
    main()
