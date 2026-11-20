# 등산 음성 어시스턴트 앱 — 개발 명세서

> 런데이의 등산 버전. 개인용(1인), 아이맥 서버 + VPN + 갤럭시(Android) 구조.
> 이 문서는 Claude Code에 단계별로 넘겨 개발하는 것을 전제로 작성됨.

---

## 1. 목표와 원칙

| 항목 | 내용 |
|---|---|
| 핵심 가치 | 화면이 꺼진 상태에서, 오프라인으로, 음악을 들으며 음성 안내를 받는 등산 |
| 사용자 | 본인 1명 (갤럭시 폰) |
| 비용 | 아이맥 전기료 + Claude 비용 외 0원 (무료 공공 API, APK 직접 설치) |
| 보안 | 서버는 VPN 내부에서만 접근. 외부 포트 개방 없음 |
| 개발량 최소화 | 화면 UI는 아이맥 웹페이지(WebView). 네이티브 개발은 "산행 중 음성 안내" 하나에 집중 |

### 설계 원칙
1. **산에서는 네트워크가 없다고 가정한다.** 산행 중 필요한 모든 데이터는 출발 전에 폰에 저장한다.
2. **안내 로직은 Android 의존성이 없는 순수 Kotlin 모듈로 분리한다.** → 아이맥에서 GPX 재생으로 회귀 테스트 가능.
3. **오경보(false alarm)보다 누락이 덜 위험한 항목과, 누락이 더 위험한 항목을 구분한다.** 경로 이탈은 디바운스를 두되 지연은 최소화.

---

## 2. 시스템 구조

```
┌──────────────────────── 아이맥 (서버) ────────────────────────┐
│  FastAPI 서버 (VPN 인터페이스에만 바인딩)                       │
│   ├─ 웹 UI: 산 선택 / 코스 목록 / 코스 상세 / 기록 보기          │
│   ├─ API: 코스 패키지 다운로드, 기록 업로드                      │
│   ├─ 데이터 파이프라인: 등산로 → 그래프, 코스, POI              │
│   ├─ 날씨 수집: 기상청 단기예보 (강수확률)                       │
│   └─ 분석: 개인 페이스 모델 학습, 기록 통계                      │
│  SQLite DB                                                     │
└───────────────────────────────▲───────────────────────────────┘
                                │ VPN (출발 전 / 하산 후에만)
┌───────────────────────────────┴───────────────────────────────┐
│  갤럭시 앱 (Kotlin)                                            │
│   ├─ WebView: 아이맥 웹 UI 표시                                 │
│   ├─ 코스 패키지 로컬 저장 (Room/SQLite or JSON 파일)            │
│   ├─ HikeService (포그라운드 서비스, 화면 꺼짐 동작)             │
│   │    ├─ GPS 수신 (적응형 주기)                                │
│   │    ├─ guidance-core (순수 Kotlin) → 안내 이벤트 생성         │
│   │    └─ 음성 출력 (TTS + 오디오 포커스 ducking)               │
│   └─ 업로드 워커 (WorkManager, 네트워크 복구 시 자동 업로드)     │
└───────────────────────────────────────────────────────────────┘
```

---

## 3. 필요한 도구 설치

> 서버/개발 환경이 Ubuntu인 경우 `apt`, 아이맥(macOS)에서 직접 돌릴 경우 Homebrew 명령을 함께 적음.

### 3.1 시스템 패키지

```bash
# Ubuntu
sudo apt update
sudo apt install -y python3 python3-pip python3-venv \
    gdal-bin libgdal-dev libspatialindex-dev \
    sqlite3 \
    openjdk-17-jdk \
    adb \
    sdkmanager
```

```bash
# macOS (아이맥) 대응
brew install python gdal spatialindex sqlite openjdk@17
brew install --cask android-commandlinetools android-platform-tools
```

### 3.2 Android SDK (CLI로 빌드, Android Studio 불필요)

```bash
sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"
# Gradle은 프로젝트의 gradle wrapper(./gradlew) 사용 → 별도 설치 불필요
```

### 3.3 Python 패키지 (서버)

