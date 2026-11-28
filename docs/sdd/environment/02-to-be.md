# 동작과 수용 기준
제품의 첫 실행 대상을 Android로 설정한다. 현재 설치된 SDK 36, JDK 17 및 Gradle을 사용하면 한 앱 모듈의 빌드부터 기기 실행까지 검사할 수 있다. Java와 Android 기본 Activity를 써서 첫 화면에는 앱 이름만 표시한다. 카메라·네트워크·저장 권한은 이 환경 작업에 필요하지 않다.
- 빌드 재현: 고정 Gradle wrapper, 플러그인과 잠금 파일로 새 체크아웃에서 debug APK 및 release AAB를 생성한다.
- 코드와 테스트: Java 컴파일, debug 단위 테스트와 Android lint를 실행한다. 테스트 범위는 생성된 빌드 설정이다.
- 실행: 전용 API 36 에뮬레이터에 debug APK를 설치하고 시작 Activity와 화면에 보이는 앱 이름을 확인한다.
- Independent Review: NOT REQUIRED. 단일 빈 앱의 환경 설정이며 기존 모듈 간 통합이나 공유 제품 계약 변경이 없다. 최종 변경을 자체 검토하고 빌드와 기기 검증 근거를 남긴다.
