# 잠깐만

차를 잠깐 세우는 순간을 차가 먼저 알아채, 1분만 세워도 신고되는 주정차 금지구역이면 미리 알려 주고 세울 수 있는 자리로 안내하는 Pleos Connect 차량 앱입니다.

> 개발 중인 저장소입니다. 제출용 README(앱 소개·실행 방법·테스트 환경·필요한 설정)는 마감 전에 완성합니다.

## 모듈

| 모듈 | 내용 |
|---|---|
| `probe` | 신호 확인용 테스트 앱. 에뮬레이터가 주는 차량 신호와 Pleos SDK 공개 API를 확인하고 결과를 보고서 파일로 남깁니다. |

## 테스트 환경

- Pleos Connect Emulator v2.1.0 (Android 14, API 34, arm64)
- Pleos Vehicle SDK 2.3.2, NaviHelper SDK 3.0.5
- Android Gradle Plugin 8.8.2, Gradle 8.10.2, Kotlin 2.0.0, compileSdk 35 / minSdk 28

## 오픈소스

- Gradle Wrapper (Apache License 2.0)
