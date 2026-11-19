#pragma once
#include <string>
#include "whisper.h"

/**
 * Transcribes 16 kHz mono PCM into word-level segments, as JSON:
 * {"lang":"en","segments":[{"t":" word","t0":ms,"t1":ms,"p":avgTokenProb,"ns":noSpeechProb}, ...]}
 * Returns an empty string on failure.
 */
std::string clipforge_transcribe_json(whisper_context *ctx, const float *samples, int n, int threads,
                                      const std::string &language, ggml_abort_callback abort_cb);
