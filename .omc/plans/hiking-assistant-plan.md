# 등산 음성 어시스턴트 — 구현 계획

> 상태: **pending approval** (실행 승인 전, 코드/환경 변경 없음)
> 기준 문서: `docs/hiking-assistant-spec.md` (이하 "명세")
> 모드: Direct (명세가 상세하여 인터뷰 생략)

---

## 1. Requirements Summary

화면이 꺼진 오프라인 상태에서 음악을 들으며 TTS 음성 안내를 받는 1인용 등산 앱.
- 서버: 아이맥 FastAPI + SQLite, VPN 인터페이스에만 바인딩 (명세 §2, §6)
- 앱: Kotlin, WebView(웹 UI) + HikeService(GPS/TTS/ducking) + 순수 Kotlin `guidance-core` (명세 §5, §9)
- 5단계 개발 (명세 §10): 1 현장 안내 → 2 서버/웹/업로드 → 3 이탈 감지·회귀 테스트 → 4 날씨/페이스 → 5 그래프/재경로

## 2. 현재 상태 점검 결과 (실측)

| 항목 | 상태 | 영향 |
|---|---|---|
| 프로젝트 | `docs/hiking-assistant-spec.md` 1개뿐 (greenfield) | 디렉터리 구조(명세 §11)부터 생성 |
| Homebrew | **없음** | 명세 §3.1의 `brew install ...` 그대로 실행 불가 |
| Python | 시스템 3.9.6 | geopandas/shapely 등 최신 휠이 3.10+ 요구 → 3.11+ 필요 |
| Java | **없음** | Gradle/Android 빌드 불가, JDK 17 설치 필요 |
| Android SDK / adb / sdkmanager | **없음** (`ANDROID_HOME` 비어 있음) | 설치 + 라이선스 동의 필요 |
| GDAL | 없음 | pyogrio/shapely 휠에 GDAL 번들 → **시스템 GDAL은 불필요할 가능성 높음** (Phase 0에서 검증) |
| VPN | **Tailscale** (stockRecom 세션과 동일 구성, 확인 완료) | 명세 §2/§6의 "VPN IP 바인딩"을 **루프백 바인딩 + Tailscale Serve(HTTPS)** 로 대체 (§3-4 참고) |
| 대상 코스 | 수락산 5코스 (사용자 확정, 지난주 산행 기록 있음) | Phase 1 하드코딩 패키지 대상 |
| 아이맥 | Intel(x86_64), git 2.39.3 있음, git 저장소는 아직 아님 | 에뮬레이터 가능하나 실기기 위주, Phase 0에서 `git init` |

## 3. 명세에 대한 계획상 보정 (권고)

명세를 그대로 따르면 생기는 위험을 줄이기 위해 아래 3가지를 계획에 반영했습니다. 거부하면 명세 순서 그대로 진행 가능합니다.

1. **`guidance-core`를 Phase 1부터 별도 모듈로 시작** — 명세는 Phase 3에서 분리하지만, Phase 1 로직(정기/쉼터/정상 안내)을 서비스 안에 먼저 쓰면 Phase 3에서 전면 재작성하게 됩니다. 처음부터 순수 Kotlin 모듈 + JVM 단위 테스트로 만들면 폰 없이 `./gradlew :guidance-core:test`로 검증됩니다.
2. **Phase 0 (환경) + 데이터 스파이크 추가** — 환경이 비어 있고, 가장 큰 데이터 리스크(산림청 SHP 그래프 연결성, 명세 §7-3)가 Phase 5에서야 드러납니다. 산 1개로 SHP 로드→그래프→연결 요소 수 확인만 먼저 해 보는 반나절짜리 스파이크를 둡니다.
3. **Phase 1 하드코딩 코스는 실제 GPX로 생성** — 본인이 갈 코스의 실제 궤적(또는 SHP 추출)으로 패키지를 만들어, 현장 테스트 때 좌표가 어긋나는 일을 방지.

4. **네트워크 노출 방식을 stockRecom과 동일하게 변경** — 명세는 VPN 인터페이스 IP 바인딩 + HTTP이지만, 이미 검증된 구성(uvicorn `127.0.0.1:<포트>` + `tailscale serve` HTTPS, tailnet 전용, funnel 미사용)을 재사용합니다. 장점: 인터페이스명(`utunN`)이 고정이 아니라 IP 바인딩이 깨지기 쉬운 문제 회피, 자동 인증서로 **cleartext 예외(networkSecurityConfig) 불필요**. 인증은 명세대로 정적 토큰을 유지(stockRecom은 무인증이지만, 명세의 "VPN과 이중 보호" 원칙 유지).

