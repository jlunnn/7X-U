package com.sevenxu.eq;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 音頻引擎：source（測試音／檔案／咪）→ 31 段 DSP → AudioTrack → 指定 BUS。
 *
 * <p>一條 AudioTrack（MODE_STREAM）做完所有嘢，跟鋼琴 app 實測有效嘅做法：
 * builder → setPreferredDevice → 先寫一段 → play()。</p>
 *
 * <p>⚠️ 車機會「收咗要求但唔出聲」，所以格式有階梯（目標格式 → 44.1k 單），
 * 而且每次都把結果寫入狀態行，用戶一睇就知係邊一層出事。</p>
 */
public final class Engine {

    public static final int M_TONE = 0;
    public static final int M_FILE = 1;
    public static final int M_MIC = 2;

    private static final int FRAMES = 1024;         // 一個 block
    private static final int MIC_RATE = 44100;
    private static final int SPEC_N = 4096;         // 頻譜窗

    private final Context ctx;
    private final Routes routes;
    private final Dsp dsp = new Dsp();
    private final Gen gen = new Gen();

    private volatile boolean run;
    private Thread thread;
    private AudioTrack track;
    private AudioRecord rec;
    private AudioFocusRequest focusReq;
    private AudioSource src;
    private MicSource micSrc;

    private volatile boolean playing;
    private volatile int mode = M_TONE;
    private volatile int route = Routes.INNER;
    private volatile int volPct = 70;

    // 測試音設定
    private volatile int toneKind = Gen.PINK;
    private volatile int toneBand = 17;             // 1 kHz
    private volatile float toneAmp = 0.30f;

    private volatile File file;

    private volatile String status = "未播放";
    private volatile String trackInfo = "";
    private volatile String lastError = "";
    private volatile int rate = 44100;
    private volatile boolean stereo;

    private final float[] spec = new float[Bands.N];
    private final float[] ring = new float[SPEC_N];
    private int ringPos;
    private int ringCount;
    private volatile float inLevelDb = -120f;
    private volatile float outRmsDb = -120f;
    private volatile float outPeakDb = -120f;

    public Engine(Context c, Routes r) {
        this.ctx = c.getApplicationContext();
        this.routes = r;
    }

    public Dsp dsp() {
        return dsp;
    }

    // ------------------------------------------------------------- 外界操作

    public boolean playing() {
        return playing;
    }

    public int mode() {
        return mode;
    }

    public int route() {
        return route;
    }

    public String status() {
        return status;
    }

    public String trackInfo() {
        return trackInfo;
    }

    public String lastError() {
        return lastError;
    }

    public float inLevelDb() {
        return inLevelDb;
    }

    public float outRmsDb() {
        return outRmsDb;
    }

    public float outPeakDb() {
        return outPeakDb;
    }

    public int volPct() {
        return volPct;
    }

    public void setVolPct(int v) {
        volPct = Math.max(0, Math.min(100, v));
    }

    public int toneKind() {
        return toneKind;
    }

    public void setToneKind(int k) {
        toneKind = k;
    }

    public int toneBand() {
        return toneBand;
    }

    /** 跟手指試聽：拖去邊一段，測試音就播嗰段中心頻率。 */
    public void setToneBand(int b) {
        toneBand = Bands.clampBand(b);
    }

    public float toneAmp() {
        return toneAmp;
    }

    public void setToneAmp(float a) {
        toneAmp = Math.max(0.02f, Math.min(0.8f, a));
    }

    public File file() {
        return file;
    }

    /** 頻譜（dBFS，31 段）；最後一次更新可能係 -120（未收到聲）。 */
    public float[] spectrum() {
        float[] o = new float[Bands.N];
        System.arraycopy(spec, 0, o, 0, Bands.N);
        return o;
    }

