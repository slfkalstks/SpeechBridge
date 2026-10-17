# 전체 UI 리디자인 검증

2026-10-17. 기준: plans/design-system-refresh.md. 이전 자동 제목·분류 기능 검증은 REDESIGN_VALIDATION.md를 별도 참고한다.

## 바뀐 부분

- 청록/흰 단일 테마를 블루 계열 밝은·어두운 시스템 테마로 교체. 바탕/내용/번역/동작/검토/오류 의미 색상 분리.
- 큰 음성 버튼, 별도 언어쌍 표면, 하단 음성 안내 sheet, 선택 시 여는 보조 키보드 입력. 마이크나 실시간 파형을 동작하는 것처럼 표시하지 않음.
- 상단 글자 위계,3개 하단 탐색/넓은 화면 rail, 통일된 벡터 아이콘, 내용 표면·여백·둥근 모서리 역할 정리.
- 기록의 날짜별 묶음과 미리보기, 할 일의 완료 취소선·검토 문구·출처 링크, 설정/편집/분류의 공통 테마.
- 종료 기록 상세에서 기록 탭 선택 및 목록으로 돌아가기. 대화 탭은 진행 대화 또는 시작 화면으로 복귀.
- 키보드 입력 선택 시 필드 포커스. 작은 높이의 머리말과 여백 축소.

## 자동 검사

| 검사 | 결과 | 근거 |
|---|---|---|
| Android APK + iOS 공통 Kotlin 컴파일 | 성공 | .runtime/nonvoice-final-gradle.log |
| Android host 회귀 | 33 tests, failures0/errors0/skipped0 | docs/validation/design-refresh-tests.json |
| iOS simulator 회귀 | 33 tests, failures0/errors0/skipped0 | .runtime/design-refresh-ios-test.log 및 위 JSON |
| Xcode simulator 앱 | BUILD SUCCEEDED | .runtime/nonvoice-xcode.log |
| 두 팔레트 문서/런타임 색상 일치 | 통과 | docs/validation/design-contrast.json |
| 일반 텍스트 대비 | 밝은15+어두운15=30조합 통과, 최소5.62:1 | 위 JSON |
| DESIGN.md lint | errors0/warnings0 | docs/validation/designmd-lint.json |

기존 테스트에는 템플릿 테스트가 포함된다. 이번 작업에서 서버/모델 코드는 변경하지 않았고 Node38 및 모델6사례를 재실행했다고 주장하지 않는다. 이전 premium-ui-audit.json은 과거 정적 검사 이력이며 이번 Kotlin 네이티브 전체 검증 결과로 사용하지 않는다.

## 직접 본 네이티브 화면/동작

Cua로 실제 Simulator/Android Studio의 앱 화면을 관찰했다. 웹 목업이 아니다.

- iPhone17 Pro/iOS26.5: 밝은 대화 시작, 기록 날짜 묶음, 기존 Apollo 기록의 원문/한국어 번역, 할 일 완료·검토 표시 확인.
- 다크 모드 전환: 앱 재실행 없이 할 일 화면이 어두운 스킴으로 바뀜. 설정 폼·음성 안내 sheet·보조 입력창 확인.
- 음성 버튼 → 준비 안내 → 키보드 입력. 현재 미구현 상태를 명시하며 마이크 권한/녹음 시작은 없음.
- 최대 Dynamic Type 세로 시작 화면: 음성 제어를 가로로 재배치해 표시. 최대 글자+가로에서 아래쪽이 잘리는 것을 발견하고 상단 제목/여백을 줄인 최종 빌드로 재확인: 마이크·준비 문구·키보드·하단 탐색 전체 표시.
- 일반 글자+가로 시작 화면: rail 전환과 언어쌍/음성 제어 확인.
- 보조 입력 선택 후 포커스 표시, 소프트웨어 키보드 표시 상태에서 입력창/전송 제어/탐색이 가려지지 않음 확인.
- 검증용 미전송 문자열 `2026` 입력 → 기록 탭 → 대화 → 키보드 재진입에서 초안 보존 확인. 해당 검증용 초안만 비웠고 기록은 생성하지 않음.
- 기록 업무 필터 → 업무 분류 → 기존 apollo 폴더 선택 → 해당 기록 표시 확인. 전체 필터로 복원.
- Android API35/Medium Phone: 기존 QA-local-AI 할 일과 새 색상/표면/메타데이터/탐색 표시 확인. 분리된 Running Devices 창의 좌표 클릭이 Studio 편집창으로 전달되어 Android 전체 클릭 흐름은 검증했다고 주장하지 않음.

## 보존과 한계

기존 기록/폴더/할 일 완료 상태/모델을 보존했다. 이번 시각 작업에서 서버나 모델을 실행하지 않았고 새로운 기록도 만들지 않았다. 최종 Android APK/iOS 앱을 기존 앱 위에 설치했다. iPhone은 밝은 모드·글자large·세로·대화 시작 화면으로 복원했다.

실제 음성·실기기는 사용자 요청대로 계속 보류. VoiceOver/TalkBack 실제 청취, 모든 화면×글자 배율×키보드×회전 조합, 태블릿/폴더블 전체 매트릭스, OS 뒤로 제스처 전체 흐름은 미검증이다. 고대비 모드 전용 색상이나 Android wallpaper Dynamic Color를 구현했다고 주장하지 않는다. 네이티브 Compose UI이며 UIKit/SwiftUI 전용 컴포넌트로 재작성하지 않았다.
