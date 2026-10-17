# UI 참고 조사 — 2026-10-17

공식 소개용 화면은 현재 설치 앱과 버전이 다를 수 있다. 제품 동작 전체를 직접 사용·검증한 것은 아니다. 화면 이미지는 `validation/references/`에 내부 비교용으로 보관하며 앱 자산으로 사용하지 않는다.

| 출처 | 확인한 자료 | 적용 방향(설계 판단) |
|---|---|---|
| [Papago App Store](https://apps.apple.com/us/app/naver-papago-ai-translator/id1147874819) | Apple lookup의 공개 스크린샷: 언어 선택 한 줄, 큰 원문/번역 영역 | 시작 폼 제거, 언어쌍 선택, 큰 대화 본문 |
| [Google Translate 공식](https://translate.google.com/about/) | 공식 대화 스크린샷: 언어/자동 마이크, 원문과 번역 위계 | 언어와 입력 제어를 가까이, 자동 음성은 준비 상태로 명확히 표시 |
| [Apple 음성 메모](https://support.apple.com/guide/iphone/organize-recordings-iph73bfaba07/ios) | 즐겨찾기·폴더·자동 수집 폴더의 공식 설명 | 기록을 먼저 저장하고 나중에 폴더로 정리 |
| [Notta 공식 앱 안내](https://support.notta.ai/hc/en-us/articles/17604467003803-Introduction-about-Notta-App) / [Google Play](https://play.google.com/store/apps/details?id=com.langogo.transcribe) | 공식 화면의 폴더 선택, 검색, 날짜/제목 목록. Google Play 제품 등록 확인 | 행을 눌러 상세 열기, 검색/폴더는 필요할 때 표시 |
| [Mobbin](https://mobbin.com/) | 공개 페이지의 실제 앱 화면/흐름 라이브러리 소개 확인 | 개별 화면 장식보다 대화→종료→기록 흐름을 기준으로 비교. 로그인 뒤 상세 컬렉션은 확인하지 않음 |
| [Page Flows](https://pageflows.com/) / [Screenlane](https://screenlane.com/) | 검색으로 서비스 확인, 직접 접근은403 | 개별 디자인을 보았다고 간주하지 않음 |

새 UI의 선택: 3개 탭(대화·기록·할 일), 상단 설정, 흰 캔버스·청록 제어, 위계가 분명한 원문/번역, 줄 구분 목록, 더보기에서 보조 행동. 고정 업무 유형은 회의·할 일·아이디어·기타. 사용자 폴더는 별도 축으로 프로젝트/고객/팀 이름을 자유롭게 작성한다. AI는 기존 폴더만 고르며 임의 폴더를 생성하지 않는다.
