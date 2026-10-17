# UX Contract

## Product context

한국어 UI의 모바일 대화 작업 도구. 한·영·일 입력을 지원한다. 일본 시장 전용 업무/주소/결제/법률 흐름 없음. AI 결과는 사용자가 확인한다. 시간은 저장된 ISO 시각을 기기 로컬 날짜로 표시하며 상대 기한은 원문 그대로 유지한다. 접근성 목표는 충분한 대비,48dp 터치 영역,큰 글자에서 재배치,키보드·스크린리더 의미 제공이며 인증/전면 적합성 주장을 하지 않는다.

## Business-context sources

`docs/PRODUCT.md`, `Conversation.kt`의 저장/검토/revision 불변 규칙, `BridgeViewModel.kt`의 요청 무효화, `docs/API_SETUP.md`의 인증/오류 계약. 현재 범위는 `plans/design-system-refresh.md`와 `plans/conversation-redesign.md`. 저장 정책이나 자동 할 일 확정 정책을 바꾸지 않는다.

## Visual contract

`DESIGN.md`와 `BridgeDesign.kt` → MaterialTheme → 공통 Panel/Heading/StatusBadge/SnackbarHost/BridgeIcon. 런타임 팔레트가 값의 주인이다. `scripts/check-design.py`로 드리프트/대비 확인. 기기 설정/라이트/다크 모드는 하나의 아이콘으로 순환 선택하며 Archive에 저장한다.

## Canonical UI Map

| Capability | Canonical owner | Source of truth | Allowed variants | Verification |
|---|---|---|---|---|
| Select/Listbox | LanguageBar + ModalBottomSheet | DESIGN + 이 계약 | 좌우 독립 언어 선택·가운데 즉시 교환 | 작은 화면·큰 글자·키보드 |
| Date | displayDate | 저장 ISO 시각/기기 로컬 시간 | 날짜만, 원문 기한 문자열 | 날짜 경계 테스트 |
| Form | Material3 OutlinedTextField + 공통 편집 대화상자 | 이 계약 | 시작/발언/항목/연결 | 입력 보존·유효성 검사 |
| Scrollbar | Compose LazyColumn/verticalScroll | 플랫폼 UI 계약 | 모바일 touch 및 키보드 scrolling | 긴 내용·키보드 |
| Toast | SnackbarHost + BridgeState.message | 이 계약 | 일시 안내와 항목별 오류 | Short 자동 닫힘 + 닫기 |
| CRUD | BridgeViewModel/Conversation + ConfirmActionDialog | PRODUCT/이 계약 | 세션/발언/정리 항목 | 공통 테스트·앱 흐름 |

웹 CSS/ARIA/URL query는 적용 대상이 아니다. Compose semantics, 네이티브 텍스트 입력/Material 대화상자를 사용한다. 새 웹 UI나 중복 컴포넌트 계층을 만들지 않는다.

## Dataset navigation

기록은 로컬 제목/발언 검색, 할 일은 검토/진행/완료 필터와 내용/담당/기한 검색. 전체 데이터는 기존 저장소에 있고 LazyColumn이 화면 항목을 지연 구성한다. 서버 pagination은 없음. 검색·필터·스크롤과 작성 초안은 메모리만 사용하며 앱 프로세스 종료 시 제거한다. 검색은 TextFieldValue.composition이 끝난 내용에 적용하며 clear는 즉시 처리하고 필드로 포커스를 반환한다. 화면 상태 복원으로 비공개 초안을 저장하지 않는다.

## Flow ledger

| Operation | Trigger | Pending | Success destination | Success feedback | Failure recovery |
|---|---|---|---|---|---|
| Create | 대화 시작/입력 추가 | 중복 클릭 제한 | 현재 대화 | 추가된 발언·저장 상태 | 초안 유지 |
| Edit | 수정 저장 | 기기 저장 대기 | 원래 화면 | 새 원문·재검토 표시 | 미저장 배너·재시도 |
| Delete | 이름/영향을 읽고 삭제 | 기기 저장 대기 | 같은 목록 | 항목 제거·저장 상태 | 메모리 상태 유지·저장 재시도 |
| Search | 입력/필터 | 로컬 계산 | 같은 목록 | 결과 개수/검색 없음 | 초기화 |
| Translate/extract | 명시적 요청 또는 발언 추가/대화 종료 | 항목별 진행/취소 | 같은 대화 | 번역/초안, 자동 확정 없음 | 항목별 오류·재시도 |
| Cancel/back | 취소/화면 이동 | 필요시 변경 확인 | 원래 화면 | 원문 유지 | 발언/시작 초안 메모리 유지 |

