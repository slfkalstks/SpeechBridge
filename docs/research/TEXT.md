# 무료 번역·회의 행동 항목 추출 조사

조사일: 2026-10-17. 대상: 한국어·영어·일본어의 여섯 번역 방향, Mac M3 Max 36GB 서버, 모바일에서는 큰 모델 실행하지 않음. 설치·다운로드·API 호출·실측은 하지 않았다. 문서에 있는 기능/요금/라이선스와 이 프로젝트에 대한 설계 판단을 구분한다.

## 권고와 확신 수준

**지속적으로 API 요금 없이 사용할 기본 경로는 Mac 서버의 TranslateGemma 12B 번역 + 별도 범용 LLM의 근거 기반 추출이다.** 이는 현재 자료에 따른 우선 검증 구성이지, 성능 1위의 실측 결론은 아니다. 번역은 4B·12B·27B, 추출은 Gemma 4 12B QAT·26B-A4B QAT와 Qwen3.5-9B·Qwen3.8-27B를 동일 데이터로 비교한다. 일반 LLM 한 개로 번역과 추출을 처리하는 구성도 반드시 비교해야 한다. 모델별 파라미터 수나 출시일만으로 한일 업무 대화 품질을 판정할 수 없다.

무료 클라우드는 **DeepL API Free / Azure Translator F0 / Groq Free / Gemini API Free**가 유력한 비교 후보다. 유료 전환을 하지 않은 계정에서 실제 제공 모델과 한도를 확인해야 한다. API 계정이 없으므로 현시점에서는 각 사용자 계정의 접근 가능 여부를 검증하지 않았다. 무료 서비스의 변경·한도 소진에도 시연이 유지되도록 Mac 경로를 보유하는 것이 적합하다는 설계 판단이다.

자체 서버도 전기·인터넷·Mac 가동은 필요하다. 여기서 무료는 **추가 API 사용료와 서버 임대료가 없는 것**이다. 사용자의 폰에 모델 메모리·추론 부담을 전가하지 않는다.

## 1. 번역 모델 후보

