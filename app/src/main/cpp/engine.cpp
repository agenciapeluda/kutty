// Kutty · motor de audio
//  - salida Oboe de baja latencia: beat (loop o libre, con velocidad), sonido a
//    scratchear que sigue al disco, fader, metrónomo y preescucha
//  - entrada opcional (DVS): decodifica el timecode en modo relativo leyendo la
//    fase de la portadora estéreo en cuadratura
//  - captura posición y fader cada 32 cuadros para la puntuación
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
    std::atomic<Buf*> sample{nullptr};
    std::atomic<Buf*> beat{nullptr};
    std::vector<Buf*> graveyard;
    std::mutex cmdMutex;
    static constexpr int CMDN = 1024;
    Cmd cmds[CMDN];
    std::atomic<uint32_t> cmdHead{0};
    uint32_t cmdTail = 0;

    // disco desde la interfaz (táctil o jog): seqlock
    std::atomic<uint32_t> seq{0};
    double uiTgt = 0, uiVel = 0;
    int64_t uiTime = 0;
    std::atomic<float> uiFader{0};
    std::atomic<int> discSource{0};   // 0 interfaz, 1 timecode
    std::atomic<int> faderSource{0};  // 0 interfaz, 1 corte del timecode
    std::atomic<double> cue{0.03};
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
    int sr = 48000;
    int inDevice = 0;
    std::atomic<int64_t> written{0};
    double pos = 0.03, gain = 0;
    bool beatOn = false;
    int64_t beatStartFrame = 0;
    double beatPos = 0, beatRate = 1, beatLoopStart = 0, beatLoopLen = 0;
    bool beatLoop = true;
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

    void setTarget(double tgt, double vel) {
        uint32_t s = seq.load(std::memory_order_relaxed);
        seq.store(s + 1, std::memory_order_release);
        uiTgt = tgt; uiVel = vel; uiTime = nowNs();
        seq.store(s + 2, std::memory_order_release);
    }
    void readTarget(double& t, double& v, int64_t& tm) {
        for (int i = 0; i < 8; i++) {
            uint32_t a = seq.load(std::memory_order_acquire);
            if (a & 1) continue;
            t = uiTgt; v = uiVel; tm = uiTime;
            if (seq.load(std::memory_order_acquire) == a) return;
        }
    }

    void swapBuf(std::atomic<Buf*>& slot, Buf* b) {
        Buf* old = slot.exchange(b);
        if (old) graveyard.push_back(old);
        while (graveyard.size() > 4) { delete graveyard.front(); graveyard.erase(graveyard.begin()); }
    }

    // ---- salida ----
    bool openOut() {
        oboe::AudioStreamBuilder b;
        b.setDirection(oboe::Direction::Output)
            ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
            ->setSharingMode(oboe::SharingMode::Exclusive)
            ->setFormat(oboe::AudioFormat::Float)
            ->setChannelCount(2)
            ->setUsage(oboe::Usage::Game)
            ->setDataCallback(this)
            ->setErrorCallback(this);
        auto r = b.openStream(out);
        if (r != oboe::Result::OK) { LOG("openOut %s", oboe::convertToText(r)); return false; }
        sr = out->getSampleRate();
        out->setBufferSizeInFrames(out->getFramesPerBurst() * 2);
        out->requestStart();
        LOG("salida %d Hz burst %d", sr, out->getFramesPerBurst());
        return true;
    }
    void onErrorAfterClose(oboe::AudioStream* s, oboe::Result) override {
        if (s == out.get()) { openOut(); }
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
        if (s == out.get()) renderOut((float*)data, n);
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
                case C_START_BEAT: beatOn = true; beatStartFrame = c.frame; beatPos = c.a; beatRate = c.b; beatLoopLen = c.c; beatLoopStart = c.a; beatLoop = c.flag != 0; rates.clear(); break;
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
                case C_JUMP: pos = c.a; break;
            }
        }
        (void)f0;
    }

    static inline float readMono(const Buf* b, double sec, int sr) {
        double x = sec * sr;
        int64_t k = (int64_t)std::floor(x);
        if (k < 0 || k + 1 >= b->frames()) return 0.f;
        float fr = float(x - k);
        return b->data[k] + (b->data[k + 1] - b->data[k]) * fr;
    }

    void renderOut(float* o, int n) {
        const int64_t f0 = written.load(std::memory_order_relaxed);
        popCmds(f0);
        Buf* smp = sample.load(std::memory_order_acquire);
        Buf* bt = beat.load(std::memory_order_acquire);
        const int64_t tNow = nowNs();
        double tgt = 0, vel = 0; int64_t tm = tNow;
        const bool dvs = discSource.load() == 1;
        const double cueS = cue.load();
        if (dvs) {
            tgt = cueS + dvsPos.load() - dvsZero.load(); vel = dvsSpeed.load(); tm = dvsTime.load();
        } else readTarget(tgt, vel, tm);
        const bool cut = faderSource.load() == 1;
        const float fOpen = cut ? (dvsSignal.load() ? 1.f : 0.f) : uiFader.load();
        const float bv = beatVol.load(), sv = scratchVol.load();
        const double a = 1.0 - std::exp(-1.0 / (0.0025 * sr)), ga = 1.0 - std::exp(-1.0 / (0.0007 * sr));
        for (int i = 0; i < n; i++) {
            const int64_t f = f0 + i;
            // cambio de velocidad programado (Desafío)
            for (size_t r = 0; r < rates.size(); r++) if (rates[r].frame <= f) { beatRate = rates[r].rate; rates.erase(rates.begin() + r); r--; }
            float bl = 0, br = 0;
            if (beatOn && bt && f >= beatStartFrame) {
                const int64_t len = bt->frames();
                double x = beatPos * sr;
                int64_t k = (int64_t)x;
                if (k >= 0 && k + 1 < len) {
                    float fr = float(x - k);
                    bl = bt->data[k * 2] + (bt->data[(k + 1) * 2] - bt->data[k * 2]) * fr;
                    br = bt->data[k * 2 + 1] + (bt->data[(k + 1) * 2 + 1] - bt->data[k * 2 + 1]) * fr;
                }
                beatPos += beatRate / sr;
                if (beatLoop && beatLoopLen > 0 && beatPos >= beatLoopStart + beatLoopLen) beatPos -= beatLoopLen;
                if (!beatLoop && beatPos * sr >= len) beatOn = false;
            }
            // el disco sigue al objetivo, extrapolando con la velocidad
            double dt = (double)(tNow - tm) / 1e9 + (double)i / sr;
            if (dt < 0) dt = 0; if (dt > 0.03) dt = 0.03;
            const double pred = tgt + vel * dt;
            pos += (pred - pos) * a;
            gain += (fOpen - gain) * ga;
            float s = smp ? readMono(smp, pos, sr) * float(gain) : 0.f;
            float pv = 0;
            if (pvOn && smp) { pv = readMono(smp, pvPos, sr); pvPos += 1.0 / sr; if (pvPos >= pvEnd) pvOn = false; }
            float ck = 0;
            for (auto& k : clicks) {
                if (!k.active || f < k.frame) continue;
                const double t = (double)k.age / sr;
                ck += 0.32f * float(std::sin(6.283185307 * (k.accent ? 1760.0 : 1175.0) * t) * std::exp(-t * 70.0));
                if (++k.age > sr / 15) k.active = false;
            }
            const float m = s * sv + pv * sv + ck;
            float L = bl * bv + m, R = br * bv + m;
            if (L > 1.f) L = 1.f; else if (L < -1.f) L = -1.f;
            if (R > 1.f) R = 1.f; else if (R < -1.f) R = -1.f;
            o[i * 2] = L; o[i * 2 + 1] = R;
            if (++capCount >= 32) {
                capCount = 0;
                uint64_t h = capHead.load(std::memory_order_relaxed);
                capFrame[h % CAPN] = f; capPos[h % CAPN] = (float)pos; capF[h % CAPN] = fOpen > 0.5f ? 1 : 0;
                capHead.store(h + 1, std::memory_order_release);
            }
        }
        written.store(f0 + n, std::memory_order_release);
    }

    // cuadro que se está escuchando ahora
    double heardFrame() {
        if (!out) return 0;
        auto ts = out->getTimestamp(CLOCK_MONOTONIC);
        if (ts) {
            return (double)ts.value().position + (double)(nowNs() - ts.value().timestamp) * sr / 1e9;
        }
        return (double)written.load() - latencyFrames();
    }
    double latencyFrames() {
        if (!out) return 0;
        auto l = out->calculateLatencyMillis();
        return l ? l.value() * sr / 1000.0 : out->getBufferSizeInFrames();
    }
};

