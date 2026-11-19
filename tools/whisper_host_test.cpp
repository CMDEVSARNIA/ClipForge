// Host-side check of the exact transcription code the app uses (app/src/main/cpp/whisper_core.cpp).
// Build: see tools/README in the main README ("Testing captions on a desktop").
#include <cstdio>
#include <cstdint>
#include <cstring>
#include <vector>
#include "whisper_core.h"

static bool no_abort(void *) { return false; }

int main(int argc, char **argv) {
    if (argc < 3) { fprintf(stderr, "usage: %s model.bin audio_16k_mono_s16.wav [lang]\n", argv[0]); return 2; }
    FILE *f = fopen(argv[2], "rb");
    if (!f) { perror("wav"); return 1; }
    std::vector<uint8_t> buf;
    uint8_t tmp[65536];
    size_t n;
    while ((n = fread(tmp, 1, sizeof(tmp), f)) > 0) buf.insert(buf.end(), tmp, tmp + n);
    fclose(f);
    // Find the "data" chunk.
    size_t pos = 12, dataOff = 0, dataLen = 0;
    while (pos + 8 <= buf.size()) {
        uint32_t len; memcpy(&len, &buf[pos + 4], 4);
        if (memcmp(&buf[pos], "data", 4) == 0) { dataOff = pos + 8; dataLen = len; break; }
        pos += 8 + len;
    }
    if (!dataOff) { fprintf(stderr, "no data chunk\n"); return 1; }
    std::vector<float> pcm(dataLen / 2);
    for (size_t i = 0; i < pcm.size(); ++i) { int16_t s; memcpy(&s, &buf[dataOff + i * 2], 2); pcm[i] = s / 32768.0f; }
    whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(argv[1], cp);
    if (!ctx) { fprintf(stderr, "model load failed\n"); return 1; }
    std::string json = clipforge_transcribe_json(ctx, pcm.data(), (int) pcm.size(), 4, argc > 3 ? argv[3] : "auto", no_abort);
    whisper_free(ctx);
    printf("%s\n", json.c_str());
    return json.empty() ? 1 : 0;
}
