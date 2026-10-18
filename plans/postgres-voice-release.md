# PostgreSQL · 음성 연결 · 사용자 테스트 · 발표자료

2026-10-18 재개. 사용자의 작업 재개와 목표 지시에 따라 구현한다.

## 고정 조건

- 모든 설치·파일·설정 변경은 이 SpeechBridge 프로젝트 안에서만 한다. 기존 다른 프로젝트, MariaDB 서비스, 전역 패키지·설정은 변경하지 않는다.
- PostgreSQL 단일 DB를 원본으로 사용한다. 기존 계정 SQLite와 기기 기록을 안전하게 이전한다. 기기에는 같은 계정으로 전송할 대기 내용만 암호화해 임시 저장한다.
- 현재 Mac에서 무료 로컬 AI를 실행한다. Gemini/Claude/Cloud API, 게스트 계정, 화자 판별은 추가하지 않는다.
- 새 목표에 따라 실제 음성 입력·재생을 연결하고 사용자가 실기기에서 바로 테스트할 수 있는 빌드·연결 절차를 준비한다. 테스트 준비와 실제 기기에서 관찰한 결과를 구분한다.
- 완료된 실제 앱 화면이 포함된 간결한 PPTX를 만든다. Canva·미리캔버스 등의 무료 템플릿/레퍼런스를 조사하고 이용 조건에 맞춰 적용한다.

## 현재 근거와 구현 패턴

| 영역 | 근거 | 따를 패턴 / 바꿀 부분 |
|---|---|---|
| 인증 | `server/auth.mjs` | scrypt, 해시 세션, 계정별 제한 보존. SQLite를 PostgreSQL로 전환 |
| HTTP | `server/server.mjs`, `server/errors.mjs` | 의존성 주입, ApiError, 원문·토큰 로그 금지, 요청 취소 |
| 기록 | `Conversation.kt`, `BridgeViewModel.kt` | 기존 ID·revision·AI 결과 무효화 보존. 서버 repository와 암호화 outbox로 전환 |
| 보안 저장 | AndroidCredentialStore / IosCredentialStore | 플랫폼 보안 키 저장. 대기 데이터는 계정·서버별 분리 |
| 음성 | `server/voice-provider.mjs`, `plans/voice-pipeline.md` | PCM16 WAV, 한·영·일, 제한된 실행·취소·임시 파일 정리 |
| 테스트 | `server/*.test.mjs`, shared commonTest / platform tests | Node test, kotlin.test, 실제 PostgreSQL 통합 테스트 추가 |

## 순서와 완료 기준

- [ ] 1. 프로젝트 전용 PostgreSQL 바이너리·데이터·소켓·로그·백업, 역할 분리, 마이그레이션
- [x] 2. PostgreSQL 회원/세션, SQLite 안전 이전, 기존 API 계약 회귀
- [ ] 3. 모든 사용자 데이터 스키마·API, 소유권 격리, 충돌/중복 요청 방지, 복구 가능한 동기화
- [ ] 4. 기기 암호화 outbox, 계정 전환 격리, 연결 복구, 기존 기록 가져오기, DB 원본 UI
- [ ] 5. Android/iOS 녹음 권한·캡처·재생, 음성 API 회원 인증, 자동 언어 처리, 취소/수정/화면 이탈 처리
- [ ] 6. 서버+앱 회귀, 샘플 음성 통합, 테스트용 설치 빌드·안전한 휴대전화 접속, 백업 복원
- [ ] 7. 실제 앱 화면 캡처, 템플릿 조사, 간결한 발표 PPTX 생성·렌더링 검수
- [ ] 8. README·실행 절차·검증 근거 갱신, 비밀정보 제외 후 GitHub 푸시

2026-10-18 진행: 프로젝트 전용 PostgreSQL 설치/역할/마이그레이션과 백업 생성 완료, 복원 검증은 남음. 단계2 완료. 단계3 정상화 저장·조회·변경 API와 계정 격리·재전송·충돌·revision·순서 테스트 완료; AI 작업의 서버 기록 연결은 후속. 단계4 SyncRepository/HttpSyncRemote 공통 기반과 테스트 완료, 아직 실제 App/ViewModel·플랫폼 보안 대기 저장소 연결 전. 서버63개, Android/iOS 각각58개 테스트 통과. 음성·실기기 준비·PPT까지 전체 목표는 계속 진행 중.

## 데이터 계약

계정·인증 세션·환경설정·업무 폴더·대화·발화와 수정본·번역·요약·할 일·초안·AI 작업·동기화 변경/영수증을 PostgreSQL에 보관한다. 내부적으로 사용자 ID를 포함한 키와 외래 키를 사용한다. 인증된 사용자 ID는 서버에서 결정하며 요청 본문을 신뢰하지 않는다. 원음 영구 저장은 별도 요청이 없으므로 하지 않는다.

기기 대기함은 서버 ACK 후 제거한다. 재전송은 mutation ID로 중복 반영을 막고, 오래된 version 수정은 409로 충돌을 드러낸다. 로그아웃/계정 전환 시 다른 계정의 대기 내용을 업로드하지 않는다. 영구 로컬 기록 캐시를 두지 않으므로 앱 재시작 후 과거 기록 조회에는 서버 연결이 필요하다.

기존 기록의 소유자는 임의로 추정하지 않는다. 가져올 계정과 기록 수를 확인하는 이전 흐름을 둔다. 검증 전 원본 파일을 삭제하지 않으며 DB 장애를 빈 기록으로 처리하지 않는다. 사용자 수동 편집을 늦은 AI 응답이 덮어쓰지 않도록 원문·메타데이터 revision을 검사한다.

## 검증 원칙과 위험

ECC tdd-workflow의 RED → GREEN → REFACTOR 및 체크포인트를 적용한다. 단위 테스트 외에 실제 프로젝트 PostgreSQL의 트랜잭션·계정 격리·동시 로그인·동기화 재전송을 검사한다. 테스트 데이터와 운영 데이터를 분리한다.

복잡도는 높음: 기기 저장을 DB 원본으로 바꾸면서 계정별 복구·동시 수정·이전까지 보존해야 한다. 주요 위험은 데이터 유실, 계정 간 노출, AI 지연 응답, 마이크 권한과 앱 이탈, Mac 휴면/네트워크 중단이다. 각각 실패 시나리오를 먼저 테스트한다. 실제 사용자 데이터나 토큰을 테스트 결과/PPT/Git에 넣지 않는다.

PostgreSQL 선정 근거: 관계 무결성, 트랜잭션과 행 수준 정책을 함께 사용할 수 있다. 속도 우위를 실측한 주장은 아니다. [공식 행 보안 문서](https://www.postgresql.org/docs/18/ddl-rowsecurity.html), [빌드·설치](https://www.postgresql.org/docs/18/install-make.html), [백업](https://www.postgresql.org/docs/18/backup-dump.html).
