# 무료 Mac 텍스트 AI 연결 결과

2026-10-17. Apple M3 Max·36GiB, Ollama0.34.4 Metal, 단일 순차 요청. 실제 모델을 다운로드하고 합성 텍스트로 추론했다. 모델 학습·유료 API·클라우드 추론은 사용하지 않았다.

> 이 문서는 10월 17일의 비교 기록입니다. 10월 19일 상주 정책과 성능 변경은 [후속 측정](TRANSLATION_PERFORMANCE.md), 현재 선정 이유는 [모델 설명](MODEL_SELECTION.md)을 참고하세요.

## 이번 기본값

- 번역: `translategemma:12b` (Q4_K_M).
- 기록 정리: `gemma4:12b-it-qat`, `think:false`, JSON mode + 서버 검증.
- 비교 모델: `translategemma:4b`, 기존 설치 `qwen3.5:9b-q8_0`.
- 단말에는 모델을 설치하지 않는다. Ollama는127.0.0.1:11435, Node API는127.0.0.1:8787. 한 번에 모델1개·추론1개를 기본으로 한다.

이는 모든 무료 후보 중 최상이라는 결론이 아니라, 이번 제한된 실측으로 선정한 작동 기준선이다. STT/TTS·실기기·다중 사용자·긴 회의는 아직 검증하지 않았다.

## 동일한 짧은 예제 측정

단위 초. '첫 요청'은 다른 모델에서 전환한 첫 측정으로 로딩을 포함한다. 디스크 캐시까지 비운 cold-start 시험이 아니다. 로딩 뒤 서로 다른 문장 5개와 동일 문장 반복1개를 구분했다. 빌드와 다운로드를 마친 후 IDE와 에뮬레이터를 켠 상태에서 실행했다.

| 모델 | 첫 요청 | 로딩 뒤 서로 다른 문장 | 동일 문장 반복 |
|---|---:|---:|---:|
| TranslateGemma12B | 5.260 | 0.803–1.056 | 0.642 |
| TranslateGemma4B | 2.794 | 0.304–0.414 | 0.220 |

번역은 한↔영·한↔일·영↔일 각1문장. 12B는 이 예제들의 수량·시각·부정·이름을 보존했다. 4B는 '보고서 두 부'를 'both reports', Yuki를 'つきよさん'으로 바꿨으며, 땅콩을 먹을 수 없다는 문장에 명시되지 않은 알레르기 이유를 추가했다. 속도만으로4B를 기본값으로 채택하지 않았다. 이 검토는 소수 예제의 의미 대조이며, 통계적인 번역 정확도나 원어민 평가가 아니다.

| 추출 모델 | 첫 한국어 요청 | 로딩 뒤 영·일 요청 | 항목 없는 세 사례 |
|---|---:|---:|---:|
| Gemma4 12B QAT | 7.391 | 2.536 / 2.603 | 0.929–1.038 |
| Qwen3.5 9B Q8 | 8.015 | 3.899 / 4.558 | 0.674–0.745 |

두 모델 모두 한·영·일 명시적 요청을 추출했고, 후속 취소·잡담·교육용 인용 명령에서는 빈 결과를 반환했다. 원문 ID/revision·정확한 인용문을 검사했다. JSON 성공과 의미 정확도는 다르므로 앱에서 사용자가 근거를 확인한 뒤 할 일로 등록한다.

초기 개발 중에는12B 번역8–17초, Gemma4 추출90초 timeout과 일본어 요청을 빈 결과로 놓친 사례가 발생했다. 당시에는 빌드/다운로드가 일부 겹쳤고 추출 지시문 및 출력 형식도 달랐다. 이후 JSON schema 강제 출력을 JSON mode로 바꾸고 필드 매핑을 명시했다. 여러 조건을 함께 변경했으므로 특정 요인 하나가 지연의 원인이라고 단정하지 않는다. 초기 결과도 [initial-probe](validation/local-ai-initial-probe.json)에 보존했다.

## 원자료와 재현

- [12B 번역](validation/local-ai-translation-translategemma-12b-qwen3.5-9b-q8_0.json)
- [4B 번역](validation/local-ai-translation-translategemma-4b-qwen3.5-9b-q8_0.json)
- [Gemma4 추출](validation/local-ai-extraction-translategemma-12b-gemma4-12b-it-qat.json)
- [Qwen 추출](validation/local-ai-extraction-translategemma-12b-qwen3.5-9b-q8_0.json)

각 파일에 입력/출력, 전체 요청 시간, 런타임의 로드/생성 시간 및 설치 모델 digest를 기록했다. 번역 전용 실행의 파일명에 포함된 추출 모델은 실행되지 않는다.

프로젝트 루트에서 AI 런타임을 켜고 실행:

```sh
EVALUATE_TASK=translation TRANSLATION_MODEL=translategemma:12b EXTRACTION_MODEL=qwen3.5:9b-q8_0 node server/evaluate-local.mjs
EVALUATE_TASK=extraction EXTRACTION_MODEL=gemma4:12b-it-qat node server/evaluate-local.mjs
```

동일 이름의 측정 파일을 갱신한다. 실사용 대화가 아닌 스크립트의 합성 입력만 보낸다. 서버/앱 UI에서의 왕복 검증은 [VALIDATION](VALIDATION.md)을 따른다.

## 의미와 한계

짧은 텍스트 번역의 로딩 후 응답은 약1초 수준인 사례를 확인했다. 음성의 발화 종료 감지·STT·네트워크·TTS·재생을 더한 대면 통역 지연은 측정하지 않았으므로 '실시간 통역 달성'이라고 표현하지 않는다. 모델1개 상주 정책에서는 번역↔정리 전환 시 다시 로드할 수 있다. 번역 우선순위는 대기 중인 작업에만 적용하며 이미 실행 중인 추출을 강제로 선점하지 않는다. 더 긴 자료와 동시 부하에서 정확도·p50/p95·메모리 측정이 필요하다.

공식 실행 계약: [Ollama Chat API](https://docs.ollama.com/api/chat), [TranslateGemma 모델·프롬프트](https://ollama.com/library/translategemma), [Gemma4 태그](https://ollama.com/library/gemma4/tags). 공식 지원 정보와 위 실측 결과를 구분한다.
