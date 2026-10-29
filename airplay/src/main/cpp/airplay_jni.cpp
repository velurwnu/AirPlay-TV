// JNI bridge between the UxPlay AirPlay core and the Kotlin app.
//
// The core runs its own threads (RTSP/HTTP server, RTP audio, mirroring, NTP, mDNS) and reports
// everything through raop_callbacks_t. Those callbacks are forwarded here to a Kotlin object
// (com.amusic.airplay.NativeCallbacks). ALAC music is decoded here; AAC audio and H.264/H.265
// video are decoded by Android's MediaCodec on the Kotlin side.
//
// A small supervisor thread stands in for uxplay.cpp's GLib main loop: it watches the client's
// heartbeat and performs the session resets the core asks for.

#include <jni.h>
#include <android/log.h>
#include <pthread.h>
#include <signal.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include <atomic>
#include <climits>
#include <mutex>

extern "C" {
#include "raop.h"
#include "dnssd.h"
#include "logger.h"
#include "stream.h"
#include "raop_ntp.h"
}
#include "ALACBitUtilities.h"
#include "ALACDecoder.h"

#define TAG "AirPlayNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

constexpr int MISSED_FEEDBACK_LIMIT = 15;   // seconds without a client heartbeat before a reset
constexpr int MAX_SPF = 4096;               // ALAC samples per frame we can decode

JavaVM *vm = nullptr;
pthread_key_t env_key;
jclass string_class = nullptr;
jmethodID string_ctor = nullptr;

jobject callbacks = nullptr;   // global ref to the Kotlin NativeCallbacks
struct {
    jmethodID onClientRequest, onConnections, onConnectionLost, onAudioFormat, onAudio, onAudioFlush,
            onVolume, onMetadata, onCoverArt, onCoverArtStop, onProgress, onRemoteControl, onVideoCodec,
            onVideo, onVideoFlush, onMirrorRunning, onVideoReset, onSessionReset, onPin, onHlsPlay,
            onHlsScrub, onHlsRate, onHlsStop, hlsPlaybackInfo, onHlsPlaylistRemove;
} m;

std::mutex lifecycle;   // start / stop
raop_t *raop = nullptr;
dnssd_t *dnssd = nullptr;
pthread_t supervisor;
std::atomic<bool> running{false};

std::atomic<int> open_connections{0};
std::atomic<int> missed_feedback{0};
std::atomic<bool> reset_loop{false};
std::atomic<bool> reset_httpd{false};
std::atomic<bool> remove_known{false};
std::atomic<bool> hls_active{false};
std::atomic<bool> hls_eos{false};
std::atomic<double> current_volume{0.0};   // AirPlay dB: -30..0, -144 = mute

std::mutex alac_lock;
ALACDecoder *alac = nullptr;
uint32_t alac_spf = 0;
uint8_t pcm[MAX_SPF * 2 * 2];

// ---------- JNI plumbing ----------

void detach(void *) { vm->DetachCurrentThread(); }

/* The core's threads are attached once and detached when they exit. */
JNIEnv *env() {
    JNIEnv *e = nullptr;
    if (vm->GetEnv(reinterpret_cast<void **>(&e), JNI_VERSION_1_6) == JNI_OK) return e;
    JavaVMAttachArgs args{JNI_VERSION_1_6, "AirPlay", nullptr};
    if (vm->AttachCurrentThread(&e, &args) != JNI_OK) return nullptr;
    pthread_setspecific(env_key, e);
    return e;
}

/* Attached native threads never return to Java, so every callback frees its local refs. */
struct Frame {
    JNIEnv *e;
    explicit Frame(JNIEnv *env) : e(env) { if (e) e->PushLocalFrame(16); }
    ~Frame() {
        if (!e) return;
        if (e->ExceptionCheck()) { e->ExceptionDescribe(); e->ExceptionClear(); }
        e->PopLocalFrame(nullptr);
    }
};

