---
version: alpha
name: SpeechBridge
description: "한·영·일 대화를 확인 가능한 기록과 할 일로 옮기는 모바일 작업 노트"
colors:
  primary: "#3D5395"
  onPrimary: "#FFFFFF"
  primaryContainer: "#DFE7FF"
  onPrimaryContainer: "#273A70"
  background: "#F1F3F9"
  surface: "#FFFFFF"
  ink: "#232A3A"
  muted: "#566176"
  outline: "#747F93"
  divider: "#DEE3ED"
  warning: "#765008"
  warningContainer: "#FFEBC3"
  danger: "#AB3038"
  translation: "#EBEFF9"
  darkPrimary: "#B6C7FF"
  darkOnPrimary: "#182B60"
  darkPrimaryContainer: "#304473"
  darkOnPrimaryContainer: "#DCE5FF"
  darkBackground: "#121722"
  darkSurface: "#1D2432"
  darkInk: "#E5E9F4"
  darkMuted: "#B0BBCE"
  darkOutline: "#8995AA"
  darkDivider: "#354052"
  darkWarning: "#FFD58C"
  darkWarningContainer: "#48361A"
  darkDanger: "#FFB3B9"
  darkTranslation: "#272F42"
typography:
  sans:
    fontFamily: "system-ui, sans-serif"
    fontSize: "17px"
    lineHeight: "27px"
  utility:
    fontFamily: "ui-monospace, monospace"
    fontSize: "12px"
    lineHeight: "18px"
rounded:
  DEFAULT: "16px"
  small: "12px"
  large: "24px"
spacing:
  page: "20px"
  panel: "20px"
  gap: "12px"
components:
  button: { height: "48px", backgroundColor: "{colors.primary}", textColor: "{colors.onPrimary}" }
  panel: { rounded: "16px", backgroundColor: "{colors.surface}", textColor: "{colors.ink}" }
  dialog: { rounded: "24px", backgroundColor: "{colors.surface}", textColor: "{colors.ink}" }
  badge: { backgroundColor: "{colors.primaryContainer}", textColor: "{colors.onPrimaryContainer}" }
  warning: { backgroundColor: "{colors.warningContainer}", textColor: "{colors.warning}" }
  translation: { backgroundColor: "{colors.translation}", textColor: "{colors.ink}" }
  helper: { backgroundColor: "{colors.surface}", textColor: "{colors.muted}" }
  error: { backgroundColor: "{colors.surface}", textColor: "{colors.danger}" }
  divider: { backgroundColor: "{colors.divider}" }
  darkCanvas: { backgroundColor: "{colors.darkBackground}", textColor: "{colors.darkInk}" }
  darkButton: { height: "48px", backgroundColor: "{colors.darkPrimary}", textColor: "{colors.darkOnPrimary}" }
  darkPanel: { rounded: "16px", backgroundColor: "{colors.darkSurface}", textColor: "{colors.darkInk}" }
  darkBadge: { backgroundColor: "{colors.darkPrimaryContainer}", textColor: "{colors.darkOnPrimaryContainer}" }
  darkWarning: { backgroundColor: "{colors.darkWarningContainer}", textColor: "{colors.darkWarning}" }
  darkTranslation: { backgroundColor: "{colors.darkTranslation}", textColor: "{colors.darkInk}" }
  darkHelper: { backgroundColor: "{colors.darkSurface}", textColor: "{colors.darkMuted}" }
  darkError: { backgroundColor: "{colors.darkSurface}", textColor: "{colors.darkDanger}" }
  darkControlOutline: { textColor: "{colors.darkOutline}" }
  darkDivider: { backgroundColor: "{colors.darkDivider}" }
---

# SpeechBridge 디자인 — Blue notebook

## Overview

음성으로 대화하고, 원문과 번역을 읽고, 기록을 할 일로 이어가는 한국어 모바일 앱. 2026-10-17 전체 리디자인은 이전 청록/백색 시각 규칙을 대체한다. 기능 계약은 PRODUCT.md와 docs/PRODUCT.md를 따른다.

이번 방향은 짙은 블루 동작, 옅은 블루그레이 캔버스, 내용별 불투명 표면이다. 중앙의 큰 마이크 하나에 시각적 강조를 집중한다. 번역은 원문과 같은 표면 안에서 색면을 달리해 한 쌍임을 드러낸다. 기록과 할 일은 가짜 통계나 장식 없이 실제 내용을 보여준다.

## Colors

BridgeDesign.kt의 BridgePalette/BridgeNightPalette가 런타임 기준이다. 문서 colors의 dark 접두사는 어두운 팔레트 매핑이다. MaterialTheme.colorScheme의 의미 역할로 소비하며 화면에서 팔레트 상수를 직접 쓰지 않는다. 시스템 밝기 설정에 따라 두 스킴이 바뀐다. 배경색 자동 추출/사용자 색상 선택 기능을 구현했다고 주장하지 않는다.

primary는 마이크와 선택/상호작용, primaryContainer는 선택 탐색 배경, surface는 읽는 내용, secondaryContainer(translation)는 번역 영역이다. 검토는 tertiary(황색 계열), 오류·삭제는 error(적색 계열)와 문구를 함께 쓴다. 비활성/포커스/눌림 피드백은 Material 컴포넌트가 제공한다.