static Engine* E = nullptr;

#define JF(name) Java_com_agenciapeluda_kutty_audio_Engine_##name

extern "C" {
JNIEXPORT jint JNICALL JF(nStart)(JNIEnv*, jobject) {
    if (!E) E = new Engine();
    if (!E->out) E->openOut();
    return E->sr;
}
JNIEXPORT void JNICALL JF(nSetSample)(JNIEnv* env, jobject, jfloatArray arr) {
    auto* b = new Buf(); b->channels = 1;
    jsize n = env->GetArrayLength(arr); b->data.resize(n);
    env->GetFloatArrayRegion(arr, 0, n, b->data.data());
    E->swapBuf(E->sample, b);
}
JNIEXPORT void JNICALL JF(nSetBeat)(JNIEnv* env, jobject, jfloatArray arr) {
    auto* b = new Buf(); b->channels = 2;
    jsize n = env->GetArrayLength(arr); b->data.resize(n);
    env->GetFloatArrayRegion(arr, 0, n, b->data.data());
    E->swapBuf(E->beat, b);
}
JNIEXPORT void JNICALL JF(nSetTarget)(JNIEnv*, jobject, jdouble t, jdouble v) { E->setTarget(t, v); }
JNIEXPORT void JNICALL JF(nSetFader)(JNIEnv*, jobject, jfloat v) { E->uiFader.store(v); }
JNIEXPORT void JNICALL JF(nSetSources)(JNIEnv*, jobject, jint disc, jint fader) { E->discSource.store(disc); E->faderSource.store(fader); }
JNIEXPORT void JNICALL JF(nSetCue)(JNIEnv*, jobject, jdouble c) { E->cue.store(c); }
JNIEXPORT void JNICALL JF(nSetVolumes)(JNIEnv*, jobject, jfloat b, jfloat s) { E->beatVol.store(b); E->scratchVol.store(s); }
JNIEXPORT void JNICALL JF(nStartBeat)(JNIEnv*, jobject, jlong frame, jdouble startSec, jdouble rate, jboolean loop, jdouble loopLen) {
    Cmd c; c.type = C_START_BEAT; c.frame = frame; c.a = startSec; c.b = rate; c.c = loopLen; c.flag = loop ? 1 : 0; E->push(c);
}
JNIEXPORT void JNICALL JF(nStopBeat)(JNIEnv*, jobject) { Cmd c; c.type = C_STOP_BEAT; E->push(c); }
JNIEXPORT void JNICALL JF(nRate)(JNIEnv*, jobject, jlong frame, jdouble rate) { Cmd c; c.type = C_RATE; c.frame = frame; c.a = rate; E->push(c); }
JNIEXPORT void JNICALL JF(nClick)(JNIEnv*, jobject, jlong frame, jboolean accent) { Cmd c; c.type = C_CLICK; c.frame = frame; c.flag = accent ? 1 : 0; E->push(c); }
JNIEXPORT void JNICALL JF(nPreview)(JNIEnv*, jobject, jdouble start, jdouble dur) { Cmd c; c.type = C_PREVIEW; c.a = start; c.b = dur; E->push(c); }
JNIEXPORT void JNICALL JF(nStopPreview)(JNIEnv*, jobject) { Cmd c; c.type = C_STOP_PREVIEW; E->push(c); }
JNIEXPORT void JNICALL JF(nJump)(JNIEnv*, jobject, jdouble p) { Cmd c; c.type = C_JUMP; c.a = p; E->push(c); E->setTarget(p, 0); }
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