/* Strings from the iPhone are real UTF-8 (emoji in device names), which NewStringUTF rejects. */
jstring str(JNIEnv *e, const char *s) {
    if (!s) s = "";
    jsize n = (jsize) strlen(s);
    jbyteArray b = e->NewByteArray(n);
    e->SetByteArrayRegion(b, 0, n, reinterpret_cast<const jbyte *>(s));
    jstring charset = e->NewStringUTF("UTF-8");
    return (jstring) e->NewObject(string_class, string_ctor, b, charset);
}

jbyteArray bytes(JNIEnv *e, const void *data, int len) {
    jbyteArray a = e->NewByteArray(len);
    if (a && len > 0) e->SetByteArrayRegion(a, 0, len, reinterpret_cast<const jbyte *>(data));
    return a;
}

#define CALL_VOID(method, ...) do { \
        JNIEnv *e_ = env(); \
        if (!e_ || !callbacks) break; \
        Frame f_(e_); \
        e_->CallVoidMethod(callbacks, m.method, ##__VA_ARGS__); \
    } while (0)

// ---------- ALAC ----------

void alac_init(uint32_t spf) {
    std::lock_guard<std::mutex> g(alac_lock);
    if (spf == 0 || spf > MAX_SPF) spf = 352;
    if (alac && alac_spf == spf) return;
    delete alac;
    // ALACSpecificConfig for AirPlay: 44.1 kHz, 16 bit, stereo, rice params 40/10/14.
    uint8_t cookie[24] = {
            (uint8_t) (spf >> 24), (uint8_t) (spf >> 16), (uint8_t) (spf >> 8), (uint8_t) spf,
            0, 16, 40, 10, 14, 2, 0x00, 0xff,
            0, 0, 0, 0,  0, 0, 0, 0,  0, 0, 0xac, 0x44};
    alac = new ALACDecoder();
    if (alac->Init(cookie, sizeof(cookie)) != 0) {
        LOGE("ALAC decoder init failed");
        delete alac;
        alac = nullptr;
    }
    alac_spf = spf;
}

// ---------- core callbacks ----------

void cb_log(void *, int level, const char *msg) {
    int prio = level <= LOGGER_ERR ? ANDROID_LOG_ERROR
             : level == LOGGER_WARNING ? ANDROID_LOG_WARN
             : level >= LOGGER_DEBUG ? ANDROID_LOG_DEBUG : ANDROID_LOG_INFO;
    __android_log_write(prio, "UxPlay", msg);
}

void cb_conn_init(void *) {
    int n = ++open_connections;
    CALL_VOID(onConnections, n);
}

void cb_conn_destroy(void *) {
    int n = --open_connections;
    if (n <= 0) { open_connections = 0; n = 0; missed_feedback = 0; }
    CALL_VOID(onConnections, n);
}

void cb_conn_feedback(void *) { missed_feedback = 0; }

void cb_conn_reset(void *, int reason) {
    reset_httpd = true;
    reset_loop = true;
    CALL_VOID(onConnectionLost, reason);
}

void cb_report_client_request(void *, char *deviceid, char *model, char *name, bool *admit) {
    *admit = true;
    JNIEnv *e = env();
    if (!e || !callbacks) return;
    Frame f(e);
    *admit = e->CallBooleanMethod(callbacks, m.onClientRequest, str(e, deviceid), str(e, model), str(e, name));
}

void cb_display_pin(void *, char *pin) {
    JNIEnv *e = env();
    if (!e || !callbacks) return;
    Frame f(e);
    e->CallVoidMethod(callbacks, m.onPin, str(e, pin));
}

const char *cb_passwd(void *, int *len) {
    *len = 0;   // no password; access control is the optional on-screen PIN
    return nullptr;
}

void cb_register_client(void *, const char *, const char *, const char *) {}

bool cb_check_register(void *, const char *) { return true; }

void remote_control(const char *dacp_id, const char *active_remote) {
    JNIEnv *e = env();
    if (!e || !callbacks || !dacp_id || !active_remote) return;
    Frame f(e);
    e->CallVoidMethod(callbacks, m.onRemoteControl, str(e, dacp_id), str(e, active_remote));
}

void cb_export_dacp(void *, const char *active_remote, const char *dacp_id) { remote_control(dacp_id, active_remote); }

