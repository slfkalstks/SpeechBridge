# Mac 서버와 회원 인증

2026-10-18. 별도 지시 전까지 현재 MacBook을 서버로 사용한다. 모델은 Mac에서만 추론한다. 유료 API, 게스트 인증, 모바일 LLM은 없다.

## 사용 흐름

1. 앱이 빌드에 지정된 서버 주소에 접속한다. 사용자에게 서버 주소나 공용 접속 토큰을 요구하지 않는다.
2. 설정 → 로그인 / 회원가입에서 아이디와 비밀번호를 입력한다. 아이디는 영문·숫자·밑줄 3~32자(소문자로 정규화), 비밀번호는 12~128자다.
3. 회원은 번역, 최초 대화 요약·제목·분류, 할 일 추출을 사용한다. 자동 요약을 사용하려면 대화를 종료하기 전에 로그인한다. 종료 후 AI 재정리 버튼은 제공하지 않는다.
4. 로그인은 기기에 안전하게 보관되어 앱 재시작 시 복원된다. 세션은 7일 후 만료되며 다시 로그인한다. 한 계정의 활성 로그인은 최대 5개이며 새 로그인 시 가장 오래된 세션부터 해제된다.
5. 로그아웃은 서버 세션을 폐기한 후 기기의 로그인 정보를 지운다. 서버에 접속할 수 없어 폐기를 확인하지 못하면 로그아웃 실패를 표시하며 재시도가 필요하다.

대화·폴더·할 일은 **기기 단위**로 보관한다. 로그인 계정별로 기록을 분리하거나 서버에 백업·동기화하지 않는다. 로그아웃 후에도 기존 기록을 열고 수동 편집할 수 있다. 계정 전환은 기록을 숨기는 기능이 아니다. 앱은 이 내용을 설정과 로그아웃 확인창에 표시한다. 현재 비밀번호 재설정, 이메일 인증, 계정 삭제 UI는 없다.

## 현재 Mac 실행

지원 Node 범위는 22.16 이상~26 미만이다. 현재 검증한 Node는 25.2.1이며, 내장 SQLite는 해당 버전에서 experimental 경고를 출력한다. 추가 npm 서버 의존성은 없다.

앱 루트에서 별도 터미널 두 개를 사용한다.

```sh
# AI 모델 런타임 (기존 모델·캐시 사용)
npm --prefix server run start:ai

# 회원 API
npm --prefix server run setup:accounts
npm --prefix server run start:accounts
```

`setup:accounts`는 `.runtime/accounts-server.env`를 권한 0600으로 만들며 기존 설정을 덮어쓰지 않는다. `AUTH_MODE=users`, Mac loopback 8787, 기존 Ollama 11435를 사용한다. 회원 DB는 기본 `.runtime/accounts.sqlite`다. `AUTH_DB_PATH`로 경로를 별도 지정할 수 있으며 상대 경로는 프로세스 작업 폴더 기준이다. DB와 환경 파일을 삭제하면 계정·로그인이 사라지므로 로컬 모델·기록과 함께 보존한다. 앱의 대화 기록은 이 DB에 저장하지 않는다.

서버 CLI는 기본 회원 인증을 사용하고 loopback 주소에만 바인딩한다. 외부 접속은 HTTPS 프록시/터널을 통해서만 구성한다. DB, 비밀번호 해시, 세션, 모델, 서버 환경 파일은 Git 제외다.

### 시뮬레이터 자동 연결

서비스 주소를 빌드에 지정하지 않은 **Debug** 앱은 다음 주소로 자동 접속한다.

| 환경 | Mac 주소 |
| --- | --- |
| iOS 시뮬레이터 | `http://localhost:8787` |
| Android 에뮬레이터 | `http://10.0.2.2:8787` |

서버를 실행하고 앱 설정에서 회원가입/로그인하면 된다. 개발 토큰 입력은 필요 없다. 이 주소는 실제 휴대전화용 주소가 아니다. iOS Keychain 검증에는 서명된 앱이 필요하다. Xcode의 기본 시뮬레이터 서명을 유지한다. `CODE_SIGNING_ALLOWED=NO` 빌드는 컴파일 확인용이며 로그인 저장 검증에는 사용할 수 없다.

## 휴대전화용 HTTPS 주소 지정

현재 도메인·고정 HTTPS 주소는 없다. 아래 연결 설정 코드는 구현했지만 실제 외부 운영이나 실기기 연결까지 완료한 것은 아니다.

Mac에 도달하는 **HTTPS origin**을 확보한 후 개발자가 빌드에 한 번 지정한다. 일반 사용자는 이 과정을 수행하지 않는다.

