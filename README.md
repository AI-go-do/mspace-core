# mspace-core

M-SPACE 예약 시스템의 백엔드(core)예요. 조선대 IT융합대학 M-SPACE 스터디룸의 **예약, 출입, 회원** API를 열고, 겹침 금지·대표자 1건 같은 예약 규칙을 DB 제약으로 지켜요.

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

관리자 화면이 아직 없어서 SQL로 정해요. 메일 인증으로 먼저 가입한 뒤에 바꿔요. 관리자만 `GET /api/reservations/stats`(WEB/AGENT 예약 건수)를 볼 수 있어요.

```bash
docker compose exec db sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "UPDATE users SET role = '"'"'ADMIN'"'"' WHERE email = '"'"'내메일@chosun.ac.kr'"'"'"'
```

### 로그인 붙기 전 임시 방법

로그인(재웅님 담당)이 붙기 전까지는 `X-User-Id` 헤더에 회원 id를 넣어서 "누가 보낸 요청인지"를 알려 줘요. `MSPACE_DEV_HEADER=true`일 때만 켜지고, 기본값은 꺼짐이라 운영에서는 항상 401이에요.

```bash
MSPACE_DEV_HEADER=true ./mvnw spring-boot:run
curl 'localhost:8080/api/members?studentId=20230002' -H 'X-User-Id: 1'   # 참여자 학번 검색 → {"studentId":"20230002","name":"홍*동"}
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

지금 사용 중(`IN_USE`)인 예약의 대표자나 참여자가 그 방이나 옆 방을 신고해요. 옆 방은 V8에 넣은 1–2, 2–3, 3–4, 4–5예요(docs Q-05, 현장 확인 필요). 종류는 비품 고장 `BROKEN`, 창문 열림 `WINDOW_OPEN`, 어질러짐 `MESSY`, 소음 `NOISE`예요. 소음은 `"noiseConfirmed": true`가 없으면 `400 REPORT_NOISE_NOT_CONFIRMED`, 없는 방이면 `404 ROOM_NOT_FOUND`, 신고할 수 없는 사람이면 `403 REPORT_NOT_ALLOWED`예요. 메모는 선택이고 500자까지예요. 사진과 결과 알림은 아직이에요.

```bash
curl -X POST localhost:8080/api/reports -H 'X-User-Id: 1' -H 'Content-Type: application/json' \
  -d '{"roomId":2,"category":"NOISE","content":"벽 너머로 계속 소리 질러요","noiseConfirmed":true}'   # → 201, verdict OPEN
```

관리자(`users.role = ADMIN`)는 신고 목록을 보고 판정해요(FR-RPT-103). 사실 `CONFIRMED`, 확인 불가 `UNVERIFIED`, 허위 `FALSE` 중 하나를 대기(`OPEN`)인 신고에 한 번만 줄 수 있어요. 소음은 판정 대상이 아니라 `409 REPORT_NOT_JUDGEABLE`, 이미 판정했으면 `409 REPORT_ALREADY_JUDGED`예요. 경고·정지는 벌칙이 확정되면 붙여요.

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

키패드는 사람 로그인 대신 장치 토큰으로 불러요. `.env`의 `MSPACE_DEVICE_TOKEN`이 비어 있으면 키패드 요청은 모두 401이에요. 토큰 값은 수연님 키패드 중계와 개인 메시지로만 주고받아요.

```bash
curl -X POST localhost:8080/api/access/verify -H 'Authorization: Bearer <장치 토큰>' -H 'Content-Type: application/json' \
  -d '{"eventId":"1-1-1","roomId":1,"deviceId":"keypad-1","code":"123456"}'