```bash
python3 -m venv .venv && source .venv/bin/activate
pip install \
    fastapi "uvicorn[standard]" jinja2 python-multipart \
    sqlalchemy \
    gpxpy \
    shapely pyproj geopandas pyogrio \
    networkx \
    osmnx \
    requests httpx \
    astral \
    numpy pandas scipy \
    pytest
```

| 패키지 | 용도 |
|---|---|
| fastapi, uvicorn, jinja2 | 웹 UI + API 서버 |
| sqlalchemy | SQLite ORM |
| gpxpy | GPX 파싱/생성 |
| shapely, pyproj | 기하 연산, 좌표계 변환 (EPSG:5179/5186 → 4326) |
| geopandas, pyogrio | 산림청 SHP 등산로 파일 읽기 |
| networkx | 등산로 그래프 구성, 연결성 검증 |
| osmnx | OSM 등산로(path/footway/track) 보조 데이터 |
| requests, httpx | 기상청 API 호출 |
| astral | 일출/일몰 시각 계산 |
| numpy, pandas, scipy | 페이스 모델 학습, 기록 분석 |
| pytest | 서버 테스트 |

### 3.4 웹 프론트엔드 (설치 불필요)
- **Leaflet.js** (지도 표시) — 정적 파일로 서버에 포함
- 지도 타일: OpenStreetMap 또는 브이월드(무료 API 키)

### 3.5 Android 라이브러리 (Gradle 의존성)
| 라이브러리 | 용도 |
|---|---|
| `com.google.android.gms:play-services-location` | FusedLocationProvider (오프라인 GPS 동작) |
| `androidx.work:work-runtime-ktx` | 하산 후 자동 업로드 |
| `androidx.room` (선택) | 로컬 기록 저장 |
| `kotlinx-serialization-json` | 코스 패키지 JSON 파싱 |
| `junit` | guidance-core 단위/회귀 테스트 |
| Android 기본 `TextToSpeech`, `AudioManager` | 음성 출력, ducking |

### 3.6 외부 데이터/API (무료, 키 발급 필요)
| 데이터 | 출처 | 비고 |
|---|---|---|
| 등산로 (경로, 난이도, 소요시간) | 공공데이터포털 "산림청 등산로" | SHP 파일, 좌표계 확인 필수 |
| 등산로 보조 | OpenStreetMap | 연결 누락 보완용 |
| 강수확률 | 공공데이터포털 "기상청 단기예보 조회서비스" | `POP` 항목, 위경도 → 격자(nx, ny) 변환 필요 |
| 일몰 시각 | astral로 계산 | API 불필요 |

---

## 4. 기능 명세 — 웹 UI (아이맥)

### 4.1 산 선택
- 산 목록 + 지도 마커
- 다녀온 산: ✓ 배지와 방문 횟수

### 4.2 코스 목록
- 코스별: 거리, 누적 상승고도, 난이도, 표준 소요시간
- 기록이 있으면 **내 예상 시간** 병기 (예: `표준 2:30 / 내 예상 2:10`)
- 다녀온 코스 표시
- 산행 예상 시간대의 **시간별 강수확률** 표시

### 4.3 코스 상세
- 지도에 코스 경로 + 정상/쉼터/갈림길 마커
- 과거 기록 궤적 오버레이 (회차별 색상)
- 회차별 기록 표: 날짜, 총 소요시간, 정상까지 시간, 휴식 횟수/시간, 평균 페이스
- **[이 코스로 출발]** 버튼 → 앱이 코스 패키지 다운로드

### 4.4 기록 업로드 결과
- 업로드된 기록이 해당 코스 상세에 자동 추가
- 업로드 시 페이스 모델 재학습

---

## 5. 기능 명세 — 산행 중 음성 어시스턴트 (Android)

### 5.1 코스 패키지 (출발 전 다운로드)