void cb_audio_remote_control_id(void *, const char *dacp_id, const char *active_remote) { remote_control(dacp_id, active_remote); }

void cb_audio_get_format(void *, unsigned char *ct, unsigned short *spf, bool *using_screen, bool *is_media,
                         uint64_t *audio_format) {
    LOGI("audio format ct=%d spf=%d usingScreen=%d isMedia=%d format=0x%llx", *ct, *spf, *using_screen,
         *is_media, (unsigned long long) *audio_format);
    if (*ct == 2) alac_init(*spf);
    CALL_VOID(onAudioFormat, (jint) *ct, (jint) *spf, (jboolean) *using_screen, (jboolean) *is_media);
}

/* ct 2: ALAC, decoded here and passed on as 16-bit stereo PCM; ct 4 / 8: AAC-LC / AAC-ELD frames. */
void cb_audio_process(void *, raop_ntp_t *, audio_decode_struct *data) {
    // How long until the sender wants this packet heard (unknown before the first clock sync).
    jlong delay_us = LLONG_MIN;
    if (data->ntp_time_local) {
        delay_us = ((int64_t) data->ntp_time_local - (int64_t) raop_ntp_get_local_time()) / 1000;
    }
    JNIEnv *e = env();
    if (!e || !callbacks) return;
    Frame f(e);
    jbyteArray payload;
    if (data->ct == 2) {
        std::lock_guard<std::mutex> g(alac_lock);
        if (!alac) return;
        BitBuffer bits;
        BitBufferInit(&bits, data->data, (uint32_t) data->data_len);
        uint32_t frames = 0;
        if (alac->Decode(&bits, pcm, alac_spf, 2, &frames) != 0 || frames == 0) return;
        payload = bytes(e, pcm, (int) (frames * 4));
    } else {
        payload = bytes(e, data->data, data->data_len);
    }
    e->CallVoidMethod(callbacks, m.onAudio, (jint) data->ct, payload, (jlong) data->rtp_time, delay_us);
}

void cb_audio_flush(void *) { CALL_VOID(onAudioFlush); }

double cb_audio_set_client_volume(void *) { return current_volume; }

void cb_audio_set_volume(void *, float volume) {
    current_volume = volume;
    CALL_VOID(onVolume, (jfloat) volume);
}

void cb_audio_set_metadata(void *, const void *buffer, int len) {
    JNIEnv *e = env();
    if (!e || !callbacks || !buffer) return;
    Frame f(e);
    e->CallVoidMethod(callbacks, m.onMetadata, bytes(e, buffer, len));
}

void cb_audio_set_coverart(void *, const void *buffer, int len) {
    JNIEnv *e = env();
    if (!e || !callbacks || !buffer || len <= 0) return;
    Frame f(e);
    e->CallVoidMethod(callbacks, m.onCoverArt, bytes(e, buffer, len));
}

void cb_audio_stop_coverart_rendering(void *) { CALL_VOID(onCoverArtStop); }

void cb_audio_set_progress(void *, uint32_t *start, uint32_t *curr, uint32_t *end) {
    CALL_VOID(onProgress, (jlong) *start, (jlong) *curr, (jlong) *end);
}

int cb_video_set_codec_and_size(void *, video_codec_t codec, unsigned short width_source,
                                unsigned short height_source, unsigned short, unsigned short,
                                unsigned short width, unsigned short height) {
    CALL_VOID(onVideoCodec, (jboolean) (codec == VIDEO_CODEC_H265), (jint) width_source, (jint) height_source,
              (jint) width, (jint) height);
    return 0;
}

void cb_video_process(void *, raop_ntp_t *, video_decode_struct *data) {
    JNIEnv *e = env();
    if (!e || !callbacks) return;
    Frame f(e);
    e->CallVoidMethod(callbacks, m.onVideo, bytes(e, data->data, data->data_len), (jboolean) data->is_h265);
}

void cb_video_flush(void *) { CALL_VOID(onVideoFlush); }

void cb_video_pause(void *) {}

void cb_video_resume(void *) {}

void cb_mirror_video_running(void *, bool is_running) { CALL_VOID(onMirrorRunning, (jboolean) is_running); }

