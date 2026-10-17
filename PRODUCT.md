# SpeechBridge

<!-- impeccable:product-schema 1 -->

## Platform
adaptive

Kotlin Multiplatform, Compose Multiplatform on Android and iOS.

## Confirmed product context
Canonical detailed product contract: [docs/PRODUCT.md](docs/PRODUCT.md).
Korean-language mobile conversation translator and private local record/task app for a course project.
Voice is the central interaction; keyboard is secondary. Actual microphone integration is explicitly deferred by the user. The UI must disclose this, never simulate live listening.
All conversations save automatically. On ending, free models running on the user's Mac generate title and classification. Daily/work categories, fixed work types and user-created work folders are supported. AI tasks require evidence review.
Existing records, models and local settings must be preserved. No paid API, phone LLM, production deployment, physical-device validation or new voice integration in this task.

## Current request
2026-10-17: redesign all screens using apple-design-skill, MaterialYouNewTab, Impeccable and Taste Skill; install useful design skills autonomously. User also explicitly requests consideration of app colors. Existing factual copy and functional contracts remain authoritative; old visual styling may be replaced.

## Evidence and limits
See docs/LANGUAGE_SETTINGS_VALIDATION.md and docs/REDESIGN_VALIDATION.md for prior verified behavior. New visual validation is tracked separately in plans/design-system-refresh.md. Simulator findings are not physical-device evidence.