```json
{
  "package_version": 1,
  "mountain": { "id": "bukhansan", "name": "북한산" },
  "course": {
    "id": "bukhansan-baegundae-01",
    "name": "백운대 코스",
    "polyline": [[lat, lon, ele], ...],
    "summit_index": 412,
    "end_index": 830
  },
  "graph": {
    "nodes": [{ "id": 1, "lat": 0, "lon": 0, "ele": 0, "type": "junction|summit|shelter|trailhead" }],
    "edges": [{ "id": 10, "from": 1, "to": 2, "geometry": [[lat, lon, ele], ...],
                "length_m": 320, "ascent_m": 45, "descent_m": 3, "closed": false }]
  },
  "pois": [{ "name": "OO쉼터", "type": "shelter", "lat": 0, "lon": 0, "course_dist_m": 1850 }],
  "weather": [{ "time": "2026-11-21T13:00+09:00", "pop": 60 }],
  "sunset": "2026-11-21T17:16+09:00",
  "pace_model": { "type": "tobler_personal", "factor": 0.92, "grade_bins": { } }
}
```

- `closed: true` 엣지(출입금지/폐쇄)는 경로 계산에서 **반드시 제외**

### 5.2 안내 문구

**정기 안내** (기본: 1km마다 또는 10분마다, 설정 가능)
> "정상까지 2.3킬로, 약 55분 남았어요. 현재 페이스 킬로당 22분이에요."

| 항목 | 계산 방법 |
|---|---|
| 남은 거리 | 코스 위 현재 진행 거리 → 목표 지점(정상 전: 정상, 정상 후: 종점)까지 |
| 남은 시간 | 남은 구간을 세그먼트별로 나눠 `세그먼트 길이 / 페이스모델(경사)` 합산. 단순 거리÷속도 금지 |
| 현재 페이스 | 최근 5~10분 이동평균, 휴식 구간 제외, `km당 N분` |
| (선택) 상대 페이스 | "평소보다 10% 빨라요" — 설정에서 on/off |

**상황별 안내**

| 이벤트 | 문구 예시 | 트리거 |
|---|---|---|
| 쉼터 접근 | "300미터 앞에 쉼터가 있어요." | POI 300m 전 1회 |
| 갈림길 사전 안내 | "200미터 앞 갈림길에서 오른쪽이에요." | 갈림길 200m 전 1회 |
| 정상 도착 | "정상이에요. 2시간 10분 걸렸고, 평소보다 5분 빨라요." | 정상 반경 30m 진입 |
| 하산 시작 | "하산 시작해요. 입구까지 3.1킬로, 약 1시간 남았어요." | 정상 이탈 후 |
| 경로 이탈 | "코스를 벗어났어요." | 5.3 참고 |
| 코스 복귀 | "코스로 돌아왔어요." | 5.3 참고 |
| 대안 경로 | "다른 코스로 들어섰어요. 이 길로 정상까지 1.8킬로, 약 50분이에요." | 5.4 참고 |
| 등산로 아님 | "등산로를 벗어났어요. 남서쪽 40미터에 등산로가 있어요." | 5.4 참고 |
| 강수 예보 | "1시간 뒤 강수확률 70퍼센트예요." | 저장된 예보 기준, 강수확률 ≥ 60% 시간대 1시간 전 |
| 하산 권고 | "일몰까지 남은 시간이 빠듯해요. 지금 하산을 권해요." | 예상 하산 완료 시각 > 일몰 − 30분 |

**문구 포맷 규칙**
- TTS가 자연스럽게 읽도록 단위는 한글로: `km` → "킬로", `m` → "미터", `%` → "퍼센트"
- 1km 미만은 50m 단위 반올림, 1km 이상은 0.1km 단위
- 시간은 5분 단위 반올림, 1시간 이상은 "1시간 20분"

**안내 우선순위와 간격**
1. 경로 이탈 / 하산 권고 (즉시, 다른 안내 중단 가능)
2. 갈림길 사전 안내
3. 쉼터 / 정상 / 강수
4. 정기 안내

- 비긴급 안내 간 최소 간격 20초
- 같은 이벤트 중복 안내 금지 (이벤트별 쿨다운)
- 모든 안내 항목 설정에서 on/off

### 5.3 경로 이탈 판정 (디바운스)

| 파라미터 | 기본값 | 설명 |
|---|---|---|
| `ACCURACY_MAX_M` | 50 | 정확도가 이보다 나쁜 GPS fix는 판정에서 제외 |
| `OFF_DIST_M` | 30 | 코스까지 거리 임계값 |
| `OFF_HOLD_S` | 15 | 임계값 초과가 이 시간 이상 지속되면 이탈 |
| `ON_DIST_M` | 15 | 복귀 임계값 (히스테리시스) |
| `ON_HOLD_S` | 10 | 복귀 판정 지속 시간 |

