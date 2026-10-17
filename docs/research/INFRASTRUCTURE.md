# 무료 실행 환경·호스팅·평가 조사

2026-10-17. 사용자는 현재 Mac만 사용할 수 있다고 답했다. 로컬 `sysctl` 조회 결과 Apple M3 Max, 물리 메모리 38,654,705,664 bytes(36 GiB)다. Ollama 실행 파일은 있으나 조사 시 서버는 실행 중이 아니었다. 모델 설치·다운로드·추론 측정·클라우드 가입·공개 배포는 하지 않았다.

## 권고: 현재 Mac을 추론 서버로 사용

폰은 마이크 취득·오디오 전송/재생·UI·기록 저장을 맡고 Mac은 STT·번역·추출·TTS를 맡는다. 기존 장비이므로 API 호출료·서버 임대료 없는 경로를 구성할 수 있다. 전기·인터넷·Mac 가동은 필요하다. Mac이 잠들거나 연결이 끊어지면 서버도 사용할 수 없으며, 무제한 동시 사용자나 상시 운영 보장은 없다.

| 실행기 | 공식 근거 | 적용 판단 |
|---|---|---|
| Ollama native macOS | [FAQ](https://docs.ollama.com/faq): 로컬 실행, `OLLAMA_NO_CLOUD=1`, 모델 상주·동시성 설정. Docker Desktop의 macOS GPU passthrough 미지원 설명 | 번역의 첫 연결 경로. 클라우드 모델 비활성화, localhost 유지. Mac GPU 사용을 위해 네이티브 실행 우선 |
| llama.cpp Metal | [원 저장소](https://github.com/ggml-org/llama.cpp), [서버](https://github.com/ggml-org/llama.cpp/blob/master/tools/server/README.md): Metal·양자화·JSON 제약 | 공식 GGUF/QAT를 직접 비교할 경로. 제약 출력은 사실 정확도 보증이 아님 |
| MLX / MLX-LM | [Apple MLX](https://github.com/ml-explore/mlx), [MLX-LM](https://github.com/ml-explore/mlx-lm): Apple Silicon 추론·양자화·미세조정 | 지원되는 모델의 Mac 비교 경로. 모든 최신 모델이 모든 런타임 버전에서 되는 것은 아니므로 모델별 지원 확인 |
| NeMo-Speech.cpp Metal | [NVIDIA 공식](https://github.com/NVIDIA/NeMo-Speech.cpp): Mac 실행·음성 WebSocket | 스트리밍 STT 우선 경로. 모델과 런타임 revision을 함께 고정 |
| MLX-Audio | [구현자 저장소](https://github.com/Blaizzy/mlx-audio) | STT/TTS의 Apple Silicon 포트. 원 모델 제작자의 공식 구현과 구분 |

36GB는 GPU 전용 메모리가 아니라 macOS·IDE·시뮬레이터·모델이 공유한다. 가중치 파일 크기에 KV cache·중간 tensor·오디오 buffer·런타임이 추가된다. 따라서 큰 번역기와 추출기를 동시에 올리기보다 STT/TTS와 활성 번역기를 우선하고, 기록 정리는 낮은 우선순위/종료 후 처리부터 비교한다. 이 스케줄링은 설계 제안이며 실측 결과가 아니다.

## 무료 외부 연산·호스팅 범주

| 방안 | 현재 공식 조건 | 프로젝트 판단 |
|---|---|---|
| Google Colab Free | [FAQ](https://research.google.com/colaboratory/faq.html): GPU와 한도 가변, 자원 보장 없음. 무료 managed runtime에서 원격 제어·노트북 UI를 우회하는 사용 제한 | 모델 실험 후보. 상시 모바일 API 서버로 가정하지 않음 |
| Kaggle Notebooks | [문서](https://www.kaggle.com/docs/notebooks), [GPU 사용](https://www.kaggle.com/docs/efficient-gpu-usage): 계정별 GPU quota와 notebook 실행 | 비교 실험 후보. 현재 계정 quota 미확인; 고정 시간이나 상시 제공을 보장하지 않음 |
| Hugging Face Spaces CPU | [Spaces](https://huggingface.co/docs/hub/spaces-overview), [하드웨어](https://huggingface.co/docs/hub/spaces-gpus): CPU Basic은 2vCPU·16GB·50GB이며 시간당 비용 없음. 다만 신규 compute Space 생성은 paid plan 필요; 자격 있는 ZeroGPU는 예외 | 과거의 “누구나 새 CPU Space를 무료 생성” 안내를 현재 사실로 쓰지 않음 |
| HF ZeroGPU | [공식 조건](https://huggingface.co/docs/hub/spaces-zerogpu): 이메일 확인·30일 초과·정상 개인 계정에 최대2개 호스팅, free 사용자 GPU5분/일, 동적 할당·대기열, Gradio | 짧은 비교/demo 후보. 실시간 장시간 회의의 주 서버로 선정하지 않음 |
| Oracle Always Free | [공식 리소스](https://docs.oracle.com/en-us/iaas/Content/FreeTier/freetier_topic-Always_Free_Resources.htm): A1 합계2OCPU·12GB, CPU 서버. 용량 부족·유휴 회수 가능 | 경량 CPU/API 중계 후보. GPU 모델 서버 대체로 선정할 실측 근거 없음. 현재 Mac부터 검증 |
| Cloudflare Workers AI Free | [가격](https://developers.cloudflare.com/workers-ai/platform/pricing/): 하루10,000 neurons, 초과 실패, 일부 모델 paid 전용 | 지원 무료 모델의 비교 후보. 임의 가중치를 올리는 무료 GPU VM과 다름 |
| Render Free | [무료 조건](https://render.com/docs/free), [사양](https://render.com/docs/compute-plans): 512MB·0.1CPU, 비활성15분 후 sleep | 작은 중계 서버 후보. 이번 다중 AI 모델 추론 서버로 부적합하다는 설계 판단 |
| Koyeb Free | [인스턴스](https://www.koyeb.com/docs/reference/instances), [가입·가격 FAQ](https://www.koyeb.com/docs/faqs/pricing): 무료 인스턴스와 별도의 유료 GPU, 가입 카드 조건 | 무료 중계 검토군. GPU 무상 상시 서버로 분류하지 않음 |
| Railway Free | [공식](https://docs.railway.com/pricing/free-trial): 초기$5/30일 trial 이후 Free 월$1 resource credit | 소규모 중계 실험 후보. GPU 음성 서비스의 지속 비용 해결로 보지 않음 |

무료 API의 모델별 한도·데이터 조건은 [STT](STT.md), [텍스트](TEXT.md), [TTS](TTS.md)에 따로 정리했다. 기간 한정 credits, 매월 갱신 무료 quota, 자체 호스팅을 같은 의미의 무료로 합치지 않는다. 계정 순환이나 quota 우회는 운영 방안에 포함하지 않는다.

## 폰 연결

현재 시뮬레이터는 localhost로 Mac의 서버에 연결 가능하며 Android는 adb reverse를 사용한다. 실제 폰의 localhost는 Mac이 아니다. 실기기에는 도달 가능한 HTTPS/WSS 경로가 필요하고 학교 Wi-Fi의 단말 간 통신도 확인해야 한다.

[Cloudflare Quick Tunnel](https://developers.cloudflare.com/tunnel/get-started/quick-tunnels/)은 계정·도메인 없이 임시 URL을 제공하는 개발용 후보다. uptime 보장 없음, 최대200개 진행 중 요청, SSE 미지원, 매번 hostname 변경 조건이 있다. 브라우저 이메일 인증은 비대화식 모바일 API 클라이언트를 지원하지 않으므로 기존 앱 토큰 인증을 대체한다고 안내하지 않는다. 실제 음성 WebSocket 연결은 별도 통합 시험 전까지 미검증이다. 현재 공개 터널을 만들거나 Mac 포트를 외부에 개방하지 않았다.

## 통합 음성 모델도 조사했는가

[Seamless Communication](https://github.com/facebookresearch/seamless_communication)은 음성 번역·스트리밍 연구 경로이며 코드와 모델 라이선스가 다르다. SeamlessM4T/Streaming의 CC-BY-NC 가중치는 과제의 비상업 범위와 조건을 확인해 비교할 수 있다. [Qwen3-Omni](https://arxiv.org/abs/2509.17765)도 통합 경로 후보지만 큰 전체 가중치와 Mac 실행 성능을 따로 검증해야 한다. 활성 파라미터 수는 전체 모델 메모리 크기가 아니다.

이 앱에서는 번역 없이 기록만 사용하거나, 원문을 고친 뒤 재번역·근거 항목을 검토해야 한다. 따라서 독립 교체 가능한 STT→텍스트→TTS 구성을 시작점으로 권고한다. 통합 모델이 같은 수정·취소·근거 계약과 품질/속도에서 우세하면 교체 비교할 수 있으며 구조만으로 배제하지 않는다.

## 성능 확정에 필요한 자료

- [FLEURS](https://huggingface.co/datasets/google/fleurs): CC-BY4.0, 한·영·일 공개 음성 기준선. 낭독 데이터이므로 실제 대면 겹말/소음 검증을 대신하지 않음.
- [FLORES-200](https://github.com/facebookresearch/flores/blob/main/flores200/README.md): 여섯 번역 방향의 공통 평가 출발점. 업무의 수치·정정·담당자 문맥은 별도 fixtures로 보완.
- [Silero VAD](https://github.com/snakers4/silero-vad): 음성 구간 검출 후보. VAD는 STT나 화자 식별이 아니며 발화 절단 오류도 평가해야 함.
- [pyannote Community-1](https://huggingface.co/pyannote/speaker-diarization-community-1): 후속 화자 분리 비교군. 접근·사용 조건 확인 필요. 화자 구간만으로 사람 이름이나 담당자를 확정하지 않음.

같은 입력·같은 양자화 조건을 기록하고 cold/warm, p50/p95, 오류율, 최대 메모리와 swap을 측정한다. 대화 중에는 STT·번역·TTS를 우선하고 추출 동시 실행 시의 성능 저하를 별도 비교한다. 최초 표시, 발화 종료 후 최종 번역, 첫 음성 도착까지를 나누어 측정한다. 문서의 160/320ms chunk나 GPU 벤치마크를 전체 앱 지연으로 발표하지 않는다.

개인 음성 없이 공개 음성/텍스트로 첫 비교는 가능하지만 이번 작업에서는 실행하지 않았다. 사용자가 미뤄 둔 실기기 음성 검증은 이후 단계로 유지한다.
