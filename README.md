# mspace-core

M-SPACE 예약 시스템의 백엔드(core)예요. 조선대 IT융합대학 M-SPACE 스터디룸의 **예약, 출입, 회원** API를 열고, 겹침 금지·1인 1건(대표자·참여자) 같은 예약 규칙을 DB 제약으로 지켜요.

- 학생 폰(front)과 문 앞 키패드, AI 비서가 모두 이 서버의 API만 불러요. 권한 판단도 여기서만 해요.
- **규칙·API의 기준은 [AI-go-do/docs](https://github.com/AI-go-do/docs) 저장소예요.** 이 README나 코드 주석이 docs와 다르면 docs를 따르고, 다른 곳은 고쳐요.
- 요구사항: [요구사항 명세](https://github.com/AI-go-do/docs/blob/main/requirements/requirements.md) · API: [API 명세](https://github.com/AI-go-do/docs/blob/main/api/README.md)
- 팀 규칙: [팀 컨벤션](https://github.com/AI-go-do/docs/blob/main/conventions.md) · [인프라 · CI/CD](https://github.com/AI-go-do/docs/blob/main/infra/infra-cicd.md) · [팀 공통 맥락](TEAM.md)

## 문서

- [결정 기록](docs/결정.md): 왜 이렇게 했나 (A vs B → 결정)
- [트러블슈팅](docs/트러블슈팅.md): 막힌 것, 원인, 해결, 결과 숫자
- API·요구사항은 팀 문서 [AI-go-do/docs](https://github.com/AI-go-do/docs)가 기준이에요

## 기술 스택

| 영역 | 사용 기술 |
|---|---|
| 언어 | Java 21 |
| 프레임워크 | Spring Boot 4.1 |
| 빌드 | Maven (저장소에 든 `./mvnw`를 쓰면 Maven 설치 없이 같은 버전으로 빌드돼요) |
| DB | PostgreSQL 16 · Flyway (`src/main/resources/db/migration`) |
| 테스트 | JUnit 5 · Testcontainers PostgreSQL (테스트 때 Docker 필요) |

## 로컬 실행

필요한 것: JDK 21, Docker

```bash
docker compose up -d            # 로컬 DB(PostgreSQL 16)만 띄움
./mvnw spring-boot:run          # 서버 실행, 처음 뜰 때 Flyway가 테이블을 만듦 (Windows는 mvnw.cmd)
curl localhost:8080/actuator/health   # {"status":"UP"}이면 정상
./mvnw test                     # 테스트 (Docker로 테스트용 DB를 따로 띄움)
```

측정 테스트(`NaiveCheckThenInsertMeasure`, DB 제약 없이 "조회 후 저장"만 하면 중복이 몇 건 생기나)는 오래 걸려서 기본 테스트에서 빠져 있어요. 따로 돌릴 때는 이렇게 해요.

```bash
./mvnw test -Dtest=NaiveCheckThenInsertMeasure -Dmeasure=true
```

설정값은 `.env.example`을 `.env`로 복사해서 채워요. `.env`는 커밋하지 않아요.

### 관리자 지정 (시연 준비)

관리자 화면이 아직 없어서 SQL로 정해요. 메일 인증으로 먼저 가입한 뒤에 바꿔요. 관리자만 `GET /api/reservations/stats`(WEB/AGENT 예약 건수)와 아래 [관리자 도구](#관리자-도구-116)를 쓸 수 있어요.

```bash
docker compose exec db sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "UPDATE users SET role = '"'"'ADMIN'"'"' WHERE email = '"'"'내메일@chosun.ac.kr'"'"'"'
```

### 로그인 붙기 전 임시 방법

로그인(재웅님 담당)이 붙기 전까지는 `X-User-Id` 헤더에 회원 id를 넣어서 "누가 보낸 요청인지"를 알려 줘요. `MSPACE_DEV_HEADER=true`일 때만 켜지고, 기본값은 꺼짐이라 운영에서는 항상 401이에요.

```bash
MSPACE_DEV_HEADER=true ./mvnw spring-boot:run
curl 'localhost:8080/api/members?studentId=20230002' -H 'X-User-Id: 1'   # 참여자 학번 검색 → {"studentId":"20230002","name":"홍*동","me":false} (me: 찾은 사람이 나)
curl -X POST localhost:8080/api/friends/requests -H 'X-User-Id: 1' -H 'Content-Type: application/json' -d '{"studentId":"20230002"}'   # 친구 요청 → 201 PENDING
curl -X POST localhost:8080/api/friends/requests/1/accept -H 'X-User-Id: 2'   # 받은 사람이 수락 (참여자는 서로 친구만, 결정 15번)
curl localhost:8080/api/friends -H 'X-User-Id: 1'                  # 친구 창: friends·received·sent
curl -X POST localhost:8080/api/reservations -H 'X-User-Id: 1' -H 'Content-Type: application/json' \
  -d '{"roomId":1,"startAt":"2026-11-03T14:00:00+09:00","endAt":"2026-11-03T16:00:00+09:00","participantStudentIds":["20230002","20230003"]}'
curl localhost:8080/api/reservations/me -H 'X-User-Id: 1'          # 내 예약 (번호는 대표자에게만)
curl -X POST localhost:8080/api/reservations/1/cancel -H 'X-User-Id: 1'   # 시작 전 취소 → {"id":1,"status":"CANCELED"}
curl -X POST localhost:8080/api/reservations/1/checkout -H 'X-User-Id: 1' # 퇴실 (입실한 대표자만)
```

비서처럼 응답을 못 받으면 다시 보내는 쪽은 `Idempotency-Key` 헤더(1~64자)를 붙여요. 같은 키로 다시 보내면 새로 만들지 않고 처음 예약을 `200`으로 돌려주고, 같은 키를 다른 내용에 쓰면 `409 IDEMPOTENCY_KEY_REUSED`예요.

### 지금 바로 쓰기 (즉시 시작)

버튼 누른 시각부터 시작하고, 끝은 30분 칸으로 골라요. 끝은 최대 (지금 + 2시간)을 30분 칸으로 올린 시각이에요(14:17 → 16:30). 다음 예약이 있으면 그 전까지만 돼요(아니면 409). 10분 안에 키패드로 입실하지 않으면 노쇼예요. 끝난 뒤나 일찍 퇴실한 뒤에 다시 누르면 새 예약 1건이에요. 사용 중에 이어 쓰려면 아래 연장하기를 써요.

```bash
curl -X POST localhost:8080/api/reservations/instant -H 'X-User-Id: 1' -H 'Content-Type: application/json' \
  -d '{"roomId":1,"endAt":"2026-11-03T16:30:00+09:00","participantStudentIds":["20230002","20230003"]}'
```

예약을 만들기(미리 예약·즉시 시작) 직전에는 지난 예약 정리(노쇼·자동 퇴실)를 한 번 돌려요. 그래서 끝나자마자 이어서 누르거나 노쇼된 방을 바로 잡을 때, 1분 정리를 기다리지 않아도 돼요.

### 연장하기 (FR-RSV-105)

사용 중(`IN_USE`)인 예약의 대표자가 종료 10분 전부터 종료 전까지 누를 수 있어요. 한 번에 지금 예약을 퇴실하고, 누른 시각부터 고른 끝까지 새 예약을 바로 사용 중으로 만들어요(키패드·노쇼 없음). 끝은 지금 종료 + 30분 단위, 최대 2시간이에요. 새 입실 번호가 나오고 예전 번호는 바로 무효예요. 다음 칸을 남이 잡았으면 `409 RESERVATION_TIME_TAKEN`이고 지금 예약은 그대로예요. 연장은 1회만 돼요(`409 RESERVATION_EXTENSION_LIMIT`). 연장까지 쓴 뒤 30분 안에 시작하는 새 예약도 같은 409예요. 숫자는 `mspace.reservation.extension`에 있어요.

```bash
curl -X POST localhost:8080/api/reservations/1/continue -H 'X-User-Id: 1' -H 'Content-Type: application/json' \
  -d '{"endAt":"2026-11-03T16:00:00+09:00"}'   # → 201, 새 예약(IN_USE)과 새 번호
```

### 신고 (FR-RPT-101)

지금 사용 중(`IN_USE`)인 예약의 대표자나 참여자가 그 방이나 옆 방을 신고해요. 옆 방은 V8에 넣은 1–2, 2–3, 3–4, 4–5예요(docs Q-05, 현장 확인 필요). 종류는 비품 고장 `BROKEN`, 창문 열림 `WINDOW_OPEN`, 어질러짐 `MESSY`, 소음 `NOISE`예요. 소음은 `"noiseConfirmed": true`가 없으면 `400 REPORT_NOISE_NOT_CONFIRMED`, 없는 방이면 `404 ROOM_NOT_FOUND`(사용 중지된 방은 신고할 수 있어요), 신고할 수 없는 사람이면 `403 REPORT_NOT_ALLOWED`예요. 메모는 선택이고 500자까지예요. **어질러짐 `MESSY`만 더 좁게 받아요(11/8):** 자기 방만(`403 REPORT_MESSY_OWN_ROOM_ONLY`), 입실 뒤 5분 안만(`409 REPORT_MESSY_TOO_LATE`, 연장으로 생긴 예약도 이 코드), 그 예약에 입실 사진이 있어야(`409 REPORT_CHECKIN_PHOTO_REQUIRED`). 5분은 `mspace.report.messy.window-minutes`.

```bash
curl -X POST localhost:8080/api/reports -H 'X-User-Id: 1' -H 'Content-Type: application/json' \
  -d '{"roomId":2,"category":"NOISE","content":"벽 너머로 계속 소리 질러요","noiseConfirmed":true}'   # → 201, verdict OPEN
```

관리자(`users.role = ADMIN`)는 신고 목록을 보고 판정해요(FR-RPT-103). 사실 `CONFIRMED`, 확인 불가 `UNVERIFIED`, 허위 `FALSE` 중 하나를 대기(`OPEN`)인 신고에 한 번만 줄 수 있어요. 소음은 판정 대상이 아니라 `409 REPORT_NOT_JUDGEABLE`, 이미 판정했으면 `409 REPORT_ALREADY_JUDGED`예요. 판정하면 결과가 알림으로 가요(FR-RPT-104).

- 사실: 신고자에게 고맙다는 알림. 그 방을 바로 전에 쓴 예약(신고자 예약 제외, 신고 전에 입실한 것 중 가장 최근)의 대표자에게 경고 1회. 찾지 못하면 경고 없음
- 확인 불가: 신고자에게 처리하지 않았다는 알림, 벌칙 없음
- 허위: 신고자에게 경고 1회
- 30일 안에 경고 3회면 14일 정지. 사용 위반 경고는 예약·연장 정지(`403 USER_SUSPENDED`), 허위 신고 경고는 신고 정지(`403 REPORT_SUSPENDED`). 두 경고는 따로 셈. 정지가 걸리면 그 전 경고는 다시 세지 않아요
- 숫자는 `mspace.report.penalty`(count, window-days, suspend-days)로 바꿔요

```bash
curl 'localhost:8080/api/reports?verdict=OPEN' -H 'X-User-Id: 9'                     # 관리자만, 최근 신고가 위
curl -X POST localhost:8080/api/reports/7/judge -H 'X-User-Id: 9' -H 'Content-Type: application/json' \
  -d '{"verdict":"FALSE"}'                                                             # → 200
```

### 이용 규칙 (FR-WEB-003)

`GET /api/rules`는 로그인 없이 볼 수 있어요. 예약 규칙 숫자(7일·30분·2시간·노쇼 15/10분·연장)는 실제 검사에 쓰는 설정값을 그대로 내려 줘요. 이용 수칙 8개와 체크인·퇴실 때 보여 줄 한 줄 안내는 `mspace.rules`(docs 요구사항 3.1 원본)에 있어요. 화면과 비서(`usage_rules`)가 같은 내용을 써요. 노쇼 분은 DB 트리거와 같아야 해서 `RulesApiTest`가 둘을 비교해요.

```bash
curl localhost:8080/api/rules   # → {"reservation":{...},"conduct":[...],"reminders":{...}}
```

### 키패드 (장치)

키패드·QR·센서·방 카메라는 사람 로그인 대신 장치 토큰으로 불러요. `.env`의 `MSPACE_DEVICES`에 `deviceId:roomId:토큰`을 쉼표로 이어 적으면 토큰이 그 방에 묶여서, 다른 방 이름으로 보내면 `403 DEVICE_ROOM_MISMATCH`, 등록과 다른 `deviceId`로 보내면 `403 DEVICE_ID_MISMATCH`예요. 예전 공용 토큰 `MSPACE_DEVICE_TOKEN`은 장치를 하나씩 옮기는 동안만 모든 방에 받고, 다 옮기면 비워요. 새 장치 토큰은 공용 토큰과 다른 값이어야 해요(같으면 서버가 뜨지 않아요). 보드 하나가 같은 방 장치 둘을 보내면(ESP32 `sensor-2`·`keypad-2`) 두 줄에 같은 토큰을 적어도 돼요. 다른 방끼리는 안 돼요. 둘 다 비어 있으면 장치 요청은 모두 401이에요. 토큰 값은 개인 메시지로만 주고받아요.

```bash
curl -X POST localhost:8080/api/access/verify -H 'Authorization: Bearer <장치 토큰>' -H 'Content-Type: application/json' \
  -d '{"eventId":"1-1-1","roomId":1,"deviceId":"keypad-1","code":"123456"}'
# → {"result":"OPENED"} 또는 {"result":"DENIED","reason":"WRONG_CODE"|"NOT_YET"|"LOCKED"}
```

번호는 시작 10분 전부터 받아요. 아직 입실하지 않았으면 입실 마감(미리 예약은 시작 + 15분)까지만 열리고, 그 뒤에는 `WRONG_CODE`예요. 이미 입실한 사람은 끝나는 시각 전까지 다시 열 수 있어요.

### 입실 QR (11/6)

예약 만들기·지금 바로 쓰기·연장하기 응답과 `GET /api/reservations/me`에 `qrText`가 있어요. 값은 `MSPACE:<방 번호>:<입실 번호>`(예: `MSPACE:1:123456`)예요. 화면이 이 글자로 QR 그림을 그려 폰에 띄우면, 문 앞 웹캠(mspace-device `qr_door.py`)이 읽어서 키패드와 같은 `POST /api/access/verify`로 물어요(장치 이름 `qr-<방>`). 그래서 열림 규칙·5번 틀리면 1분 잠금(방 단위, 키패드와 같이)도 키패드와 같아요. `accessCode`가 `null`이면(참여자, 노쇼·취소·퇴실) `qrText`도 `null`이에요. core는 그림을 만들지 않아요(새 라이브러리 없음, 11/6 지영 결정).

### 노쇼·자동 퇴실 (1분마다)

서버가 1분마다 시간이 지난 예약을 정리해요. 따로 부를 API는 없어요.

| 지금 상태 | 언제 | 바뀌는 상태 |
|---|---|---|
| `BOOKED` | 체크인 마감이 지나면: 미리 예약은 시작 + 15분, 즉시 시작은 누른 시각 + 10분 (`checkin_deadline`) | `NO_SHOW` |
| `IN_USE` | 끝나는 시각이 되면 | `AUTO_CHECKED_OUT` (퇴실 시각 = 끝나는 시각) |
| `IN_USE` | 재실 센서가 15분 비어 있음 (아래 "재실·CO2 센서") | `AWAY_RELEASED` (퇴실 시각 = 15분이 찬 시각) |

바뀌면 방·대표자·참여자 시간이 바로 풀려요. 다른 작업이 예약을 오래 잡고 있으면 잠금 대기 상한(`mspace.reservation.lock-timeout`, 3초)을 넘긴 쪽만 건너뛰고 다음 번에 다시 해요. 주기는 `mspace.reservation.sweep.interval`(기본 `PT1M`)이고, `MSPACE_SWEEP_ENABLED=false`면 꺼져요. 테스트에서는 꺼 두고(`src/test/resources/config/application.yml`) 시계를 옮겨서 직접 불러요.

### 노쇼 벌칙 (FR-RSV-106)

30일 안에 노쇼가 2번이면 2번째 노쇼가 정리되는 순간부터 14일 동안 이용 정지예요(V9 `user_suspension`). 노쇼를 바꾸는 정리와 같은 트랜잭션에서 걸어요. 대표자 기록만 세요.

- 정지 중에는 대표자로 예약하면 `403 USER_SUSPENDED`(메시지에 끝나는 날짜), 참여자로 넣으면 `403 PARTICIPANT_SUSPENDED`(누구인지는 안 알려 줘요). 미리 예약, 지금 바로 쓰기, 연장(연장도 새 예약이라서) 모두 막아요.
- 정지가 끝나면 횟수는 처음부터 다시 세요. 정지 전에 잡아 둔 예약은 그대로 둬요.
- 정리가 동시에 돌아도 정지는 한 줄만 생겨요(같은 사람 기간 겹침 금지 `one_suspension_at_a_time` + `ON CONFLICT DO NOTHING`).
- 숫자는 `mspace.reservation.no-show-penalty`(2번·30일·14일)에 있어요.
- 자리 비움 3번 = 노쇼 1번(FR-DEV-103): 노쇼 1번을 3점, 자리 비움 1번을 1점으로 더해 6점이면 정지예요. 자리 비움 퇴실도 같은 트랜잭션에서 세요.

### 예약 알림 3종 (FR-NTF-101, 102, 104)

1분마다 시각을 보고 알림을 만들어요. 문구에는 방 이름·시각만 넣어요(이름·학번 없음).

| 종류 | 언제 | 누구에게 | 제목 |
|---|---|---|---|
| `START_SOON` | 미리 예약 시작 10분 전 (지금 바로 쓰기는 안 보냄) | 대표자·참여자 전원 | 입실 10분 전입니다 |
| `END_SOON` | 사용 중, 끝 10분 전. 다음 칸이 비면 "연장할 수 있어요" | 전원 | 종료 10분 전입니다 |
| `NO_SHOW_SOON` | 입실 안 했고 입실 마감 5분 전 | 대표자만 | 노쇼 5분 전입니다 |

- 예약·사람·종류별 한 번만 (`notification_log` PK + `ON CONFLICT DO NOTHING`). 동시에 10번 돌아도 받는 사람마다 종류별로 한 번만 생겨요(건수는 받는 사람 수에 따라 달라요. 노쇼 5분 전은 대표자만).
- 알림은 앱 알림함(V10 `notification`)에 저장돼요. `GET /api/notifications/me`로 최근 50개를 봐요.
- 읽음 표시: 알림 하나는 `POST /api/notifications/{id}/read`(204, 두 번 눌러도 204, 남의 알림은 404 `NOTIFICATION_NOT_FOUND`), 모두 읽음은 `POST /api/notifications/read-all`(`{"count":이번에 바뀐 개수}`), 종 아이콘 점은 `GET /api/notifications/me/unread-count`(`{"count":3}`).
- 메일은 재웅님 담당이에요. `NotificationChannel`을 구현한 빈을 등록하면 알림함 저장이 커밋된 뒤 불려요. 메일이 실패해도 알림함에는 남고, 다시 보내지는 않아요.
- 분(minute) 값은 `mspace.notification.reminder`에서 바꿔요. 끄려면 `MSPACE_REMINDER_ENABLED=false`.

### 재실·CO2 센서 (FR-DEV-101~104)

센서 장치(수연님)가 키패드와 같은 장치 토큰으로 불러요. 형식은 mspace-device `docs/sensor-events-api.md`예요.

```bash
curl -X POST localhost:8080/api/sensor-events -H 'Authorization: Bearer <장치 토큰>' -H 'Content-Type: application/json' \
  -d '{"eventId":"o-1","roomId":1,"deviceId":"sensor-1","kind":"OCCUPANCY","occupied":false,"sources":{"pir":false,"radar":false},"observedAt":"2026-11-06T10:40:00+09:00"}'
curl -X POST localhost:8080/api/sensor-events -H 'Authorization: Bearer <장치 토큰>' -H 'Content-Type: application/json' \
  -d '{"eventId":"c-1","roomId":1,"deviceId":"sensor-1","kind":"CO2","ppm":1080,"source":"virtual","observedAt":"2026-11-06T10:41:00+09:00"}'
# → {"result":"STORED"} 또는 {"result":"DUPLICATE"}. 401 토큰, 400 형식(모르는 kind, ppm 0~10000 밖, 오프셋 없는 시각, 5분 넘는 미래, 없는 방)
```

| 무엇 | 어떻게 |
|---|---|
| 재실 저장 | 처음 이벤트, 상태를 바꾼 이벤트, 늦게 와서 반영은 못 했지만 지금과 다른 상태를 본 이벤트를 `sensor_event`에 남겨요. 10초마다 오는 "그대로" 신호는 `room_presence`의 받은 시각만 고쳐요 |
| 늦게 온 이벤트 | `observedAt`이 지금 상태보다 옛것이면 상태를 덮지 않아요(기록만) |
| 끊김 | 1분 넘게 신호가 없으면 그 방은 "있음"으로 봐요. 다시 오면 그때부터 10·15분을 다시 재요 |
| 자리 비움 알림 | 사용 중 10분 비어 있음 → 대표자에게 `AWAY_SOON` "자리에 안 계시네요" (예약당 한 번) |
| 자리 비움 퇴실 | 15분 → `AWAY_RELEASED`, 방·참여자 시간이 바로 풀려요. 끝 시각이 된 예약은 보통 자동 퇴실 |
| 환기 알림 | 1000ppm 초과가 30~90초 간격으로 3번(처음과 끝 100초 이상) → 그 방 사용자 전원에게 `VENTILATE`. 같은 방 30분에 한 번, 쓰는 사람이 없으면 안 보내요 |

- 숫자는 `mspace.sensor`에 있어요.
- 장치·방마다 토큰을 나누는 것(`403 DEVICE_ROOM_MISMATCH`)은 재웅님 답을 기다려요. 지금은 키패드와 같은 토큰 하나예요.
- "아직 있어요"(FR-DEV-103): `POST /api/reservations/{id}/still-here`. 대표자, 사용 중, **예약당 1번**(11/5 지영 "docs대로", 실제 서비스에도 넣음). 누르면 자리 비움 시계를 누른 시각부터 다시 재고, **누른 뒤에는 10분 비면 알림 없이 바로 퇴실**해요(11/5 지영, `release-after-still-here-minutes`). 돌아왔다 다시 나가면 그때부터 10분이에요. 기본으로 켜져 있고 `MSPACE_STILL_HERE_ENABLED=false`로 끌 수 있어요(끄면 404 `STILL_HERE_DISABLED`). 두 번째는 409 `STILL_HERE_ALREADY_USED`.

### 방 현황 (FR-DEV-102)

홈 화면 "지금 비어 있는 방 N개 중 M개"와 비서가 쓰는 API예요. 로그인 없이 볼 수 있고, 누가 예약했는지는 내려 주지 않아요.

```bash
curl localhost:8080/api/rooms/status
# → {"now":"…+09:00","availableCount":3,"roomCount":5,"rooms":[{"roomId":1,"roomName":"1번 방","minPeople":3,"maxPeople":5,
#     "status":"IN_USE","currentStartAt":"…","currentEndAt":"…","nextStartAt":null,"presence":"OCCUPIED"}, …]}
```

| 값 | 뜻 |
|---|---|
| `status` | 예약 기준. `AVAILABLE` 지금 걸린 예약 없음 · `RESERVED` 예약 시간인데 입실 전 · `IN_USE` 사용 중. `availableCount`는 `AVAILABLE` 수 |
| `currentEndAt` | 지금 예약이 끝나는(방이 비는) 시각. 없으면 `null` |
| `nextStartAt` | 지금 이후 가장 가까운 예약 시작. 없으면 `null` |
| `presence` | 센서가 본 재실(참고). `OCCUPIED` · `EMPTY` · `UNKNOWN`(1분 넘게 신호 없음이거나 센서 없음) |

- "비었는지"는 예약 기준이에요. 예약을 잡을 수 있는지와 같은 기준이라서요. 센서는 예약 없이 사람이 있거나 사용 중인데 비어 있는 걸 보여 주는 참고값이에요.

### 사진 체크인·아웃 (FR-RPT-102, 선택)

입실·퇴실 때 방 사진을 남기는 선택 기능이에요. 사진이 없어도 입실·퇴실은 그대로 돼요. 사진은 core DB(`reservation_photo.data`, V14)에 담아요.

```bash
curl -X POST localhost:8080/api/reservations/12/photos -H 'X-User-Id: 1' -F kind=CHECK_IN -F file=@room.jpg
# → 201 {"reservationId":12,"kind":"CHECK_IN","uploadedAt":"…+09:00","sizeBytes":123456}
curl localhost:8080/api/reservations/12/photos/CHECK_IN -H 'X-User-Id: 1' -o room.jpg   # 사진 그대로
```

- 올리기: 그 예약의 대표자·참여자, 종류별 1장(두 번째는 409 `PHOTO_ALREADY_UPLOADED`). 입실 사진은 사용 중일 때, 퇴실 사진은 사용 중이거나 퇴실한 뒤 30분 안(그 밖엔 409 `PHOTO_NOT_ALLOWED_NOW`)
- 파일: JPEG·PNG, 1장 최대 2MB. 형식은 파일 앞부분으로 봐요(400 `PHOTO_INVALID`, 크면 400 `PHOTO_TOO_LARGE`)
- 보기: 그 예약 사람들과 관리자(신고 판정)만. 아니면 404 `PHOTO_NOT_FOUND`
- 올린 지 30일 지나면 1시간마다 지워요. 숫자는 `mspace.photo`에서 바꿔요
- 배포: nginx 기본 업로드 상한이 1MB라 `client_max_body_size 4m`이 필요해요 (재웅님 확인)

### 관리자 도구 (11/6)

관리자(`users.role = ADMIN`)만 써요. 아니면 403 `ADMIN_ONLY`. 화면은 프론트 몫이고 여기는 API만 있어요.

```bash
# 강제 퇴실 (FR-RPT-104 퇴출). 사유는 빼도 되고 200자까지. 대표자·참여자 모두 알림 FORCED_CHECKOUT
curl -X POST localhost:8080/api/reservations/12/force-checkout -H 'X-User-Id: 4' -H 'Content-Type: application/json' \
     -d '{"reason":"신고 확인, 소음 계속"}'
# → {"id":12,"status":"CHECKED_OUT"}. 없는 예약 404, 사용 중이 아니면 409 RESERVATION_NOT_IN_USE

# 이용 통계 + 센서 (from·to는 서울 날짜, to 포함, 92일까지. 둘 다 빼면 오늘까지 7일, from만 주면 그날부터 7일, to만 주면 그날까지 7일)
curl 'localhost:8080/api/stats/usage?from=2026-11-01&to=2026-11-30' -H 'X-User-Id: 4'
# → {"from":…,"to":…,"days":30,"total":{…},"rooms":[{"roomId":1,"roomName":"1번 방",
#     "reservations":{"total","canceled","pending","noShow","used","forced","awayReleased","autoCheckedOut"},
#     "rates":{"noShow","actualUse","utilization"},"minutes":{"booked","used","avgUsed"},"shortUses","uniqueUsers",
#     "sensor":{"co2Readings","co2AvgPpm","co2MaxPpm","co2OverRate","thresholdPpm","ventilationAlerts",
#               "occupiedMinutes","occupiedWithoutReservationMinutes"}}, …],"byHour":[{"hour":0,"reservations":0,"used":0}, …]}

# 경고·정지 목록 (최근 days일, 기본 30, 1–365)
curl 'localhost:8080/api/penalties?days=30' -H 'X-User-Id: 4'
# → {"days":30,"warnings":[{"id","member":{"id","studentId","name"},"reason":"VIOLATION|FALSE_REPORT","reportId","reservationId","createdAt"}],
#     "suspensions":[{"scope":"USE|REPORT","id","member","reason":"NO_SHOW|WARNINGS|FALSE_REPORT","startsAt","endsAt","active"}]}

# AI가 처리한 일 (자동 처리 5개, 11/12). 날짜는 서울, 빼면 오늘. 최근 것부터
curl 'localhost:8080/api/admin/ops-actions?date=2026-11-12' -H 'X-User-Id: 4'
# → [{"id":3,"kind":"VENT_SOON|ROOM_HOT|SENSOR_DOWN|OVERTIME|UNBOOKED","roomId":1,"roomName":"1번 방",
#     "reservationId":12,"startedAt","detectedAt","detail":"CO2 910ppm, 최근 5분 790→910ppm","notified":3}]
#    SENSOR_DOWN·UNBOOKED는 관리자 기록만이라 reservationId null, notified 0

# 신고 증거 사진: 직전 이용자의 퇴실 사진, 신고자의 입실 사진
curl localhost:8080/api/reports/7/evidence -H 'X-User-Id: 4'
# → {"reportId":7,"previous":{"reservationId":11,"checkedInAt","checkedOutAt",
#     "photo":{"kind":"CHECK_OUT","exists":true,"url":"/api/reservations/11/photos/CHECK_OUT","uploadedAt"}},
#     "reporter":{"reservationId":12,"photo":{"kind":"CHECK_IN","exists":false,"url":null,"uploadedAt":null}},
#     "note":"신고자가 입실 사진을 올리지 않았어요 (또는 30일이 지나 지워졌어요)."}
# 카메라 신고(정리 확인으로 생긴 신고)는 신고자 예약이 없어서 "reporter":null
```

- 비율은 0–1 소수 셋째 자리, 분모가 0이면 null. 노쇼율·실사용률 분모는 취소·대기(BOOKED) 뺀 예약, 이용률은 기간 중 지난 시간(하루 24시간) 대비 실제 이용 시간
- 짧은 이용: 예약 시간의 절반도 안 쓰고 나간 것 (사용 중 제외)
- 재실 시간은 센서가 상태가 바뀔 때만 기록해서, 바뀐 기록 사이를 그 상태로 본 근삿값이에요. "예약 없이 있던 시간" = 센서 "있음" 구간 중 입실한 예약 구간과 겹치지 않는 부분(구간 차집합, 시간 합계끼리 빼는 게 아님)
- 직전 이용 예약은 경고 대상과 같은 규칙(같은 방, 신고자 본인이 대표인 예약은 연장 전 것까지 모두 제외, 신고 전 입실 중 가장 늦은 것, 노쇼 제외). 없으면 `previous: null`
- 카메라 신고의 `previous`는 "가장 최근 입실 예약"이 아니라 정리 확인 대상 예약이에요. 그 사이 다른 예약이 입실했으면 둘이 다를 수 있어요
- 카메라 인원 수는 core에 없어 통계에 없어요
- **AI 자동 처리 5개** (1분마다, 숫자는 아래 "실험 기록: 자동 처리 규칙"으로 고름): 미리 환기(CO2가 곧 1000을 넘을 것 같으면 사용 중 팀 전원) · 예약 전 더움(시작 10분 전에, 최근 온도 기울기로 예측한 시작 시각 온도가 28도 이상이면 대표자) · 시간 넘김(자동 퇴실 5–15분 뒤에도 끝나기 전부터 계속 "있음"이면 그 팀, 다음 예약이 10분 안이면 안 함) · 센서 고장(5분 신호 없음, 모든 방이 같이 끊기면 안 함) · 예약 없는 사용(10분 넘게 "있음", 예약 10분 전·끝난 뒤 30분은 그 팀). 학생 알림은 예약마다 종류별 1번, 관리자용 2종은 기록만. 재실 신호가 끊긴 방은 "있음"을 믿지 않아 시간 넘김·예약 없는 사용을 안 봐요. 하루 운영 보고 `facts.aiActions`에 종류별 건수가 들어가고 요약 글에 "오늘 AI가 미리 환기 3번…"처럼 써요

## 실험 기록: 신고 판정기 정확도 (FR-RPT-103)

학생 신고(창문 열림·어질러짐)를 판정기가 사실·확인 불가로 정할 때, 정답과 얼마나 맞는지 판마다 재서 남겨요. **최신이 맨 위**예요. 채택하지 않은 판도 지우지 않아요. 같은 문제집끼리만 비교해요.

- **목표 (합격선):** 처음 보는 사례 정확도 98% 이상 · 심각 오류 0 (11/12 지영)
- **심각 오류** = 실제로는 아닌데 사실로 봄 → 죄 없는 앞 팀 대표자가 경고를 받음 (억울한 경고)
- **정답 기준 (11/12 지영):** 창문이 정말 열렸으면 사실, 애매하면 확인 불가, 닫혔으면 확인 불가·허위 둘 다 정답. 점수는 판정기 결과에 확신도 0.8 기준과 `apply-false` 꺼짐(허위 → 확인 불가)만 적용해 셈. 창문 사실은 `apply-window-confirmed`, 어질러짐은 `camera-warn`을 **켠 상태** 기준이에요. 운영 기본값은 둘 다 꺼져 있어 지금 학생에게는 경고가 가지 않아요
- **방법:** 판마다 지시문(`GeminiReportJudge.PROMPT`)을 **한 가지만** 바꾸고 연습 문제로 3번 잼. 처음 보는 문제는 지시문을 고칠 때 보지 않음
- 돌리는 법: `GEMINI_EVAL_API_KEY=... EVAL_SET=dev EVAL_RUNS=3 ./mvnw test -Dtest=ReportJudgeEval -Dsurefire.failIfNoSpecifiedTests=false` → `target/eval/report-judge-eval.md`. `EVAL_SET`은 `dev`(연습) · `holdout,fresh`(처음 보는 것) · `all`
- 점수판(그래프): https://claude.ai/artifact/PbbEpcXQ8hR5Mri2phU5hx

### 문제집 (`src/test/resources/eval/report-cases.json`)

| 이름 | 만든 날 | 문제 수 | 내용 |
|---|---|---|---|
| `dev` (연습) | 11-12 | 36 | 창문 24 (열림 10 · 닫힘 7 · 애매 7) · 어질러짐 12 (맞음 7 · 안 맞음 3 · 판정기 안 부름 2) |
| `holdout` (처음 보는 것 ①) | 11-12 | 15 | 창문 12 (열림 5 · 닫힘 4 · 애매 3) · 어질러짐 3. 지시문 고칠 때 안 봄 |
| `fresh` (처음 보는 것 ②) | 11-12 3판 전 | 15 | 창문 11 (열림 4 · 닫힘 4 · 애매 3, 포근한 날 온도 2도만 떨어진 창문 등) · 어질러짐 4. 3판 지시문을 고치기 **전에** 만듦 |

- 4판부터는 센서 값을 서버처럼 신고 전 3시간 것만 넣어요(H07·F09의 이른 값 몇 개가 빠짐, 정답은 그대로). 1~3판은 이 값까지 넣고 쟀어요
- 한계: 사례·센서 숫자·창문 기준(바깥 공기 약 400~550ppm, 온도 3도)은 모두 Claude가 만든 가짜예요. 수연 실제 센서로 다시 재야 믿을 수 있어요. 이름·학번은 없어요
- 모델 `gemini-3.5-flash-lite` (무료 등급). 한 판(연습 36개 × 3번) = 무료 호출 약 99번

### 판별 기록 (최신이 맨 위)

| 판 | 판단 | 연습 36개 (3번 평균) | 처음 보는 것 (3번 평균) | 심각 오류 (3번 합) | 응답 시간 평균 (최대) | 무엇을 바꿨고 왜 변했나 |
|---|---|---|---|---|---|---|
| 4판 · 11-12 17:10 · `report-gemini-v4` (지금 코드) | **채택** | 95.4% (32·36·35) | **98.9%** (29·30·30 / 30) | 0 | 연습 2.8초 (20.0초) · 처음 1.5초 (8.1초) | 어질러짐: 카메라 물건 이름을 Gemini에 보내지 않고 "물건 수(대략)·남아 있을 확신도"만 보냄 (11/12 지영). **Gemini가 직접 답한 170번은 모두 맞음.** 틀린 6번은 모두 Google 503으로 규칙이 대신 정한 창문(연습은 108번 중 17번이 503이라 3판보다 낮게 나옴). 처음 보는 것은 3판과 같은 98.9%, 억울한 경고 0 |
| 2판 다시 · 11-12 13:00 · v2로 `fresh` 15개 | 탈락 | - | **93.3%** (13·15·14 / 15) | 1 | 1.0초 (1.8초) | 3판과 공정하게 비교하려고 2판 지시문에도 새 15개를 풀림. 포근한 날 온도가 2도만 떨어진 창문(F02)을 "열림"으로 봐 억울한 경고 1. 같은 15개에서 3판은 97.8%·심각 0 → 3판 씀 |
| 3판 · 11-12 11:15 · `report-gemini-v3` | 비교 (4판이 이어받음) | **98.1%** (36·35·35) | **98.9%** (30·30·29 / 30) | 연습 1 · 처음 0 | 1.0초 (3.1초) | 어질러짐: 신고 글이 쏟음·얼룩처럼 물건이 아닌 걸 말하는데 카메라엔 상관없는 소지품만 있으면 확인 불가 (M07 정답 확인 불가, 11/12 지영). **Gemini가 직접 틀린 건 연습 0번.** 연습에서 틀린 2번은 Google 503으로 규칙 판정기가 대신 정한 것(M07 심각 1도 규칙이 낸 것). 처음 보는 것 1번은 글 없는 컵(F14)을 사실로 봤지만 확신도 0.75 |
| 2판 · 11-12 11:00 · `report-gemini-v2` | 비교 | **99.1%** (36·36·35) | 100% (15·15·15 / `holdout`만) | 1 | 0.9초 (1.7초) | 창문 "사실"은 CO2가 30분 안에 약 550ppm 아래 **그리고** 온도가 3도 넘게 떨어질 때만, 하나만 보이면 확인 불가. 1판의 억울한 경고 8 → 1. 남은 1은 M07(음료 쏟음 신고 + 카메라 노트북). `fresh`를 풀면 93.3%라 3판보다 약함 |
| 1판 · 11-12 10:40 · `report-gemini-v1` (#121 그대로) | 기준 | **87.0%** (31·31·32) | - | 8 | 1.0초 (1.8초) | 창문 열린 건 거의 맞히지만, 애매한 3개(온도 센서 없음 W18 · 온도 그대로 W20 · CO2 천천히 W21)를 열림으로 봐 판마다 억울한 경고 2~3. 글 없는 어질러짐(M05)은 확인 불가로 봄. Google 503 4번 |
| 규칙 · 11-12 09:45 · `report-rules-v1` | 예비 | **69.4%** (25) | 60.0% (`holdout`) · 66.7% (`fresh`) | 1 | 0초 | 창문 열림은 볼 근거가 없어 늘 확인 불가라 정말 열린 것을 다 놓침. 어질러짐은 신고 글을 안 읽어 M07을 사실로 봄. Gemini가 실패(503·시간 초과)할 때만 대신 씀 |

### 4판에서 틀린 사례

| 문제집 | 회차 | 사례 | 정답 | 판정(확신도) → 적용 | 누가 | 왜 |
|---|---|---|---|---|---|---|
| 연습 | 1 | W01 · W02 · W05 · W10 창문 열림 (CO2·온도 함께 뚝 떨어짐) | 사실 | 확인 불가 → 확인 불가 | 규칙 | Google 503으로 규칙이 대신 정함. 규칙은 창문을 늘 확인 불가 |
| 연습 | 3 | W09 창문 열림 · 센서 분명 | 사실 | 확인 불가 → 확인 불가 | 규칙 | 위와 같음 (503) |
| `fresh` | 1 | F05 창문 열림 · 값 들쑥날쑥(±50), 흐름 분명 | 사실 | 확인 불가 → 확인 불가 | 규칙 | 위와 같음 (503) |

- 6번 모두 억울한 경고가 아니라 놓친 것(학생 손해 없음)이에요. 503 때 한 번 다시 물으면 줄일 수 있지만 무료 호출이 늘어요

### 3판에서 틀린 사례

| 문제집 | 회차 | 사례 | 정답 | 판정(확신도) → 적용 | 누가 | 왜 |
|---|---|---|---|---|---|---|
| 연습 | 2 | M07 어질러짐 "바닥에 음료를 쏟아 놓고 갔어요" · 카메라 laptop 0.91 | 확인 불가 | 사실(0.91) → 사실 (심각) | 규칙 | Google 503으로 규칙이 대신 정함. 규칙은 신고 글을 안 읽어 카메라 물건만 보고 사실 |
| 연습 | 3 | W05 창문 열림 · CO2 900→440, 온도 22→16.5 | 사실 | 확인 불가 → 확인 불가 | 규칙 | Google 503으로 규칙이 대신 정함. 규칙은 창문을 늘 확인 불가 |
| `fresh` | 3 | F14 어질러짐 · 신고 글 없음 · 카메라 cup 0.89 | 사실 | 사실(0.75) → 확인 불가 | Gemini | 판정은 맞지만 글이 없어 확신도를 낮게 줌 → 벌칙 없음 |

- 결과 원본: 프로젝트 공유 폴더 `mspace/eval/2026-11-12-신고판정기-*.md`
- 남은 것: ① 503 때 한 번 다시 묻기(틀린 3개 중 2개가 503, 대신 무료 호출이 늘어남) ② 수연 실제 센서 값으로 창문 기준 다시 확인 ③ `apply-false`는 꺼 둠: Gemini가 애매한 창문에도 허위를 자주 냄(3판 연습 3번 합 10번)

### 제약

- **무료 한도:** AI Studio 무료 키라 하루 부를 수 있는 횟수가 정해져 있어요. 한 판을 3번 돌리면 100번 가까이 불러서 하루에 잴 수 있는 판이 적어요
- **키 나눠 씀:** 서버 실제 판정과 경고 철회 판단기 측정은 같은 키(`GEMINI_API_KEY`)를 함께 써요. 신고 판정기 측정은 측정용 키(`GEMINI_EVAL_API_KEY`)를 따로 써요. 측정용 키가 없거나 비어 있으면 서버 키로 재서 서버와 무료 한도를 같이 써요
- **사진은 안 보냄:** 판정기는 글자와 숫자만 봐요. 창문 열림은 신고 글도 안 보내고 센서 숫자만 봐요

## 실험 기록: 자동 처리 규칙 5개 (11/12)

관리자 카드 5개를 AI가 숫자 규칙으로 스스로 처리해요(11/12 지영 "관리자는 어떻게 처리됐나만 봄"). 판마다 한 가지만 바꿔 같은 사례로 다시 쟀어요. **최신이 맨 위**예요.

> **아래 숫자는 모두 가짜 사례 정확도예요.** 실제 센서로 잰 정확도가 아니고, 실제 방에서는 더 낮을 수 있어요. 실제 센서 기록(재웅 CSV)으로 재면 그 숫자를 따로 적어요 (11/12 수연 리뷰).

| 규칙 | 누구에게 | 0판 가짜 사례 정확도 (처음 보는 것) | 채택 판 가짜 사례 정확도 (처음 보는 것) |
|---|---|---|---|
| 미리 환기 | 사용 중 팀 전원 알림 | 87.0% | **86.0%** (11판) |
| 예약 전 더움 | 대표자 알림 | 82.7% | **89.0%** (7판) |
| 센서 고장 | 관리자 기록만 | 59.0% | **99.3%** (3판) |
| 시간 넘김 | 자동 퇴실된 팀 전원 알림 | 81.7% | **94.0%** (2판) |
| 예약 없는 사용 | 관리자 기록만 | 47.3% | **96.7%** (5판) |

- **방법:** 실제 센서 기록이 아직 없어서 방·사람·센서를 흉내 낸 가짜 사례를 만들어요. 사례마다 "실제로 무슨 일이 있었나"(정답)는 숨은 값으로 정하고, 규칙은 센서가 본 값(잡음·끊김·튀는 값 포함)만 봐요. 규칙마다 연습 300개(씨앗 1)로 고치고, 처음 보는 300개(씨앗 2)는 고칠 때 보지 않아요
- **판 고르기:** 연습 점수가 가장 높은 판. 같으면 잘못 알림이 적은 쪽, 1개 차이면 단순한 쪽(시간 넘김 2판). 판단 "탈락"은 그때까지 가장 높은 판보다 연습 점수가 낮은 판이에요
- **미리 환기 11판은 예외:** 8–11판은 가짜 사례가 아니라 실제 센서 기록(실습실)으로 고르고, 가짜 사례 점수는 비교로만 적었어요. 실제 기록에서는 1000 바로 아래를 천천히 오르내리는 일이 많아 6판의 헛알림이 많았어요. 대신 가짜 사례(작은 방, 빨리 오름)에서는 놓침이 24 → 36으로 늘어요 (11/12 지영)
- **정답 기준:** 미리 환기 = 실제 CO2가 10분 안에 1000을 넘음 · 더움 = 시작할 때 실제 28도 이상 · 센서 고장 = 장치가 정말 죽음(와이파이 잠깐 끊김·학교망 끊김은 아님) · 시간 넘김 = 그 팀이 끝난 뒤 5분 넘게 남음 · 예약 없는 사용 = 예약 없는 사람이 5분 넘게 있음(일찍 온 팀·늦게 나간 팀은 아님)
- **90% 근처에서 멈춘 이유:** 미리 환기·더움은 앞으로 창문을 열지, 에어컨을 켤지를 센서로 미리 알 수 없어요. 시간 넘김은 다음 팀이 바로 들어오면 레이더로 두 팀을 나눌 수 없어 알리지 않아요(놓침 18개 대부분)
- **한계:** 사례 모양(사람 수·잡음·끊김 길이)은 Claude가 정한 가짜예요. 실제 센서 기록(재웅 CSV)으로 다시 재야 믿을 수 있어요. 이름·학번은 없어요. 외부 AI는 안 써요(무료 한도 0번)
- 돌리는 법: `./mvnw -o test -Dtest=OpsRulesEval -Dsurefire.failIfNoSpecifiedTests=false` → `target/eval/ops-rules-eval.md` (1초 안)
- 점수판(그래프): https://claude.ai/artifact/PbbEpcXQ8hR5Mri2phU5hx

### 미리 환기

| 판 | 판단 | 가짜 사례 연습 300 | 가짜 사례 처음 보는 것 300 | 잘못 알림 · 놓침 (처음 보는 것) | 무엇을 바꿨나 |
|---|---|---|---|---|---|
| 11판 | **채택** | 88.7% | 86.0% | 6 · 36 | 1분에 15 → 20 (10판 기준) |
| 10판 | 탈락 | 91.0% | 88.0% | 8 · 28 | 1분에 10 → 15 (9판 기준) |
| 9판 | 비교 | 92.3% | 89.3% | 8 · 24 | 960 아래는 1분에 10 넘게 오를 때만 8분 예측 (8판 기준. 1000 근처를 천천히 오르내릴 때 헛알림 막기) |
| 8판 | 비교 | 92.3% | 89.3% | 8 · 24 | 지금 값이 960 이상이고 오르면 30분 앞까지 예측 (6판 기준. 큰 방은 천천히 올라 8분 예측이 늦음) |
| 7판 | 비교 | 92.3% | 90.7% | 9 · 19 | 800 → 750 아래면 안 봄 (6판 기준. 놓친 것 중 빨리 오르는데 800 아래) |
| 6판 | 비교 | 92.3% | 89.3% | 8 · 24 | 지금 값을 최근 3개의 가운데 값으로 (5판 기준. 헛알림 중 마지막 값이 튄 것) |
| 5판 | 비교 | 92.0% | 88.3% | 17 · 18 | 예측 10분 → 8분 (2판 기준, 잡음으로 넘을 것 같다고 보는 것 줄이기) |
| 4판 | 비교 | 90.3% | 88.7% | 27 · 7 | 기울기를 재는 값 5분 → 10분 (2판 기준. 헛알림 23개 중 17개가 창문·나감 없이 잡음 탓) |
| 3판 | 비교 | 90.0% | 88.3% | 22 · 13 | 5분 안 값이 4개는 있어야 봄 |
| 2판 | 비교 | 90.0% | 88.3% | 22 · 13 | 지금 값이 800 아래면 안 봄 |
| 1판 | 비교 | 87.7% | 89.3% | 24 · 8 | 두 값 대신 5분 안 모든 값으로 기울기(최소제곱) |
| 0판 | 기준 | 86.3% | 87.0% | 30 · 9 | 기준: 최근 5분 첫 값·끝 값 두 개로 오르는 속도 → 10분 뒤 1000 넘으면 |

### 예약 전 더움

| 판 | 판단 | 가짜 사례 연습 300 | 가짜 사례 처음 보는 것 300 | 잘못 알림 · 놓침 (처음 보는 것) | 무엇을 바꿨나 |
|---|---|---|---|---|---|
| 7판 | **채택** | 90.7% | 89.0% | 21 · 12 | 믿는 값 15분 → 60분 (5판에서 놓친 것 대부분이 센서 멈춘 방) |
| 6판 | 비교 | 87.3% | 88.3% | 15 · 20 | 마지막 값이 오래됐으면 그 시각부터 시작까지 예측 (5판 기준) |
| 5판 | 비교 | 87.3% | 88.3% | 15 · 20 | 기울기를 튀는 값에 강하게: 최근 3개·오래된 3개의 가운데 값으로 |
| 4판 | 탈락 | 86.3% | 88.0% | 13 · 23 | 믿는 값 5분 → 15분 |
| 3판 | 탈락 | 80.0% | 76.7% | 27 · 43 | 기울기로 시작 시각 온도를 예측 |
| 2판 | 탈락 | 83.3% | 84.7% | 22 · 24 | 5분보다 오래된 값은 안 믿음 |
| 1판 | 탈락 | 85.7% | 85.0% | 40 · 5 | 최근 값 하나 대신 3개의 가운데 값 |
| 0판 | 기준 | 86.7% | 82.7% | 48 · 4 | 기준: 가장 최근 온도 하나가 28도 이상이면 |

### 센서 고장

| 판 | 판단 | 가짜 사례 연습 300 | 가짜 사례 처음 보는 것 300 | 잘못 알림 · 놓침 (처음 보는 것) | 무엇을 바꿨나 |
|---|---|---|---|---|---|
| 3판 | **채택** | 98.3% | 99.3% | 2 · 0 | 3분 → 5분 |
| 2판 | 비교 | 91.7% | 92.7% | 22 · 0 | 모든 방이 같이 끊기면 네트워크 문제로 보고 안 알림 |
| 1판 | 비교 | 85.7% | 86.0% | 42 · 0 | 60초 → 3분 |
| 0판 | 기준 | 64.3% | 59.0% | 123 · 0 | 기준: 신호가 60초 안 오면 |

### 시간 넘김

| 판 | 판단 | 가짜 사례 연습 300 | 가짜 사례 처음 보는 것 300 | 잘못 알림 · 놓침 (처음 보는 것) | 무엇을 바꿨나 |
|---|---|---|---|---|---|
| 4판 | 탈락 | 90.7% | 85.7% | 43 · 0 | 다음 예약 10분 안이어도 알림 (3판에서 놓친 13개가 모두 다음 팀이 먼저 온 때) |
| 3판 | 비교 | 94.7% | 92.3% | 6 · 17 | "계속 있음" 대신 "끝날 때 있음 + 지금 있음" |
| 2판 | **채택** | 94.3% | 94.0% | 0 · 18 | 다음 예약이 10분 안에 시작하면 안 알림 |
| 1판 | 비교 | 90.7% | 88.0% | 35 · 1 | 끝나기 전부터 계속 "있음"일 때만 |
| 0판 | 기준 | 88.0% | 81.7% | 55 · 0 | 기준: 끝나고 5~15분 사이에 "있음"이면 |

### 예약 없는 사용

| 판 | 판단 | 가짜 사례 연습 300 | 가짜 사례 처음 보는 것 300 | 잘못 알림 · 놓침 (처음 보는 것) | 무엇을 바꿨나 |
|---|---|---|---|---|---|
| 5판 | **채택** | 99.3% | 96.7% | 0 · 10 | "있음"이 5분 → 10분 이어져야 |
| 4판 | 비교 | 98.0% | 97.0% | 9 · 0 | 예약 끝나고 15분 → 30분까지 그 팀으로 봄 |
| 3판 | 비교 | 86.0% | 85.3% | 44 · 0 | 예약 끝나고 15분까지는 그 팀으로 봄 |
| 2판 | 비교 | 62.7% | 64.0% | 108 · 0 | 예약 시작 10분 전부터는 그 팀으로 봄 |
| 1판 | 비교 | 56.3% | 56.7% | 130 · 0 | "있음"이 5분 이어져야 |
| 0판 | 기준 | 43.7% | 47.3% | 158 · 0 | 기준: 예약 시간이 아닌데 "있음"이면 바로 |

## 폴더 구조

기능별로 나누고, 기능 안은 포트·어댑터로 나눠요 (팀 컨벤션 3절).

```
kr.ac.chosun.mspace
├── reservation        예약 (지영)
│   ├── domain         엔티티, 규칙, 상태
│   ├── application    서비스, 포트(인터페이스)
│   └── adapter
│       ├── in/web     컨트롤러, 요청·응답 DTO
│       └── out        DB, 메일, 키패드 구현
├── member             회원·로그인
├── room               방
├── access             출입(키패드)
├── sensor             재실·CO2 센서
├── notification       알림
└── common             공통 오류, 설정
```

## 브랜치

- `main`: 확인이 끝난 버전만 (시연용) · `develop`: 매일 합치는 곳
- 작업은 `develop`에서 `feat/<이슈번호>-<영어>` 브랜치를 따서 PR로 합쳐요. 리뷰 1명 승인 후 Squash merge.

## 버전 올리기 (SonarQube 80% 게이트용)

- `pom.xml`의 `<version>`은 팀 일정 버전과 맞춰요: v0.1 작업 중 `0.1.0-SNAPSHOT`, v0.2 작업 중 `0.2.0-SNAPSHOT`, v0.3 작업 중 `0.3.0-SNAPSHOT`.
- `main`에 버전을 낸 뒤 `develop`에서 다음 버전으로 올리는 PR을 바로 하나 만들어요.
- 왜: 팀 SonarQube(Community Build)는 PR만 따로 분석하지 못해요. 그래서 SonarQube 프로젝트의 새 코드 기준을 **이전 버전(Previous version)**으로 두고, 버전이 바뀐 뒤 고친 코드를 "새 코드"로 봐요. main으로 가는 PR은 한 버전의 변경분이라, 이 범위가 PR 변경분과 거의 같아요.
- 버전을 안 올리면 기준점이 그대로라, 처음 분석한 뒤의 코드가 전부 새 코드로 잡혀요.

## DB 변경 규칙

- 테이블은 Flyway 파일(`V<번호>__<설명>.sql`)로만 바꿔요. JPA는 검사만 해요(`ddl-auto: validate`).
- **이미 합쳐진 V 파일은 절대 고치지 않아요.** 바꿀 게 있으면 다음 번호 파일을 새로 만들어요.
- `V1__init_schema.sql`은 팀이 검증한 `schema-v2.sql`(동시 예약 100건 중복 0건 확인)의 제약·트리거를 그대로 쓰고, 테이블·컬럼·상태 이름만 요구사항 명세 v0.4에 맞췄어요.
