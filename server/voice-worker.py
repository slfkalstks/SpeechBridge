"""One offline TTS request per process. Input via stdin; no transcript logging."""
import json
import sys
import wave


def main():
    import mlx.core as mx
    import numpy as np
    from mlx_audio.tts.utils import load_model

    request = json.loads(sys.stdin.read(4096))
    language, speaker = {
        "ko": ("Korean", "sohee"), "en": ("English", "ryan"),
        "ja": ("Japanese", "ono_anna"),
    }[request["language"]]
    model = load_model(sys.argv[1])
    if model.sample_rate != 24000:
        raise ValueError("sample rate")
    mx.random.seed(42)
    chunks, tokens, samples = [], 0, 0
    for chunk in model.generate_custom_voice(
        text=request["text"], speaker=speaker, language=language,
        stream=True, streaming_interval=0.32, max_tokens=384,
        temperature=0.7, verbose=False,
    ):
        audio = np.asarray(chunk.audio, dtype=np.float32).reshape(-1)
        tokens += chunk.token_count
        samples += audio.size
        if tokens >= 384 or samples > 30 * 24000 or not np.isfinite(audio).all():
            raise ValueError("incomplete or oversized speech")
        if audio.size:
            chunks.append(audio)
    if not chunks:
        raise ValueError("empty speech")
    audio = np.concatenate(chunks)
    with wave.open(sys.argv[2], "wb") as wav:
        wav.setnchannels(1)
        wav.setsampwidth(2)
        wav.setframerate(24000)
        wav.writeframes((np.clip(audio, -1, 1) * 32767).astype("<i2").tobytes())


if __name__ == "__main__":
    try:
        main()
    except Exception:
        # Raw model errors may contain user text. The parent returns a fixed API error.
        sys.exit(1)