/* Mirrors uxplay.cpp's video_reset() plus what its main loop does after it. */
void cb_video_reset(void *, reset_type_t type) {
    LOGI("video reset, type %d", (int) type);
    switch (type) {
        case RESET_TYPE_NOHOLD:            // another client took over
            if (raop) raop_destroy_airplay_video(raop, -1);
            hls_active = false;
            break;
        case RESET_TYPE_HLS_EOS:
            hls_active = false;
            break;
        case RESET_TYPE_RTP_SHUTDOWN:      // mirroring stopped
            remove_known = true;
            break;
        case RESET_TYPE_HLS_SHUTDOWN:
            if (raop) {
                raop_destroy_airplay_video(raop, -1);
                raop_remove_hls_connections(raop);
            }
            hls_active = false;
            break;
        case RESET_TYPE_RTP_TO_HLS_TEARDOWN:
        case RESET_TYPE_ON_VIDEO_PLAY:
        default:
            break;
    }
    CALL_VOID(onVideoReset, (jint) type);
    if (type != RESET_TYPE_ON_VIDEO_PLAY) reset_loop = true;
}

// ---------- HLS video ("AirPlay video" from YouTube and other apps) ----------

void cb_on_video_play(void *, const char *location, const float start_position) {
    hls_active = true;
    JNIEnv *e = env();
    if (!e || !callbacks) return;
    Frame f(e);
    e->CallVoidMethod(callbacks, m.onHlsPlay, str(e, location), (jfloat) start_position);
}

void cb_on_video_scrub(void *, const float position) { CALL_VOID(onHlsScrub, (jfloat) position); }

void cb_on_video_rate(void *, const float rate) { CALL_VOID(onHlsRate, (jfloat) rate); }

void cb_on_video_stop(void *) { CALL_VOID(onHlsStop); }

float cb_on_video_playlist_remove(void *) {
    JNIEnv *e = env();
    if (!e || !callbacks) return 0.0f;
    Frame f(e);
    return e->CallFloatMethod(callbacks, m.onHlsPlaylistRemove);
}

/* Kotlin returns {duration, position, seekStart, seekDuration, rate, bufferEmpty, bufferFull, ended}. */
void cb_on_video_acquire_playback_info(void *, playback_info_t *info) {
    info->duration = 0.0;
    info->position = -1.0;
    info->seek_start = 0.0;
    info->seek_duration = 0.0;
    info->rate = 0.0f;
    info->ready_to_play = true;
    info->playback_likely_to_keep_up = true;
    info->playback_buffer_empty = false;
    info->playback_buffer_full = false;
    JNIEnv *e = env();
    if (!e || !callbacks) return;
    Frame f(e);
    auto arr = (jdoubleArray) e->CallObjectMethod(callbacks, m.hlsPlaybackInfo);
    if (!arr || e->GetArrayLength(arr) < 8) return;
    jdouble v[8];
    e->GetDoubleArrayRegion(arr, 0, 8, v);
    info->duration = v[0];
    info->position = v[1];
    info->seek_start = v[2];
    info->seek_duration = v[3];
    info->rate = (float) v[4];
    info->playback_buffer_empty = v[5] != 0.0;
    info->playback_buffer_full = v[6] != 0.0;
    if (v[7] != 0.0) {   // finished: the core then shuts the HLS session down
        info->position = -1.0;
        info->duration = -1.0;
    }
}

// ---------- supervisor (replaces uxplay's main loop) ----------

