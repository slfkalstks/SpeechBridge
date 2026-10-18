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

네이티브 마이크·재생과 자동 언어 처리, 최초 AI 작업의 서버 재개, 테스트용 HTTPS 연결, DB 백업 복원, Android 네이티브 저장의 추가 연결 검증 및 배포 설정 점검을 전체 목표에서 이어간다. 새로고침·충돌 사본의 초안/선택 상태 등 다중 기기 시나리오도 확대한다.
