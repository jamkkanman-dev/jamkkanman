# 잠깐만

차를 잠깐 세우는 순간을 차가 먼저 알아채, 1분만 세워도 신고되는 주정차 금지구역이면 한 번만 음성으로 알려 주고 바로 옆 세울 수 있는 자리로 안내하는 Pleos Connect 차량 앱입니다.

- 데이터 범위: 서울 강남구 전체 (금지구역 15,423곳, 단속 CCTV 358대). 시연 지점은 대치동 학원가의 실제 지점
- 제출 APK: [`apk/`](apk/) 폴더

## 주요 기능

| 기능 | 내용 | 사용한 Pleos / 차량 API |
|---|---|---|
| 멈추는 순간 감지 | 기어를 P로 바꾸거나 문이 열리면 그 자리를 바로 확인 | Android Automotive `GEAR_SELECTION` (Pleos SDK 권장 방식), Pleos Vehicle SDK `Door` |
| 금지구역 확인 | 소화전 5m, 교차로 모퉁이 5m(교차로 가장자리에서 측정), 버스정류장 10m, 횡단보도 10m(실제 횡단보도 모양의 테두리에서 측정), 어린이보호구역 안인지 확인. 과태료와 몇 m만 옮기면 되는지까지 안내 | 강남구 공공데이터 (아래 데이터 출처) |
| 단속 위험 알림 | 단속 CCTV가 50m 안이면 경고에 덧붙이고, 반경 50m 안 실제 단속 건수(2025년 10~12월)를 보여 줌. 금지구역이 아니어도 단속이 잦은 곳이면 노란 선을 확인하라고 안내 | 서울시 단속 CCTV 위치·불법주정차 단속 정보 |
| 한 번만 음성 안내 | 같은 자리에서는 다시 말하지 않음. 운전 중에는 화면 대신 음성 | Pleos TextToSpeech SDK (기기 내 합성) |
| 세울 곳 안내 | 가장 가까운 공영주차장을 보여 주고 버튼 한 번으로 길 안내 또는 경유지 추가 | Pleos NaviHelper SDK |
| 도착 전 안내 | 목적지 앞이 금지구역이면 도착 700m 전에 미리 알리고 세울 곳을 경유지로 추가 | Pleos NaviHelper SDK |
| 음성으로 묻기 | "여기 세워도 돼?", "근처에 세울 데 어디야?", "이번 달 과태료 얼마 아꼈어?"를 말로 물으면 답함 | Pleos Gleo AI 연동 (`assets/schema.json`) |
| 이번 달 아낀 과태료 | 알림을 듣고 금지구역 밖으로 옮겨 세웠을 때만 더함 | 앱 내부 기록 |
| 정차 수요 기록 | 운전자가 동의했을 때만, 정확한 위치 대신 약 50m 칸·시간대·요일만 차 안에 저장 (지자체 안심 승하차 구역 설계용) | 앱 내부 기록 |
| 지도 | 직접 그리는 오프라인 지도. 입체(3D)·평면(2D) 전환, 내 차 주변 금지구역과 세울 곳 표시 | 국토교통부 표준노드링크 (+ 시연용 빌드는 행정안전부 도로명주소 도형) |