void *supervise(void *) {
    int ticks = 0;
    while (running) {
        usleep(100 * 1000);
        if (!running) break;

        if (hls_eos.exchange(false) && hls_active.exchange(false) && raop) {
            raop_handle_eos(raop);
        }

        if (++ticks % 10 == 0) {   // once a second: client heartbeat
            if (open_connections > 0) {
                if (++missed_feedback > MISSED_FEEDBACK_LIMIT) {
                    LOGE("lost connection with client: no heartbeat for %d s", MISSED_FEEDBACK_LIMIT);
                    missed_feedback = 0;
                    reset_httpd = true;
                    remove_known = true;
                    reset_loop = true;
                }
            } else {
                missed_feedback = 0;
            }
        }

        if (reset_loop.exchange(false) && raop) {
            bool restart = reset_httpd.exchange(false);
            if (restart) raop_stop_httpd(raop);
            CALL_VOID(onSessionReset);
            if (remove_known.exchange(false)) raop_remove_known_connections(raop);
            if (restart) {
                unsigned short port = raop_get_port(raop);
                raop_start_httpd(raop, &port);
                raop_set_port(raop, port);
            }
        }
    }
    return nullptr;
}

void cache_methods(JNIEnv *e, jobject cb) {
    jclass c = e->GetObjectClass(cb);
    m.onClientRequest = e->GetMethodID(c, "onClientRequest", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Z");
    m.onConnections = e->GetMethodID(c, "onConnections", "(I)V");
    m.onConnectionLost = e->GetMethodID(c, "onConnectionLost", "(I)V");
    m.onAudioFormat = e->GetMethodID(c, "onAudioFormat", "(IIZZ)V");
    m.onAudio = e->GetMethodID(c, "onAudio", "(I[BJJ)V");
    m.onAudioFlush = e->GetMethodID(c, "onAudioFlush", "()V");
    m.onVolume = e->GetMethodID(c, "onVolume", "(F)V");
    m.onMetadata = e->GetMethodID(c, "onMetadata", "([B)V");
    m.onCoverArt = e->GetMethodID(c, "onCoverArt", "([B)V");
    m.onCoverArtStop = e->GetMethodID(c, "onCoverArtStop", "()V");
    m.onProgress = e->GetMethodID(c, "onProgress", "(JJJ)V");
    m.onRemoteControl = e->GetMethodID(c, "onRemoteControl", "(Ljava/lang/String;Ljava/lang/String;)V");
    m.onVideoCodec = e->GetMethodID(c, "onVideoCodec", "(ZIIII)V");
    m.onVideo = e->GetMethodID(c, "onVideo", "([BZ)V");
    m.onVideoFlush = e->GetMethodID(c, "onVideoFlush", "()V");
    m.onMirrorRunning = e->GetMethodID(c, "onMirrorRunning", "(Z)V");
    m.onVideoReset = e->GetMethodID(c, "onVideoReset", "(I)V");
    m.onSessionReset = e->GetMethodID(c, "onSessionReset", "()V");
    m.onPin = e->GetMethodID(c, "onPin", "(Ljava/lang/String;)V");
    m.onHlsPlay = e->GetMethodID(c, "onHlsPlay", "(Ljava/lang/String;F)V");
    m.onHlsScrub = e->GetMethodID(c, "onHlsScrub", "(F)V");
    m.onHlsRate = e->GetMethodID(c, "onHlsRate", "(F)V");
    m.onHlsStop = e->GetMethodID(c, "onHlsStop", "()V");
    m.hlsPlaybackInfo = e->GetMethodID(c, "hlsPlaybackInfo", "()[D");
    m.onHlsPlaylistRemove = e->GetMethodID(c, "onHlsPlaylistRemove", "()F");
    e->DeleteLocalRef(c);
}

void stop_locked(JNIEnv *e) {
    if (running.exchange(false)) pthread_join(supervisor, nullptr);
    if (raop) {
        raop_destroy(raop);   // stops the server and all its threads
        raop = nullptr;
    }
    if (dnssd) {
        dnssd_unregister_raop(dnssd);
        dnssd_unregister_airplay(dnssd);
        dnssd_destroy(dnssd);
        dnssd = nullptr;
    }
    {
        std::lock_guard<std::mutex> g(alac_lock);
        delete alac;
        alac = nullptr;
        alac_spf = 0;
    }
    if (callbacks) {
        e->DeleteGlobalRef(callbacks);
        callbacks = nullptr;
    }
    open_connections = 0;
    hls_active = false;
}

bool parse_mac(const char *s, char out[6]) {
    unsigned int b[6];
    if (sscanf(s, "%x:%x:%x:%x:%x:%x", &b[0], &b[1], &b[2], &b[3], &b[4], &b[5]) != 6) return false;
    for (int i = 0; i < 6; i++) out[i] = (char) b[i];
    return true;
}

}  // namespace

