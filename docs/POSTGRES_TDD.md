# PostgreSQL 전환 TDD 근거

2026-10-18. [계획](../plans/postgres-voice-release.md). ECC tdd-workflow 적용. Node 내장 test 러너와 실제 프로젝트 전용 `speechbridge_test` DB를 사용한다.

| 사용자 보장 | RED 체크포인트 | GREEN 체크포인트 | 테스트 |
|---|---|---|---|
| 로그인·해시·만료·폐기, 동시 5세션, RLS 격리 | fa1da11: DB 모듈 없음 | 5363c3b: 5개 통과 | postgres.test.mjs |
| HTTP 비동기 인증, SQLite 자동 fallback 없음 | 426bbc6: 내 계정이 빈 객체 | f71ff08: 전체 50개 통과 | postgres.test.mjs |
| 이전 원본 보존·충돌 rollback·폐기 세션 복원 방지 | 0991eeb: importer 없음 | efe5987: 전체 52개 통과 | postgres-import.test.mjs |
| 기록·설정·초안 저장, 중복/충돌/revision/계정 격리/커밋 순서 | 4d73af0: repository 없음 | 45cac4e: 새 8개 통과 | data.test.mjs |
| 항목 순서·인증된 저장/조회 API | 859a387: 순서 불일치, API 404 | e11adac: 전체 62개 통과 | data.test.mjs |

마지막 실행: `npm --prefix server run test:coverage`. 62개 통과, 실패/건너뜀 0. 로드된 서버 소스 기준 line 97.05%, branch 90.35%, function 90.69%. CLI 설치 도구·모바일 UI까지 포함한 제품 전체 커버리지 수치는 아니다. 원자료는 `.runtime/postgres/*red.log`, `*green.log`에 있다. 사용자 데이터와 자격정보는 공개하지 않는다.

SQLite backup API는 파일 헤더 카운터를 변경하므로 백업 비교를 users/sessions 행 비교로 수정했다. 원본 파일이 그대로인지 확인하는 byte 검사는 유지했다. 운영 계정 파일은 회원 0개/세션 0개였고 원본을 보존했다. 데이터가 있는 이전은 별도의 가상 계정 테스트로 검증했다.

프로젝트 PostgreSQL 18.6 빌드·체크섬·전용 소켓·마이그레이션 001~004·custom-format 백업 생성 확인. 백업 복원, 모바일 동기화, 음성·실기기 준비는 전체 계획에서 계속 진행한다. 발표자료는 최신 목표 변경에 따라 보류했다.

## 모바일 전송 대기와 후속 검증

- `61062ae` RED → `f7d4745` GREEN: SyncRepository의 기기 대기 저장·재시작·ACK 유실 재전송·계정 전환 격리·충돌 보존·ACK 후 대기 내용 제거 6개 테스트. Android/iOS 통과. 이때 플랫폼 보안 저장소는 MemoryCredentialStore 테스트 대역이며 앱 UI에는 아직 연결하지 않았다.
- `d1082bc` RED → `e07bde3` GREEN: 느린 업로드가 다음 입력의 안전한 저장을 막는 실패를 재현하고 네트워크 대기와 저장 잠금을 분리했다. 비운 초안을 다음 version으로 재사용하는 서버 테스트도 추가했다. 서버 전체 63개, Android/iOS 각각 55개 통과.
- `b1b381f` RED → `4c2737a` GREEN: HttpSyncRemote 미구현 컴파일 실패 후 구현. 계정/서버 주소에 묶인 인증, 외부 원문 오류 노출 방지, 401 세션 정리를 검사한다. Android/iOS 각각 58개 통과.

최신 서버 coverage는 line 97.05%, branch 90.39%, function 90.69%. `.runtime/postgres/draft-green.log`, `outbox-concurrency-green.log`, `http-sync-green.log` 참조. Kotlin 테스트 실행 성공을 Kotlin 커버리지 측정으로 표현하지 않는다.

빌드 캐시는 기존 전역 캐시를 읽어 APFS 복사한 `.runtime/gradle-home`, `.runtime/konan`을 사용한다. 경로 이동으로 발생한 KLIB 중복은 새 경로를 명시하고 configuration cache를 끄는 것으로 해결했다. 전역 캐시를 삭제하거나 수정하지 않았다. iOS 테스트는 Simulator 서비스 접근 권한을 받아 실행했다.