화면은 Pleos Connect 디자인 가이드(Light 테마 색·타이포그래피, 1~2초 안에 읽히는 정보량)를 따릅니다. 가로 화면은 지도와 안내를 나란히, 세로 화면은 지도 위·안내 아래로 배치합니다. 세로 화면은 Pleos 공식 폼 팩터 테스트(FormFactorConsole, Connect-S 12.9" Portrait · 628×855dp)에서 요소가 잘리거나 겹치지 않는 것을 확인했습니다.

## 실행 방법

1. **개발 환경**: Android Studio와 Pleos Connect Emulator v2.1.0을 설치하고 AVD를 만듭니다. ([Pleos Connect Emulator 설정](https://document.pleos.ai/docs/connect/guide/getting-started/application-development/configure-pleos-connect-emulator))
   - AVD 부팅 옵션은 반드시 **Cold boot**로 둡니다. Quick boot에서는 차량 신호(VHAL)를 쓸 수 없습니다.
2. **열기**: 이 저장소를 내려받아 Android Studio에서 엽니다.
3. **실행**: 실행 대상을 `Pleos Connect v2.1.0`으로 고르고 `app`을 실행합니다.
   - APK로 설치할 때: `adb install -r apk/<APK 파일>`
4. **시연 지점 고르기**: 앱 오른쪽 위 `시연` → `학원가 버스정류장`, `학원가 횡단보도`, `교차로 모퉁이`, `단속 잦은 곳` 등 대치동 지점을 누릅니다. (금지구역 데이터는 서울 강남구만 들어 있어, 옆 구로 넘어가면 "확인 범위 밖"으로 안내합니다.)
5. **차량 신호 보내기** (Pleos Connect Virtual Console)
   ```
   adb forward tcp:8088 tcp:8088
   ```
   PC 브라우저에서 `http://127.0.0.1:8088/` → `Emulation Controls`
   - `Gear Selection`: D → P
   - `Door Position`: Front left → Open
   - 신호 없이 보려면 앱의 `멈춤 신호` 버튼을 눌러도 됩니다. 같은 자리 안내를 다시 들으려면 `초기화`.

## 테스트 환경 및 필요한 설정

| 항목 | 내용 |
|---|---|
| 에뮬레이터 | Pleos Connect Emulator v2.1.0 (Android 14, API 34, arm64), 2560×1440 가로 |
| Pleos SDK | Vehicle 2.3.2, NaviHelper 3.0.5, TextToSpeech 3.3.1 |
| 빌드 | Android Gradle Plugin 8.8.2, Gradle 8.10.2, Kotlin 2.0.0, compileSdk 35 / minSdk 28 / targetSdk 34 |
| 권한 | 위치(실행 중 허용), `android.car.permission.CAR_POWERTRAIN`, `pleos.car.permission.CAR_DRIVE · CAR_LIGHTS · CAR_DOORS · CAR_SAFETY · NAVI_ROUTE · NAVI_ROUTE_SEARCH · TTS_SERVICE` |
| 음성 | 별도 설정 없이 기기 내 합성(ON_DEVICE)으로 동작. Pleos 앱 등록 정보가 있으면 `local.properties`에 `pleos.clientId`, `pleos.clientSecret`을 넣어 서버 합성도 쓸 수 있습니다 (저장소에는 넣지 않음). |
| 시간 | 어린이보호구역 과태료 시간대(08~20시)는 기기 시간대와 관계없이 한국 시각으로 계산합니다. |
| 참고 | 메모리 8GB 맥북 에어(M2)에서는 AVD 메모리 3GB, 그래픽 Host 설정에서 원활했습니다. |
| 폼 팩터 | [폼 팩터 테스트 가이드](https://document.pleos.ai/docs/pleos-only/app-test-guide/form-factor-test-guide)대로 Host Display Bridge와 FormFactorConsole로 세로 가상 디스플레이(Connect-S 12.9" Portrait, 628×855dp, 160DPI)를 만들어 확인 |

### 차량 신호 확인 결과 (Virtual Console)

| 신호 | 사용 API | 결과 |
|---|---|---|
| 기어 P | Android Automotive `GEAR_SELECTION` | 동작 확인 |
| 문 열림 | Pleos Vehicle SDK `Door.registerDoorPosition` | 동작 확인 |
| 비상등 | ① Android Automotive `HAZARD_LIGHTS_STATE` ② Pleos `TurnSignal` ③ Pleos `Safety` 경고등(Telltales) | 3rd party 앱에는 신호가 열려 있지 않음 (①은 속성 미노출, ②는 비상등 때 값이 바뀌지 않음, ③은 실시간 구독이 시스템 앱 전용 권한이고 조회도 거부됨) |

비상등은 Pleos가 3rd party 앱에 신호를 열어 주면 바로 쓸 수 있도록 판단 코드를 남겨 두었습니다 (`car/VehicleWatcher.kt`, `car/CarGearSource.kt`).

## Gleo AI 음성 연동

[Pleos App Integration Guide](https://document.pleos.ai/docs/pleos-only/app-integration-guide/guide/deliver-schema)대로 `app/src/main/assets/schema.json`에 음성으로 부를 수 있는 기능 3개를 적었습니다 (가이드 상한 3개).

| 기능 (schema 이름) | 방식 | 하는 일 | 이렇게 물으면 |
|---|---|---|---|
| `CHECK_PARKING_HERE` | activity | 지금 자리가 금지구역인지 다시 확인하고 과태료·옮길 거리·가까운 세울 곳을 음성으로 안내 | "잠깐만, 여기 세워도 돼?" |
| `FIND_LEGAL_PARKING` | activity | 가장 가까운 공영주차장으로 길 안내 시작 (NaviHelper) | "근처에 세울 데 어디야?" |
| `GET_SAVED_FINES` | service | 화면을 열지 않고 이번 달 아낀 과태료·알림 횟수를 돌려줌 | "이번 달 과태료 얼마 아꼈어?" |

- 결과는 `ai.pleos.playground.intent.action.APP_ACTION_RESPONSE` 방송으로 돌려줍니다. 가이드대로 `message`는 영어 한 문장, `data`는 JSON 배열 문자열입니다 (`car/GleoBridge.kt`).
- 앞의 두 기능은 앱이 한국어 음성으로 직접 답하므로 `mode: "silent"`로 Gleo가 겹쳐 말하지 않게 했습니다. `GET_SAVED_FINES`는 Gleo가 `data`를 보고 답합니다.
- schema는 Pleos 검토·승인 뒤에 Gleo AI에서 쓸 수 있어, 에뮬레이터에서는 Gleo가 보내는 것과 같은 Intent를 adb로 보내 세 기능 모두 응답이 돌아오는 것을 확인했습니다.
  ```
  adb shell am start -a CHECK_PARKING_HERE -p dev.jamkkanman.app --es ACTION_PARAM '{}'
  adb shell am start -a FIND_LEGAL_PARKING -p dev.jamkkanman.app --es ACTION_PARAM '{}'
  adb shell am broadcast -a GET_SAVED_FINES -p dev.jamkkanman.app --es ACTION_PARAM '{}'
  adb logcat -s Jamkkanman
  ```
  예: 학원가 버스정류장에서 `CHECK_PARKING_HERE` →
  `{"functionName":"CHECK_PARKING_HERE","status":"success","message":"Stopping here is prohibited because the car is within 10 m of a bus stop, and the fine is 40,000 won.","mode":"silent","data":"[{\"stopAllowed\":false,\"zoneType\":\"bus stop\",\"distanceM\":3,\"fineWon\":40000,\"certain\":true,\"moveM\":8,\"parkingName\":\"도곡로421 공영주차장\",\"parkingDistanceM\":17}]"}`

## 지도

- **기본 (저장소·제출 APK)**: 국토교통부 표준노드링크 도로망을 실제 차로 폭에 맞춰 그립니다.
- **시연용 빌드**: 행정안전부 도로명주소 도형(건물·실폭도로·하천·공원)을 함께 그립니다. 이 자료는 수령 조건상 국외 반출이 금지되어, 해외 서버인 GitHub 저장소와 제출 APK에는 넣지 않았습니다.
  - 직접 쓰려면 [주소기반산업지원서비스](https://business.juso.go.kr)에서 자료를 신청해 받은 뒤 `tools/build_basemap.py`로 `app/src/debug/assets/basemap_daechi.json`을 만들면 debug 빌드에만 들어갑니다.
- **입체(3D) 보기**: 비스듬히 내려다보는 원근 투영으로 건물을 세워 그립니다. 건물 도형에는 층수 정보가 없어 높이는 바닥 넓이로 정한 표현이며 실제 층수가 아닙니다.

## 데이터 출처

`app/src/main/assets/zones_daechi.json`은 아래 공식 공공데이터에서 서울 강남구와 경계 바깥 약 400m만 골라 만들었습니다. 파일 이름은 처음 시연 지역(대치동) 이름을 그대로 씁니다. (소방용수시설 7,418곳, 횡단보도 5,889곳, 버스정류장 1,114곳, 교차로 926곳, 어린이보호구역 76곳, 공영주차장 113곳, 단속 CCTV 358대, 단속 기록 63,603건)

- 확인 범위: 강남구 소방용수시설 반경 300m 안의 100m 칸(약 35km²)입니다. 소화전은 거의 모든 길에 있어 강남구 모양을 따라가므로, 사각형 범위와 달리 서초·송파 쪽으로 넘어가면 "확인 범위 밖"으로 안내합니다 (`tools/build_zones.py`).

| 데이터 | 제공 | 기준일 | 이용 조건 |
|---|---|---|---|
| 서울시 소방용수시설 현황 (OA-21306) | 서울 열린데이터광장 · 서울소방재난본부 | 2025-12-31 | 공공누리 제1유형(출처표시) |
| 서울시 버스정류소 위치정보 (OA-15067) | 서울 열린데이터광장 | 2026-09-02 | 공공누리 제1유형(출처표시) |
| 서울시 공영주차장 안내 정보 (OA-13122) | 서울 열린데이터광장 | - | 공공누리 제1유형(출처표시) |
| 서울시 교통안전시설물 횡단보도 정보 (OA-15554, 횡단보도 모양) | 서울 열린데이터광장 · 교통안전시설물관리시스템 | 2021-08 | 공공누리 제1유형(출처표시) |
| 서울특별시 자치구 횡단보도 정보 (OA-22906, 횡단보도 위치) | 서울 열린데이터광장 | 2026-03-20 | 이용허락범위 제한 없음 |
| 서울시 강남구 불법주정차 위반 단속 CCTV 위치정보 (OA-20494) | 서울 열린데이터광장 · 강남구 | 2026-10-01 | 공공누리 제1유형(출처표시) |
| 서울시 불법주정차 단속 정보 (OA-22190, 단속 위치) | 서울 열린데이터광장 | 2025-10-01 ~ 2025-12-31 | 공공누리 제1유형(출처표시) |
| 전국어린이보호구역표준데이터 (15012891) | 공공데이터포털 | 2026-07-02 | 공공데이터포털 이용 조건 |
| 전국주차장정보표준데이터 (15012896) | 공공데이터포털 | - | 공공데이터포털 이용 조건 |
| 전국표준노드링크 (지도 도로망 `roads_daechi.json`, 교차로 모퉁이 추정) | 국토교통부 국가교통정보센터 | 2026-10-14 | 공개 데이터 |
| 도로명주소 건물 도형 · 도로명이 부여된 도로 도형 · 기타자료 (시연용 빌드 지도 배경, 저장소 미포함) | 행정안전부 주소기반산업지원서비스 | 2026-09 | 제공 신청·수령 조건 준수 (국외 반출 금지) |

- 중앙차로 버스정류장은 가장자리 정차와 관계없어 뺐습니다.
- 어린이보호구역 데이터에는 구역 경계가 없어 시설 위치에서 반경 100m를 보호구역으로 추정합니다.
- 횡단보도는 실제 모양(면) 5,316곳을 넣어 테두리에서 10m 안인지 잽니다. 모양 데이터(2021)에 없는 새 횡단보도 573곳은 2026년 위치 데이터의 점에서 10m로 판단합니다. 모양 데이터의 상태코드 중 정의서에 없는 값은 2026년 위치 데이터에도 있을 때만 씁니다 (`tools/add_crosswalks.py`).
- 교차로 모퉁이는 도로교통법 제32조 제2호("교차로의 가장자리나 도로의 모퉁이로부터 5미터 이내")대로 교차로 가장자리에서 5m를 잽니다. 교차로 모양은 공식 도형이 없어 표준노드링크로 추정합니다 (`tools/add_corners.py`).
  - 교차로(노드 유형 101)에서 만나는 일반도로마다 방향과 폭(방향별 차로 수 합 × 3.0m, 「도로의 구조·시설 기준에 관한 규칙」 제10조 도시지역 최소 차로 폭)을 구하고, 이웃한 두 도로의 연석선이 만나는 점(모퉁이)을 이어 교차로 영역을 만듭니다.
  - 왕복 분리 도로의 큰 교차로는 차도마다 노드가 따로 있어, 이름이 같은 노드를 하나로 묶어 감싸는 영역을 씁니다.
  - 표준노드링크에 없는 골목길 교차로는 아직 들어 있지 않습니다.
- 단속 기록은 개별 기록과 주소를 넣지 않고 25m 칸별 건수만 넣었습니다 (`tools/add_enforcement.py`). 반경 50m 안 단속이 석 달에 30건 이상(대치동 주변 기준 상위 약 25%)이거나 단속 CCTV가 50m 안이면 '단속이 잦은 곳'으로 봅니다.
- 노란 선(황색 실선·점선)처럼 지자체가 지정한 금지 구간은 좌표가 있는 공개 데이터가 없어 (전국주정차금지(지정)구역표준데이터는 위치가 글로만 제공됨), 단속이 잦은 곳에서는 노란 선을 직접 확인하라고 안내합니다.
- 데이터 가공 도구(`tools/`)는 엑셀·CSV를 읽는 `build_zones.py`(pandas)를 빼면 외부 라이브러리 없이 좌표 변환(GRS80 횡축 메르카토르 → 위도·경도)까지 직접 처리합니다. 순서: `build_zones.py` → `add_crosswalks.py` → `add_corners.py` → `build_roads.py` → `add_enforcement.py`

## 모듈

| 모듈 | 내용 |
|---|---|
| `app` | 잠깐만 본 앱 |
| `probe` | 신호 확인용 테스트 앱. 에뮬레이터가 주는 차량 신호와 Pleos SDK 공개 API를 확인하고 결과를 보고서 파일로 남깁니다. |

## 개발 방식

- 기획과 기능 설계는 직접 했으며, 코드 작성과 자료 조사에 AI 도구(Claude)를 활용했습니다.

## 오픈소스

- Gradle Wrapper (Apache License 2.0)