## 4. Acceptance Criteria (전체, 테스트 가능)

- [ ] AC1: `./gradlew :guidance-core:test`가 폰/에뮬레이터 없이 통과 (명세 §9 시나리오 7종 전부)
- [ ] AC2: 정상 완주 GPX 재생 시 이탈 오경보 **0건**, GPS 50m 점프 시 이탈 미발생, 30m+ 이탈 20초 시 15초 시점에 정확히 1회 이탈 이벤트
- [ ] AC3: 화면 꺼짐 + 음악 재생 상태에서 갤럭시가 정기/쉼터/정상 안내를 끝까지 출력하고, 안내 후 음악 볼륨이 복원됨 (실산행 1회)
- [ ] AC4: 서버가 `127.0.0.1`에만 LISTEN (`lsof -iTCP -sTCP:LISTEN`으로 `*`/`0.0.0.0` 없음 확인), `tailscale serve status`에 tailnet 전용(funnel 꺼짐)으로 표시, 토큰 없는 요청은 401, tailnet 밖(LTE, Tailscale 끔)에서 접속 불가
- [ ] AC5: 출발 전 패키지 다운로드 → 비행기 모드에서 산행 → 하산 후 자동 업로드 → 웹 코스 상세에 기록 표시
- [ ] AC6: `closed: true` 엣지는 어떤 재경로 결과에도 포함되지 않음 (단위 테스트)
- [ ] AC7: 남은 시간 계산이 세그먼트별 경사 기반 (단순 거리÷속도 아님) — Python 기준 모델과 Kotlin 결과 오차 ≤ 1분 (명세 §9 선택 항목 채택)
- [ ] AC8: 그래프 검증 리포트에서 코스 시작↔정상↔종점이 동일 연결 요소인 산에서만 재경로 활성화

## 5. Implementation Steps

### Phase 0 — 환경 구축 + 데이터 스파이크 (선행)
1. **Homebrew 없이 설치** (공식 설치 스크립트가 이 Intel 맥에서 "Apple Silicon만 지원" 메시지로 거부함 — 실측: i5-10600, macOS 14.1):
   - Python 3.11: `uv` 설치(`curl -LsSf https://astral.sh/uv/install.sh | sh`) 후 `uv python install 3.11`
   - JDK 17: Eclipse Temurin 17 macOS x64 `.pkg` (adoptium.net) — 사용자 sudo 필요
   - Android: command-line tools zip(developer.android.com, mac)을 `~/Library/Android/sdk/cmdline-tools/latest/`에 압축 해제, `ANDROID_HOME` 설정
   - GDAL은 보류
2. `sdkmanager`로 `platform-tools`(adb 포함), `platforms;android-34`, `build-tools;34.0.0` 설치 + 라이선스 동의 (명세 §3.2)
3. `server/.venv` 생성(`uv venv --python 3.11`) 후 명세 §3.3 패키지 설치(`uv pip install`). Intel mac(x86_64) 휠 제공 여부 확인. `import geopandas, pyogrio, shapely, osmnx` 스모크 테스트로 시스템 GDAL 필요 여부 확정
4. 갤럭시(z-flip6) 개발자 옵션 + 무선/USB 디버깅 → `adb devices`에서 인식 확인
4-1. **Tailscale: 이미 설치·로그인됨 (실측)** — 이 맥이 tailnet 노드 `macbookpro-2`(100.113.103.37)이고 stockRecom과 **같은 머신**임. 폰 z-flip6(100.99.120.94)도 같은 계정. → stockRecom이 Tailscale Serve 443을 이미 사용 중이므로 이 앱은 **다른 HTTPS 포트**로 노출해야 함
4-2. `git init` + `.gitignore`(`.venv/`, `build/`, `.gradle/`, `*.keystore`, `local.properties`, 토큰/환경 파일) — git 관련 규칙은 사용자 안내 대기 중
5. **스파이크**: 산림청 SHP 1개 산 다운로드 → 좌표계 확인 → 4326 변환 → networkx 그래프 → 연결 요소 수/고립 조각 출력 (산출물은 버림, 결과만 기록)
- 완료 기준: `adb devices` 기기 표시, pytest 빈 테스트 통과, 스파이크 리포트 1장

