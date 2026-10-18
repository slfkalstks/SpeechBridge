# 휴대전화 테스트용 HTTPS 준비 검토

2026-10-18 구현·시뮬레이터 확인 완료. 공개 도메인이 없는 현재 Mac 서버를 같은 LAN의 Debug 앱에서 사용한다. **실제 휴대전화 검증은 아직 수행하지 않았다.** 다른 프로젝트·전역 인증서 저장소·공유 서비스 설정을 변경하지 않는다. 실행·설치 절차는 [시연 안내](DEMO.md)를 따른다.

## 확인한 공식 동작

- Android는 Network Security Configuration의 `debug-overrides`로 debuggable 앱에만 별도 CA를 신뢰시킬 수 있다. Release에서는 이 항목을 무시한다. [Android 공식 문서](https://developer.android.com/privacy-and-security/security-config)
- Ktor의 TLS 설정은 엔진별로 다르다. iOS Darwin 엔진은 NSURLSession을 사용하고 인증 challenge 처리 지점을 제공한다. [Ktor SSL](https://ktor.io/docs/client-ssl.html), [엔진 설명](https://ktor.io/docs/client-engines.html)
- Apple은 서버 인증 challenge를 처리할 때 서버 신뢰를 평가하도록 설명한다. `SecTrustSetAnchorCertificates`로 앱의 신뢰 루트를 제공하고 `SecTrustEvaluateWithError`로 인증서 체인·정책을 검증할 수 있다. [서버 신뢰 인증](https://developer.apple.com/documentation/foundation/performing-manual-server-trust-authentication), [신뢰 평가](https://developer.apple.com/documentation/security/sectrustevaluatewitherror(_:_:))

## 구현 방향과 확인할 조건

1. `.runtime/tls`에 프로젝트 전용 CA·서버 인증서·개인 키를 생성한다. 개인 키는 파일 권한600으로 보관하고 앱·Git에 넣지 않는다. 서버 인증서 SAN에 사용할 LAN 주소를 넣는다.
2. 프로젝트의 Node HTTPS 프록시가 선택된 LAN 주소에서 TLS를 종료하고 고정된 localhost 회원 API로 전달한다. 현재 API·PostgreSQL·Ollama의 loopback/Unix socket 설정을 유지한다.
3. 테스트 빌드에 HTTPS 주소와 공개 CA만 적용한다. Android는 Debug 리소스, iOS는 Debug 바이너리에서 지정된 테스트 호스트에만 적용한다. 호스트·유효기간·체인 검증을 유지하며 모든 인증서를 허용하는 코드를 만들지 않는다.
4. 올바른 CA 연결 성공, 다른 CA·호스트 거부, Release에서 테스트 신뢰 경로 비활성화, 리다이렉트 차단·회원 API·DB 저장을 검증한다. iOS 로컬 네트워크 권한 안내와 Android/iOS 테스트 설치 절차를 함께 준비한다.
5. Wi-Fi 변경에 따른 주소 변경·인증서 갱신·앱 재빌드 절차를 제공한다. 고정 공개 주소를 확보하는 운영 배포와는 구분한다.

## 구현과 검증 결과

- `server/local-tls.mjs`: 프로젝트 `.runtime/tls`에 CA365일·서버 인증서30일 생성, RSA/SHA256, 파일600·폴더700. 심볼릭 링크 경계·동시 생성 잠금·기존 키와 인증서 일치 확인. CA를 조용히 교체하지 않는다.
- `server/tls-proxy.mjs`: 선택한 LAN IPv4:8443에서 TLS1.2 이상, 고정127.0.0.1:8787로 전달. 요청2MB 제한, CONNECT·절대주소 거부, 자격증명·본문 로그 없음. DB·AI를 LAN에 노출하지 않는다.
- `-Pspeechbridge.localTls=true`: 공개 주소·CA 일치 확인 후 Debug 앱에 포함. Android Network Security Configuration과 iOS Darwin challenge에서 프로젝트 CA를 사용한다. iOS는 호스트·서명·유효기간 검사를 유지한다. Release 작업은 해당 플래그를 거부한다.
- Node TLS 테스트6개 통과. iOS Security.framework에서 올바른 CA 성공·다른 호스트/만료/손상 CA 거부 확인. Kotlin의 standalone simulator 실행은 신뢰 서비스 오류(-26276)를 반환했으나 동일 바이너리를 booted simulator에서 실행하면 통과했다. Gradle 테스트도 `standalone=false`, `device=booted`로 수정했다. 필요하면 `-Pspeechbridge.simulatorId=UDID`로 실행 중인 기기를 지정한다.
- Android APK에 예상 공개 DER만 포함되고 PEM/개인 키 파일이 없는지 확인. Release 보호 작업의 의도된 실패 확인. 정상 Release 전체 빌드와 실제 기기 TLS는 별도 검증 대상이다.
- 실제 iPhone 시뮬레이터에서 해당 HTTPS 빌드로 로그인·DB 예시 조회·대화 종료·AI 제목/분류/요약·결정 추출을 확인했다. Mac 시스템 인증서 저장소에는 추가하지 않았다.