- 상태 머신: `ON_COURSE → SUSPECT → OFF_COURSE → (RECOVERING) → ON_COURSE`
- 이탈 확정 시 즉시 5.4 재경로 로직 실행

### 5.4 재경로 (오프라인)
1. 현재 위치를 그래프 엣지에 투영, 20m 이내 엣지 탐색
2. **다른 등산로 위에 있음** → 현재 목표(정상 또는 종점)까지 다익스트라 최단경로(가중치 = 페이스모델 기반 예상 시간) → 대안 경로 안내, 이후 새 경로를 "현재 코스"로 전환
3. **어떤 등산로에도 없음** → 가장 가까운 등산로 방향(8방위)과 거리 안내, 30초마다 반복
4. 폐쇄 엣지 제외

### 5.5 오디오 (음악 ducking)
- TTS `AudioAttributes`: `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE`, `CONTENT_TYPE_SPEECH`
- 안내 전 `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` 요청 → 음악 앱 볼륨 자동 감소
- 블루투스 지연 대비: 포커스 획득 후 300ms 대기 후 발화
- `UtteranceProgressListener.onDone` / `onError`에서 **반드시** 포커스 반납
- 오프라인 한국어 TTS 음성 데이터 설치 여부를 앱 시작 시 확인, 없으면 경고

### 5.6 배터리 정책
| 상태 | GPS 주기 |
|---|---|
| 일반 이동 | 3~5초 |
| 갈림길 50m 이내 / SUSPECT 상태 | 1초 |
| 휴식 감지 (걸음 센서로 60초 이상 정지) | 30초 |

- 위치 기록은 메모리 버퍼 → 1~2분마다 일괄 저장
- 경로 재계산은 이탈 시에만 실행
- 앱 첫 실행 시 안내: 배터리 "제한 없음" 설정, 절전 예외 앱 등록

### 5.7 포그라운드 서비스
- `foregroundServiceType="location"`, 상단 알림 상시 표시 (진행 거리/남은 거리)
- 알림에서 일시정지/종료 버튼
- 권한: `ACCESS_FINE_LOCATION`, `ACCESS_BACKGROUND_LOCATION`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION`, `POST_NOTIFICATIONS`, `ACTIVITY_RECOGNITION`

### 5.8 기록 및 업로드
- 기록: 타임스탬프, 위경도, 고도, 정확도, 걸음 수(누적), 발생한 안내 이벤트 로그
- 하산 후 WorkManager(네트워크 제약) → 서버 `/health` 응답 확인 후 업로드
- 업로드 성공 시에만 로컬 기록 "업로드 완료" 표시 (삭제는 수동)

---

## 6. 서버 API

| Method | Path | 설명 |
|---|---|---|
| GET | `/health` | VPN 연결 확인용 |
| GET | `/` 외 웹 페이지 | 산 선택, 코스 목록, 코스 상세 |
| GET | `/api/mountains` | 산 목록 + 방문 여부 |
| GET | `/api/mountains/{id}/courses` | 코스 목록 (표준/내 예상 시간, 강수확률) |
| GET | `/api/courses/{id}/package?date=YYYY-MM-DD` | 코스 패키지(5.1) 생성 및 다운로드 |
| POST | `/api/hikes` | 산행 기록 업로드 (GPX + 걸음수 + 이벤트 로그) |
| GET | `/api/hikes?course_id=` | 코스별 기록 목록 |

- 인증: 단일 사용자 정적 토큰 (헤더). VPN과 이중 보호
- 서버 바인딩: VPN 인터페이스 IP만 (`0.0.0.0` 금지)

---

## 7. 데이터 파이프라인 (서버)

1. 산림청 SHP 로드 → 좌표계 EPSG:4326 변환
2. 선분 끝점 스냅(허용오차 3~5m)으로 노드 병합 → 그래프 생성
3. **그래프 검증 (중요)**
   - 연결 요소(connected components) 수 확인, 고립된 조각 리포트
   - 코스 시작점 ↔ 정상 ↔ 종점이 같은 연결 요소에 있는지 확인
   - 수동 검토용 지도 레이어 생성 (끊긴 지점 표시)
4. OSM 데이터로 누락 연결 보완 (자동 병합 금지, 후보만 제시 → 수동 승인)
5. POI(정상, 쉼터, 이정표) 연결
6. 고도 정보 부여 (데이터에 없으면 DEM 또는 실제 산행 기록에서 보정)

---

## 8. 개인 페이스 모델

- 기본: Tobler 등산 함수 `v = 6·exp(−3.5·|grade + 0.05|)` km/h
- 개인화: 기록을 경사 구간(bin)별로 나눠 실제 속도/Tobler 속도 비율 학습
- 기록 없을 때: 산림청 표준 소요시간 사용
- 휴식 구간(속도 < 0.3km/h, 60초 이상) 제외 후 학습

---

## 9. 테스트 전략 (GPX 재생 기반 회귀)

```
guidance-core (순수 Kotlin, Android 의존성 없음)
   입력: 코스 패키지 + 위치 시퀀스(타임스탬프 포함)
   출력: 안내 이벤트 시퀀스 [(시각, 이벤트 타입, 파라미터)]