scripts/check-design.py는 두 팔레트의 문서 일치와 실제 텍스트 색 조합을 검사한다. 실물 조명, True Tone, 모든 고대비 설정의 검증을 의미하지 않는다.

## Typography

한국어·영어·일본어 시스템 폰트. 앱의 일상 사용과 세 언어 가독성을 우선해 웹 디스플레이 서체를 추가하지 않는다. 화면 제목32/40sp Bold, 영역 제목25/34sp SemiBold, 상세 제목21/30sp SemiBold, 목록 제목17/25sp SemiBold, 본문17/27sp, 보조15/23sp, 설명13/20sp, 짧은 메타데이터12/18sp. 제목만 약한 음수 자간. 목록 제목과 미리보기만 생략 가능하며 상세 원문은 전체 줄바꿈한다.

## Layout

휴대폰은 Material 하단3탭, 넓은 화면은 rail. 대화 탭은 현재 대화/새 대화로, 종료된 기록 상세는 기록 선택 상태와 목록으로 돌아가기 버튼을 제공한다. 상단 설정 버튼은48dp. 공통 바탕과 하단 탐색의 표면을 구분한다.

대화 시작은 언어쌍 표면 → 큰 마이크 → 준비 상태·보조 키보드 진입. 실제 음성 연결 보류로 마이크는 안내 sheet만 연다. 가짜 파형·타이머·듣는 중 표시 없음. 높이460dp 미만 또는 fontScale>1.5에서는 마이크/상태/키보드를 가로 배치한다.

기록은 날짜별 제목 아래 내용 행, 할 일은 체크박스/내용/검토 상태/출처 링크. 전체·일상·업무는 한 종류의 선택 컨트롤, 상세 유형·폴더는 필요할 때 메뉴. 검색도 선택 시 표시한다. 설정은 연결 폼과 데이터 안내를 구분한다.

페이지 여백20dp, 내용 표면16dp radius, 내부20dp, 요소 간8/12/16/20dp. 최대 폭1200dp, 시작/설정 폼760dp. 큰 글자는 감싸기와 본문 스크롤을 사용한다. safeDrawingPadding과 imePadding을 유지한다.

## Elevation & Depth

배경과 내용의 톤 차이로 계층을 만든다. 내용 카드에 그림자·테두리를 겹치지 않는다. 모달/메뉴는 Material 기본 동작. 블러·유리·배경 사진·장식 애니메이션 없음. 웹용 슬로건/이미지/방향 후보를 앱 화면에 넣지 않는다.

## Shapes

작은 컨트롤12dp, 내용16dp, 언어쌍20dp, 입력24dp, 탐색28dp, 주 마이크44dp의 모서리. 크기가 다른 역할에 같은 둥근 값을 일괄 적용하지 않는다. 실제 터치 영역은 최소48dp.

## Components

BridgeTheme, AppSymbol/BridgeIcon, Panel, StatusBadge, InlineError가 공통 시각 소유자다. AppSymbol은24단위1.8두께의 단색 벡터. 유니코드 화살표·점문자 대신 의미 있는 아이콘을 사용한다. 모든 아이콘 버튼은 한국어 접근성 이름을 제공한다. 화면 제목/날짜 묶음은 heading semantics.

Material 버튼·필드·칩·탭·내비게이션·메뉴·대화상자·bottom sheet를 사용한다. 키보드 입력은 보조이며 사용자 선택 없이 포커스를 빼앗지 않는다. 번역/정리는 진행과 오류·재시도를 표시하고 오래된 결과를 보여주지 않는다. 삭제와 수정 내용 포기 확인을 유지한다.

## Do's and Don'ts

- 저장된 기록과 모델을 보존한다. 색상 변경이 도메인 규칙을 바꾸지 않는다.
- 완료는 체크와 취소선, 검토 필요는 문구로 함께 표시한다.
- 실제 마이크/실기기/접근성 청취를 검증한 것처럼 표현하지 않는다.
- 외부 디자인 저장소 코드·브랜드 자산을 앱에 복사하지 않는다. 형태·톤·계층 원칙을 자체 Compose 코드로 구현한다.
- 네이티브 검증은 docs/DESIGN_REFRESH_VALIDATION.md에 실제 확인 결과만 기록한다.

높이500dp 미만에서는 상단 제목을21sp로 축소하고 상하 여백을 줄인다. 보조 입력을 명시적으로 선택하면 입력 필드에 포커스를 둔다. dark 접두사 컴포넌트는 별도 UI가 아니라 동일 컴포넌트의 시스템 다크 모드 토큰 매핑이다.

### 언어와 설정 컨트롤 보완

언어 바는 좌측 언어/교환/우측 언어의 독립 버튼이다. 선택은 단일 bottom sheet 행에서 바로 적용한다. 화자 설정은 없다. 설정 심볼은 톱니바퀴이며 화면 모드 아이콘은 monitor/sun/moon을 순환한다. 라디오 버튼 목록과 설정 내 언어 선택, 일반 사용자의 서버 설정은 제거한다. 모드 값은 기기에 저장하고 일반 알림은 Material Short 수명으로 사라진다.