# → {"result":"OPENED"} 또는 {"result":"DENIED","reason":"WRONG_CODE"|"NOT_YET"|"LOCKED"}
```

번호는 시작 10분 전부터 받아요. 아직 입실하지 않았으면 입실 마감(미리 예약은 시작 + 15분)까지만 열리고, 그 뒤에는 `WRONG_CODE`예요. 이미 입실한 사람은 끝나는 시각 전까지 다시 열 수 있어요.

### 노쇼·자동 퇴실 (1분마다)

서버가 1분마다 시간이 지난 예약을 정리해요. 따로 부를 API는 없어요.

| 지금 상태 | 언제 | 바뀌는 상태 |
|---|---|---|
| `BOOKED` | 체크인 마감이 지나면: 미리 예약은 시작 + 15분, 즉시 시작은 누른 시각 + 10분 (`checkin_deadline`) | `NO_SHOW` |
| `IN_USE` | 끝나는 시각이 되면 | `AUTO_CHECKED_OUT` (퇴실 시각 = 끝나는 시각) |

바뀌면 방·대표자·참여자 시간이 바로 풀려요. 다른 작업이 예약을 오래 잡고 있으면 잠금 대기 상한(`mspace.reservation.lock-timeout`, 3초)을 넘긴 쪽만 건너뛰고 다음 번에 다시 해요. 주기는 `mspace.reservation.sweep.interval`(기본 `PT1M`)이고, `MSPACE_SWEEP_ENABLED=false`면 꺼져요. 테스트에서는 꺼 두고(`src/test/resources/config/application.yml`) 시계를 옮겨서 직접 불러요.

### 노쇼 벌칙 (FR-RSV-106)

30일 안에 노쇼가 2번이면 2번째 노쇼가 정리되는 순간부터 14일 동안 이용 정지예요(V9 `user_suspension`). 노쇼를 바꾸는 정리와 같은 트랜잭션에서 걸어요. 대표자 기록만 세요.

- 정지 중에는 대표자로 예약하면 `403 USER_SUSPENDED`(메시지에 끝나는 날짜), 참여자로 넣으면 `403 PARTICIPANT_SUSPENDED`(누구인지는 안 알려 줘요). 미리 예약, 지금 바로 쓰기, 연장(연장도 새 예약이라서) 모두 막아요.
- 정지가 끝나면 횟수는 처음부터 다시 세요. 정지 전에 잡아 둔 예약은 그대로 둬요.
- 정리가 동시에 돌아도 정지는 한 줄만 생겨요(같은 사람 기간 겹침 금지 `one_suspension_at_a_time` + `ON CONFLICT DO NOTHING`).
- 숫자는 `mspace.reservation.no-show-penalty`(2번·30일·14일)에 있어요. 자리 비움 3번 = 노쇼 1번(FR-DEV-103)은 자리 비움 기능과 같이 붙여요.

### 예약 알림 3종 (FR-NTF-101, 102, 104)

1분마다 시각을 보고 알림을 만들어요. 문구에는 방 이름·시각만 넣어요(이름·학번 없음).

| 종류 | 언제 | 누구에게 | 제목 |
|---|---|---|---|
| `START_SOON` | 미리 예약 시작 10분 전 (지금 바로 쓰기는 안 보냄) | 대표자·참여자 전원 | 입실 10분 전입니다 |
| `END_SOON` | 사용 중, 끝 10분 전. 다음 칸이 비면 "연장할 수 있어요" | 전원 | 종료 10분 전입니다 |
| `NO_SHOW_SOON` | 입실 안 했고 입실 마감 5분 전 | 대표자만 | 노쇼 5분 전입니다 |

- 예약·사람·종류별 한 번만 (`notification_log` PK + `ON CONFLICT DO NOTHING`). 동시에 10번 돌아도 3건이에요.
- 알림은 앱 알림함(V10 `notification`)에 저장돼요. `GET /api/notifications/me`로 최근 50개를 봐요.
- 메일은 재웅님 담당이에요. `NotificationChannel`을 구현한 빈을 등록하면 알림함 저장이 커밋된 뒤 불려요. 메일이 실패해도 알림함에는 남고, 다시 보내지는 않아요.
- 분(minute) 값은 `mspace.notification.reminder`에서 바꿔요. 끄려면 `MSPACE_REMINDER_ENABLED=false`.

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
