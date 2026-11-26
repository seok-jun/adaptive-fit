# Adaptive Fit 개인용 모바일 AI 코치

2026-11-26 결정. [Epic #1](https://github.com/seok-jun/adaptive-fit/issues/1)의 모바일 플랫폼, 저장 방식, 공급자 및 키 보관 항목은 이 문서의 결정을 따른다. 사용자 한 명이 본인 휴대폰에 앱을 설치하고 본인 API 계정으로 사용한다.

## 코칭과 운동 흐름

첫 실행 시 설정에서 사용할 공급자와 키를 등록한다. 다음 실행부터는 오늘의 코칭을 바로 연다. 진행 중 세션이 있으면 그 세션을 복원한다. AI는 최근 수행 이력, 장비, 가용 시간을 바탕으로 오늘의 계획과 변경 이유를 제안한다. 앱은 제약을 검증하고 사용자가 승인한 계획만 적용한다. 운동을 마치면 실제 수행을 바탕으로 다음 세션을 제안한다.

운동 화면에는 현재 종목, 직전 수행값, 목표와 큰 세트 완료 버튼을 둔다. 기본값으로 수행하면 키보드 없이 한 번 눌러 기록하고 휴식을 시작한다. 중량·횟수는 필요할 때 수정한다. 완료된 세트와 타이머 종료 시각을 같은 SQLite 트랜잭션에 저장하고 고유 세트 ID로 중복 탭을 처리한다. 저장 실패 시 편집값을 보존한다. 화면 잠금·앱 종료 후에도 종료 시각으로 남은 휴식을 계산한다. 오프라인에서는 마지막 승인 계획과 수동 기록을 사용한다.

## 기술 선택

| 영역 | 선택 |
| --- | --- |
| 설치형 앱 | TypeScript + React Native + Expo. 설치용 빌드에 JS와 기본 자산을 포함해 PC와 개발 서버가 꺼져 있어도 실행한다. |
| 기기 저장 | expo-sqlite에 프로필·계획 버전·실제 수행·코치 선택을 보존한다. expo-secure-store에는 공급자별 개인 API 키를 별도 항목으로 보관한다. |
| AI 통신 | React Native fetch로 선택한 공급자의 고정 HTTPS 엔드포인트에 직접 요청한다. 별도 앱 백엔드·프록시·중앙 DB는 운영하지 않는다. |
| 훈련 엔진 | 순수 TypeScript로 단위, 워밍업 구분, 볼륨과 증량·시간 제약을 검증한다. 코치 제안은 Zod로 검증한 뒤 이 엔진을 통과해야 한다. |
| 검증 | jest-expo와 React Native Testing Library로 요청 변환·화면 동작을 검증한다. 설치 빌드의 키 보관, 실통신, 앱 재시작은 기기에서 별도로 확인한다. |

## 공급자·모델·추론 설정

- 설정에서 Claude(Anthropic) 또는 OpenAI, 모델, 해당 모델이 지원하는 추론 강도를 선택한다. 앱에 모델별 기능표를 버전 관리하고 화면과 요청 검증기가 같은 표를 사용한다. 모델 기본값을 선택하면 선택적 강도 필드를 생략한다. 강도 조절 미지원 모델은 기본값만 표시한다.
- OpenAI는 `https://api.openai.com/v1/responses`에 Bearer 키로 요청하고 강도는 `reasoning.effort`로 전달한다. Claude는 `https://api.anthropic.com/v1/messages`에 해당 공급자의 `x-api-key`와 `anthropic-version`을 보내며 모델에 따라 `output_config.effort`와 지원되는 thinking 설정을 사용한다. 구현 시 모델별 공식 문서로 조합을 확인한다.
- 선택값은 앱 재시작 후 복원한다. 공급자나 모델을 바꾸면 유효한 옵션을 다시 표시하고 저장 시점과 전송 시점에 검증한다. 폐기된 모델이나 거부된 옵션은 재선택을 안내한다. 공급자·모델을 자동 전환하지 않는다.
- 키는 사용자가 기기에서 입력·교체·삭제한다. 빌드 설정·SQLite·로그·크래시 보고·내보내기에는 키를 포함하지 않는다. 코치를 호출할 때만 선택한 공급자의 키를 읽고 해당 공급자에게만 전송한다. 첫 전송 전에 보낼 운동 정보와 개인 계정 과금을 안내하고 동의를 받는다.
- 키 누락·인증 실패·요청 제한·타임아웃·잘못된 응답을 구분해 복구 행동을 안내한다. 요청 실패나 오프라인 전환으로 기존 계획과 기록이 바뀌지 않아야 한다. 실제 기록은 AI가 수정하지 않는다.

참고 문서 — [Expo SQLite](https://docs.expo.dev/versions/latest/sdk/sqlite/), [Expo SecureStore](https://docs.expo.dev/versions/latest/sdk/securestore/), [OpenAI 추론 설정](https://developers.openai.com/api/docs/guides/reasoning), [Claude 추론 강도](https://platform.claude.com/docs/en/build-with-claude/effort).