    /**
     * 開始播。
     *
     * @param mode  {@link #M_TONE}／{@link #M_FILE}／{@link #M_MIC}
     * @param route {@link Routes#INNER}／{@link Routes#OUTER}
     */
    public synchronized boolean start(int mode, int route) {
        stop();
        this.mode = mode;
        this.route = route;
        src = null;
        micSrc = null;

        int wantRate = MIC_RATE;
        int wantCh = 1;
        String what = "";

        if (mode == M_FILE) {
            if (file == null || !file.isFile()) {
                status = "❌ 未揀檔案";
                return false;
            }
            try {
                src = openFile(file);
                wantRate = src.rate();
                wantCh = src.channels() >= 2 ? 2 : 1;
                what = file.getName() + " · ";
            } catch (Throwable e) {
                lastError = "開檔案失敗：" + e;
                AppLog.e("EQ", lastError);
                status = "❌ 開唔到檔案（" + e.getClass().getSimpleName() + "）";
                return false;
            }
        } else if (mode == M_MIC) {
            if (!buildRecord()) {
                status = "❌ 開唔到咪（檢查錄音權限）";
                return false;
            }
            micSrc = new MicSource();
            src = micSrc;
            what = "咪 · ";
        } else {
            src = new ToneSource();
            wantRate = MIC_RATE;
            wantCh = 1;
            what = Gen.KIND_NAME[toneKind] + " · ";
        }

        AudioDeviceInfo dev = routes.device(route);
        if (dev == null) {
            AppLog.e("EQ", "搵唔到輸出裝置：" + (route == Routes.OUTER ? "車外" : "車內"));
        }
        requestFocus();
        if (!buildTrack(wantRate, wantCh, dev, routes.usage(route))) {
            status = "❌ 開唔到音頻輸出（" + Routes.friendly(route) + "）";
            closeSrc();
            return false;
        }
        dsp.setRate(rate);
        dsp.clearState();

        run = true;
        playing = true;
        status = what + Routes.friendly(route) + " · 播放中";
        thread = new Thread(this::loop, "eq-audio");
        thread.start();
        return true;
    }

    public synchronized void stop() {
        run = false;
        Thread t = thread;
        thread = null;
        if (t != null) {
            try {
                t.join(800);
            } catch (InterruptedException ignored) {
            }
        }
        releaseTrack();
        releaseRecord();
        abandonFocus();
        closeSrc();
        playing = false;
        if (!status.startsWith("❌")) status = "已停止";
    }

    /** 改設定（音色／route／檔案）之後即刻重開，唔使用戶再撳一次。 */
    public synchronized void restartIfPlaying() {
        if (!playing) return;
        int m = mode, r = route;
        start(m, r);
    }

    public void setFile(File f) {
        this.file = f;
    }

    // ------------------------------------------------------------- 音源實作

    private interface AudioSource {
        int read(float[] l, float[] r, int want) throws Throwable;

        int rate();

        int channels();

        void close();
    }

    /** 測試音（無限長）。 */
    private final class ToneSource implements AudioSource {
        private int lastBand = -1;
        private int fade;

        @Override
        public int read(float[] l, float[] r, int want) {
            int b = toneBand;
            double f;
            if (toneKind == Gen.SINE || toneKind == Gen.SWEEP) {
                f = Bands.FREQ[b];
            } else {
                f = Bands.FREQ[b];
            }
            if (b != lastBand) {
                lastBand = b;
                fade = rate / 60;                       // 16 ms 淡入，換頻唔會「啪」
            }
            gen.fill(l, r, want, rate, toneKind, f, toneAmp);
            if (fade > 0) {
                int n = Math.min(fade, want);
                for (int i = 0; i < n; i++) {
                    float g = (float) i / Math.max(1, fade);
                    l[i] *= g;
                    if (r != l) r[i] *= g;
                }
                fade -= n;
            }
            return want;
        }

        @Override
        public int rate() {
            return MIC_RATE;
        }

        @Override
        public int channels() {
            return 1;
        }

        @Override
        public void close() {
        }
    }

    /** 咪（實時 DSP）。 */
    private final class MicSource implements AudioSource {
        private final short[] buf = new short[FRAMES];