// ---------- exported to Kotlin (com.amusic.airplay.AirPlayNative) ----------

extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM *jvm, void *) {
    vm = jvm;
    JNIEnv *e = nullptr;
    if (vm->GetEnv(reinterpret_cast<void **>(&e), JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    pthread_key_create(&env_key, detach);
    jclass s = e->FindClass("java/lang/String");
    string_class = (jclass) e->NewGlobalRef(s);
    string_ctor = e->GetMethodID(s, "<init>", "([BLjava/lang/String;)V");
    e->DeleteLocalRef(s);
    signal(SIGPIPE, SIG_IGN);   // a client vanishing mid-write must not kill the app
    ntp_global_init();
    return JNI_VERSION_1_6;
}

/**
 * Starts the receiver. Returns the TCP port it listens on, or a negative error:
 * -1 bad arguments, -2 mDNS init failed, -3 server init failed, -4 mDNS registration failed
 * (UDP 5353 / multicast not available).
 */
extern "C" JNIEXPORT jint JNICALL
Java_com_amusic_airplay_AirPlayNative_start(JNIEnv *e, jobject, jobject cb, jstring jname, jstring jmac,
                                            jstring jhost, jstring jkeyfile, jint width, jint height,
                                            jint fps, jboolean h265, jboolean hls, jboolean pin,
                                            jstring jlang, jint base_port, jint log_level) {
    std::lock_guard<std::mutex> g(lifecycle);
    stop_locked(e);

    const char *name = e->GetStringUTFChars(jname, nullptr);
    const char *mac = e->GetStringUTFChars(jmac, nullptr);
    const char *host = e->GetStringUTFChars(jhost, nullptr);
    const char *keyfile = e->GetStringUTFChars(jkeyfile, nullptr);
    const char *lang = e->GetStringUTFChars(jlang, nullptr);
    jint result = 0;

    do {
        char hw_addr[6];
        if (!parse_mac(mac, hw_addr)) { result = -1; break; }

        callbacks = e->NewGlobalRef(cb);
        cache_methods(e, callbacks);
        reset_loop = false;
        reset_httpd = false;
        remove_known = false;
        hls_eos = false;
        missed_feedback = 0;
        current_volume = -15.0;

        setenv("UXPLAY_MDNS_HOSTNAME", host, 1);
        int err = 0;
        dnssd = dnssd_init(name, (int) strlen(name), hw_addr, 6, pin ? 1 : 0, &err);
        if (err || !dnssd) { LOGE("dnssd_init error %d", err); dnssd = nullptr; result = -2; break; }

        // Same feature set as uxplay.cpp's start_dnssd().
        const int on[] = {1, 2, 5, 6, 7, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 25, 27, 28, 30};
        for (int bit = 0; bit < 62; bit++) dnssd_set_airplay_features(dnssd, bit, 0);
        for (int bit : on) dnssd_set_airplay_features(dnssd, bit, 1);
        dnssd_set_airplay_features(dnssd, 0, hls ? 1 : 0);    // AirPlay video
        dnssd_set_airplay_features(dnssd, 4, hls ? 1 : 0);    // HLS
        dnssd_set_airplay_features(dnssd, 42, h265 ? 1 : 0);  // screen multi-codec (H.265)

        raop_callbacks_t cbs;
        memset(&cbs, 0, sizeof(cbs));
        cbs.conn_init = cb_conn_init;
        cbs.conn_destroy = cb_conn_destroy;
        cbs.conn_reset = cb_conn_reset;
        cbs.conn_feedback = cb_conn_feedback;
        cbs.audio_process = cb_audio_process;
        cbs.video_process = cb_video_process;
        cbs.audio_flush = cb_audio_flush;
        cbs.video_flush = cb_video_flush;
        cbs.video_pause = cb_video_pause;
        cbs.video_resume = cb_video_resume;
        cbs.audio_set_client_volume = cb_audio_set_client_volume;
        cbs.audio_set_volume = cb_audio_set_volume;
        cbs.audio_get_format = cb_audio_get_format;
        cbs.audio_set_metadata = cb_audio_set_metadata;
        cbs.audio_set_coverart = cb_audio_set_coverart;
        cbs.audio_stop_coverart_rendering = cb_audio_stop_coverart_rendering;
        cbs.audio_remote_control_id = cb_audio_remote_control_id;
        cbs.audio_set_progress = cb_audio_set_progress;
        cbs.mirror_video_running = cb_mirror_video_running;
        cbs.report_client_request = cb_report_client_request;
        cbs.display_pin = cb_display_pin;
        cbs.register_client = cb_register_client;
        cbs.check_register = cb_check_register;
        cbs.passwd = cb_passwd;
        cbs.export_dacp = cb_export_dacp;
        cbs.video_reset = cb_video_reset;
        cbs.video_set_codec_and_size = cb_video_set_codec_and_size;
        cbs.on_video_play = cb_on_video_play;
        cbs.on_video_scrub = cb_on_video_scrub;
        cbs.on_video_rate = cb_on_video_rate;
        cbs.on_video_stop = cb_on_video_stop;
        cbs.on_video_playlist_remove = cb_on_video_playlist_remove;
        cbs.on_video_acquire_playback_info = cb_on_video_acquire_playback_info;

        raop = raop_init(&cbs);
        if (!raop) { result = -3; break; }
        raop_set_log_callback(raop, cb_log, nullptr);
        raop_set_log_level(raop, log_level);
        if (raop_init2(raop, 1, mac, keyfile)) { free(raop); raop = nullptr; result = -3; break; }

        raop_set_plist(raop, "width", width);
        raop_set_plist(raop, "height", height);
        raop_set_plist(raop, "refreshRate", fps);
        raop_set_plist(raop, "maxFPS", fps > 60 ? 60 : fps);
        if (hls) raop_set_plist(raop, "hls", 1);
        if (pin) raop_set_plist(raop, "pin", 0);   // a new random PIN on screen for each first-time pairing

        // Port 0: any free port. With a base port N: TCP N (RTSP), N+1 (mirroring); UDP N..N+2 (timing, control, audio).
        unsigned short b = (unsigned short) base_port;
        unsigned short tcp[2] = {(unsigned short) (b ? b + 1 : 0), b};
        unsigned short udp[3] = {b, (unsigned short) (b ? b + 1 : 0), (unsigned short) (b ? b + 2 : 0)};
        raop_set_tcp_ports(raop, tcp);
        raop_set_udp_ports(raop, udp);
        unsigned short port = raop_get_port(raop);
        if (raop_start_httpd(raop, &port) < 0) { result = -3; break; }
        raop_set_port(raop, port);
        raop_set_dnssd(raop, dnssd);
        if (hls) raop_set_lang(raop, lang, lang, lang);

        if (dnssd_register_raop(dnssd, port) || dnssd_register_airplay(dnssd, port)) {
            LOGE("mDNS registration failed");
            result = -4;
            break;
        }
        LOGI("AirPlay receiver \"%s\" on port %d, features 0x%llx", name, port,
             (unsigned long long) dnssd_get_airplay_features(dnssd));

        running = true;
        pthread_create(&supervisor, nullptr, supervise, nullptr);
        result = port;
    } while (false);

    if (result < 0) stop_locked(e);
    e->ReleaseStringUTFChars(jname, name);
    e->ReleaseStringUTFChars(jmac, mac);
    e->ReleaseStringUTFChars(jhost, host);
    e->ReleaseStringUTFChars(jkeyfile, keyfile);
    e->ReleaseStringUTFChars(jlang, lang);
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_amusic_airplay_AirPlayNative_stop(JNIEnv *e, jobject) {
    std::lock_guard<std::mutex> g(lifecycle);
    stop_locked(e);
}

/** The HLS player reached the end of the video. */
extern "C" JNIEXPORT void JNICALL
Java_com_amusic_airplay_AirPlayNative_hlsEnded(JNIEnv *, jobject) {
    hls_eos = true;
}
