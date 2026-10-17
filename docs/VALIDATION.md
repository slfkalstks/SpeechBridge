# 구현·조사 검증

2026-10-17. 실제 키·유료 요청·마이크를 사용하지 않았다. AI 처리는 서버가 담당하는 방향으로 확정했다.

## 최신: 음성 제외 UI·텍스트 검증

[NONVOICE_VALIDATION.md](NONVOICE_VALIDATION.md)가 최신이다. Node34개, Android host28개, iOS simulator28개 통과. 새 UI·초안·검색·필터, 실제 iOS 텍스트 AI→검토→할 일→재실행 복원, 텍스트 모델 재평가 및 확인 한계를 기록했다. 아래 내용은 과거 검증 이력이다.

## 작업 공간 이동

프로젝트와 초기 자료를 `middle_test` 아래로 이동했다. 이동 전후 파일 수·크기 일치, 끊어진 심볼릭 링크 없음, 접속 토큰 권한0600 보존 확인. 새 경로에서 Node 테스트18개 통과, Android APK·iOS Kotlin 컴파일·Kotlin 테스트 Gradle 검증 성공(일부 작업은 캐시 재사용). Xcode 전체 빌드도 새 경로와 `.runtime/xcode-derived`에서 성공했다. Python venv와 NeMo 실행 파일도 새 경로에서 확인했다.

## 이전 변경: 인증된 로컬 음성 API

- Node **34개 통과**(기존18개+음성16개). 입력·인증·잘못된 엔진 출력·공용 admission·timeout·늦은 응답·SIGTERM/SIGKILL·close 이후 슬롯 반환·임시 파일 권한/삭제·서버 종료 검증.
- 실제 한·영·일 합성 샘플을 인증된 HTTP STT/TTS로 처리했다. TTS 시작 중 취소→프로세스 close→후속 STT 성공, 잔여 요청 폴더0개 확인. 상세·한계는 [LOCAL_VOICE_API_RESULTS](LOCAL_VOICE_API_RESULTS.md).
- 이번 변경은 Node/Python 서버와 문서 범위다. Kotlin/플랫폼 앱 소스는 변경하지 않았으며 아래 앱 빌드 결과는 이전 세션 검증이다. V3 앱 음성 연결과 V4 통합 측정은 남아 있다.

## 이전 변경: 무료 Mac 텍스트 AI 연결

- Ollama0.34.4 Metal과 프로젝트 전용 모델 캐시로 TranslateGemma12B/4B·Gemma4 12B QAT·기존Qwen3.5 9B Q8을 실제 실행했다. 모델별 입력/출력/지연/digest는 [LOCAL_AI_RESULTS](LOCAL_AI_RESULTS.md)에 기록했다.
- 당시 Node 서버 테스트 **18개 통과**. 원격/유료 경로 차단, 근거 인용 검사, context 초과, timeout·소켓 종료 취소·대기열 상한을 검사했다.
- Kotlin 핵심 **19개 통과**: ConversationTest8개, BridgeViewModelTest11개. 템플릿2개를 포함하면21개. 기존 저장/역순 응답 외에 취소 후 늦은 응답·조작 인용·후속 취소 발언·담당자 변경 초안 보존을 검사했다.
- Android debug APK 빌드·iOS simulator arm64 Kotlin 컴파일·Xcode iosApp 시뮬레이터 전체 빌드 통과.
- 아래 이전 검증 이력과 이번 실측을 구분한다. 모델 추론 성공은 STT/TTS·실시간 통역 검증을 뜻하지 않는다.

## 자동 검사

검사 명령:

```sh
JAVA_HOME='/Users/molla/Library/Java/JavaVirtualMachines/temurin-25.0.4.1/Contents/Home' ./gradlew :androidApp:assembleDebug :shared:compileKotlinIosSimulatorArm64 :shared:testAndroidHostTest --console=plain
node --test server/*.test.mjs
JAVA_HOME='/Users/molla/Library/Java/JavaVirtualMachines/temurin-25.0.4.1/Contents/Home' xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -sdk iphonesimulator -configuration Debug -derivedDataPath /private/tmp/speechbridge-derived build CODE_SIGNING_ALLOWED=NO
```

## 이번 Android 실제 모델 UI 확인

Android 에뮬레이터5554에 최신 APK를 업데이트했다.127.0.0.1:8788의 임시 API와 adb reverse를 사용했고 실사용 토큰 대신 테스트 전용 토큰을 사용했다. 모델은 실제 TranslateGemma12B/Gemma4 12B QAT다.

- 앱에서 health 요청 → 번역/정리 모델 준비, 음성 연결 전 표시 확인.
- QA-local-AI 대화(번역/기록 on)에서 영어 `Yuki, please send the updated schedule by Friday.` 입력.
- 실제 한국어 번역 `유키 씨, 업데이트된 일정은 금요일까지 보내주세요.`가 표시됨.
- 실제 AI 추출 → 한국어 요청 초안, 담당Yuki/기한Friday → 근거창에서 원문과 AI 인용 확인 → 검토 완료 → 할 일 등록 확인.
- 후속 `cancel that request` 발언 입력/실제 번역 → 기존 할 일 즉시 검토 필요, 대화 변경 경고, 완료 버튼 비활성화 확인.
- 다시 정리하면 새 항목 없음·기존 항목은 최근 AI 정리에서 제외됨 경고. 근거창에 원래 요청과 후속 취소 발언이 함께 표시됨을 확인.
- QA-local-AI 합성 기록은 검토 예시로 보존했다. 임시8788 API/포트 연결은 종료하고 앱 메모리의 테스트 접속 정보는 앱 종료로 해제했다. 이후 사용자 요청으로 정상8787 API와11435 AI 런타임도 종료했다. 재연결은 API_SETUP 절차를 따른다.