- `bcae15b` RED → 계정별 ViewModel 저장 연결: 로그인 전 기록 차단, 기존 기기 기록 자동 배정 금지, 오프라인 변경·초안의 대기 저장, 로그아웃과 다른 계정의 업로드 격리 3개 테스트. Android/iOS 각각 61개 통과. `.runtime/postgres/workspace-red.log`, `workspace-green.log`, `workspace-ios-green.log`. 아직 실제 App 진입점에는 활성화하지 않았으며 기존 기록 가져오기·충돌 처리 UI와 플랫폼 보안 저장소 연결이 남아 있다.

- `57f59e4` RED → 세션 만료 도중 보안 저장이 지연되면 다음 입력을 버리던 경합 수정. 이전 계정 화면을 즉시 비우되, 큐에 이미 들어온 저장을 끝낸 뒤 계정 세대를 전환한다. Android/iOS 각각 62개, 실패·건너뜀 0. `.runtime/postgres/expiry-write-red.log`, `expiry-write-green.log`.

## 실제 앱 연결 추가 체크포인트

| RED | GREEN | 보장 |
|---|---|---|
| ffb585d | 8c26979 | 보안 저장 실패 후 계정 만료·다른 계정 로그인·동일 계정 복구, 실패 중 되돌리기 |
| e91dc57 | 5ca2bb5 | 앞선 저장 실패와 무관하게 초안 보존, 서버 언어 enum 계약 |
| 3c37856 | a612758 | 명시적 기존 기록 이전·서버 검증 후 원본 정리·충돌 양쪽 보관 |
| 0e66a99 | fc3da17 | 기존 모바일 폴더 이름 200자 DB 이전 |
| f09cd28 | 4a80596 | 연결 복구 후 자동 재전송 |

최신 Android/iOS 각각70개, 서버64개 통과. `4d6af20`에서 실제 App과 네이티브 보안 저장소를 연결했다. [실제 UI·DB 확인](CLOUD_WORKSPACE_VALIDATION.md)과 단위 테스트 결과를 구분한다.

## 백업·초안 복구·기기 메타데이터

| RED | GREEN | 보장 |
|---|---|---|
| 60fb195 | 499a376 | 새 DB 복원/원본 보존, 데이터·RLS 검증, 세션 부활 방지, 손상 archive 거부·실패 DB 정리 |
| c0ccbd0 | 233ece5 | 사본 초안 scope·현재 선택, 종료/삭제된 대화 초안 복구·재시작 중복 방지, 새로고침 초안 동기화 |
| 0a05ee2 | 10b9256 | 실제 기기 플랫폼 등록, 구버전 mutation 재시도 호환, 유효하지 않은 플랫폼 거부 |

서버70개 통과. 로드된 서버 소스 line96.62/branch90.83/function90.83%; backup 모듈 line92.65/branch89.19/function90.91%. 운영 backup도 별도DB복원17개테이블비교와임시DB정리를확인했다. `.runtime/postgres/backup-*.log`, `device-*.log`, `draft-recovery-*.log`, `recovery-final.log`가 로컬 근거다. 새 UI초안복구 테스트를 실제 다중 휴대전화 확인으로 표현하지 않는다.

## 최초 AI 작업 영속화와 앱 자동 반영

| RED | GREEN | 보장 |
|---|---|---|
| 12fb077 | 3d6d793 | 종료와 작업 생성의 원자성, 중복 방지, RLS, 사용자 수정·삭제 보호, 임대 만료 재획득·늦은 완료 거부, 제한된 재시도 |
| 36eeeeb | 3f78db8 | 작업 순차 실행, 음성과 연산 자원 공유, 종료 시 작업 반환, 시간초과·DB 장애 후 계속 처리 |
| 86d3870 | ec686e4 | 앱의 중복 AI 요청 제거, 서버 결과 자동 반영, 조회 대기 중 안전한 로컬 저장·계정 전환·사용자 편집 보호 |

Node 83개 모두 통과, 로드된 소스 line96.98/branch91.43/function91.80%. 새 작업 repository line99.11/branch98.18/function95.24%, worker line100/branch91.18/function90%. Android host99개/iOS simulator99개 모두 통과, 실패·건너뜀 0. Android Debug APK와 signed Xcode Debug 앱 빌드 성공. 원자료 `.runtime/postgres/jobs-*.log`, `worker-*.log`. Kotlin 커버리지를 측정한 수치는 아니다.