### Phase 1 — 현장 음성 안내 (명세 §5.1, 5.2, 5.5~5.7)
- `android/guidance-core/` (순수 Kotlin): 코스 패키지 모델(kotlinx-serialization), 진행 거리 투영, 정기 안내 / 쉼터 300m / 정상 30m 이벤트, 문구 포맷터(§5.2 포맷 규칙), 우선순위·쿨다운(20초) 큐
- `android/app/`: `HikeService`(foregroundServiceType=location, 알림 + 일시정지/종료), FusedLocationProvider 적응형 주기(§5.6), TTS + `AudioFocus` ducking(§5.5: 포커스 후 300ms, onDone/onError에서 반납), 오프라인 한국어 TTS 설치 확인
- 권한 플로우: Android 11+는 `ACCESS_BACKGROUND_LOCATION`을 포그라운드 권한과 **별도 단계**로 요청, `POST_NOTIFICATIONS`, `ACTIVITY_RECOGNITION`, 배터리 "제한 없음" 안내 (삼성 One UI의 "절전 앱" 자동 제한 주의)
- `testdata/` 하드코딩 패키지 JSON (실제 GPX 기반)
- 테스트: guidance-core JUnit(문구/반올림/쿨다운), 실기기 실산행 1회(AC3)

### Phase 2 — 서버 기본 + 웹 UI + WebView (명세 §4, §6)
- `server/app/`: FastAPI, SQLAlchemy 모델(mountain/course/hike), `/health`, `/api/courses/{id}/package`, `POST /api/hikes`(GPX+걸음수+이벤트 로그), 정적 토큰 헤더 인증, `uvicorn --host 127.0.0.1 --port 8001`(8000은 stockRecom 사용 중) + `tailscale serve --bg --https=8443 http://127.0.0.1:8001` (443은 stockRecom 점유, **기존 serve 설정을 덮어쓰지 않도록 실행 전 `tailscale serve status` 확인 필수**). 접속 URL은 `https://macbookpro-2.taila5103b.ts.net:8443`, launchd로 상시 실행(stockRecom 방식 `caffeinate -i uvicorn` 참고)
- 웹: Jinja2 + Leaflet(정적 포함) 코스 상세 + 기록 오버레이(회차별 색상)
- 앱: WebView(`[이 코스로 출발]` → 패키지 다운로드 → 로컬 저장), WorkManager 업로드(네트워크 제약, `/health` 확인 후 업로드, 성공 시에만 "업로드 완료" 표시)
- 접속 URL은 `https://<아이맥호스트>.<tailnet>.ts.net` 형태(HTTPS) → cleartext 예외 불필요. 앱 설정에 서버 URL을 하드코딩하지 않고 입력/저장 가능하게 함
- **주의**: 폰은 Tailscale이 켜져 있어야 다운로드/업로드 가능 (Android는 VPN 슬롯 1개 → 다른 VPN 앱과 충돌 여부 확인)
- 테스트: pytest(API/인증/바인딩), 앱↔서버 E2E(AC5)

### Phase 3 — 이탈 감지 + 회귀 테스트 (명세 §5.3, §9)
- guidance-core에 상태 머신(`ON_COURSE→SUSPECT→OFF_COURSE→RECOVERING`), 파라미터 5종(`ACCURACY_MAX_M` 등) 설정화
- `server/tools/`: 합성 GPX 생성기(노이즈/점프/이탈 주입), `testdata/scenarios/`에 GPX + 기대 이벤트 JSON
- GPX 재생 시뮬레이터(JVM CLI) + 시나리오 7종 회귀 테스트 (AC1, AC2)

### Phase 4 — 날씨·일몰·페이스 모델 (명세 §5.2, §8)
- `server/weather/`: 기상청 단기예보 호출, 위경도→격자(nx, ny) 변환, POP 추출, 패키지 `weather[]` 채우기. 예보 범위(약 3일) 밖 날짜는 "예보 없음"으로 처리
- `server/pace/`: Tobler 기본 + 경사 bin별 개인 보정 학습(휴식 구간 제외), 업로드 시 재학습
- `astral` 일몰 계산 → 하산 권고 이벤트(예상 하산 완료 > 일몰 − 30분)
- 웹: 산 선택/코스 목록, "내 예상 시간", 시간별 강수확률
- 테스트: Python↔Kotlin 남은 시간 비교(AC7), 일몰 임박 시나리오

