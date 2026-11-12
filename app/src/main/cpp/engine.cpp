// Kutty · motor de audio
//  - salida Oboe de baja latencia: beat (loop o libre, con velocidad), sonido a
//    scratchear que sigue al disco, fader, metrónomo y preescucha
//  - el disco: seguidor de velocidad críticamente amortiguado (inercia, sin
//    escalones), lectura Hermite de 4 puntos sobre copias filtradas del sonido
//    que se eligen según la velocidad del plato (antialias), limitador suave
//  - diagnóstico: graba 10 s de la salida
//  - entrada opcional (DVS): decodifica el timecode en modo relativo leyendo la
//    fase de la portadora estéreo en cuadratura
//  - captura posición y fader cada 32 cuadros para la puntuación
#include <algorithm>
#include <cstdint>
#include <cstring>
#include <oboe/Oboe.h>
#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <cmath>
#include <mutex>
#include <vector>
#include <memory>
#include <time.h>

#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "kutty", __VA_ARGS__)

static inline int64_t nowNs() {
    timespec t{};
    clock_gettime(CLOCK_MONOTONIC, &t);
    return int64_t(t.tv_sec) * 1000000000LL + t.tv_nsec;
}

struct Buf {
    std::vector<float> data;
    int channels = 1;
    int64_t frames() const { return int64_t(data.size()) / channels; }
};

// Sonido a scratchear: el original y tres copias pasabajos de fase cero
// (para velocidades ×2, ×4 y ×8). Al acelerar el plato se pasa de una a otra.
static constexpr int LV = 4;
struct Smp {
    std::vector<float> lv[LV];
    int64_t frames() const { return int64_t(lv[0].size()); }
};

// Butterworth de 2º orden ida y vuelta (fase cero, 4º orden en magnitud)
static void lowpassZeroPhase(const std::vector<float>& in, std::vector<float>& out, double fc, double sr) {
    const double w = 2.0 * M_PI * fc / sr, c = std::cos(w), al = std::sin(w) / (2.0 * 0.7071067811865476), a0 = 1 + al;
    const double b0 = (1 - c) / 2 / a0, b1 = (1 - c) / a0, b2 = b0, a1 = -2 * c / a0, a2 = (1 - al) / a0;
    const size_t n = in.size();
    std::vector<double> t(n);
    double x1 = 0, x2 = 0, y1 = 0, y2 = 0;
    for (size_t i = 0; i < n; i++) { double x = in[i], y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2; x2 = x1; x1 = x; y2 = y1; y1 = y; t[i] = y; }
    x1 = x2 = y1 = y2 = 0;
    out.resize(n);
    for (size_t k = n; k-- > 0;) { double x = t[k], y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2; x2 = x1; x1 = x; y2 = y1; y1 = y; out[k] = (float)y; }
}

// Hermite de 4 puntos (Catmull-Rom); fuera del sonido, silencio
static inline float hermite(const float* d, int64_t n, double x) {
    const int64_t k = (int64_t)std::floor(x);
    if (k < -2 || k >= n) return 0.f;
    auto at = [&](int64_t i) { return (i >= 0 && i < n) ? d[i] : 0.f; };
    const float t = float(x - (double)k);
    const float xm = at(k - 1), x0 = at(k), x1 = at(k + 1), x2 = at(k + 2);
    const float c1 = 0.5f * (x1 - xm), c2 = xm - 2.5f * x0 + 2.f * x1 - 0.5f * x2, c3 = 0.5f * (x2 - xm) + 1.5f * (x0 - x1);
    return ((c3 * t + c2) * t + c1) * t + x0;
}

enum CmdType { C_START_BEAT, C_STOP_BEAT, C_RATE, C_CLICK, C_PREVIEW, C_STOP_PREVIEW, C_JUMP };
struct Cmd {
    int type = 0;
    int64_t frame = 0;
    double a = 0, b = 0, c = 0;
    int flag = 0;
};

class Engine : public oboe::AudioStreamDataCallback, public oboe::AudioStreamErrorCallback {
public:
    // ---- estado compartido ----
    std::atomic<Smp*> sample{nullptr};
    std::atomic<Buf*> beat{nullptr};
    std::vector<Buf*> graveyard;
    std::vector<Smp*> smpGraveyard;
    std::mutex cmdMutex;
    static constexpr int CMDN = 1024;
    Cmd cmds[CMDN];
    std::atomic<uint32_t> cmdHead{0};
    uint32_t cmdTail = 0;

    // disco desde la interfaz (táctil, jog, demo): puntos con su tiempo real.
    // El motor reproduce esa trayectoria con un retardo chico (el que haga falta
    // para tener siempre datos), interpolando con Hermite: sin adivinar.
    struct Pt { int64_t t; double p; double v; };
    static constexpr int PTN = 512;
    Pt pts[PTN];
    std::atomic<uint32_t> ptHead{0};
    std::atomic<int64_t> ptFloor{0};     // después de un salto, no usar puntos anteriores
    double gapEnv = 0.012;               // cuánto tarda en llegar el próximo dato (envolvente)
    std::atomic<double> needD{0.012};
    double Dsm = 0.012;

    // reloj de audio: la hora del final de cada bloque, suavizada. Las placas USB (la REV1)
    // piden bloques grandes y a destiempo; usar la hora cruda de cada llamada hacía saltar la
    // trayectoria en cada bloque y pedir datos que todavía no llegaron (sonaba modulado).
    int64_t clkT = 0, clkF = 0;
    double clkErr = 0;

