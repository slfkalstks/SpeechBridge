# Release 설정 검증 근거

2026-10-19: 운영 주소 없이 `:androidApp:verifyProductionTransport`를 실행해 BUILD SUCCESSFUL을 재현했다. 주소가 빈 Release는 로그인할 수 없으므로 명시적 HTTPS 주소를 요구하도록 바꾼다. `.runtime/demo/release-config-red.log`.

실제 운영 주소가 아직 없어 컴파일 검증에는 예약된 `https://speechbridge.invalid`를 사용한다. 이는 서비스 가능한 출시 파일이 아니며 시연용 Debug 빌드와 분리한다.