합성 텍스트를 실제 회원 HTTP→테스트 PostgreSQL에 저장한 뒤 서버 인스턴스를 재생성해 로컬 모델 정리 완료·작업 1개·후속 수동 제목 보존을 확인했다. 결과 [organization-jobs.json](../server/validation/organization-jobs.json)의 약7.5초는 단일 사례이며 일반 성능 지표가 아니다. 별도로 실제 iOS 시뮬레이터에서 종료·처리 중 표시·완료 자동 반영을 관찰했다.

운영 DB 선택 보완: `11b6d75` RED → `164ba9d` GREEN. 백업·마이그레이션이 프로젝트 서버 설정의 기본/복원 DB를 따르고 다른 소켓·포트·DB를 거부한다. 현재 선택 DB의 마이그레이션 재실행 확인. 최종 Node85개 통과, loaded line97.02/branch91.43/function91.95%; target 모듈 line100/branch90.91/function100%. `.runtime/postgres/target-*.log`. 실제 운영 DB 전환을 수행했다는 뜻은 아니다.


## LAN HTTPS와 바로 시연

| RED | GREEN | 보장 |
|---|---|---|
| c0a208e | df25a8e | 프로젝트 CA/서버 인증서, 올바른 TLS 성공·잘못된 CA/호스트 거부, 제한된 loopback 프록시 |
| 652e723 | ac07a3f | symlink 경계 밖 생성 거부, 동시 키 생성 잠금, 공개 DER 불일치 거부 |
| 6492f31 | 9361ae3 | iOS 정상 CA/호스트/유효기간 검증, Debug 전용 앱 신뢰 설정, Release 플래그 차단 |
| e6c08ff | b13b946 | 다른 프로젝트/인터페이스 리스너 거부, 시연 데이터 재준비 시 수정·삭제 보존, DB 정규화 저장 |

최종 Node93개 통과. 로드된 소스 line91.45/branch90.50/function88.37%; CLI 전체 실행 경로 커버리지는 아니다. AndroidHost99/iOSSim100개 통과, APK·signed Xcode Debug 빌드 성공. TLS 테스트6개, native trust 테스트1개 포함. iOS 독립 테스트 실행은 Security 서비스 오류를 반환해 booted simulator 실행으로 바꿨고 동일 테스트가 통과했다. 검사 우회·skip은 하지 않았다.

`Demo.command` 실제 실행 및 프로젝트 API/HTTPS 정상 종료 후 자동 재시작 검증. APK 공개 DER 일치·개인 키/PEM 미포함 확인. Release 보호 작업은 localTls=true에서 의도대로 실패했다. 실제 iOS UI에서 HTTPS 로그인→DB 기록→대화 종료→자동 제목/분류/요약→결정 후보→할 일 목록 확인. 이는 실제 휴대전화·마이크 품질이나 전체 Release 빌드 검증은 아니다. 로컬 근거는 `.runtime/tls/{mobile-final,xcode,final-coverage,release-rejected}.log`, `.runtime/demo/{readiness.json,launch-validation.log,restart-validation.log}`.


## 계정 삭제와 계정 부활 방지

| RED | GREEN | 보장 |
|---|---|---|
| 01f799a | 74f988a | 현재 비밀번호·세션·명시 확인, 계정별 cascade, 응답 유실 재시도, 최신 삭제 이력을 백업 복원에 반영 |
| 2878a88 | 389a4c0 | 복원 후 추가 삭제를 반영하기 전 복원 DB의 API 시작 차단 |
| ba815f6 | bac1918 | 예전 SQLite 재가져오기가 삭제한 계정·세션을 다시 만들지 않음 |

추가 회귀 2c2e6e2: 13개 사용자 테이블에 실제 가상 데이터를 넣은 뒤 삭제 확인, 로그인과 삭제·로그아웃과 삭제 경합. 최종 서버98개 통과, loaded line91.58/branch90.31/function89.22%. `.runtime/postgres/deletion-*.log`에 RED/GREEN 결과. 앱 삭제 화면·기기 대기함·웹 경로·백업 보존본 정리는 아직 미구현이며 이 서버 테스트로 전체 계정 삭제 완료를 주장하지 않는다. 기존 시연 계정·사용자 기록은 삭제하지 않았다.
