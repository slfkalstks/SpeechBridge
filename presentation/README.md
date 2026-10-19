# SpeechBridge 발표 자료

- **[SpeechBridge.pptx](SpeechBridge.pptx)**: 편집 가능한 16:9 PowerPoint, 10장, 슬라이드별 발표자 노트 포함.
- [발표자 노트](발표자-노트.md): 약 6–8분 설명과 라이브 시연 순서.
- [PDF](SpeechBridge.pdf): 글꼴과 배치를 보존한 미리보기.
- `assets/`: 실제 iOS 시뮬레이터에서 캡처한 6개 화면. 가상 시연 데이터만 사용했다.

앱의 블루 `#405394`와 밝은 배경 `#F2F3F9`를 사용했다. [Canva 피치덱 목록](https://www.canva.com/presentations/templates/pitch-deck/)과 [미리캔버스 PPT 목록](https://www.miricanvas.com/ko/template/all-types/ppt)의 간결한 제품 소개 구성을 참고했다. 외부 템플릿 파일·유료 요소를 복제하지 않고 새로 작성했다.

텍스트·도형은 편집 가능하며 앱 화면은 교체 가능한 PNG다. 사용 글꼴은 Apple SD Gothic Neo다. 다른 운영체제에서는 설치된 한글 글꼴로 바꾸거나 PDF를 사용한다. 글꼴 파일은 재배포하지 않는다. PowerPoint에서 일부 렌더링 차이는 있을 수 있다. 10장 모두 PDF로 렌더링해 한글·겹침·잘림을 확인했다.

## 재생성

저장소 루트에서:

```sh
npm --prefix presentation ci --ignore-scripts --cache .runtime/npm-cache
npm --prefix presentation run build
```

소스는 [`scripts/create-presentation.cjs`](../scripts/create-presentation.cjs)다. 고정된 PptxGenJS와 보안 패치된 image-size를 사용한다. 이 도구는 개발용이며 앱이나 서버 런타임에 포함되지 않는다. PDF는 PowerPoint/Keynote/LibreOffice에서 내보낼 수 있다.

원문 음성 인식 성공 화면을 만들어 넣지 않았다. 번역 사례는 실제 텍스트 입력/로컬 AI 출력이다. 실기기·마이크·청취 검증은 계속 보류다.
