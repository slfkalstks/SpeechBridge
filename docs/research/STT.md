# 무료 STT 조사: Mac 서버, 한국어·영어·일본어

조사일: 2026-10-17. 대상 장비는 현재 사용 가능한 M3 Max / 통합 메모리 36GB. 이 문서는 **문서 기반 후보 선정**이며 설치·모델 다운로드·유료 호출·실제 음성 추론을 하지 않았다. 모델 제공자의 벤치마크와 이 Mac에서 측정한 결과를 구분한다. 모델 사용료가 없어도 기존 Mac의 전력·메모리·네트워크는 사용한다.

## 판단

**서버 STT의 첫 기술 검증 후보는 Nemotron-3.5-ASR-Streaming-0.6B + NVIDIA 공식 NeMo-Speech.cpp Metal 서버다.** 세 언어, 실제 입력 스트리밍, 공식 Mac 실행 경로, 미리 변환된 작은 가중치, WebSocket 연결이 동시에 확인된다. 이는 적합도 판단이며 정확도 1위라는 의미는 아니다. 같은 음성으로 Qwen3-ASR-1.7B, Cohere Transcribe, Whisper large-v3/turbo와 비교해야 한다. 스트리밍 경쟁 후보로 Voxtral Realtime도 유지한다.

무료 클라우드는 Gemini 3.5 Transcribe Live, Azure Speech F0, Groq Whisper를 비교 후보로 둔다. 무료 할당량은 무제한 서비스 보장이 아니다. **결제 없는 계정·무료 SKU에서만 호출하고 초과 시 중단**하는 것이 이번 프로젝트 정책이다. 크레딧 소진 후 유료 전환하는 구성은 채택하지 않는다.

## 자체 Mac 서버 후보