```

- **시뮬레이터**: GPX 파일을 시간순으로 재생해 guidance-core에 입력 → 이벤트 로그 출력
- **회귀 테스트**: 시나리오별 기대 이벤트와 비교 (`./gradlew :guidance-core:test`)
- **시나리오 목록**
  - 정상 완주 (이탈 없음) → 오경보 0건
  - GPS 튐(순간 50m 점프) → 이탈 미발생
  - 실제 이탈 30m 이상 20초 → 15초 시점에 이탈 1회
  - 다른 코스로 진입 → 대안 경로 안내 1회, 이후 코스 전환
  - 등산로 밖 → 방향 안내
  - 폐쇄 구간만 남는 경우 → 폐쇄 구간으로 안내하지 않음
  - 일몰 임박 → 하산 권고
- **합성 GPX 생성기** (Python): 코스 polyline에 노이즈/이탈/점프를 주입해 시나리오 파일 생성
- (선택) Python 기준 모델로 남은 거리/시간을 계산해 Kotlin 결과와 비교

---

## 10. 개발 단계

| 단계 | 범위 | 완료 기준 |
|---|---|---|
| **1** | 코스 1개 하드코딩 패키지 + HikeService + 정기/쉼터/정상 안내 + ducking | 실제 산에서 화면 끈 채로, 음악 들으며 안내가 끝까지 나옴 |
| **2** | 서버 기본(코스 패키지 API, 기록 업로드) + 웹 UI(코스 상세, 기록 오버레이) + WebView | 출발 전 다운로드 → 하산 후 자동 업로드 → 웹에서 확인 |
| **3** | 경로 이탈 감지 + guidance-core 분리 + GPX 재생 시뮬레이터/회귀 테스트 | 시나리오 테스트 전부 통과 |
| **4** | 산 선택/코스 목록, 날씨(강수확률), 일몰 하산 권고, 개인 페이스 모델 | 내 예상 시간 표시, 강수 안내 동작 |
| **5** | 등산로 그래프 파이프라인 + 검증 + 재경로 | 그래프 검증 리포트 통과한 산에서만 재경로 활성화 |

---

## 11. 디렉터리 구조 (제안)

```
hiking-assistant/
├── server/
│   ├── app/            # FastAPI (api, web, templates, static/leaflet)
│   ├── pipeline/       # SHP → 그래프, 검증, POI
│   ├── weather/        # 기상청 API, 격자 변환
│   ├── pace/           # 페이스 모델
│   ├── tools/          # 합성 GPX 생성기
│   └── tests/
├── android/
│   ├── app/            # HikeService, TTS/오디오, WebView, 업로드
│   └── guidance-core/  # 순수 Kotlin 안내 엔진 + 테스트
└── testdata/
    └── scenarios/      # 회귀 테스트용 GPX + 기대 이벤트
```

---

## 12. 안전 메모
- 앱 안내는 보조 수단이며, 현장 이정표가 우선
- 재경로는 그래프 검증을 통과한 산에서만 활성화
- 혼자 산행 시 이어폰은 주변음 모드 또는 한쪽만 사용 권장
- 6시간 이상 코스는 보조배터리 지참
