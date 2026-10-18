# 계정별 모바일 DB 연결 검증

2026-10-18. [진행표](../plans/postgres-voice-release.md), [TDD 근거](POSTGRES_TDD.md).

## 구현과 회귀

실제 Android/iOS 진입점이 계정별 DB 저장에 연결되었다. 로그인 전 기록 차단, 계정 전환 시 화면 비우기, 플랫폼별 별도 보안 대기 저장소, 초안 저장, ACK 후 제거, 15초 재전송, 저장 실패와 로그인 만료 경합을 검사했다. 기존 기기 기록은 자동 배정하지 않고 명시적 가져오기·서버 내용 검증 후에만 원본을 비운다. 충돌은 사용자 선택으로 서버 원본과 로컬 사본을 각각 보관한다.

- Node 64개 통과, 실패·건너뜀 0. 실제 별도 PostgreSQL 테스트 DB 사용.
- Android host 70개, iOS simulator 70개 통과, 실패·건너뜀 0.
- Android Debug APK 및 서명된 Xcode Debug 시뮬레이터 앱 빌드 성공.
- 서버 소스 coverage: line 97.05%, branch 90.27%, function 90.69%. 모바일 UI·설치 CLI 전체 coverage가 아니다.
- 마이그레이션005: 기존 모바일 규칙과 맞춰 폴더 이름을 200자까지 보존.

실행 로그는 `.runtime/postgres/locked-storage-*.log`, `draft-contract-*.log`, `import-conflict-*.log`, `folder-length-*.log`, `reconnect-*.log`, `cloud-xcode.log`에 있다.

## 실제 iOS 앱과 PostgreSQL 연결

iPhone 17 Pro / iOS 26.5 시뮬레이터, 프로젝트 회원 API와 PostgreSQL을 사용했다. 별도로 만든 가상 계정만 사용했다.

1. 로그인 전 계정 화면 표시 및 기존 기록 비노출.
2. 가상 계정 로그인 후 빈 서버 기록 표시.
3. 다크 아이콘 선택 후 PostgreSQL user_settings에 DARK/version1 저장 확인.
4. 앱 종료·재실행 후 Keychain 로그인 및 DB의 다크 모드 복원 확인.
5. 화면 키보드로 가상 한국어 문장 입력. conversation_drafts에 저장되고 대화는 아직 0개인 것을 DB에서 확인.
6. 기록 버튼 후 utterances에 같은 문장이 저장되고 소비된 초안이 제거된 것을 확인.
7. 기기 테마 복원 후 SYSTEM/version2 확인. 로그아웃 후 기록 화면 차단 및 서버 세션 0개 확인.
8. 이번에 만든 가상 계정 2개와 종속 테스트 데이터만 정리했다. 기존 기기 기록 4개·폴더 1개는 가져오거나 지우지 않았다.

자동 키보드 입력이 Option 문자로 변하는 시뮬레이터 입력 도구 문제는 화면 키보드 클릭으로 우회했다. AI 엔진은 이 검증에서 켜져 있지 않아 번역 연결 오류가 표시되었으며 원문 DB 저장은 유지되었다. 이 결과를 음성·번역 추론 성공으로 해석하지 않는다. 실제 휴대전화 검증은 포함하지 않는다.

## 후속 검증

네이티브 마이크·재생과 자동 언어 처리는 연결했고 [음성 검증](NATIVE_VOICE_VALIDATION.md)에 범위를 기록했다. DB 백업 복원·초안 복구·최초 AI 작업의 서버 재개 검증도 아래에 추가했다. 테스트용 HTTPS 연결, Android 네이티브 저장의 추가 연결 검증과 배포 설정 점검은 계속 진행한다.

## 초안 복구·기기 등록 추가 검증

- 충돌한 기록의 기기 사본에 초안을 연결하고 active/selected ID도 바꿔 다음 문장이 사본에 입력되도록 했다.
- 다른 기기에서 대화가 종료·삭제된 경우 남은 초안을 새 대화로 보관한다. 초안은 제출된 발화로 자동 변환하지 않고 입력창에 복원한다. 원래 삭제된 ID는 재생성하지 않는다.
- 복구 큐가 저장된 뒤 앱을 재시작해도 사본을 중복 생성하지 않는다. 보안 저장소가 잠긴 경우 원래 초안을 유지하고 다시 저장할 수 있다.
- 설정 새로고침 때 서버 초안과 언어 설정도 다시 적용해 오래된 UI 초안이 서버 내용을 덮어쓰지 않게 했다.
- 오프라인에 초안만 있고 원래 서버 대화가 없으면 빈 기록으로 진행하지 않고 재연결을 기다린다. 기기의 암호화 대기 내용은 변경하지 않는다.
- snapshot/mutate에서 Android/iOS 종류와 설치 ID를 실제 DB에 등록한다. 플랫폼 정보를 덧붙여도 기존 mutation ID의 중복 방지 결과는 유지된다.
- 최신 실제 회원 HTTP 서버에서 가상 계정으로 iOS snapshot·Android mutate 후 DB의 두 플랫폼 값 확인, 로그아웃·계정 정리 완료. 원자료 `.runtime/postgres/device-http-validation.json`.

새 테스트는 `DraftRecoveryTest`, `CloudWorkspaceTest`, `HttpSyncRemoteTest`, `server/data.test.mjs`에 있으며 실제 다중 휴대전화 검증과는 구분한다.

추가 검증 종료 시 서버70개, Android host95개/iOS simulator95개 모두 통과했다. Android Debug APK와 signed Xcode Debug 앱 빌드도 성공했다.

## 서버 AI 작업과 실제 화면 추가 검증

2026-10-18. Migration006을 적용하고 회원 API를 재시작했다. 최신 signed Debug 앱을 기존 iPhone17Pro 시뮬레이터에 설치했다.

1. 가상 계정에 합성 문장 한 개가 있는 진행 중 대화를 준비했다. 기존 기기 기록은 가져오지 않았다.
2. 앱에서 로그인→대화 종료→확인. 화면의 ‘제목, 분류와 요약 작성 중’, ‘대화를 요약하고 있어요…’를 확인했다.
3. 별도 새로고침 없이 제목 ‘금요일 출시 확정’, 분류 ‘업무 · 회의’, 요약이 표시됐다. DB에서도 작업 succeeded/attempts1과 같은 결과를 확인했다.
4. 설정에서 ‘서버 저장 완료’를 확인하고 로그아웃했다. 서버 세션 0개 확인 후 이번 가상 계정만 제거했다.

로컬 근거는 `.runtime/postgres/jobs-ui.json`이다. 합성 텍스트의 UI·DB 흐름이며 실제 마이크나 실제 휴대전화 검증이 아니다. 최신 자동화 회귀는 서버83개, Android/iOS 각각99개 통과. 로그아웃 후에도 이미 DB에 접수된 자동 정리는 계속된다는 안내를 반영했다.
