/*
 * 離線 DSP 驗證（唔屬 app，唔會入 APK）。
 * 用真嘅 Dsp.java / Gen.java（唔係 Python 重寫）跑：
 *   javac -encoding UTF-8 -d /tmp/dspcheck app/src/main/java/com/sevenxu/eq/{Bands,Dsp,Gen,Presets}.java tools/DspCheck.java
 *   java -cp /tmp/dspcheck DspCheck
 */
import com.sevenxu.eq.Bands;
import com.sevenxu.eq.Dsp;
import com.sevenxu.eq.Gen;
import com.sevenxu.eq.Presets;

public class DspCheck {

    static int pass = 0, fail = 0;

    static void ok(String what, boolean cond, String detail) {
        if (cond) {
            pass++;
            System.out.printf("  ✅ %-52s %s%n", what, detail);
        } else {
            fail++;
            System.out.printf("  ❌ %-52s %s%n", what, detail);
        }
    }

    public static void main(String[] args) {
        int rate = 44100;
        System.out.println("=== 1) 響應曲線（解析解） ===");
        for (double f : new double[]{100, 1000, 10000}) {
            float[] g = Presets.zeros();
            int b = Bands.nearest(f);
            g[b] = 12;
            double r = Dsp.responseDb(g, f, rate);
            ok(String.format("1k 段 +12 dB → %.0f Hz 響應", f), Math.abs(r - 12) < 0.8,
                    String.format("實測 %.2f dB（段中心 %.0f Hz，Q=%.3f）", r, Bands.FREQ[b], Bands.Q));
        }
        {
            float[] g = Presets.zeros();
            g[Bands.nearest(1000)] = -12;
            double r = Dsp.responseDb(g, 1000, rate);
            ok("-12 dB @ 1k 段", Math.abs(r + 12) < 0.8, String.format("實測 %.2f dB", r));
        }
        {
            float[] g = Presets.zeros();
            g[Bands.nearest(1000)] = 12;
            double far = Dsp.responseDb(g, 60, rate);
            ok("1k 段 +12 對 60 Hz 影響（1/3 八度唔應太寬）", Math.abs(far) < 0.6,
                    String.format("60 Hz 側漏 %.2f dB", far));
        }
        {
            float[] g = Presets.zeros();
            double r = Dsp.responseDb(g, 1000, rate);
            ok("全 0 dB = 平坦", Math.abs(r) < 1e-9, String.format("%.3f dB", r));
        }

        System.out.println("=== 2) 真正過 filter（單頻正弦 + RMS 比值，唔受窗函數影響） ===");
        int[] boosts = {12, -12, 6, 0};
        for (int boost : boosts) {
            double measured = measureGain(rate, 1000, boost);
            ok(String.format("1k 段 %+d dB → 實測量測差值", boost),
                    Math.abs(measured - boost) < 0.2, String.format("%+.2f dB", measured));
        }
        for (int bandIdx : new int[]{3, 10, 20, 30}) {
            double measured = measureGain(rate, Bands.FREQ[bandIdx], 9);
            ok(String.format("%s Hz 段 +9 dB → 實測", Bands.LABEL[bandIdx]),
                    Math.abs(measured - 9) < 0.3, String.format("%+.2f dB", measured));
        }
        {
            // 寬頻：粉紅噪音，睇 1 kHz 能量差（同解析解對照，容差大啲）
            double m = measurePinkGain(rate, 1000, 12);
            ok("粉紅噪音 1k 段 +12 dB（寬頻實測）", Math.abs(m - 12) < 1.5, String.format("%+.2f dB", m));
        }

        System.out.println("=== 3) 全段 +12 嘅穩定性 / 限幅 ===");
        {
            Dsp d = new Dsp();
            d.setRate(rate);
            float[] g = new float[Bands.N];
            java.util.Arrays.fill(g, 12);
            d.setGainsNow(g);
            float[] l = new float[4096];
            float[] r = new float[4096];
            Gen gen = new Gen();
            double peak = 0;
            boolean nan = false;
            for (int blk = 0; blk < 200; blk++) {
                gen.fill(l, r, l.length, rate, Gen.PINK, 0, 0.25f);
                d.process(l, r, l.length);
                for (float v : l) {
                    if (Float.isNaN(v) || Float.isInfinite(v)) nan = true;
                    peak = Math.max(peak, Math.abs(v));
                }
            }
            ok("31 段全 +12：冇 NaN/爆音", !nan && peak <= 0.96 && peak > 0.1,
                    String.format("峯值 %.4f（限幅後 ≤ 0.94）", peak));
            ok("輸出量測有數（RMS dBFS）", d.outRmsDb() < 0 && d.outRmsDb() > -40,
                    String.format("RMS %.1f dBFS / 峯值 %.1f dBFS", d.outRmsDb(), d.outPeakDb()));
        }

        System.out.println("=== 4) 平滑（拖滑桿唔會一下爆） ===");
        {
            Dsp d = new Dsp();
            d.setRate(rate);
            float[] l = new float[1024];
            float[] r = l;
            Gen gen = new Gen();
            d.setGainsNow(Presets.zeros());
            d.set(Bands.nearest(1000), 12);
            ok("一改 target 就報 settling", d.settling(), "settling()=true");
            int blocks = 0;
            while (d.settling() && blocks < 200) {
                gen.fill(l, r, l.length, rate, Gen.PINK, 0, 0.2f);
                d.process(l, r, l.length);
                blocks++;
            }
            double ms = blocks * 1024.0 / rate * 1000;
            ok("平滑用時 < 400 ms", !d.settling() && ms < 400, String.format("%.0f ms / %d blocks", ms, blocks));
        }

        System.out.println("=== 5) 採樣率 / 48 kHz ===");
        {
            Dsp d = new Dsp();
            d.setRate(48000);
            float[] g = Presets.zeros();
            g[Bands.nearest(1000)] = 12;
            d.setGainsNow(g);
            double rr = Dsp.responseDb(g, 1000, 48000);
            ok("48 kHz 下 1k +12", Math.abs(rr - 12) < 0.8, String.format("%.2f dB", rr));
            double high = Dsp.responseDb(g, 20000, 48000);
            ok("48 kHz 下 20 kHz 段仍然有限（≤ 0.5 dB 側漏）", Math.abs(high) < 0.5, String.format("%.2f dB", high));
        }

        System.out.println("=== 6) Preset 曲線合理（每段都在 ±12 內、級聯總增益有限） ===");
        {
            for (Presets.P p : Presets.builtin()) {
                float mx = 0, mn = 0;
                for (float v : p.gains) {
                    mx = Math.max(mx, v);
                    mn = Math.min(mn, v);
                }
                double worst = 0;
                for (int i = 0; i < Bands.N; i++) worst = Math.max(worst, Dsp.responseDb(p.gains, Bands.FREQ[i], rate));
                // 過一次真噪音，確認限幅後冇爆
                Dsp d = new Dsp();
                d.setRate(rate);
                d.setGainsNow(p.gains);
                float[] l = new float[4096];
                float[] r = l;
                Gen g2 = new Gen();
                double peak = 0;
                for (int i = 0; i < 60; i++) {
                    g2.fill(l, r, l.length, rate, Gen.PINK, 0, 0.3f);
                    d.process(l, r, l.length);
                    for (float v : l) peak = Math.max(peak, Math.abs(v));
                }
                ok(String.format("preset「%s」", p.name),
                        mx <= 12.01 && mn >= -12.01 && worst <= 15.0 && peak <= 0.96,
                        String.format("max %+.1f / min %+.1f dB，段中心最壞 %+.1f dB，噪音峯值 %.3f", mx, mn, worst, peak));
            }
        }

        System.out.println();
        System.out.println("結果： " + pass + " 通過 / " + fail + " 失敗");
        if (fail > 0) System.exit(1);
    }

