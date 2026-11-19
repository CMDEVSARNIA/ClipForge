// Shared by the JNI bridge and the host test (tools/whisper_host_test.cpp).
#include "whisper_core.h"
#include <cstdio>

static void append_json_string(std::string &out, const char *s) {
    out.push_back('"');
    for (const unsigned char *p = (const unsigned char *) s; *p; ++p) {
        unsigned char c = *p;
        switch (c) {
            case '"': out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            default:
                if (c < 0x20) {
                    char buf[8];
                    snprintf(buf, sizeof(buf), "\\u%04x", c);
                    out += buf;
                } else {
                    out.push_back((char) c);
                }
        }
    }
    out.push_back('"');
}


std::string clipforge_transcribe_json(whisper_context *ctx, const float *samples, int n, int threads,
                                      const std::string &langStr, ggml_abort_callback abort_cb) {
    whisper_full_params p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    p.n_threads = threads;
    p.print_progress = false;
    p.print_realtime = false;
    p.print_special = false;
    p.print_timestamps = false;
    p.translate = false;
    p.no_context = true;
    p.single_segment = false;
    // One segment per word, with timestamps.
    p.token_timestamps = true;
    p.max_len = 1;
    p.split_on_word = true;
    p.language = langStr.c_str();
    p.detect_language = false;
    p.suppress_blank = true;
    p.suppress_nst = true;
    p.no_speech_thold = 0.6f;
    p.abort_callback = abort_cb;
    p.abort_callback_user_data = nullptr;

    if (whisper_full(ctx, p, samples, n) != 0) return std::string();

    std::string json = "{\"lang\":";
    append_json_string(json, whisper_lang_str(whisper_full_lang_id(ctx)));
    json += ",\"segments\":[";
    const int nseg = whisper_full_n_segments(ctx);
    for (int i = 0; i < nseg; ++i) {
        // Average probability over the segment's text tokens (special tokens start with "[_").
        float psum = 0.f;
        int pn = 0;
        const int ntok = whisper_full_n_tokens(ctx, i);
        for (int t = 0; t < ntok; ++t) {
            const char *tt = whisper_full_get_token_text(ctx, i, t);
            if (tt && tt[0] == '[' && tt[1] == '_') continue;
            psum += whisper_full_get_token_p(ctx, i, t);
            pn++;
        }
        char nums[160];
        snprintf(nums, sizeof(nums), ",\"t0\":%lld,\"t1\":%lld,\"p\":%.4f,\"ns\":%.4f}",
                 (long long) whisper_full_get_segment_t0(ctx, i) * 10,
                 (long long) whisper_full_get_segment_t1(ctx, i) * 10,
                 pn > 0 ? psum / pn : 0.f,
                 whisper_full_get_segment_no_speech_prob(ctx, i));
        if (i > 0) json.push_back(',');
        json += "{\"t\":";
        append_json_string(json, whisper_full_get_segment_text(ctx, i));
        json += nums;
    }
    json += "]}";

    return json;
}