    // demo y mano automática: trayectoria programada por cuadro de audio (sin pasar por la pantalla)
    struct FPt { int64_t f; double p; uint8_t fa; };
    static constexpr int FPN = 4096;
    FPt fpts[FPN];
    std::atomic<uint32_t> fpHead{0};
    std::atomic<int> scriptDisc{0}, scriptFader{0};
    void pushScript(const int64_t* fs, const double* ps, const int8_t* fa, int n) {
        uint32_t h = fpHead.load(std::memory_order_relaxed);
        int64_t newest = h ? fpts[(h - 1) % FPN].f : INT64_MIN;
        for (int k = 0; k < n; k++) {
            if (fs[k] <= newest) continue;
            fpts[h % FPN] = {fs[k], ps[k], (uint8_t)(fa[k] ? 1 : 0)};
            h++; newest = fs[k];
        }
        fpHead.store(h, std::memory_order_release);
    }
    std::atomic<float> uiFader{0};
    std::atomic<int> discSource{0};   // 0 interfaz, 1 timecode
    std::atomic<int> faderSource{0};  // 0 interfaz, 1 corte del timecode
    std::atomic<double> cue{0.03};
    std::atomic<double> endSec{1e9};   // fin del trim: pasado el fin, silencio
    std::atomic<float> beatVol{0.75f}, scratchVol{0.9f};

    // DVS
    std::atomic<double> dvsPos{0}, dvsSpeed{0};
    std::atomic<int64_t> dvsTime{0};
    std::atomic<bool> dvsSignal{false};
    std::atomic<float> dvsLevel{0}, dvsQuality{0};
    std::atomic<double> dvsZero{0};
    std::atomic<float> dvsThreshold{0.02f};
    std::atomic<float> dvsCarrier{1000.f};
    std::atomic<int> dvsInvert{0};
    std::atomic<bool> dvsRecenterReq{false};
    double phPrev = 0, phAcc = 0, envSq = 0, spdSm = 0, hpL = 0, hpR = 0, xL = 0, xR = 0;
    bool sig = false;

    // captura
    static constexpr int CAPN = 1 << 16;
    int64_t capFrame[CAPN];
    float capPos[CAPN];
    uint8_t capF[CAPN];
    std::atomic<uint64_t> capHead{0};
    uint64_t capTail = 0;

    // ---- estado del hilo de audio ----
    std::shared_ptr<oboe::AudioStream> out, in;
    std::mutex outMx;
    int outDevice = 0;
    int outWantCh = 2;
    std::atomic<int> outCh{2};
    std::atomic<int> outActualDev{0};
    std::atomic<int> outExclusive{0};
    int64_t outBase = 0;
    int sr = 48000;
    int inDevice = 0;
    std::atomic<int64_t> written{0};
    double pos = 0.03, gain = 0;
    double dv = 0;        // velocidad del disco (segundos de sonido por segundo)
    double spSm = 0;      // |velocidad| suavizada, para el antialias
    float limG = 1.f;     // ganancia del limitador
    // filtro de continua (como un pre de phono): con el disco quieto no sale nada
    double dcX = 0, dcY = 0, pvX = 0, pvY = 0;

    // diagnóstico: 10 s de la salida
    std::atomic<int> recState{0};      // 0 nada, 1 grabando, 2 listo
    std::vector<float> recBuf;
    std::atomic<int64_t> recPos{0};
    int64_t recLen = 0;
    bool beatOn = false;
    int64_t beatStartFrame = 0;
    double beatPos = 0, beatRate = 1, beatLoopStart = 0, beatLoopLen = 0;
    bool beatLoop = true;
    // keylock: dos cabezas de lectura a velocidad 1 con ventanas de Hann al 50 %
    std::atomic<bool> keylock{false};
    double klStart[2] = {0, 0}; int64_t klAge[2] = {0, 0};
    double beatSample(const Buf* bt, double sec, float& l, float& r) {
        if (beatLoop && beatLoopLen > 0) {
            double e = beatLoopStart + beatLoopLen;
            while (sec >= e) sec -= beatLoopLen;
            while (sec < beatLoopStart && sec < 0) sec += beatLoopLen;
        }
        const int64_t len = bt->frames();
        double x = sec * sr; int64_t k = (int64_t)x;
        if (k < 0 || k + 1 >= len) { l = r = 0; return sec; }
        float fr = float(x - k);
        l = bt->data[k * 2] + (bt->data[(k + 1) * 2] - bt->data[k * 2]) * fr;
        r = bt->data[k * 2 + 1] + (bt->data[(k + 1) * 2 + 1] - bt->data[k * 2 + 1]) * fr;
        return sec;
    }
    struct PendingRate { int64_t frame; double rate; };
    std::vector<PendingRate> rates;
    struct Click { int64_t frame; int accent; int age; bool active; };
    std::vector<Click> clicks;
    bool pvOn = false;
    double pvPos = 0, pvEnd = 0;
    int capCount = 0;

    Engine() { rates.reserve(64); clicks.reserve(128); }

    void push(const Cmd& c) {
        std::lock_guard<std::mutex> lk(cmdMutex);
        uint32_t h = cmdHead.load(std::memory_order_relaxed);
        cmds[h % CMDN] = c;
        cmdHead.store(h + 1, std::memory_order_release);
    }

