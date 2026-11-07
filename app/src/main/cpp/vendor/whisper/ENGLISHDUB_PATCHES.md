# EnglishDub local patches

Base source: upstream whisper.cpp v1.7.6, MIT licensed.

`src/whisper.cpp`: propagate `whisper_full_params.abort_callback` and its data through the scheduled convolution, encoder, cross-attention, and decoder graph helper. Register the callback with CPU backends and check an already-requested abort before graph computation.

The upstream direct graph helper registered the abort callback, while the scheduled helper used by encoder/decoder graphs did not. The latter checked cancellation only after encoding. EnglishDub's native cancellation instrumentation test exposed this delay. This patch changes cancellation propagation, not model weights, quantization, or successful inference math.

Preserve upstream notices. Re-evaluate this patch when upgrading whisper.cpp and retain the cancellation regression test.