    /**
     * 用單頻正弦量測某段嘅真實增益：輸出 RMS ÷ 平坦 RMS（同一粒正弦、同一條路）。
     * 幅度用 0.01 → 限幅器處於線性區，比值 = 濾波器喺該頻率嘅增益。
     */
    static double measureGain(int rate, double freq, double gainDb) {
        double on = sineRms(rate, freq, gainDb);
        double off = sineRms(rate, freq, 0);
        if (off <= 1e-12) return 0;
        return 20 * Math.log10(on / off);
    }

    static double sineRms(int rate, double freq, double gainDb) {
        Dsp d = new Dsp();
        d.setRate(rate);
        float[] g = Presets.zeros();
        g[Bands.nearest(freq)] = (float) gainDb;
        d.setGainsNow(g);
        float[] l = new float[4096];
        float[] r = l;
        Gen gen = new Gen();
        for (int i = 0; i < 40; i++) {              // 暖機（filter 穩態）
            gen.fill(l, r, l.length, rate, Gen.SINE, freq, 0.01f);
            d.process(l, r, l.length);
        }
        double acc = 0;
        int n = 0;
        for (int i = 0; i < 40; i++) {              // 量測 40 blocks
            gen.fill(l, r, l.length, rate, Gen.SINE, freq, 0.01f);
            d.process(l, r, l.length);
            for (float v : l) {
                acc += v * v;
                n++;
            }
        }
        return Math.sqrt(acc / Math.max(1, n));
    }

    /** 寬頻（粉紅噪音）版本：1 kHz 附近能量差，用 Goertzel 平均。 */
    static double measurePinkGain(int rate, double freq, double gainDb) {
        return pinkMag(rate, freq, gainDb) - pinkMag(rate, freq, 0);
    }

    static double pinkMag(int rate, double freq, double gainDb) {
        Dsp d = new Dsp();
        d.setRate(rate);
        float[] g = Presets.zeros();
        g[Bands.nearest(freq)] = (float) gainDb;
        d.setGainsNow(g);
        float[] l = new float[8192];
        float[] r = l;
        Gen gen = new Gen();
        for (int i = 0; i < 20; i++) {
            gen.fill(l, r, l.length, rate, Gen.PINK, 0, 0.25f);
            d.process(l, r, l.length);
        }
        double acc = 0;
        int n = 0;
        for (int i = 0; i < 40; i++) {
            gen.fill(l, r, l.length, rate, Gen.PINK, 0, 0.25f);
            d.process(l, r, l.length);
            acc += Gen.magDb(l, l.length, freq, rate);
            n++;
        }
        return acc / n;
    }
}
