# 과제 배포(assignment) — ERD · 데이터 흐름

- 대상 모듈: `legacy/assignment-thymeleaf`
- **분석 범위**: "새 배포" 화면 하나 — `GET /distributions/new`(E1)와 `POST /distributions`(E2), 그리고 두 핸들러가 호출하는 코드까지. 배포 목록 · 재배포 · 과제 목록이 쓰는 SQL 은 적지 않았다.
- 경로 표기: `ARCHITECTURE.md` 와 같다(Java 는 `src/main/java/com/example/assign/` 생략).
- 모듈 밖 파일 별칭: `schema` = `db/mariadb/init/01-schema.sql`, `seed` = `db/mariadb/init/02-seed.sql`
- 범위 안 코드는 뷰 · 저장 프로시저를 쓰지 않는다. `schema` 의 뷰 `v_item_public` 은 문항 은행용이라 해당 없음.

## 1. 테이블 관계

```mermaid
erDiagram
    unit ||--o{ assignment : "unit_id (fk_assignment_unit)"
    assignment ||--o{ distribution : "assignment_id (fk_distribution_assignment)"
    class ||--o{ distribution : "class_id (fk_distribution_class)"
    distribution ||--o{ submission : "distribution_id (범위 안에서 접근 안 함)"

    unit {
        INT id PK
        VARCHAR code UK
        VARCHAR name
        TINYINT grade
    }
    assignment {
        INT id PK
        VARCHAR title
        INT unit_id FK
        DATETIME due_at "NOT NULL"
        CHAR status "DEFAULT 'O'"
    }
    class {
        INT id PK
        VARCHAR name
        VARCHAR teacher_id
    }
    distribution {
        INT id PK "AUTO_INCREMENT 없음"
        INT assignment_id FK
        INT class_id FK
        DATETIME distributed_at
        TINYINT redistributed "DEFAULT 0"
    }
```

| 테이블 | 정의 | 범위 안 사용 |
|--------|------|--------------|
| `unit` | `schema:11-18` | 읽기(과제의 단원 코드 · 이름) |
| `class` | `schema:75-80` | 읽기 |
| `assignment` | `schema:82-90` | 읽기 |
| `distribution` | `schema:92-103` | 읽기(건수 · 최대 id), **쓰기(INSERT)** |
| `submission` | `schema:105-115` | 범위 안 코드에서 접근 없음 |

스키마에서 확인한 점:

- `distribution` 에 `(assignment_id, class_id)` UNIQUE 제약이 없다. 키는 PK 와 단일 컬럼 인덱스 둘뿐이다(`schema:98-100`). 중복 방지는 코드가 한다(`dao/DistributionDao.java:46` 주석).
- `distribution.id` 는 `INT NOT NULL` 이고 AUTO_INCREMENT 가 없다(`schema:93`). id 는 코드가 `MAX(id)+1` 로 만든다(`dao/DistributionDao.java:54-57`).
- `assignment.status` 주석은 `O=진행 C=마감` 두 값뿐이다(`schema:87`). 코드는 `X`(연장) · `D`(삭제)도 쓴다(`web/DistributionController.java:61`, `service/DistributionService.java:56`, `dao/AssignmentDao.java:36`). CHECK 제약은 없다.
- `assignment.due_at` 은 `NOT NULL` 이다(`schema:86`). 코드의 `dueAt != null` 검사는 스키마상 늘 참이다.
- `seed` 에 같은 (과제 2, 학급 1) 배포가 두 건 있다(`seed:133-134`, id 3 · 4). 코드의 중복 검사 기준으로는 이미 "중복" 상태인 데이터다.

## 2. 읽기 · 쓰기 위치

| # | 진입점 | 메서드 | SQL (요지) | R/W | 테이블 | 근거 |
|---|--------|--------|-----------|-----|--------|------|
| Q1 | E1 | `AssignmentDao#findAllForSelect` | `BASE_SELECT WHERE a.status IN ('O','X','C') ORDER BY a.due_at ASC, a.id ASC` | R | assignment, unit, distribution(하위 쿼리) | `dao/AssignmentDao.java:21-24`, `:34-39` |
| Q2 | E1 | `ClassDao#findAll` | `SELECT id, name, teacher_id FROM class ORDER BY id ASC` | R | class | `dao/ClassDao.java:20-22` |
| Q3 | E2(컨트롤러 · 서비스 두 번) | `AssignmentDao#findById` | `BASE_SELECT WHERE a.id = ?` (상태 조건 없음) | R | assignment, unit, distribution(하위 쿼리) | `dao/AssignmentDao.java:41-48` |
| Q4 | E2 | `ClassDao#findById` | `SELECT id, name, teacher_id FROM class WHERE id = ?` | R | class | `dao/ClassDao.java:24-30` |
| Q5 | E2 | `DistributionDao#countByAssignmentAndClass` | `SELECT COUNT(*) FROM distribution WHERE assignment_id = ? AND class_id = ?` | R | distribution | `dao/DistributionDao.java:47-52` |
| Q6 | E2 | `DistributionDao#nextId` | `SELECT COALESCE(MAX(id), 0) FROM distribution` (+1 은 Java) | R | distribution | `dao/DistributionDao.java:54-57` |
| Q7 | E2 | `DistributionDao#insert` | `INSERT INTO distribution (id, assignment_id, class_id, distributed_at, redistributed) VALUES (?, ?, ?, ?, 0)` | **W** | distribution | `dao/DistributionDao.java:59-63` |

`BASE_SELECT`(`dao/AssignmentDao.java:21-24`)는 `assignment a LEFT JOIN unit u` 에 `COUNT(DISTINCT d.class_id)` 하위 쿼리(`class_cnt`)를 붙인다. 범위 안 화면은 `class_cnt` 를 표시하지 않는다(`templates/distribution_form.html:17` 은 id · title · unitCode · dueAt 만 사용).

## 3. 데이터 흐름

```
[E1 GET /distributions/new]
  Q1 assignment(+unit) ──▶ 모델 assignments ──▶ 과제 <select> (마감 · 상태로 disabled 판정)
  Q2 class              ──▶ 모델 classes     ──▶ 학급 <select>
  AppClock.now()        ──▶ 모델 now

[E2 POST /distributions  assignmentId, classId]
  Q3 (컨트롤러, 트랜잭션 밖) ── 존재 · 마감 검사
  ── @Transactional distribute() 시작 ──
  Q3 → Q4 → (status 'D' 검사) → Q5 → Q6 → Q7
  ── 커밋 ──
  redirect:/distributions (flash message "배포 #id")
```

- 쓰기는 Q7 한 곳, 대상은 `distribution` 한 테이블이다. `distributed_at` 은 DB `NOW()` 가 아니라 `AppClock.now()` 값(`service/DistributionService.java:63`), `redistributed` 는 SQL 리터럴 `0` 이다(`dao/DistributionDao.java:61`).
- Q5 → Q6 → Q7 은 한 트랜잭션(`service/DistributionService.java:46`) 안이지만 잠금(`FOR UPDATE` 등)이 없다. 격리 수준도 코드에서 지정하지 않는다(MariaDB 기본값에 따름 — 추정, 운영 DB 설정 확인 필요).
