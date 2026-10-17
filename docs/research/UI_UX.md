# 추가 유료 도구 없는 UI/UX 경로

2026-10-17. UI/UX 관련 질문도 이번 무료 대안 조사 범위에 포함했다. 별도 서비스 가입·유료 MCP·이미지 생성·디자인 업로드를 하지 않았다.

## 권고

**현재 설치된 ECC 설계 스킬 + Compose Multiplatform 실제 화면 검증**을 기본으로 한다. 편집 가능한 디자인 캔버스가 필요하면 **Penpot Free + 공식 MCP**를 추가한다. 별도 디자인 구독을 필수 의존성으로 만들 필요가 없다. 이 조합이 자동으로 디자인 품질 1위라는 주장은 아니며, 기존 KMP 코드와 무료 조건에 맞는 작업 방식이라는 판단이다.

스킬은 작업 절차이고 디자인 생성 모델 자체가 아니다. 스킬 파일이나 Penpot MCP의 무료 여부와 이를 실행하는 AI 클라이언트의 구독/사용량은 별개다. 현재 세션 외의 유료 AI 상품 구매를 제안하거나 호출하지 않는다.

## 설치된 스킬의 역할

| 역할 | 스킬 | 적용 방식 |
|---|---|---|
| 목적·기능·사용 흐름 | `ecc:product-capability`, `ecc:blueprint` | 번역/기록 독립 설정, 원문 수정→검토→행동 연결을 화면 계약으로 정리. 기존 설계에 사용됨 |
| 시각 방향·상태 | `ecc:frontend-design-direction` | 정보 위계·긴 번역·부분 결과·오류·권한·빈 상태를 정의. 웹 CSS 지시를 Compose의 테마와 컴포넌트로 옮겨 적용 |
| 디자인 시스템 | `ecc:design-system` | 색·서체·여백·컴포넌트 일관성. 기본 출력은 JSON/CSS/HTML 중심이므로 KMP에서는 `ColorScheme`·`Typography`·spacing으로 대응 |
| 실제 KMP 구현 | `ecc:compose-multiplatform-patterns` | 공통 Compose UI 및 플랫폼 경계. 웹용 React/Tailwind 출력을 그대로 붙이지 않음 |
| 접근성·마감 | `ecc:accessibility`와 실제 기기/시뮬레이터 검사 | 큰 글자·상태의 문자 표시·터치 영역·VoiceOver/TalkBack·긴 한일영 문구 확인 |

스킬 역할은 설치본 `SKILL.md`를 기준으로 한다. 특히 frontend-design-direction은 로컬 품질 확인에 외부 서비스가 필요 없고, 선택적 UIZZE paid MCP와 무료 스킬이 별개라고 명시한다. 이번 경로에는 해당 유료 MCP를 사용하지 않는다. 스킬 이름에 premium이 있다는 이유만으로 가격을 추정하거나, 설치되어 있다는 이유만으로 적용했다고 말하지 않는다.

설치본 근거: `frontend-design-direction` (개발 환경에 설치한 로컬 스킬), `design-system` (개발 환경에 설치한 로컬 스킬), `Compose` (개발 환경에 설치한 로컬 스킬).

## 디자인 도구 비교

| 경로 | 확인 사항 | 선택 |
|---|---|---|
| Compose에서 직접 설계·렌더링 | 현재 프로젝트로 Android/iOS 렌더링 가능. iOS accessibility는 [공식 문서](https://kotlinlang.org/docs/multiplatform/compose-ios-accessibility.html) 참고 | **기본**. 디자인→구현 변환 오차를 직접 확인. 실제 UI 평가를 생략하는 의미가 아님 |
| Penpot Professional Free | [공식 요금](https://penpot.app/pricing): $0, 파일 개수 제한 없이 저장 용량 범위 내 사용. 팀·스토리지·이력 조건은 플랜 표 확인 | **별도 캔버스 우선 후보**. 학생 인증 없이 무료 경로 검토 가능 |
| Penpot 공식 MCP | [공식 소개](https://penpot.app/ai/mcp-server)는 free/open 명시. [설치 문서](https://help.penpot.app/mcp/)는 원격/로컬 연결과 파일·토큰·컴포넌트 편집 설명 | 디자인 자동 편집 경로. 아직 연결하지 않았음. 모델 이용료까지 무료가 된다는 뜻은 아님 |
| Figma Starter | [가격](https://www.figma.com/pricing/), [Starter](https://help.figma.com/hc/en-us/articles/13838684089751-Starter-plan-overview): 무료이나 파일/AI/MCP 한도 있음. 현재 가격표 MCP20회/월 | 기존 Figma 사용 선호가 있을 때 후보. 무료 무제한 자동 편집으로 소개하지 않음 |
| Figma Education | [공식 교육 플랜](https://www.figma.com/education/): 자격 확인 학생·교육자에게 무료 교육 도구 | 수업 과제이므로 유력 대안이지만 사용자 인증 성공·자격은 미확인 |
| Figma MCP write | [공식 가이드](https://help.figma.com/hc/en-us/articles/39216419318551-Get-started-with-the-Figma-MCP-server): write는 Professional/Education/Organization/Enterprise, beta 무료 후 사용량 과금 예정 | Starter read 접근과 write를 혼동하지 않음. 미래 유료 기능을 필수로 채택하지 않음 |
| Google Stitch | [Google 공식 소개](https://developers.googleblog.com/en/stitch-a-new-way-to-design-uis/)에서 UI 시안·프론트엔드 코드 생성 기능 확인 | 추가 아이디어 탐색 후보. 현재 무료 한도·계정 접근 조건을 공식 공개 본문으로 충분히 확인하지 못했으므로 무제한 무료로 확정하지 않음 |

Penpot의 무료 코어와 저장/팀 제한을 혼동하지 않는다. self-host 소프트웨어가 무료여도 서버 자원은 필요하다. 현재 AI 모델과 같은 Mac에서 추가 디자인 서버를 상시 실행하기보다 직접 Compose 또는 무료 hosted 디자인 도구로 시작하는 것이 단순하다는 판단이다.

## SpeechBridge의 구체적 UX 기준

아래는 설계 제안이며 구현/사용성 검증 완료 주장이 아니다.

1. 시작 화면에서 번역과 기록을 각각 켜고 끈다. 여행 번역·일상 기록·외국어 회의 세 상황을 모두 표현한다.
2. 인식 중 문장은 임시 상태로, 확정 문장은 별도 표시한다. 번역 대기와 번역 실패를 빈 번역문으로 숨기지 않는다.
3. 확정된 번역만 TTS로 읽고 취소/끼어들기 동작을 명확히 한다. 빠른 것처럼 보이기 위해 미확정 잘못된 음성을 먼저 재생하지 않는다.
4. 정리 항목에서 원문 근거를 바로 열고 내용·담당자·기한을 수정한다. 수정된 원문과 오래된 항목은 재검토 상태를 표시한다.
5. 한국어·영어·일본어 긴 문장, 양쪽 화자 시야, 손쉬운 언어 전환, 큰 글자를 실제 화면으로 비교한다.
6. 서로 다른 시각 방향의 시안과 같은 사용 과제를 비교해 선택한다. 유료 템플릿이나 생성 도구를 사용했는지가 품질 기준이 아니다.

현재 UI는 첫 기능용 구현이다. 이 조사만으로 새로운 디자인 시스템·Penpot 시안·접근성 검증이 완료된 것은 아니다.
