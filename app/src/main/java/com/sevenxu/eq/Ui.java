package com.sevenxu.eq;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 車機 UI 風格（深色、幼邊框分格、大觸控區、遠距離睇得清）。
 *
 * <p>跟 Lun 嘅車機偏好：按鈕文字要大（18sp）、卡片框起、間距緊貼、淺色幼界線代替大間距。</p>
 */
public final class Ui {

    public static final int BG = 0xFF0E0F12;
    public static final int BG_TOP = 0xFF141821;
    public static final int SURFACE = 0xFF171A20;
    public static final int SURFACE_2 = 0xFF1F232B;
    public static final int STROKE = 0xFF2C3138;
    public static final int TEXT = 0xFFF4F6F8;
    public static final int DIM = 0xFFA9AEB6;
    public static final int ACCENT = 0xFF4DA3FF;
    public static final int ACCENT_SOFT = 0x334DA3FF;
    public static final int ON_ACCENT = 0xFF07131F;
    public static final int DANGER = 0xFFE5484D;
    public static final int OK = 0xFF3DD68C;
    public static final int WARN = 0xFFFFC24B;
    public static final int CUT = 0xFFFF8A5B;      // 負增益用色（橙）
    public static final int SPEC = 0x773DD68C;     // 頻譜柱（綠、半透明）

    public static final int R_CARD = 14;
    public static final int R_BTN = 10;
    public static final int PAD = 10;
    public static final int GAP = 8;

    public static final float BTN_TEXT = 18f;
    public static final float BTN_TEXT_SM = 15f;

    private Ui() {
    }

    public static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    public static int sp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().scaledDensity);
    }

    /** 圓角 + 幼邊框底（車機風格）。 */
    public static GradientDrawable box(int fill, int stroke, float radiusDp, Context c) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(fill);
        d.setCornerRadius(dp(c, radiusDp));
        if (stroke != 0) d.setStroke(Math.max(1, dp(c, 1)), stroke);
        return d;
    }

    /** 主背景（輕微斜向漸變）。 */
    public static GradientDrawable bg() {
        return new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{BG_TOP, BG});
    }

    public static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    public static LinearLayout col(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    /** 卡片容器（有框）。 */
    public static LinearLayout card(Context c) {
        LinearLayout l = col(c);
        l.setBackground(box(SURFACE, STROKE, R_CARD, c));
        l.setPadding(dp(c, PAD), dp(c, GAP), dp(c, PAD), dp(c, GAP));
        return l;
    }

    public static TextView text(Context c, String s, float sizeSp, int colour) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sizeSp);
        t.setTextColor(colour);
        t.setIncludeFontPadding(false);
        return t;
    }

    public static TextView title(Context c, String s) {
        TextView t = text(c, s, 20f, TEXT);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    /** 一般按鈕（大觸控區、文字 18sp、圓角）。 */
    public static Button button(Context c, String s) {
        Button b = new Button(c);
        b.setText(s);
        b.setTextSize(BTN_TEXT);
        b.setAllCaps(false);
        b.setIncludeFontPadding(false);
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(dp(c, 46));
        b.setMinimumHeight(dp(c, 46));
        b.setPadding(dp(c, 4), 0, dp(c, 4), 0);
        b.setBackground(box(SURFACE_2, STROKE, R_BTN, c));
        b.setTextColor(TEXT);
        return b;
    }

    /** 分段選擇掣（選中 = accent 實心）。 */
    public static Button segment(Context c, String s) {
        Button b = button(c, s);
        b.setTextSize(BTN_TEXT_SM);
        return b;
    }

    public static void setActive(Button b, boolean active) {
        b.setBackground(box(active ? ACCENT : SURFACE_2, active ? ACCENT : STROKE, R_BTN,
                b.getContext()));
        b.setTextColor(active ? ON_ACCENT : TEXT);
        b.setTypeface(active ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
    }

    public static void setAccent(Button b, boolean accent) {
        b.setBackground(box(accent ? ACCENT : SURFACE_2, accent ? ACCENT : STROKE, R_BTN,
                b.getContext()));
        b.setTextColor(accent ? ON_ACCENT : TEXT);
    }

    /** 橫向分隔幼線。 */
    public static View divider(Context c) {
        View v = new View(c);
        v.setBackgroundColor(STROKE);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(c, 1))));
        return v;
    }

    public static LinearLayout.LayoutParams lp(int w, int h, float weight) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.weight = weight;
        return p;
    }

    /** 小標籤（例如「前級」「音量」）。 */
    public static TextView label(Context c, String s) {
        TextView t = text(c, s, 14f, DIM);
        return t;
    }

    public static void pad(View v, int leftDp) {
        v.setPadding(dp(v.getContext(), leftDp), 0, 0, 0);
    }

    public static int alpha(int colour, float a) {
        return (colour & 0x00FFFFFF) | (Math.round(255 * Math.max(0, Math.min(1, a))) << 24);
    }

    public static int mix(int a, int b, float t) {
        int ar = Color.red(a), ag = Color.green(a), ab = Color.blue(a);
        int br = Color.red(b), bgc = Color.green(b), bb = Color.blue(b);
        return Color.rgb((int) (ar + (br - ar) * t), (int) (ag + (bgc - ag) * t),
                (int) (ab + (bb - ab) * t));
    }
}
