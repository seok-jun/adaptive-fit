# Adaptive Fit

개인 Android 휴대폰에서 Claude 또는 OpenAI로 운동 코칭을 받는 Expo 앱이다. 개인 키 등록 → 공급자·모델·추론 강도 선택 → 목표·시간·기구 확인 → 전송 → 제안 승인 순으로 사용한다. 과금은 사용자가 선택한 공급자 계정에 발생한다. 키는 앱 설정에서만 입력한다. 기기에 저장된 마지막 승인 계획은 오프라인에서도 열 수 있다.

## 개발과 검증

Node 22.19 이상, Expo SDK 54, Android SDK 36, JDK 17을 사용한다. 저장소 루트에서 다음을 실행한다.

```powershell
npm ci --legacy-peer-deps
npm run typecheck
npm test
npx expo prebuild --platform android --no-install
```

Windows 로컬 APK 빌드에는 Android SDK의 플랫폼·빌드 도구 36, NDK 27.1.12297006, CMake 3.22.1이 필요하다. JAVA_HOME과 ANDROID_HOME을 설정한다. 개인 서명 키는 제품 디렉터리 밖에 보관한다. FIT_SIGN_FILE은 별칭 adaptive-fit을 가진 키 저장소의 절대 경로, FIT_SIGN_PASSWORD는 저장소 및 키 암호다. 비밀값은 프로세스 환경으로 전달한다.

```powershell
cd android
.\gradlew.bat :app:testDebugUnitTest :app:assembleRelease "-PreactNativeArchitectures=arm64-v8a,x86_64"
```

APK 경로는 android/app/build/outputs/apk/release/app-release.apk다. 같은 패키지를 업데이트할 때는 같은 서명 키를 사용한다. 첫 실행에서 직접 입력한 공급자 키를 사용하며 공용 키는 배포하지 않는다.

[제품 결정](docs/product-direction.md) · [첫 코칭 설계와 검사 범위](docs/sdd/2/README.md)