| 후보 | 세 언어 | 실시간 처리의 실체 / Mac 경로 | 코드·가중치 | 판단 |
|---|---|---|---|---|
| **NVIDIA Nemotron 3.5 ASR Streaming 0.6B** | 한·영·일 모두 transcription-ready | 상태 캐시를 유지하는 FastConformer-RNNT. 공식 NeMo-Speech.cpp가 Metal과 WebSocket 서버 지원 | 런타임 Apache-2.0 / 모델 OpenMDW-1.1 | 실시간 1차 후보. [모델](https://huggingface.co/nvidia/nemotron-3.5-asr-streaming-0.6b), [공식 런타임](https://github.com/NVIDIA/NeMo-Speech.cpp) |
| **Qwen3-ASR 1.7B / 0.6B** | 모두 지원 | 모델은 offline/streaming 통합. 원 저자 패키지 streaming은 vLLM 전용. MLX-Audio 포트는 Mac에서 파일 전사 및 토큰 출력 가능 | 원본 코드·모델 Apache-2.0; 포트 별도 확인 | 1.7B 정확도 비교 우선. MLX의 `stream_transcribe(audio.wav)`를 실시간 오디오 입력 지원과 혼동하지 않는다. [공식](https://github.com/QwenLM/Qwen3-ASR), [가중치](https://huggingface.co/Qwen/Qwen3-ASR-1.7B), [MLX 구현 문서](https://github.com/Blaizzy/mlx-audio/blob/main/mlx_audio/stt/models/qwen3_asr/README.md) |
| **Whisper large-v3 / large-v3-turbo** | 모두 지원 | 원본은 30초 window 기반 파일 전사. live는 VAD·겹치는 chunk·확정 정책을 추가. Mac은 whisper.cpp Metal/CoreML 또는 Apple MLX 예제 | 원본 코드·가중치 MIT; whisper.cpp MIT | 검증 기준선. turbo는 더 빠른 전사 후보지만 자체 번역용 모델이 아니다. [OpenAI](https://github.com/openai/whisper), [whisper.cpp](https://github.com/ggml-org/whisper.cpp), [MLX](https://github.com/ml-explore/mlx-examples/tree/main/whisper) |
| **Cohere Transcribe 03-2026, 2B** | 모두 지원 | offline 전사 + 긴 파일 chunking. MLX-Audio에 직접 구현됨 | 모델 Apache-2.0 / MLX-Audio 구현 별도 라이선스 | 문장 확정·기록 정확도 비교 후보. [모델](https://huggingface.co/CohereLabs/cohere-transcribe-03-2026), [MLX 문서](https://github.com/Blaizzy/mlx-audio/blob/main/mlx_audio/stt/models/cohere_asr/README.md) |
| **Voxtral Mini 4B Realtime 2602** | 모두 평가표 포함 | 원래부터 streaming용 causal encoder. 공식 vLLM·Transformers 경로, 커뮤니티 `voxtral.c` Metal/입력 스트리밍 경로 | 모델 Apache-2.0 / voxtral.c MIT | Nemotron의 실시간 경쟁 후보. 포트 저자도 추가 검증 필요 명시. [모델](https://huggingface.co/mistralai/Voxtral-Mini-4B-Realtime-2602), [C 구현](https://github.com/antirez/voxtral.c) |
| **SenseVoiceSmall** | 모두 지원 | non-autoregressive offline 모델. VAD 구간별 빠른 전사와 native input-streaming은 다르다. FunASR·sherpa-onnx·MLX 포트 경로 | 코드와 가중치 별도. 공식 모델 카드: FunASR Model License | 작은 모델 비교군. MIT 가중치라고 잘못 분류하지 않는다. [모델](https://huggingface.co/FunAudioLLM/SenseVoiceSmall), [원 저자](https://github.com/QwenAudio/SenseVoice), [모델 라이선스](https://github.com/modelscope/FunASR/blob/main/MODEL_LICENSE) |
| **Fun-ASR-MLT-Nano-2512, 800M** | 모두 지원 | multilingual 별도 checkpoint. 기본 Fun-ASR-Nano와 언어 범위 다름. 공식 예시는 CUDA, Mac 경로 검증 필요 | MLT 모델 카드 Apache-2.0; toolkit MIT | 예비 비교군. 일반 Nano는 한국어 후보로 대체하면 안 됨. [모델](https://huggingface.co/FunAudioLLM/Fun-ASR-MLT-Nano-2512), [toolkit](https://github.com/modelscope/FunASR) |
| **VibeVoice-ASR / ASR-Streaming-7B** | 모두 지원 | 일반 ASR는 긴 녹음+화자/시점, 새 Streaming은 실제 입력과 화자 귀속 전사. streaming 카드 총 파라미터 표시는 9B | MIT 표시 | 회의 기록 고급 비교군. streaming 모델의 Mac 실행 속도·공유 메모리 경쟁 미검증. [일반 ASR](https://github.com/microsoft/VibeVoice/blob/main/docs/vibevoice-asr.md), [Streaming](https://huggingface.co/microsoft/VibeVoice-ASR-Streaming-7B) |
| **VibeVoice-ASR-BitNet** | 한국어·영어 명시, 일본어 보장 확인 부족 | ARM/CPU용 압축 GGUF. 공개 속도는 AMD EPYC, Mac 실측 아님 | MIT 표시 | 전력/메모리 대안. 세 언어 주력 선정 전 일본어 검증 필요. [공식 모델](https://huggingface.co/microsoft/VibeVoice-ASR-BitNet) |
| **Meta Omnilingual ASR v2 300M–7B** | 광범위 다국어; 정확한 locale 목록 확인 필요 | CTC/LLM/Unlimited 계열. 공식 interface는 현재 real-time streaming 미지원이라고 설명 | 코드·모델 Apache-2.0 | 언어 확장·배치 비교군. [공식](https://github.com/facebookresearch/omnilingual-asr), [아키텍처와 streaming 한계](https://github.com/facebookresearch/omnilingual-asr/blob/main/src/omnilingual_asr/models/README.md) |
| **sherpa-onnx / Zipformer / Vosk** | 세 언어 모델 경로 존재 | toolkit 자체는 모델이 아님. sherpa는 영어·한국어 streaming, 일본어 offline 등 모델별 다름. Vosk는 경량 streaming 후보 | toolkit과 모델별 라이선스 분리. Vosk 해당 ko/ja 모델 Apache-2.0 | CPU 중심 기준선. 최신 대형 모델보다 좋다는 근거 없음. [sherpa 모델 목록](https://github.com/k2-fsa/sherpa-onnx), [Vosk 모델별 성능·라이선스](https://alphacephei.com/vosk/models) |

### 실행기와 모델을 구분

- **faster-whisper**는 Whisper를 CTranslate2로 실행한다. 공식 GPU 경로는 NVIDIA CUDA이며 Intel CPU/RTX 벤치마크를 Mac Metal 속도로 해석하면 안 된다. Mac의 우선 실행기는 whisper.cpp 또는 MLX로 둔다. [공식 문서](https://github.com/SYSTRAN/faster-whisper)
- **WhisperStreaming / WhisperLiveKit** 같은 도구는 chunk 재인식·안정된 prefix 확정·VAD를 조합하는 통합 계층이다. 네트워크로 텍스트가 조금씩 나온다는 사실만으로 모델이 native input-streaming이라는 뜻은 아니다. [WhisperStreaming 저자 구현](https://github.com/ufal/whisper_streaming)
- **WhisperKit**는 Apple Silicon/CoreML용 별도 구현 후보다. 서버에 Swift 프로세스를 둘 수도 있지만 기존 Node 서버와 연결 계층이 필요하다. [원 프로젝트](https://github.com/argmaxinc/argmax-oss-swift)
- **FluidAudio**의 Nemotron multilingual 문서에 열거된 언어는 en/es/fr/it/pt/de/zh/ja이며 한국어가 보이지 않는다. 원 NVIDIA 모델의 한국어 지원을 모든 CoreML 변환본의 검증 상태로 확대하지 않는다. [구현 프로젝트 모델 문서](https://github.com/FluidInference/FluidAudio/blob/main/Documentation/Models.md)
- **parakeet-rs**는 Nemotron3.5 ONNX streaming을 제공하는 추가 CPU/Rust 경로다. 원 저자 런타임과 구분해 비교한다. [구현 프로젝트](https://github.com/altunenes/parakeet-rs)

## 우선 후보 설치·연결 가능성

Nemotron3.5의 공식 Hugging Face 파일 메타데이터를 직접 조회했다. 가중치 자체는 다운로드하지 않았다.

| artifact | 확인한 크기 | 용도 |
|---|---:|---|
| `nemotron-3.5-asr-streaming-0.6b.q8_0.gguf` | 742,090,464 bytes, 약 742 MB | NeMo-Speech.cpp 바로 사용; 별도 변환 불필요 |
| `model.safetensors` | 2,552,062,944 bytes | Transformers 원본 |
| `nemotron-3.5-asr-streaming-0.6b.nemo` | 2,368,284,501 bytes | NeMo 원본 |

근거: [공식 파일 목록](https://huggingface.co/nvidia/nemotron-3.5-asr-streaming-0.6b/tree/main), [조회 API](https://huggingface.co/api/models/nvidia/nemotron-3.5-asr-streaming-0.6b/tree/main). 파일 크기는 실행 시 메모리 사용량이 아니다.

공식 설치 문서는 Apple Silicon에서 `macos-aarch64-metal` prebuilt archive를 선택한다. 소스 빌드라면 Xcode Command Line Tools, CMake≥3.26, Ninja, SentencePiece, Abseil과 하위 모듈이 필요하다. 런타임/가중치가 없다면 **다운로드 없는 실제 추론 검증은 불가능**하다. `--version`/`doctor`는 설치 확인이며 인식 검증이 아니다. [설치 문서](https://github.com/NVIDIA/NeMo-Speech.cpp/blob/main/docs/install.md), [빌드 문서](https://github.com/NVIDIA/NeMo-Speech.cpp/blob/main/docs/build.md)

`/v1/audio/transcriptions/realtime`는 전용 WebSocket 프로토콜이다. PCM16 입력, `session.update` 언어 설정, 중간 `delta`/최종 `completed`, commit/clear/cancel이 명시돼 있다. OpenAI 유료 서비스가 아닌 로컬 서버이며, OpenAI Realtime과 완전히 동일한 프로토콜도 아니다. 모바일은 음성 취득·전송·표시만 맡고 Mac이 추론한다. [공식 HTTP/WS 계약](https://github.com/NVIDIA/NeMo-Speech.cpp/blob/main/docs/api.md)

## 정확도와 속도를 어디까지 말할 수 있는가

아래는 **제공자 자체 보고**다. 같은 실험으로 평가한 순위가 아니다.

| 보고 출처 | 조건 | 확인된 수치 | 해석 |
|---|---|---|---|
| Nemotron3.5 모델 카드 | FLEURS, 언어 지정, 320ms chunk | 한국어 CER 7.27%, 일본어 CER 12.22%, 영어 WER 8.27% | chunk는 end-to-end latency가 아님 |
| 같은 카드 | 560ms chunk | 한국어 7.18%, 일본어 11.91%, 영어 7.99% | 설정에 따라 지연·정확도 교환 |
| Qwen 기술 보고서 | 12개 언어 FLEURS 평균 | Qwen1.7B 4.90, Whisper large-v3 5.27, Qwen0.6B 7.57 | 한국어/일본어 개별 우승을 뜻하지 않음 |
| Voxtral 모델 카드 | FLEURS, 480ms 설정 | 영어 4.90%, 일본어 9.59%, 한국어 15.74% | NVIDIA와 정규화·metric 조건 정렬 전 직접 순위 불가 |

출처: [Nemotron 평가](https://huggingface.co/nvidia/nemotron-3.5-asr-streaming-0.6b#performance), [Qwen 보고서](https://arxiv.org/html/2601.21337v1), [Voxtral 평가](https://huggingface.co/mistralai/Voxtral-Mini-4B-Realtime-2602#benchmark-results).

NeMo-Speech.cpp 공개 속도 벤치마크는 영어 Nemotron 모델을 RTX4090·DGX Spark·Intel i7에서 측정했다. 이 Mac의 다국어 결과가 아니다. Qwen의 매우 큰 처리량도 vLLM·동시 요청 조건이다. 정확히 같은 Mac·마이크·언어·전처리·동시 번역 부하의 비교자료는 확인하지 못했다. [NVIDIA 런타임 벤치마크](https://github.com/NVIDIA/NeMo-Speech.cpp/blob/main/BENCHMARK.md)

Cohere의 영어 leaderboard 성능과 다국어 성능은 구분해야 한다. MLX 포트는 M1 Max에서 자체 측정한 사례를 제공하지만 음성/언어/설정이 다르므로 M3 Max의 앱 지연 보장으로 사용하지 않는다. VAD도 항상 정확도를 높이지는 않으며 해당 문서의 깨끗한 낭독 실험에서는 악화됐다. [Cohere 모델 평가](https://huggingface.co/CohereLabs/cohere-transcribe-03-2026), [MLX 포트 평가](https://github.com/Blaizzy/mlx-audio/blob/main/mlx_audio/stt/models/cohere_asr/README.md)

## 무료 클라우드 및 OS 경로

| 경로 | 무료 조건/성격 | 활용 판단 |
|---|---|---|
| **Gemini 3.5 Transcribe Live / Transcribe** | 현재 공식 요금표에 무료 tier 입출력 무료. 세 언어 포함. Live는 WebSocket, 세션당 최대10분, live diarization/word timestamps 미지원 | Mac 부하를 줄이는 유력 무료 비교 후보. 무료 account quota·데이터 이용 정책 확인 필요. [요금](https://ai.google.dev/gemini-api/docs/pricing), [언어·기능](https://ai.google.dev/gemini-api/docs/models/gemini-3.5-transcribe), [Live 계약](https://ai.google.dev/gemini-api/docs/live-api/live-transcribe) |
| **Azure Speech F0** | 실시간 STT 월5시간, Standard/Custom 공유. Batch는 F0 미지원. Speech Translation 월5시간 별도 표기 | 제한된 수업 시연 후보. 기존 유료 S0와 엄격히 구분. [요금](https://azure.microsoft.com/en-us/pricing/details/speech/), [쿼터](https://learn.microsoft.com/en-us/azure/ai-services/speech-service/speech-services-quotas-and-limits) |
| **Groq Whisper large-v3 / turbo** | 무료 plan, 파일 전사 API, 요청/음성시간 제한. 계정 Limits가 최종값 | 녹음 chunk 전사나 기준선. 파일 업로드 API를 native input streaming으로 부르지 않는다. [STT](https://console.groq.com/docs/speech-to-text), [무료 제한 표](https://console.groq.com/docs/rate-limits) |
| **Cohere Transcribe API** | 공식 문서는 무료·rate-limited experimentation 명시. production은 별도 Model Vault | 무료 외부 정확도 비교. [문서](https://docs.cohere.com/docs/transcribe), [API 제한](https://docs.cohere.com/docs/rate-limits) |
| **Deepgram / AssemblyAI** | 신규 무료 크레딧. Deepgram $200, AssemblyAI $50 확인. 상시 재충전 무료량과 다름 | 무료 크레딧만으로 시연 가능성은 있으나 소진 후 지속 무료 기본 구조로 삼지 않음. [Deepgram](https://deepgram.com/pricing), [AssemblyAI 무료 정책](https://support.assemblyai.com/articles/5370767329-can-i-sign-up-for-free) |
| **Mac SpeechAnalyzer / SpeechTranscriber** | Apple OS 내장 on-device 엔진, live/long-form. 지원 locale과 설치 여부 runtime 조회 필요 | 휴대폰 대신 Mac 프로세스에서 사용할 예비 경로. 세 언어 가용성·서버 형태 통합 별도 확인. [Apple 발표](https://developer.apple.com/videos/play/wwdc2025/277/), [지원 조회 API](https://developer.apple.com/documentation/speech/speechtranscriber) |
| **Android SpeechRecognizer / iOS 기본 STT** | OS 제공 기능이나 환경·엔진·언어에 따라 다름. Android 문서는 연속 인식용이 아니며 원격 인식일 수 있다고 설명 | 짧은 입력 보조 후보. 사용자 단말 부담을 줄인 서버 중심 공통 경로와는 별도 옵션. [Android 공식](https://developer.android.com/reference/android/speech/SpeechRecognizer) |

무료 웹 UI(브라우저 음성 입력, 무료 전사 사이트, 제품 데모)는 앱에서 호출 가능한 무료 API 계약이 아니다. 숨겨진 API 역공학이나 계정 순환으로 무료 quota를 우회하는 구조는 후보로 삼지 않았다. 무료 credits·Spaces·Colab에서 잠깐 실행하는 실험은 지속 서버 운영과 구분한다.

## 주력으로 고르지 않은 범주도 기록

- Parakeet TDT v3의 다국어는 25개 유럽언어이며 한국어·일본어 공통 모델 대체재가 아니다. 일본어 별도 Parakeet checkpoint와 Nemotron3.5를 혼동하지 않는다. [NVIDIA 모델 카드](https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3)
- Granite Speech 5.0 TurboCTC는 작고 Mac 경로가 있지만 공식 지원은 영어다. 영어 전용 경로 실험으로 남긴다. [IBM 모델 카드](https://huggingface.co/ibm-granite/granite-speech-5.0-470m-turboctc)
- Kyutai STT 2.6B는 영어 전용 checkpoint이므로 세 언어 주력에는 맞지 않는다. [원 저자 구현](https://github.com/kyutai-labs/delayed-streams-modeling)
- Qwen-Audio-3.0-ASR 최신 보고서는 확인했으나 보고서 존재만으로 다운로드 가능한 무료 가중치·Mac 실행 경로가 있다는 결론을 내리지 않는다. [논문](https://arxiv.org/abs/2609.07549)

## 최종 선정 실험 제안

모델을 학습하기 전에 같은 ko/en/ja 평가셋으로 사전학습 모델을 비교한다. 한 언어가 약하면 해당 언어만 다른 모델로 라우팅할 수 있다. 개인 음성 제공 없이도 라이선스가 허용하는 공개 음성과 정답 전사로 초기 검증을 준비할 수 있으나, 실제 대화 검증을 대신하지 않는다.

1. Nemotron3.5 Metal, Voxtral Metal, Qwen1.7B MLX, Cohere MLX, Whisper turbo/large-v3를 동일 조건으로 비교한다.
2. 한국어·일본어 CER, 영어 WER, 숫자·이름·날짜·부정문 정확도, 무음 오검출, 문장 누락/반복을 따로 측정한다.
3. 최초 부분문자 지연, 발화 종료 후 확정 지연, 수정 횟수, p50/p95, 20분 이상 세션의 메모리와 번역/요약 동시 실행 영향을 측정한다. 모델 chunk delay와 앱 지연을 따로 기록한다.
4. 실시간 모델이 충분하면 단일 경로. 필요할 때만 발화가 끝난 후 더 정확한 후보로 재검토하는 이중 경로를 실험한다. 사용자가 수정한 기록을 자동으로 덮어쓰지 않는다. 비저장 모드 오디오 버퍼도 영구 저장하지 않는다.
5. API 무료 비교는 결제 미연결·무료 tier를 확인한 뒤 진행하고 429/무료량 종료 시 유료 fallback 없이 자체 Mac 또는 재시도로 처리한다.

확인되지 않은 것: 이 Mac 실제 p95, 세 언어의 실제 대면 소음/겹말 인식률, 여러 모델 동시 상주 메모리, 무료 계정의 실제 quota, 학습 데이터 확보 없이 fine-tuning으로 좋아지는 정도. 따라서 **현 시점 선정은 근거가 가장 잘 맞는 출발점이며 측정된 최종 우승자는 없다.**