## Navigation and responsive behavior

종료한 대화 화면 내부에 대화/요약 탭. 진행 중 대화는 하나. 저장된 기록을 연 뒤 새 대화 또는 진행 중 대화로 돌아갈 수 있다. 넓은 화면은 rail과 폭이 제한된 내용 영역. 전체 발언/근거는 상세에서 줄바꿈한다. 입력창은 IME 확정 Enter로 전송하지 않고 명시적 추가 버튼으로만 전송한다.

## Overlays and feedback

Material AlertDialog를 공통으로 사용한다. 삭제는 즉시 영구 삭제하는 기존 저장 정책이며 되돌리기를 가장하지 않는다. 사용자 소유 데이터의 실제 테스트 삭제는 하지 않는다. 변경된 수정 폼을 닫을 때는 버릴지 확인한다. 새 발언 폼은 닫아도 ViewModel 초안이 남는다. 연결 폼의 미적용 값도 이번 앱 실행 메모리에만 남는다. 토큰은 기본 마스킹, 명시적 보기/숨기기, 화면 이탈 시 다시 마스킹한다. SnackbarHost는 Short 자동 닫힘(접근성 권장 시간 적용)이며 중요한 실패는 원래 항목에도 남는다.

## Async and resilience

로컬 변경은 메모리에 적용 후 직렬/원자 저장하고 미저장·재시도를 제공한다. 읽기 실패는 기존 파일 보호를 위해 편집 잠금. 요청별 오류를 보존하고 성공/재시도 시 해당 오류만 지운다. 변경/삭제/종료/연결 변경은 이전 요청을 무효화한다. 중복 요청은 취소·sequence로 늦은 응답을 무시한다. 초안은 navigation에는 유지하고, 종료 확인 시 작성 중 문장을 기록에 포함한다. 삭제/프로세스 재시작 시 초안은 폐기한다. 토큰/초안은 ArchiveCodec에 전달하지 않는다.

## Validation

원문/내용 공백 금지, 번역 두 언어 다름, HTTPS 또는 승인된 로컬 개발 주소. 서버 입력 계약을 넘는 입력은 오류로 안내하고 기존 내용을 보존한다. URL/토큰 오류는 필드와 연결되며 비밀값은 로그·배너에 넣지 않는다. 담당자·기한을 자동 추측하지 않는다.

## Verification

Node 테스트, Kotlin 공통 테스트, Android/iOS 빌드, `scripts/check-design.py`, designmd lint, premium strict static audit. Android/iOS 직접 UI 확인, 큰 글자/넓은 화면/긴 한국어·일본어, 빈 목록/검색 없음/오프라인/토큰 오류/취소/복원 확인. 이번 결과는 `docs/DESIGN_REFRESH_VALIDATION.md`, 이전 기능 검증은 `docs/REDESIGN_VALIDATION.md`에 기록한다. 공식 semantics 근거: https://developer.android.com/develop/ui/compose/accessibility/semantics 및 https://kotlinlang.org/docs/multiplatform/compose-ios-accessibility.html . 시뮬레이터와 실기기·자동검사와 스크린리더 청취는 구분한다.

## 2026-10-17 사용자 요청에 따른 계약 변경 (위 기존 정책보다 우선)

