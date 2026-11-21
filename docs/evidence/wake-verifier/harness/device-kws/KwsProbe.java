// Device lane for the sherpa-onnx open-vocabulary keyword spotter (#1045 spike, benchmark-only).
// Runs under app_process with the app's pinned sherpa-onnx 1.13.0 AAR classes and JNI library and
// onnxruntime-android 1.24.3, so the native kernels match what the app ships.
//
// usage: app_process -Djava.library.path=<lib> <dir> KwsProbe <model-dir> <keywords> <ring-dir> <out.tsv>
//                    <threshold> <score> <threads>
import android.os.Debug;
import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.KeywordSpotter;
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig;
import com.k2fsa.sherpa.onnx.OnlineModelConfig;
import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig;
import java.io.File;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.Arrays;

public final class KwsProbe {
    private static final int SR = 16000;

    public static void main(String[] a) throws Exception {
        String model = a[0];
        File[] rings = new File(a[2]).listFiles((d, n) -> n.endsWith(".wav"));
        Arrays.sort(rings);
        long pss0 = Debug.getPss();
        long t0 = System.nanoTime();

        OnlineTransducerModelConfig tr = new OnlineTransducerModelConfig();
        tr.setEncoder(model + "/encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx");
        tr.setDecoder(model + "/decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx");
        tr.setJoiner(model + "/joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx");
        OnlineModelConfig mc = new OnlineModelConfig();
        mc.setTransducer(tr);
        mc.setTokens(model + "/tokens.txt");
        mc.setNumThreads(Integer.parseInt(a[6]));
        mc.setProvider("cpu");
        FeatureConfig fc = new FeatureConfig();
        fc.setSampleRate(SR);
        fc.setFeatureDim(80);
        KeywordSpotterConfig kc = new KeywordSpotterConfig();
        kc.setFeatConfig(fc);
        kc.setModelConfig(mc);
        kc.setMaxActivePaths(4);
        kc.setKeywordsFile(a[1]);
        kc.setKeywordsThreshold(Float.parseFloat(a[4]));
        kc.setKeywordsScore(Float.parseFloat(a[5]));
        kc.setNumTrailingBlanks(1);
        KeywordSpotter kws = new KeywordSpotter(null, kc);
        double loadMs = (System.nanoTime() - t0) / 1e6;
        long pss1 = Debug.getPss();
        long peak = pss1;

        float[] flush = new float[SR / 2];
        try (PrintWriter out = new PrintWriter(a[3])) {
            for (int i = 0; i < rings.length; i++) {
                float[] pcm = readPcm16(rings[i]);
                long s = System.nanoTime();
                OnlineStream st = kws.createStream("");
                st.acceptWaveform(pcm, SR);
                st.acceptWaveform(flush, SR);
                st.inputFinished();
                String hit = "";
                while (kws.isReady(st)) {
                    kws.decode(st);
                    String k = kws.getResult(st).getKeyword();
                    if (!k.isEmpty()) {
                        hit = k;
                        kws.reset(st);
                    }
                }
                st.release();
                double ms = (System.nanoTime() - s) / 1e6;
                out.println(rings[i].getName() + "\t" + (hit.isEmpty() ? "reject" : "accept") + "\t" + ms);
                if (i % 25 == 0) peak = Math.max(peak, Debug.getPss());
            }
            peak = Math.max(peak, Debug.getPss());
            out.println("#meta\tload_ms=" + loadMs + "\tpss_before_kb=" + pss0 + "\tpss_after_load_kb=" + pss1
                    + "\tpss_peak_kb=" + peak + "\tvm_hwm_kb=" + status("VmHWM"));
        }
        kws.release();
    }

    private static float[] readPcm16(File f) throws Exception {
        ByteBuffer b = ByteBuffer.wrap(Files.readAllBytes(f.toPath())).order(ByteOrder.LITTLE_ENDIAN);
        int p = 12;
        while (p + 8 <= b.limit()) {
            int id = b.getInt(p), len = b.getInt(p + 4);
            if (id == 0x61746164) { // "data"
                float[] x = new float[len / 2];
                for (int i = 0; i < x.length; i++) x[i] = b.getShort(p + 8 + 2 * i) / 32768f;
                return x;
            }
            p += 8 + len + (len & 1);
        }
        throw new IllegalArgumentException("no data chunk: " + f);
    }

    private static long status(String field) throws Exception {
        for (String l : Files.readAllLines(new File("/proc/self/status").toPath())) {
            if (l.startsWith(field + ":")) return Long.parseLong(l.split("\\s+")[1]);
        }
        return -1;
    }
}
