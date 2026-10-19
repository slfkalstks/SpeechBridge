# Release 설정 검증 근거

2026-10-19: 운영 주소 없이 `:androidApp:verifyProductionTransport`를 실행해 BUILD SUCCESSFUL을 재현했다. 주소가 빈 Release는 로그인할 수 없으므로 명시적 HTTPS 주소를 요구하도록 바꾼다. `.runtime/demo/release-config-red.log`.

실제 운영 주소가 아직 없어 컴파일 검증에는 예약된 `https://speechbridge.invalid`를 사용한다. 이는 서비스 가능한 출시 파일이 아니며 시연용 Debug 빌드와 분리한다.

GREEN: 주소 없는 Release는 명시적 오류로 실패했다. `https://speechbridge.invalid`를 지정한 Android Release AAB와 iOS 기기용 Release archive가 빌드됐다. 테스트 CA·개인 키·env 리소스가 AAB에 없음을 검사했다. 이 예약 주소는 실제 접속 대상이 아니며 archive에는 배포 서명이 없다. 실제 출시에 사용할 산출물로 오인하지 않는다.