    // Un solo productor a la vez (Kotlin lo serializa). t en ns de CLOCK_MONOTONIC; 0 = ahora.
    void pushPts(const int64_t* ts, const double* ps, int n, double vel) {
        const int64_t now = nowNs();
        uint32_t h = ptHead.load(std::memory_order_relaxed);
        int64_t newest = h ? pts[(h - 1) % PTN].t : 0;
        if (newest > 0) {
            const double gap = (now - newest) / 1e9;   // edad del último dato cuando llega el siguiente
            if (gap > 0 && gap < 0.06) gapEnv = std::max(gap, gapEnv * 0.998);
            double d = gapEnv + 0.002; if (d < 0.003) d = 0.003; if (d > 0.05) d = 0.05;
            needD.store(d, std::memory_order_relaxed);
        }
        for (int k = 0; k < n; k++) {
            int64_t t = ts[k] > 0 ? ts[k] : now;
            if (t > now) t = now;
            if (h && t <= newest) { pts[(h - 1) % PTN].p = ps[k]; pts[(h - 1) % PTN].v = vel; continue; }   // mismo instante: se pisa
            pts[h % PTN] = {t, ps[k], vel};
            h++; newest = t;
        }
        ptHead.store(h, std::memory_order_release);
    }
    void setTarget(double tgt, double vel, int64_t tm = 0) { pushPts(&tm, &tgt, 1, vel); }
    void jumpTo(double p) {
        const int64_t now = nowNs();
        ptFloor.store(now, std::memory_order_relaxed);
        int64_t t = now; pushPts(&t, &p, 1, 0);
    }

    void swapBuf(std::atomic<Buf*>& slot, Buf* b) {
        Buf* old = slot.exchange(b);
        if (old) graveyard.push_back(old);
        while (graveyard.size() > 4) { delete graveyard.front(); graveyard.erase(graveyard.begin()); }
    }
    void swapSmp(Smp* b) {
        Smp* old = sample.exchange(b);
        if (old) smpGraveyard.push_back(old);
        while (smpGraveyard.size() > 4) { delete smpGraveyard.front(); smpGraveyard.erase(smpGraveyard.begin()); }
    }

