# 음성 중심 UI 리디자인 검증

2026-10-17. 최신 사용자 범위: 음성 입력이 주 UI, 키보드는 보조 입력. 음성 자동 감지/연결은 UI만 제공하며 실제 마이크·실기기 검증은 보류한다.

## 변경

- 시작 제목/번역·기록 스위치를 제거. 모든 새 대화는 저장. 마이크 준비 UI 중심, 언어쌍 선택은 한 줄, 키보드는 선택 시에만 노출.
- 종료한 대화에 자동 AI 제목/일상·업무 분류. 업무 고정 유형(회의·할 일·아이디어·기타)과 사용자 폴더. 실패 시 원문과 날짜 제목 보존, 재시도/수동 수정.
- 기록·할 일은 줄 구분 목록. 전체/일상/업무 공통 필터, 업무 세부 필터는 메뉴 하나에 통합. 검색은 필요할 때 열고 보조 행동은 더보기로 이동.
- 연결 탭을 제거하고 상단 설정으로 이동. 실제 Mac AI 접속 주소/토큰 설정은 여전히 필요.
- 기존 version1 기록을 보존하고 folders/분류 필드는 기본값으로 확장. 폴더 삭제는 소속만 해제. 원문 revision/메타데이터 revision/폴더 snapshot 검사로 오래된 AI 응답이 사용자 변경을 덮어쓰지 않음.

## 자동 검사

- Node `node --test server/*.test.mjs`: 38개 통과(기존 음성 회귀16개 포함; 실제 음성 실행 아님).
- Android host 33개, iOS simulator 33개 통과. 공통31개와 각 플랫폼2개 포함(템플릿 테스트 포함).
- Android APK, iOS Kotlin compile, Xcode simulator build 성공. 최종 소스/로그: `.runtime/nonvoice-final-gradle.log`, `.runtime/nonvoice-final-tests.log`, `.runtime/nonvoice-xcode.log`.
- DESIGN 공식 lint: 오류0/경고0. premium strict static audit: 오류0/경고0. 팔레트 일치와 본문 색 대비9쌍 통과. Kotlin의 모든 접근성 동작을 검사하는 도구가 아니며 WCAG 전체 인증을 뜻하지 않음.

## 실제 Mac 모델

무료 Gemma4 12B QAT 사용. 가상 대화6개(한·영·일, 가족여행/업무회의/업무요청/아이디어/개인심부름)를 검사.

첫 검사에서 업무 요청과 아이디어를 관계없는 기존 프로젝트 폴더로 배치하는 오류2개 발견. 원문에 폴더명이 명시된 경우만 자동 배치하도록 보완. 보완 후6/6 기대 분류 일치, 각1.57–2.02초(모델이 이미 로드된 상태; 일반적인 응답 시간 보장 아님).

원자료: `server/validation/organization-suite-before-grounding.json`, `server/validation/organization-suite.json`. 테스트 코드 `server/evaluate-organization.mjs`. 실제 사용자 평가나 일반 정확도 측정이 아니며 제목·유형 의미 오류 가능성이 남음. 폴더 별칭/간접 지칭은 자동 배치하지 않을 수 있어 수동 변경 가능.

## 시뮬레이터 직접 확인

- Android API35: 최종 APK 설치, 음성 중심 첫 화면·언어쌍·3개 탐색 확인. Android Studio 분리된 Running Devices 창에서 입력 자동화가 `noWindowsAvailable`로 실패해 전체 클릭 흐름은 iOS로 검증.
- iPhone17Pro iOS26.5: 음성 버튼의 준비 안내, 수동 언어쌍 변경, 폴더 `apollo` 생성, 설정의 임시 검증 토큰 적용, 가상 영어 대화 입력, 실제 한국어 번역 확인.
- 종료 후 날짜 임시 제목→`Apollo Project Release Schedule`, `업무·회의·apollo`로 자동 변경 확인. 유형을 수동으로 할 일로 바꿔 저장.
- 새 수동 항목 `release review`의 근거 검토→체크박스 완료. 업무 필터에 표시, 재실행 후 제목/분류/폴더/완료 상태 복원. 기존3개 가상 기록 보존.
- 최종 간소화 화면에서 업무 분류 메뉴→폴더 목록→apollo 선택 확인. 세로 및 가로 rail 배치 확인.
- 최대 글자(accessibility-extra-extra-extra-large)·가로 첫 화면에서 음성 제어가 밀리는 문제를 수정. 작은 높이에서는 불필요한 안내 제목을 숨기고 마이크/안내/키보드를 가로 배치. 최종 렌더에서 세 제어가 모두 보임을 확인. 검증 후 글자 크기large로 복원.
- 해당 가상 회의의 기존 AI 할 일 추출은 빈 목록을 반환했다. 자동 제목/분류 검증과 별개이며 AI가 모든 결정을 찾아낸다고 주장하지 않는다.

## 보류·검증 한계

실제 마이크/휴대전화, 음성 자동 언어 감지/자동 음성 번역, 원음 녹음은 미구현 또는 보류. 준비 UI를 실제 녹음으로 표현하지 않는다. VoiceOver/TalkBack 청취, 양 플랫폼 전체 키보드·회전·최대 글자 조합, 실제 사용자 사용성 평가는 전체 검증하지 않았다. 삭제 회귀는 테스트 저장소에서 검사했고 사용자 기록을 UI로 영구 삭제하지 않았다.

## 종료 상태

이번 검증용 API8787/Ollama11435 종료. 모델과 기존 환경은 보존. 임시 접속 토큰은 앱 재실행으로 초기화했다. 최종 Android APK와 iOS simulator 앱을 설치했다.
