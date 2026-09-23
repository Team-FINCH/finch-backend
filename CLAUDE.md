# 백엔드 에이전트 지침

이 문서는 `backend/` 이하에서 작업하는 AI 에이전트가 읽는 지침이다.
사람이 읽어도 되지만, 사람용 규약의 원본은 아래 문서들이고 여기서는 중복 서술하지 않는다.

## 프로젝트 개요

기존 증권 서비스를 클론하고 AI 투자 비서 기능을 얹는 웹 서비스다. 한 줄 정의는 "내 계좌와 성향을 읽고
투자 판단을 돕는 AI 비서"다. 실제 금전 이동 없이 가상 예수금으로 거래하지만, 모의·연습 서비스로 소개하지 않는다.
**백엔드가 원장과 시세의 단일 진실 공급원이다.** 프론트는 백엔드만 호출하고, AI 서버(FastAPI)도
백엔드가 중계한다. AI 는 백엔드를 읽어 분석과 설명을 제공할 뿐 원장을 쓰지 않는다.

- 프론트가 쓰는 Base URL 은 `/api/v1` 하나다
- 최종 발표 2026-09-28. 기능 동결 9/18, 전면 동결 9/23

## 레포 구조

모노레포이고 파트별로 최상위 디렉토리가 나뉜다.

```
backend/    Spring (이 문서의 적용 범위)
frontend/   React
ai/         FastAPI
infra/      배포
docs/       팀 공용 문서
```

**`backend/`와 `docs/` 밖의 파일을 고치지 않는다.**
다른 파트 디렉토리에서 문제를 발견하면 고치지 말고 보고한다.
팀 프로젝트에서 남의 코드를 조용히 바꾸는 것이 가장 나쁘다.

## 읽어야 할 문서

작업 전에 관련된 것을 읽는다. 이 문서들이 원본이고 아래 요약은 길잡이일 뿐이다.

```
docs/api/apiSpec.md                   백엔드 API 명세. 응답 형식·멱등성·페이징·에러 코드의 계약 원본이고
                                      프론트·AI 와 어긋나면 이 문서가 기준이다. 수정은 백엔드 파트가 한다
docs/spec/featureSpec.md              기능 명세서
docs/convention/backConvention.md     백엔드 컨벤션 (패키지 구조, 계층 경계, 도메인 규약)
docs/convention/gitConvention.md      브랜치·커밋·MR 절차
docs/convention/mrConvention.md       MR 템플릿
docs/api/aiApiSpec.md                 AI 파트 인터페이스 계약
ai/docs/openapi.json                  AI 서버의 실제 구현 스키마. AI 파트 문서보다 이쪽이 사실이다
frontend/docs/contracts.md            프론트가 백엔드 회신을 기다리는 미확정·충돌 항목
```

배포·운영 명세(infraSpec, infraRunbook)는 레포가 아니라 노션에 있다.
클러스터 구성이나 배포 절차가 필요한 작업이면 그쪽을 먼저 확인한다.

## 기술 스택

버전은 `backend/build.gradle` 이 단일 진실 원천이다. 이 문서에 버전을 적지 않는다.

```
Java + Spring Boot (4 계열)
Spring Data JPA / PostgreSQL / Flyway
Redis (멱등성 키, 시세 캐시)
Spring Security + 자체 발급 JWT, 카카오 OAuth
WebClient (KIS·AI 서버 호출)
Spring WebSocket + STOMP (실시간 시세)
JUnit 5 + Testcontainers
```

빌드는 Gradle 이고 모든 명령은 `backend/` 에서 실행한다.

## 검증

작업을 끝내기 전에 `backend/` 에서 아래를 통과시킨다.

```
./gradlew build
```

- **Testcontainers 가 Docker 를 요구한다.** Docker 가 떠 있지 않으면 `test` 태스크가 실패한다.
  컴파일만 확인하려면 `./gradlew compileJava` 를 쓴다
- **IntelliJ 에서 import 가 전부 빨갛다면** Gradle 프로젝트가 연결되지 않은 것이다.
  `backend/build.gradle` 우클릭 → Link Gradle Project. 코드 문제가 아니므로 코드를 고치지 않는다

## 커밋과 MR

`docs/convention/gitConvention.md` 를 따른다. 자주 어기는 것만 옮겨 적는다.

- 커밋 메시지는 `<타입>: <제목>`. **이모지를 붙이지 않는다.** 콜론 앞에 공백을 넣지 않는다
- 본문은 제목만으로 "왜"가 안 드러날 때만 쓴다. 기본은 한 줄이다
- 제목 끝에 마침표를 붙이지 않는다
- 한 커밋에는 한 가지 문제만 담는다
- 브랜치는 `FINCH-{티켓번호}-{기능명}`
- 1브랜치 n커밋 1MR
- **MR 은 작성자 본인이 머지하지 않는다.** 리뷰한 팀원이 머지한다

## 다른 파트에 물어야 할 것

명세에 없거나 문서끼리 어긋나는 것을 발견하면 **혼자 정하고 넘어가지 않는다.**
`apiSpec.md` 는 백엔드 소유 문서이므로 백엔드가 고치는 것이 맞지만, 고친 값이 프론트·AI 의 구현 전제를
바꾸는 경우에는 문서를 고치고 그 사실을 알린다. 구두 합의는 문서에 반영되기 전까지 확정이 아니다.

프론트가 백엔드 회신을 기다리는 항목은 `frontend/docs/contracts.md` 의 미확정·충돌 표에 모여 있다.