    // ---- salida ----
    bool openOut() {
        std::lock_guard<std::mutex> lk(outMx);
        if (out) { out->stop(); out->close(); out.reset(); }
        oboe::AudioStreamBuilder b;
        b.setDirection(oboe::Direction::Output)
            ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
            ->setSharingMode(oboe::SharingMode::Exclusive)
            ->setFormat(oboe::AudioFormat::Float)
            ->setChannelCount(outDevice > 0 ? outWantCh : 2)
            ->setChannelConversionAllowed(true)
            ->setFormatConversionAllowed(true)
            ->setUsage(oboe::Usage::Media)
            ->setDataCallback(this)
            ->setErrorCallback(this);
        if (outDevice > 0) b.setDeviceId(outDevice);
        if (sr > 0 && written.load() > 0) b.setSampleRate(sr)->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium);
        auto r = b.openStream(out);
        // la placa USB puede no aceptar modo exclusivo o esa cantidad de canales: se prueba más suelto
        if (r != oboe::Result::OK && outDevice > 0) { b.setSharingMode(oboe::SharingMode::Shared); r = b.openStream(out); }
        if (r != oboe::Result::OK && outDevice > 0) { b.setChannelCount(2); r = b.openStream(out); }
        if (r != oboe::Result::OK && outDevice > 0) { b.setPerformanceMode(oboe::PerformanceMode::None); r = b.openStream(out); }
        if (r != oboe::Result::OK) { LOG("openOut %s", oboe::convertToText(r)); out.reset(); outActualDev.store(-1); return false; }
        outCh.store(out->getChannelCount());
        outActualDev.store(out->getDeviceId());
        outExclusive.store(out->getSharingMode() == oboe::SharingMode::Exclusive ? 1 : 0);
        if (written.load() == 0) sr = out->getSampleRate();
        outBase = written.load();
        out->setBufferSizeInFrames(out->getFramesPerBurst() * 2);
        out->requestStart();
        LOG("salida %d Hz burst %d", sr, out->getFramesPerBurst());
        return true;
    }
    void onErrorAfterClose(oboe::AudioStream* s, oboe::Result) override {
        if (s == out.get() || !out) { openOut(); }
        else if (in && s == in.get()) { in.reset(); openIn(inDevice); }
    }

    bool openIn(int deviceId) {
        inDevice = deviceId;
        if (in) { in->stop(); in->close(); in.reset(); }
        if (deviceId < 0) return true;
        oboe::AudioStreamBuilder b;
        b.setDirection(oboe::Direction::Input)
            ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
            ->setSharingMode(oboe::SharingMode::Exclusive)
            ->setFormat(oboe::AudioFormat::Float)
            ->setChannelCount(2)
            ->setSampleRate(sr)
            ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium)
            ->setInputPreset(oboe::InputPreset::Unprocessed)
            ->setDataCallback(this)
            ->setErrorCallback(this);
        if (deviceId > 0) b.setDeviceId(deviceId);
        auto r = b.openStream(in);
        if (r != oboe::Result::OK) {
            b.setSharingMode(oboe::SharingMode::Shared);
            r = b.openStream(in);
        }
        if (r != oboe::Result::OK) { LOG("openIn %s", oboe::convertToText(r)); in.reset(); return false; }
        in->requestStart();
        LOG("entrada %d Hz canales %d", in->getSampleRate(), in->getChannelCount());
        return true;
    }

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream* s, void* data, int32_t n) override {
        if (s->getDirection() == oboe::Direction::Output) renderOut((float*)data, n, s->getChannelCount());
        else decodeIn((const float*)data, n, s->getChannelCount());
        return oboe::DataCallbackResult::Continue;
    }

    // ---- DVS relativo: la fase de la portadora es la posición del disco ----
    void decodeIn(const float* d, int n, int ch) {
        if (dvsRecenterReq.exchange(false)) dvsZero.store(phAcc);
        const float carrier = dvsCarrier.load(), th = dvsThreshold.load();
        const double twoPi = 6.283185307179586, kEnv = 1.0 - std::exp(-1.0 / (0.004 * sr));
        const double kSp = 1.0 - std::exp(-1.0 / (0.004 * sr)), kHp = 1.0 - std::exp(-twoPi * 30.0 / sr);
        const int inv = dvsInvert.load() ? -1 : 1;
        int good = 0, tot = 0;
        for (int i = 0; i < n; i++) {
            float L = d[i * ch], R = ch > 1 ? d[i * ch + 1] : 0.f;
            hpL += (L - hpL) * kHp; hpR += (R - hpR) * kHp;
            double l = L - hpL, r = R - hpR;
            envSq += (l * l + r * r - envSq) * kEnv;
            double lvl = std::sqrt(envSq);
            if (!sig && lvl > th) sig = true; else if (sig && lvl < th * 0.6) sig = false;
            double ph = std::atan2(r, l), dp = ph - phPrev;
            if (dp > M_PI) dp -= twoPi; else if (dp < -M_PI) dp += twoPi;
            phPrev = ph;
            if (sig) {
                double step = inv * dp / (twoPi * carrier);  // segundos de disco
                phAcc += step;
                spdSm += (step * sr - spdSm) * kSp;
                tot++; if (std::fabs(dp) < 1.2) good++;
            } else {
                spdSm *= 0.995;
            }
        }
        dvsPos.store(phAcc);
        dvsSpeed.store(sig ? spdSm : 0.0);
        dvsTime.store(nowNs());
        dvsSignal.store(sig);
        dvsLevel.store((float)std::sqrt(envSq));
        if (tot > 0) dvsQuality.store(dvsQuality.load() * 0.8f + 0.2f * float(good) / float(tot));
    }

    void popCmds(int64_t f0) {
        uint32_t h = cmdHead.load(std::memory_order_acquire);
        while (cmdTail != h) {
            const Cmd c = cmds[cmdTail % CMDN];
            cmdTail++;
            switch (c.type) {
                case C_START_BEAT: beatOn = true; beatStartFrame = c.frame; beatPos = c.a; beatRate = c.b; beatLoopLen = c.c; beatLoopStart = c.a; beatLoop = c.flag != 0; rates.clear(); klStart[0] = klStart[1] = c.a; klAge[0] = 0; klAge[1] = (int64_t)(0.03 * sr); break;
                case C_STOP_BEAT: beatOn = false; rates.clear(); for (auto& k : clicks) k.active = false; break;
                case C_RATE: if (rates.size() < 64) rates.push_back({c.frame, c.a}); break;
                case C_CLICK: {
                    bool placed = false;
                    for (auto& k : clicks) if (!k.active) { k = {c.frame, c.flag, 0, true}; placed = true; break; }
                    if (!placed && clicks.size() < 128) clicks.push_back({c.frame, c.flag, 0, true});
                    break;
                }
                case C_PREVIEW: pvOn = true; pvPos = c.a; pvEnd = c.a + c.b; break;
                case C_STOP_PREVIEW: pvOn = false; break;
                case C_JUMP: pos = c.a; dv = 0; break;
            }
        }
        (void)f0;
    }

    // lectura del sonido: Hermite sobre la copia que corresponde a la velocidad
    static inline float readSmp(const Smp* b, double sec, int sr, double speed) {
        const double x = sec * sr;
        const int64_t n = b->frames();
        double l = speed > 1.0 ? std::log2(speed) : 0.0;
        int i0 = (int)l; double fr = l - i0;
        if (i0 >= LV - 1) { i0 = LV - 1; fr = 0; }
        float v = hermite(b->lv[i0].data(), n, x);
        if (fr > 0.001) v += (hermite(b->lv[i0 + 1].data(), n, x) - v) * float(fr);
        return v;
    }

    void renderOut(float* o, int n, int oc) {
        const int64_t f0 = written.load(std::memory_order_relaxed);
        popCmds(f0);
        Smp* smp = sample.load(std::memory_order_acquire);
        Buf* bt = beat.load(std::memory_order_acquire);
        const int64_t tNow = nowNs();
        double tgt = 0, vel = 0; int64_t tm = tNow;
        const bool dvs = discSource.load() == 1;
        const double cueS = cue.load();
        // reloj suavizado: hora del primer cuadro de este bloque
        {
            const int64_t fEnd = f0 + n;
            if (clkT == 0) { clkT = tNow; clkF = fEnd; }
            else {
                const double pred = (double)clkT + (double)(fEnd - clkF) * 1e9 / sr;
                const double err = (double)tNow - pred;
                if (std::fabs(err) > 50e6) { clkT = tNow; clkErr = 0; }   // se cortó o se reabrió la salida
                else { clkT = (int64_t)(pred + err * 0.02); clkErr = std::max(std::fabs(err), clkErr * 0.995); }
                clkF = fEnd;
            }
        }
        const int64_t tStart = clkT - (int64_t)((double)n * 1e9 / sr);
        // trayectoria programada (demo / mano automática)
        const bool scD = scriptDisc.load(std::memory_order_relaxed) != 0;
        const bool scF = scriptFader.load(std::memory_order_relaxed) != 0;
        static constexpr int FN = 512;
        FPt fp[FN]; int fn = 0, fc = 0;
        if (scD || scF) {
            const uint32_t h = fpHead.load(std::memory_order_acquire);
            const uint32_t m = h < (uint32_t)FN ? h : (uint32_t)FN;
            for (uint32_t j = 0; j < m; j++) fp[fn++] = fpts[(h - m + j) % FPN];
        }
        // copia de los últimos puntos para este bloque
        static constexpr int SN = 64;
        Pt sp[SN]; int sn = 0;
        if (dvs) {
            tgt = cueS + dvsPos.load() - dvsZero.load(); vel = dvsSpeed.load(); tm = dvsTime.load();
        } else {
            const uint32_t h = ptHead.load(std::memory_order_acquire);
            const int64_t floorT = ptFloor.load(std::memory_order_relaxed);
            const uint32_t m = h < (uint32_t)SN ? h : (uint32_t)SN;
            for (uint32_t j = 0; j < m; j++) { const Pt& q = pts[(h - m + j) % PTN]; if (q.t >= floorT) sp[sn++] = q; }
            // el retardo se mueve despacio (si no, cambiaría la afinación)
            const double want = needD.load(std::memory_order_relaxed) + clkErr / 1e9;
            const double maxStep = 0.005 * n / sr;   // como mucho 0,5 % de cambio de velocidad
            Dsm += std::max(-maxStep, std::min(maxStep, want - Dsm));
        }
        const int64_t Dns = (int64_t)(Dsm * 1e9);
        int cur = 0;
        const bool cut = faderSource.load() == 1;
        const float fOpen = cut ? (dvsSignal.load() ? 1.f : 0.f) : uiFader.load();
        const float bv = beatVol.load(), sv = scratchVol.load();
        const double endS = endSec.load();
        const double ga = 1.0 - std::exp(-1.0 / (0.0007 * sr));
        const double kSp = 1.0 - std::exp(-1.0 / (0.003 * sr));
        const float kRel = 1.f - std::exp(-1.f / (0.08f * sr));
        const double dts = 1.0 / sr;
        // el disco: pasabajos de 2º orden críticamente amortiguado (ω = 180 rad/s, ~11 ms)
        // sobre la trayectoria. Sin velocidad directa: así sí se comen los escalones
        // de cada punto táctil (antes pasaban y sonaba a chapa).
        constexpr double W = 180.0, W2 = W * W;
        constexpr float LIM = 0.89f;  // -1 dBFS
        const double dcR = std::exp(-2.0 * M_PI * 20.0 / sr);   // pasaaltos de 20 Hz
        const bool rec = recState.load(std::memory_order_acquire) == 1;
        for (int i = 0; i < n; i++) {
            const int64_t f = f0 + i;
            // cambio de velocidad programado (Desafío)
            for (size_t r = 0; r < rates.size(); r++) if (rates[r].frame <= f) { beatRate = rates[r].rate; rates.erase(rates.begin() + r); r--; }
            float bl = 0, br = 0;
            if (beatOn && bt && f >= beatStartFrame) {
                const int64_t len = bt->frames();
                if (keylock.load(std::memory_order_relaxed) && std::fabs(beatRate - 1.0) > 0.002) {
                    const int64_t G = (int64_t)(0.06 * sr);
                    for (int hd = 0; hd < 2; hd++) {
                        if (klAge[hd] >= G) { klAge[hd] = 0; klStart[hd] = beatPos; }
                        const double ph = (double)klAge[hd] / G;
                        const float w = float(std::sin(M_PI * ph)); const float w2 = w * w;
                        float l, r; beatSample(bt, klStart[hd] + (double)klAge[hd] / sr, l, r);
                        bl += l * w2; br += r * w2;
                        klAge[hd]++;
                    }
                } else {
                    double x = beatPos * sr;
                    int64_t k = (int64_t)x;
                    if (k >= 0 && k + 1 < len) {
                        float fr = float(x - k);
                        bl = bt->data[k * 2] + (bt->data[(k + 1) * 2] - bt->data[k * 2]) * fr;
                        br = bt->data[k * 2 + 1] + (bt->data[(k + 1) * 2 + 1] - bt->data[k * 2 + 1]) * fr;
                    }
                }
                beatPos += beatRate / sr;
                if (beatLoop && beatLoopLen > 0 && beatPos >= beatLoopStart + beatLoopLen) beatPos -= beatLoopLen;
                if (!beatLoop && beatPos * sr >= len) beatOn = false;
            }
            // el disco: un resorte críticamente amortiguado que sigue posición y velocidad
            double xt, vt;
            // programado: posición y fader del cuadro exacto
            float fo = fOpen;
            if (fn > 0) {
                while (fc + 1 < fn && fp[fc + 1].f <= f) fc++;
                if (scF) fo = fp[fc].fa ? 1.f : 0.f;
            }
            if (scD && fn > 0) {
                if (f <= fp[0].f) xt = fp[0].p;
                else if (fc + 1 >= fn) xt = fp[fn - 1].p;
                else { const FPt& a = fp[fc]; const FPt& b = fp[fc + 1]; xt = a.p + (b.p - a.p) * double(f - a.f) / double(b.f - a.f); }
                vt = 0;
            } else if (dvs) {
                double dt = (double)(tNow - tm) / 1e9 + (double)i / sr;
                if (dt < 0) dt = 0; if (dt > 0.03) dt = 0.03;
                xt = tgt + vel * dt; vt = vel;
            } else if (sn == 0) { xt = pos; vt = 0; }
            else {
                const int64_t qt = tStart + (int64_t)((double)i * 1e9 / sr) - Dns;
                if (sn == 1 || qt <= sp[0].t) { const Pt& q = qt <= sp[0].t ? sp[0] : sp[sn - 1]; xt = q.p; vt = 0; }
                else if (qt >= sp[sn - 1].t) {
                    // llegó tarde el dato: se sigue un poquito con la última velocidad y se frena
                    const double over = (qt - sp[sn - 1].t) / 1e9;
                    xt = sp[sn - 1].p + sp[sn - 1].v * std::min(over, 0.008); vt = over < 0.008 ? sp[sn - 1].v : 0;
                } else {
                    while (cur + 2 < sn && sp[cur + 1].t <= qt) cur++;
                    while (cur > 0 && sp[cur].t > qt) cur--;
                    const Pt& a = sp[cur]; const Pt& b = sp[cur + 1];
                    const double u = double(qt - a.t) / double(b.t - a.t);
                    xt = a.p + (b.p - a.p) * u; vt = 0;
                }
            }
            if (!dvs || scD) vt = 0;   // táctil/jog: filtrado puro; DVS ya viene suave y conserva su velocidad
            dv += (W2 * (xt - pos) + 2 * W * (vt - dv)) * dts;
            if (dv > 16) dv = 16; else if (dv < -16) dv = -16;
            pos += dv * dts;
            // para atrás frena en el cue
            if (pos < cueS) { pos = cueS; if (dv < 0) dv = 0; }
            spSm += (std::fabs(dv) - spSm) * kSp;
            gain += (fo - gain) * ga;
            float s = 0.f;
            if (smp) {
                // pasado el fin, silencio (con 3 ms de rampa para que no haga click)
                double eg = (endS - pos) / 0.003; if (eg > 1) eg = 1; else if (eg < 0) eg = 0;
                const double raw = eg > 0 ? readSmp(smp, pos, sr, spSm) * eg : 0.0;
                dcY = raw - dcX + dcR * dcY; dcX = raw;
                s = float(dcY * gain);
            }
            float pv = 0;
            if (pvOn && smp) { pv = hermite(smp->lv[0].data(), smp->frames(), pvPos * sr); pvPos += 1.0 / sr; if (pvPos >= pvEnd) pvOn = false; }
            pvY = pv - pvX + dcR * pvY; pvX = pv; pv = float(pvY);
            float ck = 0;
            for (auto& k : clicks) {
                if (!k.active || f < k.frame) continue;
                const double t = (double)k.age / sr;
                ck += 0.32f * float(std::sin(6.283185307 * (k.accent ? 1760.0 : 1175.0) * t) * std::exp(-t * 70.0));
                if (++k.age > sr / 15) k.active = false;
            }
            const float m = s * sv + pv * sv + ck;
            float L = bl * bv + m, R = br * bv + m;
            // limitador suave: ataque instantáneo, relajación de 80 ms
            {
                const float pk = std::max(std::fabs(L), std::fabs(R));
                const float gt = pk > LIM ? LIM / pk : 1.f;
                if (gt < limG) limG = gt; else limG += (gt - limG) * kRel;
                L *= limG; R *= limG;
            }
            if (rec) {
                const int64_t rp = recPos.load(std::memory_order_relaxed);
                if (rp < recLen) { recBuf[rp * 2] = L; recBuf[rp * 2 + 1] = R; recPos.store(rp + 1, std::memory_order_relaxed); }
                else recState.store(2, std::memory_order_release);
            }
            float* fr = o + (size_t)i * oc;
            if (oc == 1) fr[0] = 0.5f * (L + R);
            else { fr[0] = L; fr[1] = R; for (int c = 2; c < oc; c++) fr[c] = (c & 1) ? R : L; }  // salida 3-4 (auriculares) = master
            if (++capCount >= 32) {
                capCount = 0;
                uint64_t h = capHead.load(std::memory_order_relaxed);
                capFrame[h % CAPN] = f; capPos[h % CAPN] = (float)pos; capF[h % CAPN] = fo > 0.5f ? 1 : 0;
                capHead.store(h + 1, std::memory_order_release);
            }
        }
        written.store(f0 + n, std::memory_order_release);
    }

    // cuadro que se está escuchando ahora
    double lastHeard = 0;
    double heardFrame() {
        std::unique_lock<std::mutex> lk(outMx, std::try_to_lock);
        if (!lk.owns_lock() || !out) return lastHeard;
        auto ts = out->getTimestamp(CLOCK_MONOTONIC);
        double h;
        if (ts && ts.value().position > 0) h = (double)outBase + (double)ts.value().position + (double)(nowNs() - ts.value().timestamp) * sr / 1e9;
        else h = (double)written.load() - latencyUnlocked();
        // nunca para atrás ni por delante de lo escrito
        if (h > (double)written.load()) h = (double)written.load();
        if (h < lastHeard - sr * 0.05) h = lastHeard;
        lastHeard = h;
        return h;
    }
    double latencyUnlocked() {
        if (!out) return 0;
        auto l = out->calculateLatencyMillis();
        return l ? l.value() * sr / 1000.0 : out->getBufferSizeInFrames();
    }
    double latencyFrames() {
        std::unique_lock<std::mutex> lk(outMx, std::try_to_lock);
        if (!lk.owns_lock()) return 0;
        return latencyUnlocked();
    }
};

