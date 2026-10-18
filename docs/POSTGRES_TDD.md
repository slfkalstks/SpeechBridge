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

프로젝트 PostgreSQL 18.6 빌드·체크섬·전용 소켓·마이그레이션 001~004·custom-format 백업 생성 확인. 백업 복원, 모바일 동기화, 음성·실기기 준비, PPT는 전체 계획에서 계속 진행한다.
