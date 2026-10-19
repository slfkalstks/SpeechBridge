# 아키텍처 슬라이드 로고 출처

2026-10-19 확인. 기술 식별을 위한 공식 자산을 원래 색상과 비율로 사용했다. 각 로고의 권리는 해당 권리자에게 있으며 SpeechBridge와의 제휴를 뜻하지 않는다.

| 파일 | 공식 출처 | 원본 |
|---|---|---|
| `postgresql.png` | [PostgreSQL press kit](https://www.postgresql.org/about/press/presskit17/#images-and-logos) | [Elephant PNG](https://www.postgresql.org/media/img/about/press/elephant.png) |
| `nodejs.svg`, `nodejs.png` | [Node.js branding](https://nodejs.org/en/about/branding) | [Dark horizontal SVG](https://nodejs.org/static/logos/nodejsDark.svg). PPT 호환용 PNG도 함께 보관. |
| `kotlin-multiplatform.png` | [Kotlin brand assets](https://kotlinlang.org/docs/kotlin-brand-assets.html) | [공식 ZIP](https://resources.jetbrains.com/storage/products/kotlin/docs/kotlin_logos.zip)의 `Kotlin Ecosystem Logos/Kotlin Multiplatform/Primary/Dark/Kotlin Multiplatform Logo.png` |

## 구조도 확인 근거

- `shared/src/commonMain/kotlin/kc/ac/uc/speechbridge/AccountManager.kt`: 회원 API.
- `shared/src/commonMain/kotlin/kc/ac/uc/speechbridge/HttpVoiceApi.kt`: 음성 API.
- `server/server.mjs`: Node.js 내장 `node:http` 기반 HTTP API. 별도 FastAPI/Express 프레임워크 없이 라우팅한다. 요청은 JSON이며 일반 응답은 JSON, 음성 합성 응답은 WAV다.
- `server/tls-proxy.mjs`: HTTPS → 내부 HTTP 프록시.
- `server/local-provider.mjs`: Ollama `/api/chat`.
- `server/voice-provider.mjs`: NeMo-Speech.cpp 및 Python/MLX 로컬 프로세스.
- `server/db/`와 `server/package.json`: PostgreSQL, `pg`, RLS.
- `shared/src/iosMain/`와 `shared/src/androidMain/`: Keychain / Android Keystore.

슬라이드는 기능별 API 이름과 데이터 흐름을 표시하며 실제 주소는 생략한다. Kotlin 로고는 이전 시안용으로 보관하며 현재 슬라이드에는 사용하지 않는다.
