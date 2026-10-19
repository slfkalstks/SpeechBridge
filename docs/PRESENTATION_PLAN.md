# 발표자료 제작 기준

2026-10-19 완료: [편집 가능한 10장 PPTX](../presentation/SpeechBridge.pptx), [발표자 노트](../presentation/발표자-노트.md), [실제 화면과 재생성 방법](../presentation/README.md). 아래는 초기 구성 계획이다.

사용자 지시: 최종 구현 후 PPTX 제작. 핵심만 간결하게, 실제 앱 화면 필수. Canva·미리캔버스 등 템플릿을 조사하며 무료 이용 조건에 맞는 템플릿 수정도 가능하다.

## 구성안

1. SpeechBridge — 음성 대화를 번역과 실행할 일로
2. 사용 흐름 — 말하기 → 번역 → 기록 → 할 일
3. 실제 대화 화면 — 음성 입력과 번역 결과를 크게 배치
4. 실제 기록 화면 — 자동 제목·요약·일상/업무 분류
5. 실제 할 일 화면 — 근거 확인과 완료 처리
6. 시스템 — 휴대전화 / Mac API·로컬 AI / PostgreSQL 관계
7. 데이터 — 회원별 보관, 암호화 전송 대기, 재연결
8. 시연 — 하나의 짧은 가상 대화가 기록과 할 일로 이어지는 화면

슬라이드 수는 최종 화면에 맞춰 조정한다. 앱의 블루·밝은 배경·다크 모드 색상을 활용한다. 한 장에 한 메시지를 두고 화면 캡처가 주인공이 되게 한다. 사용자 계정·토큰·개인 기록 대신 명시적으로 만든 가상 시연 데이터를 캡처한다. 구현되지 않은 UI를 완성 화면으로 그려 넣지 않는다.

## 조사 출발점 (2026-10-18)

- [Canva 프레젠테이션 템플릿](https://www.canva.com/presentations/templates/)
- [Canva 피치덱 템플릿](https://www.canva.com/presentations/templates/pitch-deck/)
- [미리캔버스 템플릿](https://www.miricanvas.com/ko/templates?from=gnb)
- [미리캔버스 PPT 템플릿](https://www.miricanvas.com/ko/template/all-types/ppt)
- [미리캔버스 PPTX 가져오기 안내](https://blog.miricanvas.com/pptx-import-launch)

최종 제작은 외부 템플릿 파일을 복제하지 않고 앱의 팔레트로 새로 구성했다. 실제 앱 화면 6개를 사용했으며 10장 전체의 한글·배치를 PDF 렌더링으로 검수했다. 최종 원본은 사용자 요청대로 PPTX다.
