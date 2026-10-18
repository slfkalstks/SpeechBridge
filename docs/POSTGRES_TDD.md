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