        @Override
        public int read(float[] l, float[] r, int want) {
            if (rec == null) return -1;
            int n = rec.read(buf, 0, want);
            if (n < 0) return 0;
            for (int i = 0; i < n; i++) {
                float v = buf[i] / 32768f;
                l[i] = v;
                if (r != l) r[i] = v;
            }
            if (mode == M_MIC) feedRing(l, n);
            return n;
        }

        @Override
        public int rate() {
            return MIC_RATE;
        }

        @Override
        public int channels() {
            return 1;
        }

        @Override
        public void close() {
        }
    }

    /** 由 MediaExtractor + MediaCodec 解碼（mp3／aac／m4a／ogg…）。 */
    private final class CodecSource implements AudioSource {
        private final MediaExtractor ex = new MediaExtractor();
        private final MediaCodec codec;
        private final int mRate;
        private final int mCh;
        private short[] pending;
        private int pendPos;
        private int pendLen;
        private boolean inputDone;
        private boolean eos;

        CodecSource(File f) throws Throwable {
            ex.setDataSource(f.getAbsolutePath());
            int idx = -1;
            MediaFormat fmt = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat mf = ex.getTrackFormat(i);
                String mime = mf.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    idx = i;
                    fmt = mf;
                    break;
                }
            }
            if (idx < 0) throw new IllegalStateException("冇音頻軌");
            ex.selectTrack(idx);
            mRate = fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                    ? fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44100;
            mCh = fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                    ? fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 2;
            codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME));
            codec.configure(fmt, null, null, 0);
            codec.start();
        }

        @Override
        public int read(float[] l, float[] r, int want) throws Throwable {
            int filled = 0;
            int guard = 0;
            while (filled < want) {
                if (pendPos >= pendLen) {
                    if (eos) return filled > 0 ? filled : -1;
                    drain();
                    if (pendPos >= pendLen) {
                        if (eos) return filled > 0 ? filled : -1;
                        if (++guard > 400) return filled > 0 ? filled : 0;
                        continue;
                    }
                }
                int avail = (pendLen - pendPos) / mCh;
                int take = Math.min(want - filled, avail);
                for (int i = 0; i < take; i++) {
                    if (mCh >= 2) {
                        float a = pending[pendPos + i * mCh] / 32768f;
                        float b = pending[pendPos + i * mCh + 1] / 32768f;
                        l[filled + i] = a;
                        if (r != l) r[filled + i] = b;
                    } else {
                        float a = pending[pendPos + i] / 32768f;
                        l[filled + i] = a;
                        if (r != l) r[filled + i] = a;
                    }
                }
                pendPos += take * mCh;
                filled += take;
            }
            return filled;
        }

        private void drain() {
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            if (!inputDone) {
                int inIdx = codec.dequeueInputBuffer(0);
                if (inIdx >= 0) {
                    ByteBuffer ib = codec.getInputBuffer(inIdx);
                    int size = ib == null ? -1 : ex.readSampleData(ib, 0);
                    if (size < 0) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inputDone = true;
                    } else {
                        long ts = ex.getSampleTime();
                        codec.queueInputBuffer(inIdx, 0, size, ts, 0);
                        ex.advance();
                    }
                }
            }
            int outIdx = codec.dequeueOutputBuffer(info, 5000);
            if (outIdx >= 0) {
                if (info.size > 0) {
                    ByteBuffer ob = codec.getOutputBuffer(outIdx);
                    int shorts = info.size / 2;
                    if (pending == null || pending.length < shorts) pending = new short[Math.max(8192, shorts)];
                    if (ob != null) {
                        ob.position(info.offset);
                        ob.limit(info.offset + info.size);
                        ob.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pending, 0, shorts);
                    }
                    pendPos = 0;
                    pendLen = shorts;
                }
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) eos = true;
                codec.releaseOutputBuffer(outIdx, false);
            } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat nf = codec.getOutputFormat();
                AppLog.d("EQ", "解碼格式：" + nf);
            } else if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (inputDone) eos = true;
            }
        }

        @Override
        public int rate() {
            return mRate;
        }

        @Override
        public int channels() {
            return mCh;
        }

        @Override
        public void close() {
            try {
                codec.stop();
            } catch (Throwable ignored) {
            }
            try {
                codec.release();
            } catch (Throwable ignored) {
            }
            try {
                ex.release();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 後備：自己解 PCM WAV（MediaExtractor 唔一定食 WAV）。 */
    private final class WavSource implements AudioSource {
        private final RandomAccessFile raf;
        private final long dataStart;
        private final long dataLen;
        private final int mRate;
        private final int mCh;
        private final byte[] bytes = new byte[FRAMES * 8];
        private long read;

        WavSource(File f) throws Throwable {
            raf = new RandomAccessFile(f, "r");
            byte[] hdr = new byte[12];
            raf.readFully(hdr);
            if (!(hdr[0] == 'R' && hdr[1] == 'I' && hdr[2] == 'F' && hdr[3] == 'F')) {
                throw new IllegalStateException("唔係 WAV");
            }
            int mRate0 = 44100;
            int mCh0 = 2;
            long off = 12;
            long len = 0;
            while (off < raf.length() - 8) {
                raf.seek(off);
                byte[] ch = new byte[8];
                raf.readFully(ch);
                String id = new String(ch, 0, 4, "US-ASCII");
                int sz = (ch[4] & 0xff) | ((ch[5] & 0xff) << 8) | ((ch[6] & 0xff) << 16) | ((ch[7] & 0xff) << 24);
                if (id.equals("fmt ")) {
                    byte[] fb = new byte[Math.min(sz, 16)];
                    raf.readFully(fb);
                    mCh0 = (fb[2] & 0xff) | ((fb[3] & 0xff) << 8);
                    mRate0 = (fb[4] & 0xff) | ((fb[5] & 0xff) << 8) | ((fb[6] & 0xff) << 16) | ((fb[7] & 0xff) << 24);
                } else if (id.equals("data")) {
                    off += 8;
                    len = sz;
                    break;
                }
                off += 8 + sz + (sz % 2);
            }
            dataStart = off;
            dataLen = len > 0 ? len : (raf.length() - off);
            mRate = mRate0 <= 0 ? 44100 : mRate0;
            mCh = mCh0 <= 0 ? 1 : mCh0;
        }

        @Override
        public int read(float[] l, float[] r, int want) throws Throwable {
            if (read >= dataLen) return -1;
            int frameBytes = 2 * mCh;
            long left = Math.min(dataLen - read, (long) want * frameBytes);
            int bytesWanted = (int) (left - (left % frameBytes));
            if (bytesWanted <= 0) return -1;
            raf.seek(dataStart + read);
            int got = raf.read(bytes, 0, Math.min(bytesWanted, bytes.length));
            if (got <= 0) return -1;
            read += got;
            int frames = got / frameBytes;
            for (int i = 0; i < frames; i++) {
                short a = (short) (((bytes[i * frameBytes + 1] & 0xff) << 8) | (bytes[i * frameBytes] & 0xff));
                l[i] = a / 32768f;
                if (mCh >= 2 && r != l) {
                    short b = (short) (((bytes[i * frameBytes + 3] & 0xff) << 8) | (bytes[i * frameBytes + 2] & 0xff));
                    r[i] = b / 32768f;
                } else if (r != l) {
                    r[i] = l[i];
                }
            }
            return frames;
        }

        @Override
        public int rate() {
            return mRate;
        }

        @Override
        public int channels() {
            return mCh;
        }

        @Override
        public void close() {
            try {
                raf.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private AudioSource openFile(File f) throws Throwable {
        boolean wav = f.getName().toLowerCase().endsWith(".wav");
        if (!wav) {
            try {
                return new CodecSource(f);
            } catch (Throwable e) {
                AppLog.e("EQ", "解碼器開唔到，試 WAV 解析：" + e);
            }
        }
        return new WavSource(f);
    }

    // ---------------------------------------------------------------- 內部

    private boolean buildRecord() {
        try {
            int min = AudioRecord.getMinBufferSize(MIC_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            int size = Math.max(min, FRAMES * 8);
            rec = new AudioRecord.Builder()
                    .setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(MIC_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build())
                    .setBufferSizeInBytes(size)
                    .build();
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
                releaseRecord();
                return false;
            }
            rec.startRecording();
            return true;
        } catch (Throwable e) {
            AppLog.e("EQ", "AudioRecord 失敗：" + e);
            releaseRecord();
            return false;
        }
    }

    /** 格式階梯：目標格式 → 目標 rate 單聲道 → 44.1k 單 → 48k 單。 */
    private boolean buildTrack(int wantRate, int wantCh, AudioDeviceInfo dev, int usage) {
        int[] rates = wantRate == 44100 ? new int[]{44100, 48000} : new int[]{wantRate, 44100, 48000};
        int[] chans = wantCh >= 2 ? new int[]{2, 1} : new int[]{1};
        for (int r : rates) {
            for (int ch : chans) {
                AudioTrack t = null;
                try {
                    t = new AudioTrack.Builder()
                            .setAudioAttributes(new AudioAttributes.Builder()
                                    .setUsage(usage)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                    .build())
                            .setAudioFormat(new AudioFormat.Builder()
                                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                    .setSampleRate(r)
                                    .setChannelMask(ch == 2 ? AudioFormat.CHANNEL_OUT_STEREO
                                            : AudioFormat.CHANNEL_OUT_MONO)
                                    .build())
                            .setBufferSizeInBytes(FRAMES * ch * 2 * 4)
                            .setTransferMode(AudioTrack.MODE_STREAM)
                            .build();
                    if (dev != null) {
                        try {
                            t.setPreferredDevice(dev);
                        } catch (Throwable e) {
                            AppLog.e("EQ", "setPreferredDevice 失敗：" + e);
                        }
                    }
                    short[] mute = new short[FRAMES * ch];
                    t.write(mute, 0, mute.length, AudioTrack.WRITE_BLOCKING);
                    t.play();
                    track = t;
                    rate = r;
                    stereo = ch == 2;
                    trackInfo = String.format(java.util.Locale.US, "%.1f kHz %s", r / 1000.0,
                            stereo ? "立體" : "單");
                    AppLog.d("EQ", "AudioTrack 開成功 " + trackInfo + " 路由到 "
                            + (t.getRoutedDevice() == null ? "?" : t.getRoutedDevice().getAddress()));
                    return true;
                } catch (Throwable e) {
                    AppLog.e("EQ", "AudioTrack " + r + "/" + ch + "ch 失敗：" + e);
                    if (t != null) {
                        try {
                            t.release();
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
        }
        return false;
    }

    private void requestFocus() {
        try {
            AudioManager am = routes.am();
            if (am == null) return;
            AudioAttributes aa = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build();
            focusReq = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(aa)
                    .setWillPauseWhenDucked(false)
                    .setOnAudioFocusChangeListener(c -> {
                    })
                    .build();
            int res = am.requestAudioFocus(focusReq);
            AppLog.d("EQ", "audio focus = " + res);
        } catch (Throwable e) {
            AppLog.e("EQ", "focus 失敗：" + e);
        }
    }

    private void abandonFocus() {
        try {
            AudioManager am = routes.am();
            if (am != null && focusReq != null) am.abandonAudioFocusRequest(focusReq);
        } catch (Throwable ignored) {
        }
        focusReq = null;
    }

    private void releaseTrack() {
        AudioTrack t = track;
        track = null;
        if (t == null) return;
        try {
            t.pause();
            t.flush();
            t.stop();
            t.release();
        } catch (Throwable ignored) {
        }
    }

    private void releaseRecord() {
        AudioRecord r = rec;
        rec = null;
        if (r == null) return;
        try {
            r.stop();
        } catch (Throwable ignored) {
        }
        try {
            r.release();
        } catch (Throwable ignored) {
        }
    }

    private void closeSrc() {
        AudioSource s = src;
        src = null;
        if (s != null) {
            try {
                s.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 音頻 thread：source → DSP → 音量 → AudioTrack。 */
    private void loop() {
        float[] l = new float[FRAMES];
        float[] r = new float[FRAMES];
        short[] out = new short[FRAMES * 2];
        int blocks = 0;
        try {
            while (run) {
                AudioSource s = src;
                AudioTrack t = track;
                if (s == null || t == null) break;
                int got;
                try {
                    got = s.read(l, r, FRAMES);
                } catch (Throwable e) {
                    lastError = "讀取失敗：" + e;
                    AppLog.e("EQ", lastError);
                    status = "❌ 讀取失敗（" + e.getClass().getSimpleName() + "）";
                    break;
                }
                if (got < 0) {
                    status = "播放完畢";
                    break;
                }
                if (got == 0) continue;
                if (got < FRAMES) {
                    for (int i = got; i < FRAMES; i++) {
                        l[i] = 0;
                        r[i] = 0;
                    }
                }
                outRmsDb = dsp.outRmsDb();
                outPeakDb = dsp.outPeakDb();
                dsp.process(l, r, FRAMES);
                float v = volPct / 100f;
                int n = FRAMES * (stereo ? 2 : 1);
                if (stereo) {
                    for (int i = 0; i < FRAMES; i++) {
                        out[i * 2] = clamp(l[i] * v);
                        out[i * 2 + 1] = clamp(r[i] * v);
                    }
                } else {
                    for (int i = 0; i < FRAMES; i++) out[i] = clamp(l[i] * v);
                }
                int wr = t.write(out, 0, n, AudioTrack.WRITE_BLOCKING);
                if (wr < 0) {
                    status = "❌ 寫入失敗（" + wr + "）";
                    AppLog.e("EQ", status);
                    break;
                }
                if (mode == M_MIC && (++blocks % 4 == 0)) computeSpectrum();
                outRmsDb = dsp.outRmsDb();
                outPeakDb = dsp.outPeakDb();
            }
        } catch (Throwable e) {
            lastError = "引擎例外：" + e;
            AppLog.e("EQ", lastError);
            status = "❌ 引擎例外（" + e.getClass().getSimpleName() + "）";
        } finally {
            if (playing) {
                playing = false;
                releaseTrack();
            }
        }
    }

    private static short clamp(float v) {
        if (v > 1f) v = 1f;
        if (v < -1f) v = -1f;
        return (short) Math.round(v * 32767f);
    }

    private void feedRing(float[] l, int n) {
        for (int i = 0; i < n; i++) {
            ring[ringPos] = l[i];
            ringPos = (ringPos + 1) % SPEC_N;
            if (ringCount < SPEC_N) ringCount++;
        }
    }

    /** 由咪訊號算 31 段頻譜（Goertzel），顯示「EQ 出咗之後喇叭實際發咩聲」。 */
    private void computeSpectrum() {
        if (ringCount < SPEC_N / 2) return;
        float[] ordered = new float[SPEC_N];
        int start = (ringPos - ringCount + SPEC_N) % SPEC_N;
        for (int i = 0; i < ringCount; i++) ordered[i] = ring[(start + i) % SPEC_N];
        float acc = 0;
        for (int i = 0; i < ringCount; i++) acc += ordered[i] * ordered[i];
        inLevelDb = (float) (acc <= 0 ? -120 : 20 * Math.log10(Math.sqrt(acc / ringCount)));
        for (int k = 0; k < Bands.N; k++) {
            double f = Bands.FREQ[k];
            if (f > rate * 0.45) {
                spec[k] = -120;
                continue;
            }
            spec[k] = (float) Gen.magDb(ordered, ringCount, f, rate);
        }
    }
}
