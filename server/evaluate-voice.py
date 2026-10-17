"""Synthetic speech smoke test. No microphone, no remote inference, no paid API.

Run with .runtime/voice/venv/bin/python server/evaluate-voice.py from the app root.
Downloads must already be complete. Outputs are test fixtures, never user speech.
"""
import contextlib
import importlib.metadata
import json
import os
from pathlib import Path
import subprocess
import time
import wave

ROOT = Path(__file__).resolve().parents[1]
RUNTIME = ROOT / ".runtime/voice"
os.environ["HF_HOME"] = str(RUNTIME / "hf")
os.environ["HF_HUB_OFFLINE"] = "1"
os.environ["TRANSFORMERS_OFFLINE"] = "1"
os.environ["HF_HUB_DISABLE_TELEMETRY"] = "1"

import mlx.core as mx
import numpy as np
from scipy.signal import resample_poly
from mlx_audio.tts.utils import load_model

CASES = [
    ("ko", "Korean", "sohee", "유키 씨, 금요일까지 수정한 일정을 보내 주세요."),
    ("en", "English", "ryan", "Yuki, please send the updated schedule by Friday."),
    ("ja", "Japanese", "ono_anna", "ユキさん、金曜日までに修正した予定を送ってください。"),
]
OUT = RUNTIME / "fixtures"
OUT.mkdir(exist_ok=True)
REPORT = ROOT / "docs/validation/local-voice-smoke.json"
report = {
    "scope": "synthetic TTS to STT smoke test; not human speech accuracy or listening quality",
    "ttsModel": "mlx-community/Qwen3-TTS-12Hz-0.6B-CustomVoice-bf16",
    "ttsRevision": "6415d95f88be018ff9e46813119dc3bc12261328",
    "asrRevision": "ea30d66debe3740a08b573244286791d423d6b3e",
    "asrSha256": "3fc991d3badad7277c11030a7519832cddaf2057aafed6d4b25147e953a070b1",
    "mlxAudioVersion": importlib.metadata.version("mlx-audio"),
    "metal": mx.metal.is_available(),
    "results": [],
}

def save_report():
    REPORT.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")

def write_wav(path, audio, rate):
    with wave.open(str(path), "wb") as wav:
        wav.setnchannels(1)
        wav.setsampwidth(2)
        wav.setframerate(rate)
        wav.writeframes((np.clip(audio, -1, 1) * 32767).astype("<i2").tobytes())

started = time.monotonic()
model = load_model(str(RUNTIME / "models/qwen3-tts-0.6b"))
report["ttsLoadSeconds"] = time.monotonic() - started
report["supportedSpeakers"] = model.supported_speakers
save_report()
for code, language, speaker, text in CASES:
    result = {"language": code, "input": text, "speaker": speaker}
    report["results"].append(result)
    try:
        mx.random.seed(42)
        mx.reset_peak_memory()
        started = time.monotonic()
        chunks, tokens = [], 0
        for chunk in model.generate_custom_voice(
            text=text, speaker=speaker, language=language,
            stream=True, streaming_interval=0.32, max_tokens=384,
            temperature=0.7, verbose=False,
        ):
            audio = np.asarray(chunk.audio, dtype=np.float32).reshape(-1)
            if audio.size:
                result.setdefault("ttsFirstChunkSeconds", time.monotonic() - started)
                chunks.append(audio)
            tokens += chunk.token_count
        result["ttsSeconds"] = time.monotonic() - started
        result["ttsMlxPeakBytes"] = mx.get_peak_memory()
        if not chunks or tokens >= 384:
            raise RuntimeError("missing or potentially truncated speech")
        audio = np.concatenate(chunks)
        if model.sample_rate != 24000 or audio.size > 30 * model.sample_rate:
            raise RuntimeError("unexpected sample rate or duration")
        result["audioSeconds"] = audio.size / model.sample_rate
        result["ttsComputeSecondsPerAudioSecond"] = result["ttsSeconds"] / result["audioSeconds"]
        write_wav(OUT / f"{code}-tts.wav", audio, 24000)
        write_wav(OUT / f"{code}-asr.wav", resample_poly(audio, 2, 3), 16000)
        save_report()
        started = time.monotonic()
        run = subprocess.run([
            str(RUNTIME / "nemo-speech-0.2.0-macos-aarch64-metal/bin/nemo-speech"),
            "--quiet", "transcribe", str(OUT / f"{code}-asr.wav"),
            "--model", str(RUNTIME / "models/nemotron-3.5-asr-streaming-0.6b.q8_0.gguf"),
            "--language", code, "--backend", "metal", "--no-warmup", "--format", "json",
        ], capture_output=True, text=True, timeout=90, check=True)
        result["sttProcessSecondsIncludingLoad"] = time.monotonic() - started
        result["stt"] = json.loads(run.stdout)
    except Exception as exc:
        result["error"] = f"{type(exc).__name__}: {exc}"
    save_report()
    print(code, "ok" if "stt" in result else result.get("error"), flush=True)
print("Report:", REPORT.relative_to(ROOT), flush=True)
