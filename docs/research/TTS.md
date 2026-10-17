# 무료 TTS 조사 — 한국어·영어·일본어

조사일: 2026-10-17. 대상: 개발자 Mac M3 Max / 통합 메모리 36 GB를 서버로 사용하고 Android·iOS 앱은 오디오를 재생하는 구성. 이 문서는 문헌 조사이며 모델 설치·다운로드·추론·청취 평가·유료 호출을 수행하지 않았다. 무료는 기존 장비에서의 API 사용료·모델 이용료가 없는 경로를 의미하며 전력·네트워크·운영 비용까지 0이라는 뜻은 아니다.

## 결론과 검증 순서

**서버 TTS는 Qwen3-TTS CustomVoice의 MLX 실행과 Supertonic 3의 CPU 실행을 우선 비교한다.** 전자는 실제 오디오 조각 스트리밍 경로와 Apple Silicon 실행 생태계, 후자는 작은 모델로 STT·번역과 연산을 나누기 쉽다는 점 때문에 선정한 **설계 판단**이다. 아직 어느 쪽이 이 Mac에서 더 빠르고 정확한지는 확인되지 않았다. 품질 비교에는 VoxCPM2·Chatterbox Multilingual V3를 포함한다. 서버에서 모델을 실행하는 것은 사용자 휴대폰에 LLM을 설치하는 것과 다르다.

1. Qwen3-TTS 0.6B CustomVoice MLX를 시작 후보로, 1.7B CustomVoice를 품질 비교 후보로 둔다. 양자화 전후 한국어·일본어 숫자/고유명사 읽기를 함께 검사한다.
2. Supertonic 3은 CPU 저부하 비교 기준으로 둔다. 다만 **공식 저장소가 2026-09-09에 보관 처리되었고 개발·지원 종료를 선언**했으므로 유지보수 계획 없이 장기 기본값으로 확정하지 않는다.
3. 기준을 만족하는 엔진 중 지연·발음 정확도·전체 파이프라인 메모리 사용량으로 선택한다. 모델 크기·홍보 MOS·타사 GPU 벤치마크만으로 최상을 선언하지 않는다.

