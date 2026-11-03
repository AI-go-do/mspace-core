# mspace-core

M-SPACE 예약 시스템의 백엔드(core)예요. 조선대 IT융합대학 M-SPACE 스터디룸의 **예약, 출입, 회원** API를 열고, 겹침 금지·대표자 1건 같은 예약 규칙을 DB 제약으로 지켜요.

- 학생 폰(front)과 문 앞 키패드, AI 비서가 모두 이 서버의 API만 불러요. 권한 판단도 여기서만 해요.
- 요구사항: [요구사항 명세 v0.4](https://github.com/AI-go-do/docs/blob/main/requirements/requirements.md)
- 팀 규칙: [팀 컨벤션](https://github.com/AI-go-do/docs/blob/main/conventions.md) · [인프라 · CI/CD](https://github.com/AI-go-do/docs/blob/main/infra/infra-cicd.md) · [팀 공통 맥락](TEAM.md)

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

### 로그인 붙기 전 임시 방법

로그인(재웅님 담당)이 붙기 전까지는 `X-User-Id` 헤더에 회원 id를 넣어서 "누가 보낸 요청인지"를 알려 줘요. `MSPACE_DEV_HEADER=true`일 때만 켜지고, 기본값은 꺼짐이라 운영에서는 항상 401이에요.

```bash
MSPACE_DEV_HEADER=true ./mvnw spring-boot:run
curl -X POST localhost:8080/api/reservations -H 'X-User-Id: 1' -H 'Content-Type: application/json' \
  -d '{"roomId":1,"startAt":"2026-11-03T14:00:00+09:00","endAt":"2026-11-03T16:00:00+09:00","participantStudentIds":["20230002","20230003"]}'curl localhost:8080/api/reservations/me -H 'X-User-Id: 1'          # 내 예약 (번호는 대표자에게만)
curl -X POST localhost:8080/api/reservations/1/cancel -H 'X-User-Id: 1'   # 시작 전 취소 → {"id":1,"status":"CANCELED"}
```

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

## DB 변경 규칙

- 테이블은 Flyway 파일(`V<번호>__<설명>.sql`)로만 바꿔요. JPA는 검사만 해요(`ddl-auto: validate`).
- **이미 합쳐진 V 파일은 절대 고치지 않아요.** 바꿀 게 있으면 다음 번호 파일을 새로 만들어요.
- `V1__init_schema.sql`은 팀이 검증한 `schema-v2.sql`(동시 예약 100건 중복 0건 확인)의 제약·트리거를 그대로 쓰고, 테이블·컬럼·상태 이름만 요구사항 명세 v0.4에 맞췄어요.
