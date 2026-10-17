# 무료 음성 엔진 첫 실행 결과

2026-10-17. M3 Max 36GB, 새 경로 `middle_test/SpeechBridge`. 사용자 마이크·유료 API 없이 가상 문장을 합성하고 같은 음성을 전사했다. **엔진 실행 확인이며 실사용 정확도·청취 품질·앱 실시간 통역 검증은 아니다.**

## 환경과 고정 버전

- NeMo-Speech.cpp v0.2.0 macOS aarch64 Metal. 공식 release archive SHA256 `5cb02ba7c04f0b5585ce5cde9c830be5f0b7dfb4c83083c500109c381b4f2da9`와 게시된 checksum 일치.
- Nemotron3.5 ASR Streaming0.6B Q8 GGUF: revision `ea30d66debe3740a08b573244286791d423d6b3e`, 742,090,464bytes. SHA256 `3fc991d3badad7277c11030a7519832cddaf2057aafed6d4b25147e953a070b1`가 공식 Hugging Face LFS 메타데이터와 일치.
- Qwen3-TTS0.6B CustomVoice bf16 MLX 변환본: revision `6415d95f88be018ff9e46813119dc3bc12261328`. `.runtime/voice/models/qwen3-tts-0.6b`에 다운로드. Python3.13, mlx-audio0.5.9, mlx0.32.4. 전체 의존성은 `server/voice-requirements.lock.txt`.
- TTS는 한 프로세스에서 모델을 한 번 로드한 뒤 ko→en→ja 순서. STT는 각 WAV마다 새 CLI 프로세스, `--backend metal --no-warmup --format json` 사용. TTS 모델이 상주한 상태에서 STT 실행, 두 추론은 순차 실행. Ollama/텍스트 AI 동시 추론은 하지 않았다.

공식 근거: [NVIDIA 설치](https://github.com/NVIDIA/NeMo-Speech.cpp/blob/v0.2.0/docs/install.md), [ASR 모델](https://huggingface.co/nvidia/nemotron-3.5-asr-streaming-0.6b/tree/ea30d66debe3740a08b573244286791d423d6b3e), [MLX TTS 구현](https://github.com/Blaizzy/mlx-audio/blob/main/mlx_audio/tts/models/qwen3_tts/README.md), [고정된 TTS 변환본](https://huggingface.co/mlx-community/Qwen3-TTS-12Hz-0.6B-CustomVoice-bf16/tree/6415d95f88be018ff9e46813119dc3bc12261328). ASR 모델 라이선스는 OpenMDW-1.1, NVIDIA 런타임·TTS 변환본은 각 공식 저장소/카드의 Apache-2.0 표기를 확인했다.

## 관찰

| 순서/언어 | TTS 첫 조각 | 전체 합성 | 오디오 길이 | STT 프로세스 전체 시간 |
|---|---:|---:|---:|---:|
| 첫 한국어 | 3.036초 | 4.548초 | 4.960초 | 16.418초 |
| 이후 영어 | 0.205초 | 1.631초 | 4.560초 | 0.344초 |
| 이후 일본어 | 0.117초 | 1.718초 | 5.280초 | 0.350초 |

TTS 모델 로드 2.468초는 위 첫 조각 시간에 포함하지 않았다. 첫 조각은 Python generator가 PCM을 반환한 시점이며 모바일 재생까지의 시간이 아니다. STT 시간에는 프로세스 시작·로드가 포함된다. 첫 실행과 이후 차이가 컸으나 원인을 분리 측정하지 않았으므로 단일 원인으로 단정하지 않는다. 언어별 한 문장·서로 다른 내용과 순서이므로 언어별 성능 순위도 아니다. MLX가 보고한 TTS peak allocation은 약3.092GB이며 전체 시스템/프로세스 메모리가 아니다.

- 한국어 입력: `유키 씨, 금요일까지 수정한 일정을 보내 주세요.` → `유키씨 금요일까지 수정한 일정을 보내주세요.`
- 영어 입력: `Yuki, please send the updated schedule by Friday.` → `Yuki please send the updated schedule by Friday.`
- 일본어 입력: `ユキさん、金曜日までに修正した予定を送ってください。` → `由さん金曜日までに修正した予定を送ってください。`

일본어 이름 불일치가 확인됐다. TTS 발음 문제인지 STT 인식 문제인지 아직 분리하지 않았다. 엔진이 반환한 confidence=1도 정확도 확률로 사용하지 않는다. 문장 부호·띄어쓰기 외의 고유명사 오류가 있으므로 모델 실행 성공을 정확한 전사로 표시하면 안 된다. 사용자 확인/수정 단계 유지.

## 재현과 다음 검증

모델이 준비된 상태에서 앱 루트에서 실행:

```sh
.runtime/voice/venv/bin/python server/evaluate-voice.py
```

이 스크립트는 로컬 파일만 로드하고 Hugging Face offline을 켠다. Python generator의 PCM 조각을 합쳐 WAV를 만든다. STT에는 16kHz mono PCM16으로 resample한 WAV를 전달한다. 현재는 파일 전사이며 WebSocket 실시간 입력을 검증한 것이 아니다. 산출물은 `.runtime/voice/fixtures`, 원자료는 [local-voice-smoke.json](validation/local-voice-smoke.json). 반복 실행은 이 테스트 산출물을 갱신한다. 일반 사용자 녹음/앱 기록은 다루지 않는다.

남은 작업: 인증된 앱용 API, 취소 후 실제 프로세스 회수, 길이/형식 제한, Android/iOS 마이크·재생 연결, 사람 음성/소음/고유명사 검증, 사람이 듣는 발음 확인, 텍스트 모델과 혼합 부하/메모리, 반복 분포와 스트리밍 지연. 엔진 검증 종료 후 상주 음성 서버는 실행하지 않았다.
