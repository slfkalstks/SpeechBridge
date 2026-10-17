# 무료 Mac 서버 실행과 앱 연결

현재 기본은 **회원가입·로그인 서버**다. [최신 회원 서버·자동 연결 안내](ACCOUNT_SERVER.md)를 먼저 확인한다. 아래 공용 토큰 절차는 기존 개발·음성 평가용 이력이며 `AUTH_MODE=development`에서만 사용한다.

2026-10-17. 유료 API를 사용하지 않는다. 현재 실제 로컬 번역·기록 정리 provider를 연결했다. 기본 모델은 TranslateGemma12B와 Gemma4 12B QAT이며 [실측 결과](LOCAL_AI_RESULTS.md)를 확인할 수 있다. STT/TTS 서버 API도 연결했고 [음성 API 검증](LOCAL_VOICE_API_RESULTS.md)을 완료했다. 모바일 음성 UI 연결은 다음 단계다.

## 현재 Mac에서 실행

Node.js22.16 이상~26 미만과 Ollama가 필요하다. 프로젝트 개발 환경에서는 런타임과 아래 모델 다운로드를 완료했다. 새 환경은 별도 준비가 필요하다. Node 외부 npm 의존성은 없다. 터미널 두 개에서 실행한다. 같은 포트의 서버가 이미 실행 중이면 중복 실행하지 않는다.

터미널1 — AI 런타임:

```sh
cd SpeechBridge/server
npm run start:ai
```

별도 포트11435·프로젝트 `.runtime/models`·cloud 비활성·Metal 자동 사용·상주모델1개·동시추론1개. 사용자의 기본 Ollama 포트/설정을 변경하지 않는다.

처음 준비하는 다른 Mac에서는 런타임을 켠 뒤 공식 모델을 내려받는다. 다운로드 용량은 기본 두 모델 합계 약15.3GB이며 휴대전화에는 내려받지 않는다. 실제 저장공간·실행 메모리는 별도다.

```sh
OLLAMA_HOST=127.0.0.1:11435 ollama pull translategemma:12b
OLLAMA_HOST=127.0.0.1:11435 ollama pull gemma4:12b-it-qat
```

터미널2 — 앱 API:

```sh
cd SpeechBridge/server
npm run setup:local
npm run start:local
```

setup은 `.runtime/server.env`에 임의 접속 토큰을 만들며 기존 파일을 덮어쓰지 않는다. 파일 권한0600, Git 제외. 이 파일의 `APP_ACCESS_TOKEN` 값을 개발 빌드의 '설정 → 더보기 → 개발자 연결 설정'에서 사용한다. 토큰을 채팅·스크린샷·공개 저장소에 붙이지 않는다. 개발용 접속 설정이며 사용자 개인 AI 계정/API 키는 필요 없다.

| 환경 변수 | 기본값/역할 |
|---|---|
| HOST / PORT | 127.0.0.1 / 8787 |
| APP_ACCESS_TOKEN | 최소32자, setup이 생성 |
| AI_PROVIDER | `ollama-local`; `disabled`면 모델 호출 차단 |
| OLLAMA_BASE_URL | `http://127.0.0.1:11435`, 숫자 loopback HTTP 주소만 허용 |
| TRANSLATION_MODEL | `translategemma:12b`; 비교용 `translategemma:4b` 허용 |
| EXTRACTION_MODEL | `gemma4:12b-it-qat`; 비교용 `qwen3.5:9b-q8_0` 허용 |
| SPEECH_PROVIDER | 기본 `disabled`; `local-process`면 준비된 프로젝트 음성 엔진 사용. AI_PROVIDER도 ollama-local 필요 |

Qwen 비교는 이 Mac의 기존 모델 blob을 프로젝트 캐시에서 심볼릭 링크로 참조했다. 전역 캐시 파일은 수정하지 않았다. 기본 경로는 Qwen에 의존하지 않는다. 모델 이름만 맞아도 remote/cloud alias라면 추론 전에 거부한다. API 요청이 자동 다운로드나 유료 대체를 수행하지 않는다.

음성 모델/venv가 준비된 현재 Mac에서는 `.runtime/server.env`에 `SPEECH_PROVIDER=local-process`를 설정하거나 `SPEECH_PROVIDER=local-process npm run start:local`로 실행한다. 기존 서버를 먼저 종료하고 한 API 인스턴스만 실행한다. 음성만 검사할 때 Ollama는 꺼져 있어도 되며 health의 `runtime: offline`과 `speechRuntime: files_ready`가 함께 표시될 수 있다. files_ready는 실행/모델 파일 존재 검사이고 실제 추론 성공 보장이 아니다. 엔진은 요청별 자식 프로세스로 실행하며 별도 음성 포트를 열지 않는다.

## 앱 연결과 사용

Android 에뮬레이터:

```sh
/Users/molla/Library/Android/sdk/platform-tools/adb reverse tcp:8787 tcp:8787
```

개발 빌드 **설정 → 더보기 → 개발자 연결 설정 → `http://localhost:8787`·접속 토큰 입력 → 설정 적용 → 적용한 서버 상태 확인**. iOS 시뮬레이터도 동일 주소를 사용한다. 일반 원격 HTTP는 거부한다. 실제 폰은 Mac의 localhost를 공유하지 않으므로 실기기 HTTPS/WSS 연결은 후속 작업이다.

**대화 → 언어 선택 → 보조 키보드 입력 → 번역 → 종료 후 최초 AI 제목·분류·요약 → 근거 확인 → 할 일 관리** 흐름을 사용한다. 현재 대화는 항상 기록하며 기록을 끄는 UI는 없다. 대화가 바뀌면 이전 AI 항목을 재검토하며, 재추출에서 사라진 항목은 자동 삭제하지 않고 별도 경고한다. AI 결과가 틀리면 수정한 뒤 근거를 다시 검토한다.