```sh
# 실제 운영 주소로 교체. 아래 주소는 예시이며 서비스가 아니다.
./gradlew :androidApp:assembleRelease -Pspeechbridge.serverUrl=https://speech.example.com

# Xcode도 같은 프로젝트 설정을 사용하게 하려면 로컬 ~/.gradle/gradle.properties에
# speechbridge.serverUrl=https://실제-주소 를 지정하거나 아래 환경 변수로 실행한다.
SPEECHBRIDGE_SERVER_URL=https://speech.example.com xcodebuild \
  -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Release
```

Gradle property가 환경 변수보다 우선한다. HTTPS가 아닌 주소, 사용자 정보·경로·쿼리·fragment가 있는 주소는 빌드 단계에서 거부한다. 빈 주소의 Release 앱은 로컬 호스트로 접속하지 않고 서비스 준비 상태를 표시한다. HTTP 허용·인증서 검증 해제를 Release에 추가하지 않는다. 로그인 정보는 저장 당시 origin에 묶이며, 다음 빌드가 다른 origin을 사용하면 이전 토큰을 보내지 않고 다시 로그인을 요구한다.

도메인이 없는 학교 시연에는 [Cloudflare Quick Tunnel](https://developers.cloudflare.com/tunnel/get-started/quick-tunnels/) 같은 임시 HTTPS 주소를 사용할 수 있다. 공식 `cloudflared` 설치 후 운영자가 `cloudflared tunnel --url http://127.0.0.1:8787`을 실행하고, 출력된 HTTPS 주소를 빌드에 지정하는 방식이다. 이 옵션은 이번에 설치·실행하거나 공개 노출하지 않았다. 임시 주소는 재실행 시 달라질 수 있고 가동 보장이 없으므로 스토어 배포용 고정 주소로 취급하지 않는다. Mac 추론은 그대로 유지되지만 터널 사업자가 HTTPS 중계를 담당하므로 사용자 대화가 그 경로를 통과한다. 장기 운영에는 안정된 HTTPS 주소와 Mac 가동·네트워크 유지가 필요하다.

## 인증 API

| 경로 | 입력 / 역할 |
| --- | --- |
| `POST /auth/register` | `{username,password}` → `{token,expiresAt,user}` |
| `POST /auth/login` | `{username,password}` → 같은 형태의 새 세션 |
| `GET /auth/me` | `Authorization: Bearer <개인 세션>` → `{user}` |
| `POST /auth/logout` | 인증 헤더와 `{}` → 현재 세션 폐기 |
| `GET /health` | 회원 모드에서 가벼운 공개 API 상태만 반환; 모델 준비 검사 아님 |
| `POST /translate`, `/organize`, `/extract` | 개인 세션 필수; 기존 텍스트 요청 계약 유지 |

회원 모드에서는 공용 `APP_ACCESS_TOKEN`으로 우회할 수 없다. `/auth/guest`는 없으며 404다. 모바일 음성 연결은 계속 보류이고 회원 API의 음성 경로도 준비 중 오류를 반환한다.

비밀번호는 무작위 salt와 scrypt(N=131072, r=8, p=1)로 해시한다. 이 매개변수는 [OWASP의 scrypt 권고](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html)를 따른다. 동시에 수행하는 비밀번호 해시는 2개로 제한한다. 세션은 암호학적 난수 32바이트이며 서버에는 SHA-256 해시만 저장한다. SQL은 바인딩 파라미터를 사용한다. 원문·비밀번호·토큰을 서버 로그에 출력하지 않는다.

요청 제한은 회원당 AI 30회/분, 전체 POST 60회/분·동시 6개, 인증 경로는 연결 IP당 30회/15분, 유효한 아이디의 로그인/가입 시도는 10회/15분이다. `X-Forwarded-For`를 신뢰하지 않으므로 프록시를 쓰면 IP 제한은 프록시 사용자 전체에 적용된다. 이는 현재 단일 Mac·학교 시연용 보수적 설정이며 대규모 서비스 운영 검증을 의미하지 않는다.

Android는 [Android Keystore](https://developer.android.com/privacy-and-security/cryptography)의 AES-256-GCM 키로 세션을 암호화해 앱 전용 preferences에 저장하고, iOS는 [Keychain](https://developer.apple.com/documentation/security/restricting-keychain-item-accessibility)의 `WhenUnlockedThisDeviceOnly` 접근성을 사용한다. 비밀번호는 보관하지 않는다. 로그인 정보를 대화 JSON이나 일반 앱 설정에 기록하지 않는다.

## 기존 개발 토큰 모드

기존 평가 도구와 음성 엔진 검증을 위해 `npm run setup:local` / `npm run start:local`은 명시적 `AUTH_MODE=development`를 사용한다. Debug 더보기 메뉴의 주소·토큰 입력은 이 경로에 한정한다. 회원 서버와 같은 8787 포트를 동시에 열 수 없다. 개발 모드는 일반 사용자 배포 경로가 아니다.