| 후보 | 공식 확인 사항 | 프로젝트 판단 |
|---|---|---|
| TranslateGemma 4B / 12B / 27B | 번역 전용, 55개 언어, Gemma 라이선스. Google 모델 카드는 입력 컨텍스트 2K 및 지정 번역 템플릿을 설명하며 번역 외 능력을 주장하지 않는다. 자체 평가 WMT24++ COMET은 4B 81.6 / 12B 83.5 / 27B 84.4. | **로컬 번역 우선 후보 12B**. 4B는 지연 비교, 27B는 품질 상한 비교. 자동 점수는 한↔일 실제 발화 성능의 증명이 아니다. 추출 모델로 쓰지 않는다. [공식 카드](https://huggingface.co/google/translategemma-12b-it), [개발팀 논문](https://arxiv.org/abs/2601.09012) |
| HY-MT1.5 1.8B / 7B | ko/en/ja, 상호 번역·문맥·용어 개입 지원. **공식 가중치 라이선스 Territory에서 대한민국 제외**; 1.8B와7B 모두 문서에 명시. | 속도상 매력적이지만 한국에서 사용하는 현재 프로젝트 기본 후보에서 제외. 커뮤니티 양자화가 원본 조건을 없애지 않는다. [모델 카드](https://huggingface.co/tencent/HY-MT1.5-1.8B), [가중치 라이선스 1.l·2·5.c](https://huggingface.co/tencent/HY-MT1.5-7B/blob/main/License.txt) |
| MADLAD-400 3B MT | Google 공식 400+ 언어 번역 모델, Apache-2.0. | 허용적인 라이선스의 경량 번역 비교군. 최신 모델보다 무조건 나쁘다고 판단하지 않는다. [공식 카드](https://huggingface.co/google/madlad400-3b-mt) |
| NLLB-200 distilled600M / 1.3B / 3.3B | 600M 카드: CC-BY-NC-4.0, 연구 목적, 생산 배포 대상으로 출시되지 않음, 학습 입력512토큰 이내. | 비상업 과제 비교군으로 가능. 업무/문서용 장문 번역이나 상용 확장의 기본 선택으로 권하지 않음. [공식 카드](https://huggingface.co/facebook/nllb-200-distilled-600M) |
| SeamlessM4T v2 large | 음성·텍스트 번역 결합 모델, CC-BY-NC-4.0. 공식 언어 카드에 eng/jpn/kor가 있다. | 학술 비교군. STT 원문 수정→재번역이라는 앱 구조 및 Mac 실행 경로를 따로 검증해야 함. 모델이 통합되었다는 이유만으로 빠르다고 볼 수 없음. [공식 가중치](https://huggingface.co/facebook/seamless-m4t-v2-large), [공식 언어 카드](https://github.com/facebookresearch/seamless_communication/blob/main/src/seamless_communication/cards/seamlessM4T_v2_large.yaml) |
| Argos / Marian·OPUS-MT 계열 | Argos는 오프라인 패키지와 언어 피벗을 지원. 공식 패키지 인덱스에 ko→en/ja→en 포함. 직접 쌍이 없으면 피벗 과정에서 품질 손실 가능성을 공식 설명. | CPU 저비용 비교군. 설치할 **각 방향 패키지**와 **각 가중치 라이선스** 확인 필요. 코드 라이선스가 모든 모델 라이선스를 보증하지 않음. [Argos 공식](https://github.com/argosopentech/argos-translate), [패키지 인덱스](https://www.argosopentech.com/argospm/index/) |
| Tower-Plus-9B | ko/en/ja 포함. 메타데이터 CC-BY-NC-SA-4.0과 본문 CC-BY-NC-4.0 표기가 불일치. | 학술 비교 후보지만 라이선스 파일 확정 전 배포 기본값으로 선정하지 않음. [공식 카드](https://huggingface.co/Unbabel/Tower-Plus-9B) |
| Aya Expanse8B | ko/en/ja 포함23개 언어, CC-BY-NC + 사용 정책, 가중치 접근 동의 필요. | 다국어 범용 비교군. 현재 필수 우선 후보보다 설정/조건이 늘어나는 점을 고려. [공식 카드](https://huggingface.co/CohereLabs/aya-expanse-8b) |

TranslateGemma는 Ollama 공식 라이브러리에 4B **3.3GB**, 12B **8.1GB**, 27B **17GB**로 게시되어 있다. 이는 모델 배포 크기이지 총 RAM 측정치가 아니다. Ollama 페이지의 128K 표시와 Google 카드의 2K 입력 설명이 다르므로, 번역 초기 검증에는 제조사 카드의 짧은 입력 조건을 사용한다. ko/ja/en 코드는 라이브러리 지원 목록에 있다. [Ollama 모델 페이지](https://ollama.com/library/translategemma)

Mac에서 CTranslate2를 CUDA GPU 엔진처럼 취급해서는 안 된다. 공식 배포 하드웨어 문서는 ARM64 CPU와 NVIDIA GPU를 명시한다. Apple Accelerate는 CPU 실행 경로에 포함되며, 이 사실로 Metal GPU 가속을 주장할 수 없다. [공식 하드웨어](https://github.com/OpenNMT/CTranslate2/blob/master/docs/hardware_support.md), [프로젝트](https://github.com/OpenNMT/CTranslate2)

## 2. 회의 기록→요청·결정·할 일 추출

| 모델 | 확인된 장점 | 비교 목적/유보 |
|---|---|---|
| Gemma 4 12B IT / 공식 QAT | Apache-2.0, system role·thinking 제어·다국어·구조적 도구 사용 지원. 공식 QAT GGUF 배포. | **추출 초기 균형 후보**. 범용 벤치마크가 행동 항목 추출 정확성을 증명하지 않음. [가중치 카드](https://huggingface.co/google/gemma-4-12B-it), [공식 QAT](https://huggingface.co/google/gemma-4-12B-it-qat-q4_0-gguf) |
| Gemma4 26B-A4B IT QAT | MoE, 전체26B급 가중치 필요. 공식 Q4_0 로드 추정14.4GB;12B는6.7GB. 추가 컨텍스트와 프로그램 메모리는 별도. | 단일 모델로 번역+추출을 함께 처리하는 강력한 비교 후보. 4B 활성이라는 이유로4B 메모리라 해석하지 않음. [공식 메모리/양자화 가이드](https://ai.google.dev/gemma/docs/core), [공식 가중치](https://huggingface.co/google/gemma-4-26B-A4B-it) |
| Qwen3.5-9B | Apache-2.0,201개 언어·방언에 대한 개발팀 지원 설명, WMT24++ 평가 포함. | 더 작은 범용 모델의 추출/번역 통합 비교군. 한국어 액션 추출에 대한 자체 검증 필요. [공식 카드](https://huggingface.co/Qwen/Qwen3.5-9B) |
| Qwen3.8-27B | 현재 공개 공식 가중치, Apache-2.0,3.5 기반 dense모델, thinking 제어 및 전문 작업 강화. | 최신 대형 비교군.36GB에서 양자화·짧은 컨텍스트·다른 모델과의 동시 점유를 측정해야 함. 출시일만으로9B보다 실시간 경험이 좋다고 결론내리지 않음. [공식 카드](https://huggingface.co/Qwen/Qwen3.8-27B), [공식 릴리스 이력](https://github.com/QwenLM/Qwen3.8/blob/main/README.md) |

**설계 권고:** 번역은 발화 확정 직후 높은 우선순위로 처리하고, 추출은 발화 묶음 또는 대화 종료 후 낮은 우선순위로 처리한다. 두 모델의 동시 상주는 측정 전 확정하지 않는다. IDE·에뮬레이터·STT와36GB를 공유하므로 모델 재로딩 지연, 메모리 압력, swap, 첫 토큰 및 완성 지연을 함께 측정한다. 27B 번역기와27B 추출기를 동시에 유지하는 구성을 기본값으로 두지 않는다.

### 구조화 결과는 사실 검증과 다르다

Ollama는 JSON Schema를 `format`으로 받는다. llama.cpp 서버도 스키마 제약을 지원한다. 이 기능은 문법/형식을 위한 것으로, 내용에 없는 담당자·기한을 만들어내지 않는다는 보증이 아니다. [Ollama 구조화 출력](https://docs.ollama.com/capabilities/structured-outputs), [llama.cpp 서버](https://github.com/ggml-org/llama.cpp/blob/master/tools/server/README.md)

앱에 필요한 출력과 검증 규칙은 다음처럼 별도로 설계한다.

- 모델에는 발화 ID·revision·원문만 데이터로 제공하고 원문 안의 명령은 실행하지 않는다.
- 출력: `kind`, `text`, `sourceUtteranceIds`, `sourceRevisions`, `evidenceQuotes`, `owner`, `dueText`.
- 서버는 ID 존재·revision 일치·인용문 원문 포함·필드 타입을 검사한다. 근거 없는 결과는 후보로도 수용하지 않는다.
- 담당자와 기한이 명시되지 않으면 `null`. “다음 주 금요일”은 원문 표현을 보존하고 임의 날짜로 확정하지 않는다.
- 여러 발화에 걸친 요청·취소·정정·담당자 변경을 반영해야 하므로 한 문장씩 독립 추출하는 방법과 문맥 묶음 추출을 비교한다.
- 질문·가정·인용된 타인의 말·거절·취소를 확정 할 일로 바꾸지 않는다. 사용자가 근거를 확인한 후 확정한다.
- 수정된 원문은 이전 추출 결과를 검토 필요로 바꾸고, 모델이 사용자의 편집을 자동 덮어쓰지 않는다.

이는 프로젝트 검증 규칙이며 특정 모델이 이미 통과했다는 의미가 아니다.

## 3. 무료 클라우드와 과금 차단 조건

| 서비스 | 공식 확인된 무료 범위 | 적용 판단 |
|---|---|---|
| **DeepL API Free** | 월50만자. 현재 개발자 페이지는 카드 없이 시작 가능하다고 명시. Free는 음성→텍스트 번역을 포함하지 않음. | 텍스트 번역의 우선 비교 후보. 무료 플랜임을 계정에서 확인하고 유료 전환 금지. [무료 플랜](https://support.deepl.com/hc/en-us/articles/360021200939-DeepL-API-plans), [현재 개발자 가입 안내](https://www.deepl.com/en/developers), [지원 언어 문서](https://developers.deepl.com/docs/getting-started/supported-languages) |
| **Azure Translator F0** | 월200만자. 무료 할당량 소진 시403001 오류를 공식 문서가 정의. S1은 별도 유료 플랜. | 무료 F0로만 사용한다면 후보에서 제외할 이유 없음. 기존 프로젝트의 Azure 어댑터 재사용 가능성. 구독/리소스 SKU 확인 전 호출 금지. [가격](https://azure.microsoft.com/en-us/pricing/details/translator/), [오류 코드](https://learn.microsoft.com/en-us/azure/ai-services/translator/text-translation/reference/status-response-codes) |
| **Groq Free** | 현재 qwen/qwen3.8-27b·gpt-oss120b/20b 표:30RPM,1,000RPD,8,000TPM,200,000TPD. 실제 조직 한도 우선. 초과429. | 추출 무료 원격 비교 우선 후보. strict JSON은 세 모델 지원; 현재 구조화 출력에서 streaming/tool use 미지원. 일반 text streaming과 혼동 금지. [한도](https://console.groq.com/docs/rate-limits), [구조화 출력](https://console.groq.com/docs/structured-outputs), [유료 업그레이드 조건](https://console.groq.com/docs/billing-faqs) |
| **Gemini API Free** | 현재 가격표에 Gemini3.8Flash·3.5Flash-Lite 텍스트 입출력 무료 표시. 모델별RPM/TPM/RPD는 프로젝트 AI Studio에서 확인. 무료 데이터는 제품 개선에 사용될 수 있음. | 무료 클라우드의 품질 비교 후보. API Free와 소비자 Gemini 앱 구독은 다름. billing 미연결 프로젝트만 허용, paid fallback 금지. 가상 수업 데이터로 비교하고 실제 회의 데이터 전송은 처리 조건 확인. [가격](https://ai.google.dev/gemini-api/docs/pricing), [한도](https://ai.google.dev/gemini-api/docs/rate-limits), [데이터 약관](https://ai.google.dev/gemini-api/terms), [구조화 출력](https://ai.google.dev/gemini-api/docs/structured-output) |
| Cloudflare Workers AI Free | 하루10,000 neurons, UTC0시 리셋. 무료 한도 초과는 실패; 더 사용하려면 Paid 필요. 일부 모델은 무료 플랜 접근 불가. | 대체 비교 후보. neurons는 요청 수가 아니며 모델별 사용량을 환산해야 함. Gemma4 26B-A4B는 무료 가능 목록에 게시. [가격/모델별 예외](https://developers.cloudflare.com/workers-ai/platform/pricing/), [무료/유료 모델 구분](https://developers.cloudflare.com/changelog/post/2026-07-28-models-require-workers-paid/) |
| Hugging Face Inference Providers | 무료 사용자 월$0.10 크레딧, 변경 가능. 추가 사용은 구매 필요. | 짧은 실험용; 반복 시연의 주 공급자로 선정하기에는 양이 작다는 판단. [공식 과금 안내](https://huggingface.co/docs/inference-providers/pricing) |
| Cerebras | 최신 문서: 결제수단 확인 후$5,30일 유효 trial. 상시 갱신되는 무료 tier 없음. | 지속 무료 기본 경로에서 제외. 검색에 남은 옛 `/support/pricing`의 Free 설명을 사용하지 않는다. [현재 한도·FAQ](https://inference-docs.cerebras.ai/support/rate-limits) |
| Google Cloud Translation | NMT 월50만자 무료 크레딧 후 초과 과금. | 무료량은 있지만 billing 기반 초과 과금 구조이므로 이 프로젝트 기본 경로에서 제외. Gemini Developer API Free와 별개. [공식 가격](https://cloud.google.com/products/translate/pricing) |

무료 웹 번역 화면을 비공식 스크래핑하거나 소비자 로그인 세션을 API로 재활용하는 방법은 후보로 채택하지 않는다. 정식 무료 API와 공개 가중치 실행으로 요구를 달성할 수 있으며, 비공식 화면 연동은 배포용 서비스 계약·안정성 검증이 되지 않았기 때문이다. 공개 데모 Space 역시 서비스 소유자가 허용한 사용량·데이터 정책이 확인되지 않으면 앱의 필수 인프라로 사용하지 않는다.

## 4. 가장 좋은 결과를 선정할 실험

공개 점수만으로 현재 앱의 최상을 단정할 근거는 없다. 아래는 실행 전 계획이다.

1. **번역 평가 세트:** ko→en/en→ko/ko→ja/ja→ko/en→ja/ja→en 각 방향으로 일상·여행·업무 문장을 구성. 짧은 구어, 생략 주어, 경어, 부정/이중부정, 숫자·금액·시간, 고유명사, 발화 정정과 혼합 언어를 포함. 동일 원문과 조건을 모든 후보에 사용한다.
2. **번역 평가:** 이중언어 검토로 의미 보존·누락·환각·경어·용어·수치 오류를 구분. COMET/MetricX 같은 자동 점수는 보조이며 한 모델의 자체 점수를 다른 평가셋 점수와 직접 순위 비교하지 않는다. 단순 역번역 통과도 정답 증명이 아니다.
3. **추출 평가:** 세 언어 회의 예제와 다국어 혼합 예제로 요청/결정/담당자/기한/취소의 정답 근거를 지정. precision·recall·unsupported claim rate·근거 연결 정확도·JSON 유효율을 따로 기록한다. 의미가 틀리지만 JSON이 맞는 결과를 성공 처리하지 않는다.
4. **지연 평가:** Mac 로컬 및 무료 cloud 각각 cold/warm, 첫 글자와 완성 시간, p50/p95, 실패/429, 네트워크 대기, STT 동시 실행 시 지연, 메모리/swap을 측정한다. 번역만과 번역+추출을 분리 측정한다.
5. **최종 선택:** 오류 중요도가 큰 수치·부정·담당자·기한이 안전한 후보를 먼저 남기고, 그중 발화 길이별 지연과 운영 지속성이 좋은 구성을 고른다. 품질 우세가 없는 큰 모델은 기본 상주하지 않는다. 무료 cloud가 품질/지연에서 우세하면 선택적 경로로 쓰되 한도 소진은 로컬 경로 또는 명시적 재시도로 처리한다.

### 학습 여부

처음부터 학습하지 않는다. 공개 가중치+정확한 템플릿+용어집+스키마 검증을 먼저 평가한다. 그 결과 반복되는 실제 도메인 오류가 남고 사람이 검증한 학습/평가 데이터를 확보하면 LoRA를 별도 실험한다. MLX-LM은 Apple Silicon의 실행·양자화·미세조정을 지원한다. [공식 MLX-LM](https://github.com/ml-explore/mlx-lm)

모델 자체를 처음부터 학습하거나 근거 없이 fine-tuning하면 좋아진다고 주장하지 않는다. 학습한 데이터와 평가 데이터를 분리하고, 번역의 수치/부정 보존과 추출 근거 정확도가 퇴행하지 않은 경우에만 채택한다. 이는 비용 없는 소프트웨어를 이용할 수 있다는 것과 Mac에서 모든 규모의 학습이 실용적이라는 것은 다르다는 판단이다.

## 5. 조사 방법과 남은 불확실성

ECC deep-research의 다중 후보 조사→1차 출처 심독→사실/판단 구분 절차를 적용했다. Exa/Firecrawl이 제공되지 않아 웹 검색·공식 페이지 열기로 대체했다. 제조사 가중치 카드/라이선스, 공식 API 요금·한도·구조화 출력, 실행 엔진 문서를 조사했고, 커뮤니티 체감 속도는 성능 결론에 사용하지 않았다.

남은 확인: 실제 Mac 모델/양자화별 지연·RAM, 한국어↔일본어 중요 오류율, 모델별 추출 정확도, 계정별 무료 모델/한도, 학교망에서 폰→Mac 연결. 이번 조사만으로 “이 조합이 실측1위” 또는 “실시간 속도 달성”이라고 말할 수 없다. 위 shortlist는 다음 검증을 위한 구체적 후보 선정 결과다.

## 추가 탐색 범위

주요 후보 외에도 다음 대안을 확인했다. 이 표는 후보를 인위적으로 한 회사/계열에 제한하지 않기 위한 확장 비교군이다.

| 대안 | 확인 사항과 판단 |
|---|---|
| Mistral API Free | 현재 공식 quickstart는 Free mode 기본 활성·카드 불필요를 명시한다. 한도는 조직별 확인, 초과429. 무료 클라우드 비교군으로 유효하며 Le Chat/Vibe 구독과 API quota를 혼동하지 않는다. [시작 안내](https://docs.mistral.ai/getting-started/quickstarts/studio/activate-and-generate-api-key), [2026-08-12 한도 안내](https://help.mistral.ai/en/articles/698531-why-am-i-hitting-api-rate-limits-and-how-do-i-increase-them) |
| OpenRouter 무료 모델 | 무료 계정은20RPM·50회/일.1,000회/일은$10이상 구매 조건이므로 이번 조건에 포함하지 않는다. 유료 fallback을 끄고 정확한`:free`모델만 선택해야 한다. 작은 비교 실험용. [공식 무료 설명](https://openrouter.ai/blog/tutorials/how-to-get-the-lowest-cost-llm-inference-on-openrouter/) |
| NVIDIA API Catalog trial | 공식 약관은 trial 목적과 NVIDIA가 정하는 이용량을 명시한다. 영구 무료 서버로 보장하지 않고 테스트 가능 후보로만 분류. [공식 trial 약관](https://assets.ngc.nvidia.com/products/api-catalog/legal/NVIDIA%20API%20Trial%20Terms%20of%20Service.pdf) |
| Ministral3 14B Instruct | Apache-2.0,ko/en/ja 포함,JSON 출력과 system prompt 지원.8B/3B형도 공개. Mac 실행 형식 확인 후 추출 비교군으로 추가 가능. 일반 성능 점수만으로 한국어 추출 승자로 지정하지 않음. [공식 카드](https://huggingface.co/mistralai/Ministral-3-14B-Instruct-2512) |
| LiquidAI LFM2.5-8B-A1B | ko/en/ja,8.3B 전체/1.5B 활성,공식 MLX/GGUF 배포와 구조화 출력 사용례. 커스텀 LFM Open License1.0이므로 Apache로 표기하면 안 됨. 경량 추출·통합 번역 속도 비교군. 공식 H100 대량처리 속도를 M3Max 단일 대화 속도로 인용하지 않음. [공식 카드](https://huggingface.co/LiquidAI/LFM2.5-8B-A1B), [라이선스](https://huggingface.co/LiquidAI/LFM2.5-8B-A1B/blob/main/LICENSE) |
| Kanana1.5 8B Instruct2505 | Apache-2.0,한국어·영어 중심,32K native. 한국어 요청 추출 비교군으로는 의미 있으나,세 언어 통합 모델로 선택할 일본어 성능 근거는 미확보. [공식 카드](https://huggingface.co/kakaocorp/kanana-1.5-8b-instruct-2505) |

최우선 후보에서 해결되지 않는 품질/지연 문제 또는 분야 특이 오류가 보이면 이 비교군으로 확장한다. 우선순위는 검증 순서이며 프로젝트 기능이나 지원 언어의 제한이 아니다.