static Engine* E = nullptr;

#define JF(name) Java_com_agenciapeluda_kutty_audio_Engine_##name

extern "C" {
JNIEXPORT jint JNICALL JF(nStart)(JNIEnv*, jobject, jint deviceId, jint ch) {
    if (!E) E = new Engine();
    E->outDevice = deviceId; E->outWantCh = ch > 0 ? ch : 2;
    if (!E->out) E->openOut();
    return E->sr;
}
JNIEXPORT void JNICALL JF(nSetOutput)(JNIEnv*, jobject, jint deviceId, jint ch) {
    if (!E) return;
    if (E->outDevice == deviceId && E->out && E->outActualDev.load() == deviceId) return;
    E->outDevice = deviceId; E->outWantCh = ch > 0 ? ch : 2;
    E->openOut();
}
JNIEXPORT void JNICALL JF(nOutInfo)(JNIEnv* env, jobject, jintArray arr) {
    jint v[4] = {E ? E->outActualDev.load() : -1, E ? E->outCh.load() : 0, E ? E->sr : 0, E ? E->outExclusive.load() : 0};
    env->SetIntArrayRegion(arr, 0, 4, v);
}
JNIEXPORT void JNICALL JF(nSetSample)(JNIEnv* env, jobject, jfloatArray arr) {
    auto* b = new Smp();
    jsize n = env->GetArrayLength(arr); b->lv[0].resize(n);
    env->GetFloatArrayRegion(arr, 0, n, b->lv[0].data());
    for (int k = 1; k < LV; k++) lowpassZeroPhase(b->lv[0], b->lv[k], 0.45 * E->sr / double(1 << k), E->sr);
    E->swapSmp(b);
}
JNIEXPORT void JNICALL JF(nSetBeat)(JNIEnv* env, jobject, jfloatArray arr) {
    auto* b = new Buf(); b->channels = 2;
    jsize n = env->GetArrayLength(arr); b->data.resize(n);
    env->GetFloatArrayRegion(arr, 0, n, b->data.data());
    E->swapBuf(E->beat, b);
}
JNIEXPORT void JNICALL JF(nSetTarget)(JNIEnv*, jobject, jdouble t, jdouble v) { E->setTarget(t, v); }
JNIEXPORT void JNICALL JF(nSetTargetAt)(JNIEnv*, jobject, jdouble t, jdouble v, jlong tm) { E->setTarget(t, v, (int64_t)tm); }
JNIEXPORT void JNICALL JF(nScript)(JNIEnv*, jobject, jboolean disc, jboolean fader) {
    if (!E) return;
    const bool was = E->scriptDisc.load() != 0;
    E->scriptDisc.store(disc ? 1 : 0); E->scriptFader.store(fader ? 1 : 0);
    // al soltar la demo, el disco del usuario arranca de cero (sin puntos viejos)
    if (was && !disc) E->ptFloor.store(nowNs());
}
JNIEXPORT void JNICALL JF(nPushScript)(JNIEnv* env, jobject, jlongArray frames, jdoubleArray pos, jbyteArray fad, jint n) {
    if (!E || n <= 0) return;
    jlong* f = env->GetLongArrayElements(frames, nullptr);
    jdouble* p = env->GetDoubleArrayElements(pos, nullptr);
    jbyte* a = env->GetByteArrayElements(fad, nullptr);
    E->pushScript((const int64_t*)f, (const double*)p, (const int8_t*)a, n);
    env->ReleaseLongArrayElements(frames, f, JNI_ABORT);
    env->ReleaseDoubleArrayElements(pos, p, JNI_ABORT);
    env->ReleaseByteArrayElements(fad, a, JNI_ABORT);
}
JNIEXPORT void JNICALL JF(nSetEnd)(JNIEnv*, jobject, jdouble e) { E->endSec.store(e); }
JNIEXPORT jint JNICALL JF(nRecStart)(JNIEnv*, jobject, jdouble seconds) {
    if (!E || E->recState.load() == 1) return 0;
    E->recLen = (int64_t)(seconds * E->sr);
    E->recBuf.assign((size_t)E->recLen * 2, 0.f);
    E->recPos.store(0);
    E->recState.store(1, std::memory_order_release);
    return E->sr;
}
JNIEXPORT jfloat JNICALL JF(nRecProgress)(JNIEnv*, jobject) {
    if (!E) return -1.f;
    const int st = E->recState.load(std::memory_order_acquire);
    if (st == 0) return -1.f;
    if (st == 2) return 1.f;
    return E->recLen > 0 ? std::min(0.999f, float(E->recPos.load()) / float(E->recLen)) : 0.f;
}
JNIEXPORT jint JNICALL JF(nRecFetch)(JNIEnv* env, jobject, jfloatArray out) {
    if (!E || E->recState.load(std::memory_order_acquire) != 2) return 0;
    const jsize n = std::min<jsize>(env->GetArrayLength(out), (jsize)E->recBuf.size());
    env->SetFloatArrayRegion(out, 0, n, E->recBuf.data());
    E->recState.store(0);
    return n;
}
JNIEXPORT void JNICALL JF(nSetFader)(JNIEnv*, jobject, jfloat v) { E->uiFader.store(v); }
JNIEXPORT void JNICALL JF(nSetSources)(JNIEnv*, jobject, jint disc, jint fader) { E->discSource.store(disc); E->faderSource.store(fader); }
JNIEXPORT void JNICALL JF(nSetCue)(JNIEnv*, jobject, jdouble c) { E->cue.store(c); }
JNIEXPORT void JNICALL JF(nSetVolumes)(JNIEnv*, jobject, jfloat b, jfloat s) { E->beatVol.store(b); E->scratchVol.store(s); }
JNIEXPORT void JNICALL JF(nStartBeat)(JNIEnv*, jobject, jlong frame, jdouble startSec, jdouble rate, jboolean loop, jdouble loopLen) {
    Cmd c; c.type = C_START_BEAT; c.frame = frame; c.a = startSec; c.b = rate; c.c = loopLen; c.flag = loop ? 1 : 0; E->push(c);
}
JNIEXPORT void JNICALL JF(nSetKeylock)(JNIEnv*, jobject, jboolean on) { if (E) E->keylock.store(on); }
JNIEXPORT void JNICALL JF(nStopBeat)(JNIEnv*, jobject) { Cmd c; c.type = C_STOP_BEAT; E->push(c); }
JNIEXPORT void JNICALL JF(nRate)(JNIEnv*, jobject, jlong frame, jdouble rate) { Cmd c; c.type = C_RATE; c.frame = frame; c.a = rate; E->push(c); }
JNIEXPORT void JNICALL JF(nClick)(JNIEnv*, jobject, jlong frame, jboolean accent) { Cmd c; c.type = C_CLICK; c.frame = frame; c.flag = accent ? 1 : 0; E->push(c); }
JNIEXPORT void JNICALL JF(nPreview)(JNIEnv*, jobject, jdouble start, jdouble dur) { Cmd c; c.type = C_PREVIEW; c.a = start; c.b = dur; E->push(c); }
JNIEXPORT void JNICALL JF(nStopPreview)(JNIEnv*, jobject) { Cmd c; c.type = C_STOP_PREVIEW; E->push(c); }
JNIEXPORT void JNICALL JF(nJump)(JNIEnv*, jobject, jdouble p) { Cmd c; c.type = C_JUMP; c.a = p; E->push(c); E->jumpTo(p); }
JNIEXPORT void JNICALL JF(nPushPoints)(JNIEnv* env, jobject, jlongArray ts, jdoubleArray ps, jint n, jdouble vel) {
    if (!E || n <= 0) return;
    int64_t t[256]; double p[256];
    if (n > 256) n = 256;
    env->GetLongArrayRegion(ts, 0, n, reinterpret_cast<jlong*>(t));
    env->GetDoubleArrayRegion(ps, 0, n, p);
    E->pushPts(t, p, n, vel);
}
JNIEXPORT jlong JNICALL JF(nWritten)(JNIEnv*, jobject) { return E ? (jlong)E->written.load() : 0; }
JNIEXPORT jdouble JNICALL JF(nHeard)(JNIEnv*, jobject) { return E ? E->heardFrame() : 0; }
JNIEXPORT jdouble JNICALL JF(nLatency)(JNIEnv*, jobject) { return E ? E->latencyFrames() : 0; }
JNIEXPORT jint JNICALL JF(nDrain)(JNIEnv* env, jobject, jlongArray frames, jfloatArray poss, jbyteArray fs) {
    if (!E) return 0;
    jsize cap = env->GetArrayLength(frames);
    uint64_t h = E->capHead.load(std::memory_order_acquire);
    if (h - E->capTail > (uint64_t)Engine::CAPN) E->capTail = h - Engine::CAPN;
    int n = 0;
    std::vector<jlong> fr; std::vector<jfloat> ps; std::vector<jbyte> ff;
    fr.reserve(cap); ps.reserve(cap); ff.reserve(cap);
    while (E->capTail < h && n < cap) {
        uint64_t k = E->capTail % Engine::CAPN;
        fr.push_back(E->capFrame[k]); ps.push_back(E->capPos[k]); ff.push_back((jbyte)E->capF[k]);
        E->capTail++; n++;
    }
    if (n) {
        env->SetLongArrayRegion(frames, 0, n, fr.data());
        env->SetFloatArrayRegion(poss, 0, n, ps.data());
        env->SetByteArrayRegion(fs, 0, n, ff.data());
    }
    return n;
}
JNIEXPORT jboolean JNICALL JF(nOpenInput)(JNIEnv*, jobject, jint deviceId) { return E->openIn(deviceId) ? JNI_TRUE : JNI_FALSE; }
JNIEXPORT void JNICALL JF(nDvsConfig)(JNIEnv*, jobject, jfloat threshold, jfloat carrier, jboolean invert) {
    E->dvsThreshold.store(threshold); E->dvsCarrier.store(carrier); E->dvsInvert.store(invert ? 1 : 0);
}
JNIEXPORT void JNICALL JF(nDvsRecenter)(JNIEnv*, jobject) { E->dvsRecenterReq.store(true); E->dvsZero.store(E->dvsPos.load()); }
JNIEXPORT void JNICALL JF(nDvsState)(JNIEnv* env, jobject, jfloatArray outArr) {
    float v[5] = {(float)E->dvsSpeed.load(), E->dvsLevel.load(), E->dvsSignal.load() ? 1.f : 0.f, E->dvsQuality.load(), E->in ? 1.f : 0.f};
    env->SetFloatArrayRegion(outArr, 0, 5, v);
}
}
