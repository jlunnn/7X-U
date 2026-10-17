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

    public static final int BG = 0xFF0D110D;
    public static final int BG_TOP = 0xFF0D110D;
    public static final int SURFACE = 0xFF1A1F16;
    public static final int SURFACE_2 = 0xFF1A1F16;
    public static final int STROKE = 0xFF1E2418;
    public static final int TEXT = 0xFFFFFFFF;
    public static final int DIM = 0xFFA0A89A;
    public static final int ACCENT = 0xFFB6FF3B;
    public static final int ACCENT_SOFT = 0x33B6FF3B;
    public static final int ON_ACCENT = 0xFF1A1A1A;
    public static final int DANGER = 0xFFFF6B5B;
    public static final int OK = 0xFFB6FF3B;
    public static final int WARN = 0xFFB6FF3B;
    public static final int CUT = 0xFF8A6A4A;      // 負增益用色（橙）
    public static final int SPEC = 0x88B6FF3B;     // 頻譜柱（綠、半透明）

    // EQ-Control 掣款式
    public static final int BTN_FILL = 0xFF1A1F16;
    public static final int BTN_LINE = 0xFF2C3526;
    public static final int BTN_TXT = 0xFFA0A89A;
    public static final int BTN_R = 8;
    public static final int R_CARD = 16;
    public static final int R_BTN = 12;
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


    /** EQ-Control 滑桿：track（未填 #2A3025、已填 #B6FF3B、4dp 高、圓角 2）。 */
    public static android.graphics.drawable.Drawable seekTrack(Context c) {
        android.graphics.drawable.GradientDrawable bgd = new android.graphics.drawable.GradientDrawable();
        bgd.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        bgd.setColor(0xFF2A3025);
        bgd.setCornerRadius(dp(c, 2));
        bgd.setSize(0, dp(c, 4));
        android.graphics.drawable.GradientDrawable pgd = new android.graphics.drawable.GradientDrawable();
        pgd.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        pgd.setColor(0xFFB6FF3B);
        pgd.setCornerRadius(dp(c, 2));
        pgd.setSize(0, dp(c, 4));
        android.graphics.drawable.LayerDrawable d = new android.graphics.drawable.LayerDrawable(
                new android.graphics.drawable.Drawable[]{bgd, pgd});
        d.setId(0, android.R.id.background);
        d.setId(1, android.R.id.progress);
        d.setLayerInset(1, dp(c, 6), 0, dp(c, 6), 0);      // 已填部分內縮（thumb 半徑）
        return d;
    }


    /** EQ-Control 滑桿 thumb（圓形 Ø12 + 深描邊）。 */
    public static android.graphics.drawable.Drawable seekThumbCircle(Context c) {
        android.graphics.drawable.GradientDrawable t = new android.graphics.drawable.GradientDrawable();
        t.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        t.setColor(0xFFB6FF3B);
        t.setStroke(Math.max(1, dp(c, 1)), 0xFF0D110D);
        t.setSize(dp(c, 12), dp(c, 12));
        return t;
    }

    /** EQ-Control 滑桿 thumb：Ø12 霓虹綠。 */
    /** EQ-Control 滑桿 thumb：🎚 fader 圖示（高度 = bar ×1.1，手繪唔靠字型）。 */
    public static android.graphics.drawable.Drawable seekThumb(Context c) {
        int s = Math.round(dp(c, 22) * 1.1f);          // bar 高度 + 10%
        android.graphics.Bitmap bm = android.graphics.Bitmap.createBitmap(
                s, s, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas cv = new android.graphics.Canvas(bm);
        android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        float cx = s / 2f;
        // 直立槽
        p.setColor(0xFF0D110D);
        cv.drawRoundRect(cx - s * 0.10f, s * 0.06f, cx + s * 0.10f, s * 0.94f,
                s * 0.10f, s * 0.10f, p);
        p.setColor(0xFF2A3025);
        cv.drawRoundRect(cx - s * 0.075f, s * 0.10f, cx + s * 0.075f, s * 0.90f,
                s * 0.075f, s * 0.075f, p);
        // 霓虹旋鈕（中段）
        p.setColor(0xFFB6FF3B);
        cv.drawCircle(cx, s / 2f, s * 0.26f, p);
        p.setColor(0xFF0D110D);
        cv.drawCircle(cx, s / 2f, s * 0.10f, p);
        android.graphics.drawable.BitmapDrawable d =
                new android.graphics.drawable.BitmapDrawable(c.getResources(), bm);
        d.setBounds(0, 0, s, s);
        return d;
    }

    /** EQ-Control 文字風格（大寫、11sp、灰、medium、字距）。 */
    public static void tagStyle(TextView t) {
        t.setTextSize(11f);
        t.setTextColor(BTN_TXT);
        t.setAllCaps(true);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        try {
            t.setLetterSpacing(0.06f);
        } catch (Throwable ignored) {
        }
    }

    /** EQ-Control 數值文字風格。 */
    public static void valStyle(TextView t) {
        t.setTextSize(12f);
        t.setTextColor(0xFFFFFFFF);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        try {
            t.setLetterSpacing(0.04f);
        } catch (Throwable ignored) {
        }
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
    /** 一般按鈕（EQ-Control 款式：#1A1F16 底、1px 框、圓角 8、灰字）。 */
    public static Button button(Context c, String s) {
        Button b = new Button(c);
        b.setText(s);
        b.setTextSize(BTN_TEXT);
        b.setAllCaps(false);
        b.setIncludeFontPadding(false);
        b.setSingleLine(true);
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(dp(c, 46));
        b.setMinimumHeight(dp(c, 46));
        b.setPadding(dp(c, 2), 0, dp(c, 2), 0);      // 框內文字左右各 2px
        b.setBackground(box(BTN_FILL, BTN_LINE, BTN_R, c));
        b.setTextColor(BTN_TXT);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        try {
            b.setLetterSpacing(0.06f);
        } catch (Throwable ignored) {
        }
        return b;
    }

    /** 分段選擇掣（選中 = accent 實心）。 */
    public static Button segment(Context c, String s) {
        Button b = button(c, s);
        b.setTextSize(BTN_TEXT_SM);
        return b;
    }

    public static void setActive(Button b, boolean active) {
        b.setBackground(box(active ? 0xFF243024 : BTN_FILL, active ? ACCENT : BTN_LINE, BTN_R,
                b.getContext()));
        b.setTextColor(active ? ACCENT : BTN_TXT);
    }

    public static void setAccent(Button b, boolean accent) {
        b.setBackground(box(accent ? 0xFF243024 : BTN_FILL, accent ? ACCENT : BTN_LINE, BTN_R,
                b.getContext()));
        b.setTextColor(accent ? ACCENT : BTN_TXT);
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