이것은 화면에서 실제로 확인한 흐름이며, 자동 테스트의 모의 응답과 구분한다. iOS의 이번 변경은 전체 빌드까지 검증했고 같은 네트워크 UI 흐름은 아직 직접 실행하지 않았다.

## 이전 앱 실행 확인

Android 에뮬레이터 emulator-5554:

1. 시작 화면, 언어/번역/기록 선택, 한국어 UI 렌더링.
2. 번역 off·기록 on으로 QA-record-flow 생성, 직접 입력.
3. 요청 항목 생성 → 담당자/기한 입력 → 근거 확인 → 할 일 등록 → 완료 처리.
4. 원문 수정 후 완료한 항목도 ‘검토 필요’로 표시하고 완료/재진행을 비활성화함을 확인.
5. 앱 강제 종료·APK 업데이트·재시작 후 원문/항목/재검토 상태 복원 확인.
6. 공급자 키와 인증 토큰이 비어 있는 임시 서버(8788)에 adb reverse로 연결. 앱 Ktor 클라이언트에서 `/health`를 실제 요청하여 서버 응답과 각 서비스 ‘설정 필요’ 표시 확인. 유료 경로는 호출하지 않음.

검증 후 생성한 QA-record-flow 기록은 앱의 삭제 확인 절차로 제거했다. 임시 서버와 8788 포트 연결도 종료했다.

당시 재검토·서버 상태 화면은 로컬 검증 자료로 보관하며 공개 저장소에는 포함하지 않는다.

iOS iPhone 17 Pro 시뮬레이터 (iOS 26.5):

- 최종 빌드 앱 설치·실행과 시작 화면 렌더링 확인.
- 앱 저장소 위치 조회·백업 제외 디렉터리 초기화 후 정상 시작 확인.
- iOS의 전체 작성/저장/재시작 UI 흐름과 네트워크 요청은 아직 직접 실행하지 않았다.
- 당시 시작 화면은 로컬 검증 자료로 보관한다.

## 설계 검토 조치 확인

| 지적 | 구현·검증 |
|---|---|
| R1 오래된 응답 | 요청 ID·취소·근거 revision/snapshot 검사. 역순 응답, 수정/종료/삭제, 종료 기록 새 요청 테스트 통과 |
| R2 재검토 | 원문·항목 수정 시 검토 해제, 명시적 검토 때 근거 revision 갱신, 미검토 완료 거부. 테스트 및 Android UI 확인 |
| R3 저장 손실 | 필수 형식/version 검증, 읽기 실패 시 변경 잠금, 직렬·원자 쓰기, 미저장 재시도. 실패/복원 테스트 통과. iOS 쓰기 실동작 후속 |
| R4 기록 off | 전체 세션 저장 필터, 종료 시 메모리 제거, 영구 할 일 등록 거부. off 세션·파생 항목 테스트 통과 |
| R5 인증·연결 | .env 제외, 키 서버 보관, 인증 미설정 차단, redirect 거부, 로컬 개발 HTTP 설정. 서버 테스트 및 Android health 확인 |
| R6 설정/성공 구분 | health는 설정/모델 준비/런타임 상태를 구분. 모델 준비는 품질·추론 성공 보장이 아님 |
| R7 음성 미검증 | 인증된 파일 STT/TTS API와 합성 샘플 확인. 모바일 마이크/재생·실사용 검증은 미완료 |
| R8 백업 | Android noBackupFilesDir + allowBackup=false, iOS Application Support 하위 디렉터리 백업 제외. OS 백업/복원 전체 시험은 미수행 |

## 남은 검증과 구현

- 더 긴/다양한 대화에서 번역·정리 품질, 모델 전환과 동시 부하 측정. 무료 외부 API 비교는 무료 계정 조건을 확인한 경로만 사용.
- 선택한 무료 음성 서버의 스트리밍 어댑터와 마이크/오디오 권한·재생·중단·재연결 구현 및 실기기 검증.
- iOS 작성·재시작 복원·서버 연결 직접 검증, 실제 휴대전화의 네트워크·권한·성능.
- 3개 언어의 긴 입력, 작은 화면/태블릿, 큰 글자, 스크린리더, 키보드 환경 전반은 추가 검증 필요.
- 텍스트 모델 다운로드·로컬 추론은 완료. 학습·외부 서비스 배포·외부 API 대비 우위 검증은 하지 않음.
- 현재 UI는 기능용 첫 구현이다. frontend-design-premium의 전체 디자인 검토를 통과한 결과물이 아니다.

저장·네트워크 실패는 자동 테스트의 모의 실패로 검증한 부분과 실제 단말에서 관찰한 부분을 위에서 구분했다. 컴파일 통과는 실시간 음성 품질 보장이 아니다.
