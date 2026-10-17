# 디자인 참고와 설치

2026-10-17. 사용자 요청한 네 저장소를 확인했다. 기존 앱 전체 리디자인에 적용하며 외부 저장소의 실행 앱 코드를 제품에 복사하지 않았다.

| 저장소 | 확인한 자료와 적용 판단 | 로컬 설치/확보 |
|---|---|---|
| [Apple Design Skill](https://github.com/dickwu/apple-design-skill) | SKILL, accessibility/layout/typography/color/designing-for-ios/cross-platform/tab-bars/lists-and-tables/buttons. 조작과 내용의 계층, 일관된 언어, 큰 글자, 익숙한 탐색, 밝은/어두운 색상 원칙을 Compose에 적용. Apple 전용 프레임워크를 새로 도입한 것은 아님. | `.agents/skills/apple-design`, 전체 references 확보 |
| [Material You New Tab](https://github.com/prem-k-r/MaterialYouNewTab) | README의 실제 포스터와 style.css의 색상 역할/둥근 형태 확인. 옅은 동일 계열 바탕과 진한 동작 색의 관계를 참고. 웹 위젯·시계·검색엔진·확장 코드를 앱에 이식하지 않음. | `.runtime/design-references/MaterialYouNewTab/source` 소스 clone, 포스터. 브라우저 새 탭 확장은 설치하지 않음 |
| [Impeccable](https://github.com/pbakaus/impeccable) | Operate 모드의 절제, 의미별 색상, 상태/반응형 검증, native iOS/Android 참고. 새 디자인을 실제 시뮬레이터에서 확인. 웹 DOM 탐지기로 Kotlin 접근성을 검증했다고 주장하지 않음. | `.agents/skills/impeccable`, 엔진4.6.0 `.runtime/impeccable`; context 실행 확인. 자동 hook은 추가하지 않음 |
| [Taste Skill](https://github.com/Leonxlnx/taste-skill) | README가 앱에는 제품 UI용 변형을 권장. redesign-existing-projects의 기존 스택 유지·내용 위계·아이콘/상태 일관성 참고. 웹용 과장된 모션·사진·서체 규칙은 채택하지 않음. | `.agents/skills/redesign-skill`, `minimalist-skill`, `soft-skill` |

## 재현 가능한 출처

- Apple: `914b6a377c22755597e4405e54911b996de822d7`
- MYNT: `51b8e33f341f06b0265df836f525468c414ca405`
- Impeccable: `08d5b470495d242ca0470f4aeae1956c3a074f83`
- Taste: `f624f37a8c650b9cc550253104925efe4c8de246`

공식 skill-installer의 Git 방식으로 지정 commit의 스킬을 SpeechBridge 프로젝트에 설치했다. Apple 저장소는 루트 스킬의 sparse checkout이 하위 references를 빠뜨리는 것을 확인하고 전체 checkout으로 보완했다. 파일과 참조 존재를 검사했다. 설치된 스킬은 프로젝트에서 다음 턴부터 사용 가능하며 이번 턴에는 직접 읽었다. minimalist/soft는 사용 가능한 선택지로 설치했고 적용한 주요 지침은 Apple/Impeccable/redesign이다.

## 적용 방향과 범위

짙은 블루 #3D5395 + 옅은 블루그레이 #F1F3F9 + 흰 내용 영역. 다크 모드 #121722 바탕과 #1D2432 내용, #B6C7FF 동작. 한 계열 안에서 밝기를 조절해 내용과 제어를 구분한다. 황색은 검토, 적색은 오류·삭제와 문구를 함께 사용한다.

Impeccable context는 루트 PRODUCT.md 부재로 기존 docs/PRODUCT.md를 인식하지 못했다. 확인된 사용자 사실을 루트 PRODUCT.md에 연결해 정리했다. concept-seed도 실행했다(`.runtime/impeccable-direction.txt`). 제안된 기울어진 시간표/인쇄 전시판/행사 카탈로그/탑승권 문법은 모바일 음성·기록 앱의 명확성보다 장식을 늘리므로 채택하지 않았다. 사용자가 자율 리디자인을 지시했으므로 새 방향 승인·반복 인터뷰는 추가하지 않았다. 참고 자료를 혼합한 웹 스킨보다 네이티브 Operate 지침과 사용자 요구가 우선한다.

엔진은 프로젝트 내부 캐시에 설치했고 원격 telemetry는 `IMPECCABLE_NO_TELEMETRY=1`로 끈 상태에서 실행했다. 설치 자료는 개발 도구/참고용이며 Android/iOS 패키지 리소스에 포함되지 않는다. 무료 모델/도구 정책과 실제 음성·실기기 보류를 유지했다.