새 대화는 항상 저장한다. 제목 입력 없이 시작하고 종료 후 원문을 로컬 AI에 보내 간단한 제목과 일상/업무 분류를 생성한다. 실패해도 날짜 제목으로 원문을 보존하고 직접 수정 가능. 제목·분류·요약 자동 생성은 최초 한 번만 시도한다. 업무 고정 유형(회의/할 일/아이디어/기타)과 사용자 폴더는 별도 축. 기록과 할 일은 동일한 분류 필터를 사용한다. 폴더 삭제는 기록을 삭제하지 않고 소속만 해제한다. AI 응답은 원문 revision과 메타데이터 revision이 일치할 때만 적용한다. 수동 수정·폴더 변경을 늦은 AI 응답이 덮어쓰지 않는다.

음성/언어 자동감지는 이번에 UI만 준비한다. 실제 청취/권한/파형/녹음 표시를 만들지 않는다. 언어는 수동 설정, 텍스트 입력은 선택한 언어로 자동 번역한다. 준비 버튼은 설명을 열며 동작하지 않는 것을 분명히 표시한다. 기록은 확정된 텍스트를 뜻하며 미구현 원음 녹음을 뜻하지 않는다. 종료 시 작성 중 텍스트가 있으면 종료 확인에서 기록 포함 여부를 안내한다.

추가 사용자 지시: 음성 우선 화면. 키보드는 보조 입력으로 접어 두며 선택했을 때만 직접 입력창을 연다. 음성 버튼 준비 상태와 안내는 실제 청취/저장 상태로 가장하지 않는다.

업무 세부 유형·폴더 선택은 ‘업무 분류’ 대화상자로 접는다. 할 일 상태는 단일 메뉴다. 기록과 할 일의 categoryFilter는 공유하며 상태/검색은 각 목록에 따로 유지한다.

## 2026-10-17 전체 시각 교체

새 DESIGN.md가 이전 청록색/백색 스타일을 대체한다. 음성 준비 안내는 Material bottom sheet. 기록은 날짜별 묶음, 완료 할 일은 체크와 취소선. 대화 탭은 현재 진행 중인 대화 또는 새 대화 화면으로 이동하고, 종료된 기록 상세에서는 기록 탭을 선택 표시한다. 상세 상단에서 기록 목록으로 돌아갈 수 있다. 키보드 입력 선택은 필드 포커스를 요청한다. 제목/저장/분류/검토 규칙과 데이터 포맷은 변경하지 않는다.

## 2026-10-17 언어·화자·설정 보완 (최신 사용자 지시)

- 화자 입력·화자 이름 표시는 제거. 언어는 사람의 신원이 아니다. 기존 speaker 필드는 읽기 호환만 보존하고 새 화면 경로는 빈 값을 저장한다.
- 왼쪽 언어 버튼은 입력 언어, 오른쪽은 번역 언어. 선택 즉시 닫힘, 별도 적용 없음. 가운데 48dp 버튼은 현재 언어쌍 즉시 교환. 반대편 언어를 선택해도 쌍을 교환해 동일 언어쌍을 방지한다.
- 입력 초안의 텍스트는 보존하며 언어는 상단 입력 언어와 일치한다. 단순 쌍 교환 시 기존 번역·요청 유지; 실제 번역 대상이 바뀌는 발언만 이전 응답 무효화하고 재번역.
- 대화 종료 후 제목·분류·요약을 최초 한 번 생성. 요청 시도 여부를 저장한다. 이후 직접 편집만 가능하고 늦은 AI 응답은 수동 편집을 덮어쓰지 않는다. 기존 기록에 AI를 소급 실행하지 않는다.
- 설정에는 회원 계정, 화면 모드 아이콘 3개(기기 설정·라이트·다크 가로 나열 및 선택 강조), 업무 폴더, 저장 상태를 표시. 설정의 대화 언어는 제거. 개발 빌드에서만 더보기→개발자 연결 설정을 열 수 있다.
- 회원가입·로그인·개인 세션을 구현했다. Release는 빌드에 지정한 HTTPS origin으로 자동 연결하며 주소 미지정 시 service_pending이다. Debug는 시뮬레이터의 Mac 주소를 자동 사용한다. 게스트는 제공하지 않는다. 기록은 기기 단위이며 계정별 분리·동기화는 없다. 보안 저장소·세션 만료·로그아웃·현재 한계는 `docs/ACCOUNT_SERVER.md`를 따른다. 외부 HTTPS 운영과 실기기는 미검증이다.