### Phase 5 — 그래프 파이프라인 + 재경로 (명세 §5.4, §7)
- `server/pipeline/`: SHP→4326, 끝점 스냅(3~5m)→그래프, 연결성 검증 리포트 + 수동 검토 지도 레이어, OSM 보완은 **후보만 제시→수동 승인**, POI 연결, 고도 보정
- guidance-core: 엣지 투영(20m), 다익스트라(가중치=페이스 기반 시간), 대안 경로/등산로 이탈(8방위, 30초 반복), 폐쇄 엣지 제외(AC6)
- 산별 `reroute_enabled` 플래그: 검증 통과 산에서만 true (AC8)

## 6. Risks and Mitigations

| 위험 | 가능성/영향 | 완화 |
|---|---|---|
| SHP 그래프가 끊겨 있어 재경로 불가 | 높음/중 | Phase 0 스파이크로 조기 발견, Phase 5는 검증 통과 산 한정(명세 §12) |
| 삼성 절전/Doze로 백그라운드 GPS·TTS 중단 | 높음/높음 | 포그라운드 서비스 + 배터리 제한 없음 안내 + 실산행 전 집 근처 1시간 화면 꺼짐 워킹 테스트 |
| 폰이 Android 17(SDK 37)로 명세 기준(android-34)보다 높아 백그라운드 위치/포그라운드 서비스/알림 권한 동작이 다를 수 있음 (실측: SM-F741N, ko-KR, Google TTS·Tailscale 설치됨) | 중/중 | Phase 1 초기에 권한 플로우와 화면 꺼짐 GPS 수신을 집 근처에서 먼저 검증, 문제 시 compile/targetSdk 상향 검토 |
| 블루투스 이어폰에서 TTS 첫 음절 잘림/ducking 미동작 | 중/중 | 포커스 후 300ms 대기, 실제 이어폰으로 Phase 1에서 검증 |
| 하드코딩→서버 패키지 전환 시 스키마 불일치 | 중/중 | Phase 1에서 패키지 JSON 스키마(`package_version`)를 확정하고 서버가 동일 스키마 생성 |
| 파이썬/GDAL 의존성 설치 실패 | 중/낮 | Phase 0에서 3.11 venv + 스모크 테스트, 실패 시에만 GDAL brew 추가 |
| 산 정상 근처 GPS 오차로 오경보 | 중/중 | `ACCURACY_MAX_M` 필터 + 디바운스 + 시나리오 회귀 테스트 |
| 공공데이터 API 키 발급 지연 | 낮/낮 | Phase 0에서 미리 신청(산림청, 기상청) |
| VPN 미연결 상태로 출발 | 중/중 | 다운로드 전 `/health` 체크, 패키지 로컬 저장 확인 후에만 "출발" 활성화 |

## 7. 미결 사항 (실행 전 확인 필요)

**해결됨**: VPN=Tailscale(stockRecom 방식 재사용), 대상=수락산 5코스, 아이맥=Intel.

**남은 항목**
1. **Homebrew 사용 불가** — 미설치였고, 공식 설치 스크립트가 이 Intel 맥(i5-10600, macOS 14.1)에서 설치를 거부함. Homebrew 없이 uv / Temurin pkg / Android cmdline-tools로 대체(Phase 0 step 1). Tailscale도 brew cask 대신 공식 pkg 또는 App Store 사용
2. **git 규칙** — 사용자가 알려주기로 함(원격 저장소, 브랜치/커밋 규칙 등), 아직 미수신
3. **수락산 5코스 GPX 없음 (사용자 확인)** — Phase 1 하드코딩 패키지는 산림청 SHP에서 5코스 경로를 추출해 생성(Phase 0 스파이크 데이터 재사용). 첫 실산행에서 GPX를 기록해 경로 정합성을 검증. 지난주 산행을 기록한 앱이 있으면 내보내기 시도
4. **아이맥 점유 포트** — stockRecom 답변은 다른 맥(macbookpro-2) 기준이라 아이맥에서 별도 확인
5. 아이맥 Tailscale: 사용자가 같은 계정 로그인으로 확정. 설치/로그인은 사용자가 GUI에서 수행(브라우저 인증 필요), 이후 `tailscale status`로 확인

## 8. Verification Steps

- 각 Phase 종료 시: 해당 AC 항목 통과 증거(테스트 출력, `adb logcat` 발췌, 서버 바인딩 확인 명령 결과) 제출 후 다음 Phase 진행
- 회귀: Phase 3 이후 모든 PR/변경에서 `./gradlew :guidance-core:test` + `pytest server/tests` 통과 필수
- 실산행 검증은 Phase 1, 2, 5 종료 시 각 1회 (이벤트 로그를 서버 업로드로 회수해 기대 이벤트와 대조)
