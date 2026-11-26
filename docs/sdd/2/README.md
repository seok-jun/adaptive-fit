# 개인용 첫 코칭 APK
[작업 #2](https://github.com/seok-jun/adaptive-fit/issues/2) · 2026-11-26 · [제품 결정](../../product-direction.md)

제품 기준 8e83ae42d396b5db0dccf0344f61f07554ad21ff. 이 revision에서 git ls-files 결과는 LICENSE와 README.md다. 현재 상태는 [01-as-is.md](01-as-is.md), 다음 설계 산출물은 02-to-be.md다. Windows에서 node --version은 v20.11.1, Android SDK platforms 디렉터리에는 android-36, android-37.0, android-37.1이 확인됐다. 개인 키의 기기 저장과 공급자별 전송 범위를 검토한 뒤 설치 빌드로 검증한다.
[목표와 검증 기준](02-to-be.md) · [구현 계약](change-plans/first-coach.md)
## 구현 검증 (2026-11-26)
제품 코드 ae69f5b0225c554cd7a6ae4fb27833a62ed81607, Android 버전 0.1.0(1). AS-IS 0fca69a 및 PLAN 86c1b54의 독립 A·B 리뷰 통과. 최종 코드 독립 리뷰 통과. 개발 리뷰 모델 GPT-6.2 Sol high.

- TypeScript 검사와 Jest 테스트 31개 통과. 양 공급자의 합성 응답, 화면의 전송 동의와 계획 승인, SQLite 저장 실패 복구를 확인했다.
- Android MockWebServer 테스트 통과. 307·408·503 응답 각각에서 전송된 POST는 한 건이었다.
- Android SDK 36으로 서명 APK 빌드 및 v2 서명 확인. 최소 API 24, arm64-v8a와 x86_64 포함. 패키지에 JS 번들과 SecureStore·SQLite 백업/기기 이전 제외 리소스를 확인했다.
- Android 16 에뮬레이터에서 개발 서버 없이 콜드 실행했다. 합성 키 등록·삭제, Claude 모델과 높은 추론 강도, 전송 취소, 프로세스 종료 후 선택 복원 및 OpenAI 전환을 확인했다. 최종 APK를 업데이트 설치하고 저장된 OpenAI 선택과 코칭 화면을 다시 확인했다.
- APK SHA-256: 2567e42bc6250b4f9e98a6728a91709e5577f297617287634add544e216e1327. 사용자 개인 휴대폰 및 유료 공급자 계정의 응답은 미검증이다. 자동 테스트에서는 합성 입력과 응답을 사용했다. 로컬 APK와 서명 키는 브랜치에 게시하지 않았다.