출처: [Qwen 공식 모델](https://github.com/QwenLM/Qwen3-TTS), [MLX 스트리밍 구현 문서](https://github.com/Blaizzy/mlx-audio/blob/main/mlx_audio/tts/models/qwen3_tts/README.md), [Supertonic 보관 공지](https://github.com/supertone-oss-archive/supertonic).

## 자체 호스팅 비교

| 후보 | ko/en/ja | 코드·가중치 조건 | Mac·스트리밍 근거 | 판단 / 확인할 부분 |
|---|---|---|---|---|
| **Qwen3-TTS 0.6B / 1.7B** | 세 언어 모두 공식 지원 | 공식 코드 Apache-2.0; [0.6B CustomVoice 가중치](https://huggingface.co/Qwen/Qwen3-TTS-12Hz-0.6B-CustomVoice)도 Apache-2.0 | [MLX-Audio](https://github.com/Blaizzy/mlx-audio)는 Apple Silicon용 별도 오픈소스 런타임. `stream=True`로 오디오 chunk 제공 | 우선 후보. **MLX 포트는 Qwen 공식 런타임과 구분**. 고정 화자 CustomVoice면 사용자 목소리 복제 필요 없음. 공식 97ms 주장은 이 Mac의 측정값이 아님 |
| **Supertonic 3, 99M** | 세 언어 포함 31개 | 코드 MIT; [모델 Open RAIL-M](https://huggingface.co/supertone-oss-archive/supertonic-3/blob/main/LICENSE), 이용·배포 조건 있음 | ONNX CPU, Python·Node·Swift 예제. [Python SDK](https://github.com/supertone-oss-archive/supertonic-py) HTTP 서버 제공. 확인한 문서는 WAV 합성 중심이며 입력·출력 동시 스트리밍 증거 부족 | 작은 모델 후보. 문장 분할 전달을 모델 자체 스트리밍으로 부르지 않는다. v2는 일본어 미지원, **v3로 비교**. 지원 종료·archive revision 고정 필요 |
| **MeloTTS** | 세 언어 공식 지원 | [코드 MIT](https://github.com/myshell-ai/MeloTTS), [한국어](https://huggingface.co/myshell-ai/MeloTTS-Korean)·[일본어](https://huggingface.co/myshell-ai/MeloTTS-Japanese) 모델 카드 MIT | [macOS 설치·MPS/CPU 옵션](https://github.com/myshell-ai/MeloTTS/blob/main/docs/install.md). CPU 실시간 가능은 제작자 주장. 기본 API는 `tts_to_file` | 안정적 비교 기준 후보. 오래된 Python/패키지 조합의 최신 macOS 설치성 및 언어별 모델 부하를 확인해야 함 |
| **OpenVoice V2** | 세 언어 공식 지원 | [V1·V2 MIT 명시](https://github.com/myshell-ai/OpenVoice) | 음색 변환·음성 복제가 중심. 기본 TTS와 별도 단계 | 이 앱은 목소리 복제가 요구되지 않으므로 추가 합성 경로의 이득을 입증할 때 채택 |
| **Kokoro-82M** | 영어·일본어 지원, 공식 목록에서 한국어 미확인 | [코드](https://github.com/hexgrad/kokoro)·[가중치](https://huggingface.co/hexgrad/Kokoro-82M) Apache-2.0 | MLX-Audio 지원; 문장/구간 generator 제공 | 단일 3언어 엔진으로는 부적합. 영어·일본어 전용 엔진 + 한국어 별도 엔진 구성은 가능하나 운영 복잡도 증가 |
| **Piper** | 현재 voice catalog에 세 언어 존재 | [현재 엔진 GPL-3.0](https://github.com/OHF-Voice/piper1-gpl). 모델별 카드 별도. 한국어 KSS 카드의 데이터 조건은 CC BY-NC-SA 4.0 | ONNX CPU 중심. 경량 기준 후보 | 과거 문서만 보고 한국어·일본어 미지원이라고 하면 오류. 아래 voice별 라이선스 확인 필요 |
| **Chatterbox Multilingual V3, 500M** | 세 언어 공식 지원 | [코드](https://github.com/resemble-ai/chatterbox)·[모델 저장소](https://huggingface.co/ResembleAI/chatterbox) MIT | 공식 예제 `cpu` / `mps` 제공. 공식 검증 환경은 Debian/Python 3.11. V3 `t3_model="v3"` | 품질 후보. 실제 Mac 지연 미측정. **Turbo 350M·Nano110M는 영어용**이므로 그 속도를 3언어판에 적용하면 안 됨. 기본 API는 완성 파형 반환 |
| **VoxCPM2, 2B** | 세 언어 포함 30개 | [코드·가중치 Apache-2.0](https://github.com/OpenBMB/VoxCPM) | 공식 MPS 실행 및 스트리밍 문서. RTX4090 RTF 수치는 Mac 수치가 아님 | 품질 비교 후보. 48kHz 출력·목소리 제어가 목적과 맞을 때 검토. 2B의 STT/LLM 동시 자원 비용 실측 필요 |
| **OmniVoice** | 600+ 언어, 공식 언어 목록 확인 경로 제공 | [코드](https://github.com/k2-fsa/OmniVoice)·[가중치](https://huggingface.co/k2-fsa/OmniVoice) Apache-2.0 | 공식 MPS와 MLX-Audio 구현 있음. 확인한 공식 예제는 완성 음성 반환 | 광범위 언어·복제 품질 후보지만 본 앱은 3언어만 필요. 40배 실시간 같은 제작자 수치를 Mac 보장으로 쓰지 않음 |
| **Fish Audio S2-Pro** | 영어·일본어 Tier1, 한국어 Tier2 | [코드·가중치 Research License](https://github.com/fishaudio/fish-speech/blob/main/LICENSE). 연구·비상업 무료, 상업 이용 별도 계약 | [모델 카드](https://huggingface.co/fishaudio/s2-pro) 스트리밍 100ms는 NVIDIA H200 측정 | 과제 비상업 범위의 품질 비교 가능. Mac 실행 성능은 미확인. 호스팅 유료 API는 사용하지 않음 |

MLX-Audio의 Qwen 문서에 있는 6bit 짧은 문장 TTFB 표는 해당 페이지에 하드웨어 조건이 충분히 명시되지 않아 **M3 Max 수치로 채택하지 않았다**. `streaming_interval=0.32`는 생성 오디오 chunk 간격 설정 예시이며 네트워크 왕복 포함 320ms 응답 보장이 아니다. [MLX 문서](https://github.com/Blaizzy/mlx-audio/blob/main/mlx_audio/tts/models/qwen3_tts/README.md)

Piper의 정확한 후보는 `ko_KR-kss-medium`과 `ja_JP-hi_fi_captain-medium`이다. [공식 voices.json](https://huggingface.co/rhasspy/piper-voices/raw/main/voices.json)에 둘 다 있으며, [KSS 카드](https://huggingface.co/rhasspy/piper-voices/blob/main/ko/ko_KR/kss/medium/MODEL_CARD)는 CC BY-NC-SA 4.0 데이터 라이선스를 명시한다. 일본어 MODEL_CARD는 이 조사 도구에서 본문을 가져오지 못했으므로 해당 음성의 이용 조건은 미확정이다. 모델 저장소 전체의 MIT 배지만으로 모든 voice 조건을 대체하면 안 된다. [엔진의 voice별 확인 안내](https://github.com/OHF-Voice/piper1-gpl/blob/main/docs/VOICES.md)

## 무료 외부 API와 OS 기본 음성

| 경로 | 확인된 무료 범위 | 앱에서의 판단 |
|---|---|---|
| **Gemini Developer API TTS** | 현재 공식 가격표에서 `gemini-3.8-flash-tts`, `gemini-3.8-flash-lite-tts` 무료 티어 입력·출력 무료. 무료 데이터는 제품 개선 사용 표시 | 무료 계정으로만 비교 가능한 외부 후보. [TTS 문서](https://ai.google.dev/gemini-api/docs/speech-generation)에 ko/en/ja 및 스트리밍 PCM 제공. [가격표](https://ai.google.dev/gemini-api/docs/pricing)·[계정별 quota](https://ai.google.dev/gemini-api/docs/rate-limits). 무료 호출 가능 여부·현재 할당량은 사용자 계정에서 별도 확인. 유료 자동 전환을 구현하지 않음 |
| **Azure Speech F0** | [공식 가격표](https://azure.microsoft.com/en-us/pricing/details/speech/) Neural TTS 월 50만 문자 무료 | F0 전용 리소스일 때 무료 후보. S0/PAYG를 같은 것으로 취급하지 않음. [F0 quota](https://learn.microsoft.com/en-us/azure/ai-services/speech-service/speech-services-quotas-and-limits) 때문에 지속 서비스의 무제한 무료 대안은 아님 |
| **ElevenLabs Free** | [현재 API 가격표](https://elevenlabs.io/pricing/api)의 Free/PAYG 열에 모델별 포함 문자 표시(예: v4 10,000) | 계정·무료 플랜 조건 확인 후 제한된 비교용. Free와 PAYG가 함께 표시되어 무과금 계정 설정 및 초과 동작 확인 없이 기본 경로로 쓰지 않음. 유료 상품의 최저 지연을 무료 계정 보장으로 사용하지 않음 |
| **Google Cloud TTS** | [가격 문서](https://cloud.google.com/text-to-speech/pricing)에 모델별 무료 문자량 있음 | 결제 활성화 필요하며 초과 자동 청구를 명시. 사용자의 유료 금지 조건에서 기본 선택 제외. Gemini Developer API 무료 티어와 다른 상품 |
| **공개 Hugging Face 데모** | 공개 Space를 무료로 체험하는 경로 있음 | [ZeroGPU quota·queue](https://huggingface.co/docs/hub/en/spaces-zerogpu)와 운영자 종료 가능성이 있어 실시간 앱의 상시 서버로 가정하지 않음. 무료 demo 페이지를 무제한 API로 우회하지 않음 |
| **iOS AVSpeechSynthesizer / Android TextToSpeech** | 플랫폼 API로 음성을 재생할 수 있음 | [Apple](https://developer.apple.com/documentation/avfaudio/avspeechsynthesizer), [Android](https://developer.android.com/reference/android/speech/tts/TextToSpeech). 서버 모델 다운로드가 필요 없는 가벼운 대안. 음성 목록·설치 상태·한국어/영어/일본어 실제 음질은 기기별 검사. Android는 [voice 네트워크 요구 여부](https://developer.android.com/reference/android/speech/tts/Voice#isNetworkConnectionRequired())도 검사. 사용자 서버 추론 선호에 따라 선택적 fallback만 고려 |

OS 음성 사용은 LLM 학습·추론을 휴대폰에 설치하는 구성과 다르지만 단말 연산을 완전히 없애는 것은 아니다. **서버 추론 선호를 유지하려면 서버 TTS 오디오 재생을 기본 경로로 하고 OS 음성은 사용자가 선택하는 보조 경로로 둔다**는 설계 제안이다. `edge-tts`·`gTTS` 등 비공식 consumer endpoint wrapper는 라이브러리가 무료라는 이유만으로 정식 무제한 무료 API로 간주하지 않으며 이 비교의 기본 경로로 선택하지 않는다.

## 공정한 실측 계획 — 아직 수행하지 않음

이 프로젝트의 목적에 맞춘 제안이며 외부 제품 성능 사실이 아니다.

- 언어마다 같은 텍스트 묶음으로 비교: 여행 질문, 업무 지시, 부정/조건, 시간·날짜·금액, 사람/회사명, 한국어의 수사·단위, 일본어 한자 읽기, 영어 약어, 짧은 응답 및 긴 문장.
- 첫 PCM 도착까지의 시간(TTFA), 끝까지 합성 시간/오디오 길이(RTF), p50/p95, cold/warm 시작, peak memory, 발음 오류·반복·누락을 기록한다. 모델 단독과 STT·번역·기록 추출 동시 실행을 별도로 측정한다.
- 원문과 음성을 사람이 대조하고 언어별 숫자·부정·고유명사 오류를 먼저 평가한다. ASR 재전사만으로 음질·발음 정확도를 확정하지 않는다.
- 서버 합성→전송→모바일 재생 구간을 분리한다. 긴 문장을 자른 완성 WAV 전달, 출력 PCM 스트리밍, 입력 텍스트 동시 스트리밍은 서로 다른 기능으로 표시한다.
- TTS는 **확정된 번역**만 읽는다. 수정될 수 있는 번역 초안을 먼저 읽으면 되돌릴 수 없다. 새 발화/수정/취소 시 해당 generation의 오디오 재생을 중단하고 오래된 chunk를 폐기한다.
- 먼저 무료 사전학습 모델을 비교한다. 학습은 잘못 읽는 패턴과 학습용 데이터가 확인된 뒤에 검토한다. 학습을 한다고 언어 품질이나 지연이 자동으로 좋아진다고 가정하지 않는다.

## 남은 불확실성

M3 Max36GB에서의 3언어 지연·청취 품질·장시간 안정성, 다른 모델과 동시 상주할 때의 메모리/발열, Piper 일본어 voice 이용 조건, 외부 무료 API의 계정별 quota는 미검증이다. 따라서 현재 추천은 **실측할 후보 순서**이지 최종 품질 순위가 아니다.

조사 방식: ECC deep-research에 따라 모델 제작자 저장소·모델 카드·SDK 문서·공급자 가격표를 교차 확인했다. Exa/Firecrawl 대신 사용 가능한 web 검색·본문 열람 도구를 사용했다. 검색에서 발견한 포크·개인 블로그·Reddit 주장은 선정 근거로 사용하지 않았다. 저장소 및 모델 정책은 변경될 수 있으므로 채택 시 revision과 라이선스 원문을 함께 고정한다.
