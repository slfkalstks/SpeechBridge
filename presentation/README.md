# SpeechBridge 발표 자료

- **[SpeechBridge.pptx](SpeechBridge.pptx)**: 편집 가능한 16:9 PowerPoint, 7장, 슬라이드별 발표자 노트 포함.
- [발표자 노트](발표자-노트.md): 설명과 라이브 시연을 합쳐 약 5–7분.
- [PDF](SpeechBridge.pdf): 글꼴과 배치를 보존한 미리보기.
- `assets/`: 실제 iOS 시뮬레이터에서 캡처한 6개 화면. 가상 시연 데이터만 사용했다. 2026-10-19에 포인터를 앱 영역 밖으로 옮긴 뒤 6개 화면 모두 다시 촬영했다. 원본 캡처를 보존하고 PPTX의 자르기 영역과 README용 SVG 표시 영역으로 상단 시뮬레이터 도구막대만 제외한다. 기기 테두리·시간·배터리는 유지한다. 기록 상세 간소화 후 번역·요약·할 일·내용 확인 화면 4개도 새 앱에서 다시 촬영했다.

AI 모델은 5장 한 장에 이름과 역할만 표시합니다. 선정 이유·모델 크기·양자화·실행 엔진 등의 상세 설명은 발표에서 제외했습니다. 발표 슬라이드와 발표자 노트에는 모델 간 비교·개선 전후 수치를 넣지 않습니다. 상세 선정 근거는 [개발 문서](../docs/MODEL_SELECTION.md)에 별도로 보관합니다.

앱의 블루 `#405394`와 밝은 배경 `#F2F3F9`를 사용했다. [Canva 피치덱 목록](https://www.canva.com/presentations/templates/pitch-deck/)과 [미리캔버스 PPT 목록](https://www.miricanvas.com/ko/template/all-types/ppt)의 간결한 제품 소개 구성을 참고했다. 외부 템플릿 파일·유료 요소를 복제하지 않고 새로 작성했다.

앱 화면은 기존 크기·비율을 유지했습니다. 구성은 소개 → 번역 → 기록·요약 → 할 일 → AI 역할 → 시스템·API → 시연입니다. 시스템 구조와 API 흐름은 모바일 앱 → 서버의 API → 서버 내부 AI·DB가 연결되는 한 장으로 표현했습니다. 예시 자료에서는 큰 구성 요소·짧은 이름·연결 화살표의 표현 방식만 참고했습니다. API 주소·앱 개발 환경·설명 문장은 넣지 않았습니다. 로고 출처와 코드 확인 근거는 [자산 출처](assets/brands/SOURCES.md)에 기록했습니다. 설명 위주의 중복 슬라이드는 제외하고 발표자 노트도 짧게 정리했습니다. 제목의 반복과 어색한 표현을 줄이고, 발표할 때 읽기 쉽도록 긴 문장을 나눴습니다.

텍스트·도형은 편집 가능하며 앱 화면은 교체 가능한 PNG다. 사용 글꼴은 Apple SD Gothic Neo다. 다른 운영체제에서는 설치된 한글 글꼴로 바꾸거나 PDF를 사용한다. 글꼴 파일은 재배포하지 않는다. PowerPoint에서 일부 렌더링 차이는 있을 수 있다. 7장 모두 PDF로 렌더링해 한글·겹침·잘림을 확인했다.

## 재생성

저장소 루트에서:

```sh
npm --prefix presentation ci --ignore-scripts --cache .runtime/npm-cache
npm --prefix presentation run build
```

소스는 [`scripts/create-presentation.cjs`](../scripts/create-presentation.cjs)다. 고정된 PptxGenJS와 보안 패치된 image-size를 사용한다. 이 도구는 개발용이며 앱이나 서버 런타임에 포함되지 않는다. PDF는 PowerPoint/Keynote/LibreOffice에서 내보낼 수 있다.

원문 음성 인식 성공 화면을 만들어 넣지 않았다. 번역 사례는 실제 텍스트 입력/로컬 AI 출력이다. 실기기·마이크·청취 검증은 계속 보류다.