서버 주소와 접속 토큰은 앱 메모리에만 보관되어 재시작 후 다시 입력한다. 모델이 준비되어도 첫 호출/모델 전환에는 로딩 시간이 필요하다. 대기 중 취소할 수 있으며 실패해도 원문을 유지한다. 현재 앱은 마이크·음성 재생을 제공하지 않는다.

## 계약·제약

- `GET /health`: 설정 여부(`configured`), 모델 설치 가용성(`available`), 런타임 online/offline. 모델이 설치되어 있다는 검사이며 실제 번역 품질/추론 성공 보장은 아니다.
- `POST /translate`: `{text,from,to,revision}` → `{text,revision}`. ko/en/ja. 같은 언어는 원문 반환.
- `POST /extract`: `{utterances:[{id,revision,text}],language}` → `{items:[{sourceUtteranceId,sourceRevision,kind,text,owner,dueText,evidenceQuote}]}`. 정확한 ID/revision/인용문 검사, 원문에 없는 담당자/기한은 null.
- `POST /speech/token`:503 `speech_pending` (disabled provider는 `free_provider_pending`).
- `POST /speech/transcribe`: `{audioBase64,language}` → `{text,language}`. ko/en/ja, 표준44바이트 헤더 WAV PCM16 mono16kHz, 최대30초/960,044bytes. metadata chunk·압축·여분 바이트·비정규 base64 거부. PCM RMS≤16은400 `empty_audio`; VAD는 아님.
- `POST /speech/synthesize`: `{text,language}` → `audio/wav`, PCM16 mono24kHz, 최대30초/1,440,044bytes. ko/en/ja, 1~300 Unicode code point. 초과 출력·중간에 끊긴 생성은 오류 처리하며 잘라서 성공으로 반환하지 않는다. 고정된 일반 모델 목소리를 사용하며 음성 복제는 하지 않는다.
- POST 인증: `Authorization: Bearer <APP_ACCESS_TOKEN>`.
- 입력256KiB(STT JSON만1,300,000bytes)·분당60요청·HTTP 동시6개. 텍스트 추론 동시1개/대기3개, 대기 포함90초 timeout, 기존 텍스트 클라이언트100초. 번역은 대기열에서 추출보다 우선한다.
- 음성 작업은 동시1개/대기0개/90초 timeout. 텍스트가 실행·대기 중이면 음성은429 `busy`, 음성 중이면 텍스트·다른 음성도429. 취소/연결 종료/timeout 시 자식 프로세스 SIGTERM, 500ms 후 필요하면 SIGKILL, close 확인 및 파일 정리 후 슬롯 반환. timeout 응답은 회수 시간만큼90초보다 늦을 수 있다.
- 원음은 요청별 임시 폴더0700·파일0600에만 기록하고 finally에서 삭제한다. 시작 시 죽은 소유PID의 요청 폴더를 정리한다. 합성 평가용 fixtures는 재현 자료로 별도 보존한다. 서버 stderr/로그에 사용자 음성 원문을 남기지 않는다.
- 긴 번역은 문장 단위로 나누며 문장1개의UTF-8 크기가1200바이트 초과면 명시적 오류. 추출은 보수적인8192 context 예산 검사. 긴 회의를 자동으로 요약/분할하는 기능은 후속 작업이다.
- 원문/토큰 비로깅, 응답 no-store, redirect 거부. 회원 인증은 ACCOUNT_SERVER.md에 구현·검증 범위를 기록했다. 실제 단말·공개 HTTPS 운영·동시 사용자 부하 검증은 미완료다.

## 중단·되돌리기

각 실행 터미널에서 Ctrl+C로 해당 API/AI 런타임을 종료한다. API의 SIGINT/SIGTERM은 활성 요청을 취소하고 음성 자식 프로세스 회수/임시 파일 정리를 기다린다. 재시작 후에도 API 없이 직접 입력·기기 저장·수동 할 일은 사용 가능하다. `SPEECH_PROVIDER=disabled`면 음성만, `AI_PROVIDER=disabled`면 전체 모델 호출을 차단한다. 모델/사용자 기록을 자동 삭제하지 않는다.

## 종료 후 자동 제목과 분류 (2026-10-17)

앱 종료 동작은 새 `/organize` 경로를 사용한다. 기존 서버가 실행 중이면 최신 코드로 서버를 재시작한다. AI_PROVIDER=ollama-local과 기존 Gemma4 모델을 그대로 사용하며 새 유료 API/모델 설치는 없다. Debug 앱에서 설정 → 더보기 → 개발자 연결 설정으로 주소와 토큰을 입력한다. Release에는 이 메뉴가 없으며 빌드에 지정한 HTTPS 주소와 회원 로그인을 사용한다. 주소 미지정 시 서비스 준비 상태다. 별도 연결 탭은 제거했다.

원문은 AI 호출 전에 로컬 저장한다. AI 실패 시 대화의 더보기에서 재시도하거나 제목·분류를 직접 수정할 수 있다. 업무 폴더는 기록/할 일의 업무→업무 분류→폴더 관리에서 만든다. 사용자 폴더 이름이 원문에 명시되지 않으면 자동 배치하지 않는다.


2026-10-17: `/organize` 응답은 필수 `summary` 문자열(최대 2000자)을 포함한다. 제목·분류·요약은 대화 최초 종료 시 한 번 생성하며 이후 수동 편집한다. 최신 앱과 서버를 함께 사용한다. 일반 사용자는 서버 접속 토큰을 입력하지 않는 제품 방향이며, 사용자별 인증은 구현했으며 외부 운영 주소 배포는 미실행이다. 앱에 APP_ACCESS_TOKEN을 하드코딩하지 않는다.
