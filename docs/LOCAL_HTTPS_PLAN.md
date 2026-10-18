# 휴대전화 테스트용 HTTPS 준비 검토

2026-10-18 조사. **아직 구현·실기기 검증 전**이다. 공개 도메인이 없는 현재 Mac 서버를 같은 LAN의 테스트 앱에서 사용하기 위한 후속 구현안이다. 다른 프로젝트·전역 인증서 저장소·공유 서비스 설정을 변경하지 않는다.

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

현재 코드에는 Android Debug의 localhost/에뮬레이터 HTTP 예외와 iOS 로컬 네트워킹 ATS 설정만 있다. 위의 앱 전용 CA·LAN HTTPS 프록시·설치 자동화는 아직 추가하지 않았다. Ktor의 인증서 핀 설정에서 신뢰 검증을 끄는 방식은 채택하지 않는다.
