# 배포 직전 검증 · 2026-10-19

현재 상태는 **Mac 기반 학교 과제 시제품·시연 준비**다. 기능 구현, 자동 회귀, 시뮬레이터 시연, Release 빌드 검사를 수행했다. 실제 공개 출시·실기기 품질 검증까지 완료했다는 뜻은 아니다.

## 이번에 마무리한 항목

- 앱 밖 계정 삭제 페이지 `/account/delete`와 `/privacy`, 로그인/재확인/취소, 브라우저 토큰 비영속·CSP·교차 사이트 POST 차단.
- 30일 검증 백업 보존, 기존 보존 사본 등록, 서버 시작/매시간 정리와 하루 단위 백업.
- 복원 사본의 명시적 활성화·최신 삭제 이력 적용·세션 폐기·퇴역 DB 차단·강제 종료 후 재개.
- 운영 HTTPS 주소가 없는 Release 빌드 차단, 테스트 CA의 Release 포함 차단, Android 클라우드/기기 이동 백업 제외, 앱 아이콘.
- 편집 가능한 10장 [PPTX](../presentation/SpeechBridge.pptx), 발표자 노트, 실제 앱 화면 6개와 README 삽입.
- 시뮬레이터 서버 주소를 loopback HTTPS로 고정. 음성 네트워크 오류를 일반 음성 실패와 구분. 번역 모델 30분 유지·시연 전 준비.

## 확인한 결과

| 검사 | 결과와 범위 |
|---|---|
| Node 서버 회귀 | 105개 통과. 실제 프로젝트 테스트 PostgreSQL 사용 |
| Node loaded-source coverage | line 90.09%, branch 89.75%, function 88.44%. 모든 CLI/앱 전체 커버리지가 아님 |
| Android Host / iOS Simulator | 110 / 111개 통과. 음성 연결 실패 분류 포함 |
| 실제 앱 UI | 언어 반전, 실제 텍스트 번역, AI 요약, 수동 수정, 할 일, 테마, 재실행, 연결 복구, 권한 거절 알림. [상세](DEMO_UI_VALIDATION.md) |
| 웹 UI | 시연 계정 로그인·삭제 확인·취소·개인정보 안내. 이 계정 영구 삭제는 하지 않음 |
| Release | Android AAB 및 iOS 기기용 archive 빌드. Android lint 오류 0개(버전/리소스 경고는 보고서에 기록). 예약 주소 `https://speechbridge.invalid`, 미서명 검증용으로 실제 배포 불가 |
| Debug | 프로젝트 CA를 포함한 설치 APK와 signed iOS simulator 앱. 현재 loopback용이며 휴대전화는 LAN용 재빌드 필요 |
| 합성 음성 | 3언어 STT→번역→PostgreSQL 왕복→TTS 성공. [현재 원자료](../server/validation/member-voice-flow.json) |
| 접근성 점검 | 가로 테마/언어/마이크 등의 접근성 라벨 직접 확인. 정적 텍스트 대비 30쌍 통과, 최소 5.62:1. 전체 접근성 인증 아님 |
| 발표 | 10장/발표자 노트/6개 실제 화면 포함. PDF 전체 렌더링으로 한글·배치 확인 |

로컬 근거는 `.runtime/demo/release-final-coverage.log`, `voice-transport-{red,green}.log`, `release-android-final.log`, `release-ios-final.log`, `release-demo-build.log`, `readiness.json`에 있다. 이 폴더의 자격정보·모델·로그는 Git에 올리지 않는다.

## 실제 출시 전에 필요한 것

1. 공개 고정 HTTPS 도메인·정상 인증서와 지속 가동/복구 운영 환경. 현재 Mac 외 다른 프로젝트나 전역 서비스는 변경하지 않았다.
2. 운영자 연락처·최종 개인정보처리방침·공개 계정 삭제 URL, 스토어 데이터 신고·지원/심사 정보.
3. Apple 개발팀/프로비저닝과 Android 업로드 서명키·스토어 계정. 현재 산출물은 제출용 서명 파일이 아니다.
4. 사용자가 보류한 실제 휴대전화·마이크·청취·소음/블루투스/통화 중단·전체 VoiceOver/TalkBack 검증. [바로 실행할 절차](NATIVE_VOICE_VALIDATION.md).

## 남아 있는 제품 한계

- 고유명사 인식과 호명/수신자 번역 오류가 관찰됐다. 원문 수정·재번역·근거 검토가 필요하다. 합성 샘플의 성공률을 일반 정확도로 주장하지 않는다.
- 모델 첫 로딩과 번역↔요약 모델 전환, 긴 입력에서는 지연이 크다. 30분 유지와 시연 준비로 유휴 후 재로딩을 줄였지만 실시간 스트리밍 번역은 아니다.
- 현재 비밀번호 재설정/복구는 제공하지 않는다. 아이디·비밀번호 기반 학교 시연 범위다.
- Mac 한 대의 백업은 장치 고장까지 보호하지 않는다. 실제 공개 운영에는 별도 보관·모니터링이 필요하다.

출시 전에 필요한 외부 값과 보류 검증을 임의로 완료 처리하지 않았다. 사용자가 보고한 음성 오류는 주소 불일치가 확인됐고 수정 빌드를 준비했다. 실제 마이크에서 동일 오류가 사라졌는지는 아직 확인하지 않았다. 주소 변경 전 전송 대기 1건은 이전 origin의 암호화 대기함에 보존하며 자동 이관하지 않는다.

공식 근거: [Android 백업 제외 규칙](https://developer.android.com/identity/data/autobackup), [Ollama 모델 준비·유지](https://github.com/ollama/ollama/blob/main/docs/faq.mdx), [Google Play 계정 삭제](https://support.google.com/googleplay/android-developer/answer/13327111?hl=ko). 이 문서는 법적 적합성 인증이 아니다.
