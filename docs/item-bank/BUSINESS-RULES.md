# item-bank-php 비즈니스 규칙 후보

분석 대상: `legacy/item-bank-php/` (읽기 전용 분석, 코드 수정 없음). 선행 문서: `ARCHITECTURE.md`, `ERD.md`
분석 범위: 문항 검색의 조건 조합(단원 · 난이도 · 태그), 등록 시 검증, 목록의 기본 정렬과 제외 조건.
근거 표기: PHP 파일은 `legacy/item-bank-php/` 기준 경로, 스키마는 `db/mariadb/init/01-schema.sql`(아래 `schema`)이다.

작성 원칙

- 코드(조건문 · SQL)에서 확인한 것만 규칙으로 올렸다. 주석에만 있는 내용은 규칙으로 올리지 않고 비고 또는 7절에 "주석만 있음"으로 적었다.
- 주석과 코드가 다르면 코드를 기준으로 규칙을 쓰고, 주석은 비고에 남겼다.
- **수정 기능은 이 모듈에 없다.** `item` 에 대한 `UPDATE` 문이 없고(`ERD.md` 3.2절), 수정 화면도 없다. 따라서 "수정 시 검증" 규칙은 뽑을 것이 없다.

## 1. 검색 조건 조합 (`search.php`)

### BR-01 조건은 모두 AND 로 결합한다

- 규칙: 키워드 · 단원 · 난이도 · 태그 조건이 여러 개 주어지면 모두 AND 로 결합해 모든 조건을 만족하는 문항만 찾는다.
- 근거: `search.php:44`, `search.php:98`, `search.php:118`, `search.php:215`, `search.php:218`, `search.php:226`, `search.php:244`
- 근거 코드:
  ```php
  $where    = " WHERE 1=1";
  $where .= " AND unit_code = ?";
  $where .= " AND EXISTS (SELECT 1 FROM item_tag it JOIN tag t ON t.id = it.tag_id"
  ```
- 확신도: 확실
- 비고: 조건 사이에 OR 로 묶는 경로는 없다(키워드 내부의 제목/지문 OR 는 BR-03).

### BR-02 파라미터가 배열이면 첫 값만 쓴다

- 규칙: 같은 이름의 GET 파라미터가 배열로 들어오면 첫 값만 쓰므로, 단원 · 난이도 · 태그는 한 번에 하나씩만 거를 수 있다.
- 근거: `search.php:51-80` (대표 `search.php:59`, `search.php:67`)
- 근거 코드:
  ```php
  $unit = is_array($params['unit']) ? (string)reset($params['unit']) : (string)$params['unit'];
  $tag = is_array($params['tag']) ? (string)reset($params['tag']) : (string)$params['tag'];
  ```
- 확신도: 확실
- 비고: 여러 태그를 AND/OR 로 거르는 기능은 없다.

### BR-03 키워드는 제목 또는 지문 부분 일치

- 규칙: 키워드가 있으면 앞뒤 공백을 제거한 뒤, 제목 또는 지문에 키워드가 포함된(`LIKE '%키워드%'`) 문항을 찾는다.
- 근거: `search.php:85`, `search.php:97-98`
- 근거 코드:
  ```php
  $q = trim($q);
  $like = '%' . $q . '%';
  $where .= " AND (title LIKE ? OR stem LIKE ?)";
  ```
- 확신도: 확실
- 비고: 공백만 입력하면 `trim` 후 빈값이라 키워드 조건이 붙지 않는다(`search.php:90`).

### BR-04 키워드는 100자까지만 쓴다

- 규칙: 키워드가 100자(UTF-8 글자 수)를 넘으면 앞 100자만 쓰고 경고를 표시한다.
- 근거: `search.php:86-88`, `search.php:494`
- 근거 코드:
  ```php
  if (mb_strlen($q, 'UTF-8') > 100) {
      $q = mb_substr($q, 0, 100, 'UTF-8');
  ```
- 확신도: 확실
- 비고: 매직 넘버 `100`. 검색 폼 입력칸도 `maxlength="100"` 이다(`search.php:494`).

### BR-05 키워드의 `%` · `_` 는 와일드카드로 처리한다

- 규칙: 키워드에 `%` 나 `_` 가 있으면 이스케이프하지 않고 LIKE 와일드카드로 그대로 쓰며, 경고만 표시한다.
- 근거: `search.php:94-97`
- 근거 코드:
  ```php
  if (strpos($q, '%') !== false || strpos($q, '_') !== false) {
      $warnings[] = '키워드의 % 와 _ 는 와일드카드로 처리됩니다.';
  ```
- 확신도: 확실
- 비고: 예: `q=_` 이면 한 글자 이상인 제목/지문 전부가 걸린다. 이관 시 이 동작을 유지할지 결정이 필요하다.

### BR-06 한 글자 키워드는 경고만 한다

- 규칙: 키워드가 한 글자이면 검색은 그대로 하고 "결과가 많을 수 있다"는 경고를 표시한다.
- 근거: `search.php:91-93`
- 근거 코드:
  ```php
  if (mb_strlen($q, 'UTF-8') === 1) {
      $warnings[] = '키워드가 한 글자라 결과가 많을 수 있습니다.';
  ```
- 확신도: 확실
- 비고: 최소 길이로 막지는 않는다.

### BR-07 단원은 단원 코드와 정확히 일치

- 규칙: 단원 값이 있으면 앞뒤 공백을 제거한 뒤, 단원 코드(`unit_code`)가 같은 문항만 찾는다.
- 근거: `search.php:109`, `search.php:118-120`
- 근거 코드:
  ```php
  $unit = trim($unit);
  $where .= " AND unit_code = ?";
  ```
- 확신도: 확실
- 비고: 검색은 단원 `code` 로, 등록은 단원 `id` 로 받는다(`register.php:43,62`). 같은 "단원" 입력이 화면마다 다른 키를 쓴다.

### BR-08 단원 코드 형식 · 대소문자가 달라도 그대로 조회한다

- 규칙: 단원 코드가 `영문 1자 + 숫자 1~2자 + '-' + 숫자 1~2자` 형식이 아니거나 소문자가 섞여 있으면 경고만 하고 입력값 그대로 조회한다.
- 근거: `search.php:111-117`
- 근거 코드:
  ```php
  if (!preg_match('/^[A-Za-z][0-9]{1,2}-[0-9]{1,2}$/', $unit)) {
  if ($unit !== strtoupper($unit)) {
  ```
- 확신도: 확실
- 비고: 주석은 "결과는 대개 0건"(`search.php:112`)이라고 한다. 다만 테이블 collation 이 `utf8mb4_unicode_ci`(`schema:18`)라서 `m5-1` 도 `M5-1` 과 같다고 비교될 수 있다 — 경고는 뜨는데 결과는 나오는 경우가 있을 수 있음(추정, DB 동작 확인 필요).

### BR-09 없는 단원 코드도 조회는 한다

- 규칙: 단원 코드가 `unit` 테이블에 없으면 "등록되지 않은 단원 코드" 경고를 표시하되 조회는 그대로 한다.
- 근거: `search.php:125`, `search.php:141-143`
- 근거 코드:
  ```php
  $stmt = $conn->prepare("SELECT name, grade FROM unit WHERE code = ?");
  if ($unitName === '') {
      $warnings[] = '등록되지 않은 단원 코드입니다: ' . $unit;
  ```
- 확신도: 확실
- 비고: 존재하면 요약에 `단원명 (N학년)` 을 붙인다(`search.php:133`).

### BR-10 난이도를 비우면 난이도 5 문항을 뺀다 ⚠

- 규칙: 난이도 값이 비어 있으면 `level < 5` 조건을 붙여 난이도 5 문항을 결과에서 제외한다.
- 근거: `search.php:211-216`
- 근거 코드:
  ```php
  // 난이도 값이 비어 있으면 전체 난이도 검색 (1~5 모두 포함)
  if ($level == '') {
      $where .= " AND level < 5";
  ```
- 확신도: 확실
- 비고: **주석과 코드 불일치.** 주석은 "1~5 모두 포함"이지만 코드는 5 를 뺀다. 조건 없이 검색해도(`search.php` 첫 화면) 이 조건이 붙는다. 느슨한 비교 `==` 를 쓰지만 `$level` 은 `trim` 한 문자열이라 실제로는 빈 문자열일 때만 참이다. 매직 넘버 `5`. 의도 여부는 `ARCHITECTURE.md` 6절 미확인 항목과 같다.

### BR-11 난이도 1~5 는 그 값과 같은 문항만

- 규칙: 난이도가 `1`~`5` 한 자리 숫자이면 난이도가 그 값과 같은 문항만 찾는다.
- 근거: `search.php:217-221`
- 근거 코드:
  ```php
  else if (preg_match('/^[1-5]$/', $level)) {
      $where .= " AND level = ?";
      $values[] = (int)$level;
  ```
- 확신도: 확실
- 비고: 이 경우 난이도 5 도 나온다(BR-10 과 대비). 검색 폼 표기는 1 매우 쉬움 · 2 쉬움 · 3 보통 · 4 어려움 · 5 최상(`search.php:193-199`).

### BR-12 형식 밖 난이도는 정수 변환 값으로 비교한다

- 규칙: 난이도가 `1`~`5` 한 자리 형식이 아니면 경고를 표시하고, PHP `(int)` 변환 값과 같은 난이도의 문항을 찾는다.
- 근거: `search.php:223-229`
- 근거 코드:
  ```php
  $warnings[] = '난이도는 1~5 사이여야 합니다.';
  $where .= " AND level = ?";
  $values[] = (int)$level;
  ```
- 확신도: 확실
- 비고: 주석은 "대개 0건"(`search.php:224`)이다. `abc` · `0` · `9` 는 0건이지만, `03` · `3.5` · `3abc` 는 `(int)` 로 3 이 되어 난이도 3 결과가 **경고와 함께** 나온다. 등록 화면은 같은 형식 위반을 오류로 막는다(BR-30) — 같은 1~5 규칙이 두 곳에 다르게 구현돼 있다.

### BR-13 태그는 이름 정확 일치

- 규칙: 태그 값이 있으면 앞뒤 공백을 제거한 뒤, 이름이 정확히 같은 태그가 하나라도 붙은 문항만 찾는다.
- 근거: `search.php:235`, `search.php:244-247`
- 근거 코드:
  ```php
  $where .= " AND EXISTS (SELECT 1 FROM item_tag it JOIN tag t ON t.id = it.tag_id"
          . " WHERE it.item_id = v_item_public.id AND t.name = ?)";
  ```
- 확신도: 확실
- 비고: 검색은 태그 `name` 으로, 등록은 태그 `id` 로 받는다(`register.php:45,77`).

### BR-14 태그 이름은 50자까지만 쓴다

- 규칙: 태그 이름이 50자(UTF-8 글자 수)를 넘으면 앞 50자만 쓰고 경고를 표시한다.
- 근거: `search.php:240-242`
- 근거 코드:
  ```php
  if (mb_strlen($tag, 'UTF-8') > 50) {
      $tag = mb_substr($tag, 0, 50, 'UTF-8');
  ```
- 확신도: 확실
- 비고: 매직 넘버 `50`. `tag.name VARCHAR(50)`(`schema:37`)과 같은 값이다.

### BR-15 태그의 `%` · `_` 는 와일드카드가 아니다

- 규칙: 태그에 `%` 나 `_` 가 있어도 부분 일치로 바꾸지 않고 정확 일치(`=`)로 조회하며, 경고만 표시한다.
- 근거: `search.php:237-238`, `search.php:245`
- 근거 코드:
  ```php
  if (strpos($tag, '%') !== false || strpos($tag, '_') !== false) {
      $warnings[] = '태그는 부분 일치를 지원하지 않습니다.';
  ```
- 확신도: 확실
- 비고: 키워드(BR-05)와 반대 동작이다.

### BR-16 없는 태그도 조회는 한다

- 규칙: 태그 이름이 `tag` 테이블에 없으면 "등록되지 않은 태그" 경고를 표시하되 조회는 그대로 한다.
- 근거: `search.php:251-260`
- 근거 코드:
  ```php
  if ($opt['value'] !== '' && $opt['value'] === $tag) {
  if (!$tagKnown) {
      $warnings[] = '등록되지 않은 태그입니다: ' . $tag;
  ```
- 확신도: 확실
- 비고: 존재 확인은 PHP `===`(대소문자 · 공백 구분)이고 실제 조회는 DB `=`(`utf8mb4_unicode_ci`, `schema:40`)다. 대소문자만 다른 태그명은 "등록되지 않은 태그" 경고가 뜨면서 결과는 나올 수 있다(추정, DB 동작 확인 필요).

## 2. 목록 정렬 · 페이지 (`search.php`)

### BR-17 기본 정렬은 난이도 내림차순, 같으면 id 오름차순

- 규칙: 정렬 기준이 비어 있으면 난이도 내림차순, 같은 난이도면 id 오름차순으로 정렬한다.
- 근거: `search.php:317-320`
- 근거 코드:
  ```php
  case '':
      // 기본 정렬: 어려운 문항부터, 같은 난이도면 번호 순
      $orderBy = " ORDER BY level DESC, id ASC";
  ```
- 확신도: 확실
- 비고: 주석과 코드 일치. 단, 난이도를 비우면 5 가 빠지므로(BR-10) 기본 화면의 첫 행은 난이도 4 문항이다.

### BR-18 모르는 정렬 기준이면 기본 정렬

- 규칙: 정렬 기준이 `id` · `title` · `unit` · `level` · `created` 가 아니면 경고를 표시하고 기본 정렬(BR-17)을 쓴다.
- 근거: `search.php:266`, `search.php:322-325`
- 근거 코드:
  ```php
  default:
      $warnings[] = '알 수 없는 정렬 기준입니다. 기본 정렬을 사용합니다.';
      $orderBy = " ORDER BY level DESC, id ASC";
  ```
- 확신도: 확실
- 비고: 정렬 기준 · 방향은 소문자로 바꾼 뒤 비교한다(`search.php:266-267`) — `SORT=Level` 도 허용된다.

### BR-19 잘못된 정렬 방향은 무시한다

- 규칙: 정렬 방향이 `asc` · `desc` 가 아니면 경고를 표시하고 방향을 지정하지 않은 것으로 처리한다.
- 근거: `search.php:268-273`
- 근거 코드:
  ```php
  if ($dir !== 'asc' && $dir !== 'desc') {
      if ($dir !== '') {
          $warnings[] = '정렬 방향은 asc 또는 desc 만 가능합니다.';
  ```
- 확신도: 확실
- 비고: 빈 방향은 경고하지 않는다.

### BR-20 방향 미지정 시 기준별 기본 방향

- 규칙: 방향을 지정하지 않으면 `id` · `title` · `unit` 은 오름차순, `level` · `created` 는 내림차순으로 정렬한다.
- 근거: `search.php:278-282`, `search.php:286-290`, `search.php:294-298`, `search.php:302-306`, `search.php:310-314`, `search.php:330`
- 근거 코드:
  ```php
  if ($dir === 'desc') {
  if ($dir === 'asc') {
  $dir = ($sort === 'level' || $sort === 'created') ? 'desc' : 'asc';
  ```
- 확신도: 확실
- 비고: 인용 첫 줄은 `search.php:278`(`title` · `unit` 도 같은 구조), 둘째 줄은 `search.php:302`(`created` 도 같은 구조)다. `id` · `title` · `unit` 은 `desc` 일 때만 내림차순이고 나머지는 else 의 ASC, `level` · `created` 는 `asc` 일 때만 오름차순이고 나머지는 else 의 DESC 다. `search.php:330` 은 화면 표시용이다. 열 머리글 링크도 난이도만 첫 클릭이 내림차순이다(`search.php:410-413`). `created` 는 SQL 정렬은 되지만 열 머리글 링크가 없다(`search.php:392-397`).

### BR-21 정렬 동점은 id 오름차순, 단원 정렬은 난이도 내림차순이 2차 키

- 규칙: `id` 외의 모든 정렬은 방향과 관계없이 id 오름차순을 마지막 키로 쓰고, 단원 정렬은 단원 코드 다음에 방향과 관계없이 난이도 내림차순을 2차 키로 쓴다.
- 근거: `search.php:287`, `search.php:289`, `search.php:295`, `search.php:297`, `search.php:303`, `search.php:305`, `search.php:311`, `search.php:313`
- 근거 코드:
  ```php
  $orderBy = " ORDER BY unit_code DESC, level DESC, id ASC";
  $orderBy = " ORDER BY unit_code ASC, level DESC, id ASC";
  $orderBy = " ORDER BY title DESC, id ASC";
  ```
- 확신도: 확실
- 비고: `desc` 를 골라도 2차 키는 뒤집히지 않는다. `unit_code` 는 문자열 정렬이라 `M5-10` 이 `M5-2` 앞에 올 수 있다(추정, collation 기준).

### BR-22 한 페이지는 20건

- 규칙: 결과는 한 페이지 20건씩 보여 주고, `(페이지 - 1) × 20` 건을 건너뛴다.
- 근거: `search.php:349`, `search.php:523`, `search.php:553`
- 근거 코드:
  ```php
  $offset = ($page - 1) * 20;
  $limit  = " LIMIT 20 OFFSET " . (int)$offset;
  'page_size'    => 20,
  ```
- 확신도: 확실
- 비고: 매직 넘버 `20` 이 세 곳에 따로 박혀 있다(상수 없음).

### BR-23 페이지 번호 보정: 숫자 아님 → 1, 1 미만 → 1, 999 초과 → 999

- 규칙: 페이지 번호가 숫자가 아니면 1페이지(경고), 1 미만이면 1페이지, 999 를 넘으면 999페이지(경고)를 보여 준다.
- 근거: `search.php:336-348`
- 근거 코드:
  ```php
  if (preg_match('/^[0-9]+$/', $pageRaw)) {
  if ($page < 1) {
  if ($page > 999) {
  ```
- 확신도: 확실
- 비고: 매직 넘버 `999`. `page=0` 과 빈 `page` 는 경고 없이 1 로 바뀐다(`search.php:339,342`).

### BR-24 0건이면 메시지만 보여 준다

- 규칙: 총 건수가 0 이면 "검색 결과가 없습니다" 메시지를 내고 결과 표를 그리지 않는다.
- 근거: `search.php:657-660`
- 근거 코드:
  ```php
  if ($total === 0) {
      echo '<p id="message">검색 결과가 없습니다</p>' . "\n";
      return;
  ```
- 확신도: 확실
- 비고: 총 건수는 전체 조건으로 따로 센다(`search.php:526`). 총 건수가 1 이상인데 범위를 벗어난 페이지를 요청하면 메시지 없이 빈 표가 나온다.

## 3. 목록 제외 조건

### BR-25 검색 결과는 공개(`A`) 문항만

- 규칙: 검색 결과와 총 건수는 `status = 'A'` 인 문항만 대상으로 하므로, 검수중(`R`) · 삭제(`D`) 등 `A` 가 아닌 문항은 나오지 않는다.
- 근거: `search.php:522`, `search.php:526`, `schema:70`
- 근거 코드:
  ```php
  $from   = " FROM v_item_public";
  ```
  ```sql
  WHERE i.status = 'A';
  ```
- 확신도: 확실
- 비고: 조건은 PHP 가 아니라 뷰 정의에 있다. 뷰가 `JOIN unit`(`schema:69`, inner join)이지만 `item.unit_id` 가 NOT NULL + FK 라서(`schema:22,32`) 단원 때문에 빠지는 문항은 없다. 스키마 주석은 "등록 화면도 이 뷰를 기준으로 삼는다"(`schema:51`)지만 `register.php` 는 뷰를 쓰지 않는다.

### BR-26 단원 목록의 문항 수는 공개(`A`) 문항만 센다

- 규칙: 단원 목록 화면의 단원별 문항 수는 `status = 'A'` 인 문항만 센다.
- 근거: `units.php:15`, `units.php:17`
- 근거 코드:
  ```php
  // 단원별 공개(status='A') 문항 수. 삭제 · 검수중 문항은 세지 않는다.
  (SELECT COUNT(*) FROM item i WHERE i.unit_id = u.id AND i.status = 'A') AS item_count
  ```
- 확신도: 확실
- 비고: 주석과 코드 일치. **같은 "공개" 조건이 두 곳에 따로 구현돼 있다** — 뷰(`schema:70`)와 `units.php:17`. 단원 목록은 난이도 5 를 빼지 않는다(검색 기본 조건 BR-10 과 다름). 문항이 0 개인 단원도 목록에 나온다(`FROM unit u` 에 조건 없음, `units.php:18`).

### BR-27 단원 목록은 학년 → 코드 오름차순

- 규칙: 단원 목록 화면 · 검색 폼 · 등록 폼의 단원은 학년 오름차순, 같은 학년이면 코드 오름차순으로 나열한다.
- 근거: `units.php:19`, `search.php:156`, `register.php:27`
- 근거 코드:
  ```php
  ORDER BY u.grade ASC, u.code ASC
  $res = $conn->query("SELECT code, name, grade FROM unit ORDER BY grade ASC, code ASC");
  $res = $conn->query("SELECT id, code, name FROM unit ORDER BY grade ASC, code ASC");
  ```
- 확신도: 확실
- 비고: 세 곳이 같은 정렬을 각자 쓴다. 코드 문자열 정렬 주의(BR-21 비고와 같음). 검색 폼은 학년별 `optgroup` 으로 묶는다(`search.php:454-455`).

### BR-28 태그 목록 · 태그 표시는 태그 id 순

- 규칙: 검색 폼 · 등록 폼의 태그 목록과 검색 결과의 태그 이름 목록은 태그 id 오름차순으로 나열한다.
- 근거: `search.php:178`, `register.php:34`, `schema:64`
- 근거 코드:
  ```php
  $res = $conn->query("SELECT name FROM tag ORDER BY id ASC");
  ```
  ```sql
  (SELECT GROUP_CONCAT(t.name ORDER BY t.id SEPARATOR ',')
  ```
- 확신도: 확실
- 비고: 결과 표의 태그 칸은 쉼표로 이어 붙인 문자열이고, 태그가 없으면 빈칸이다(`search.php:678`).

## 4. 등록 시 검증 (`register.php`)

### BR-29 제목은 앞뒤 공백 제거 후 5자 이상 200자 이하

- 규칙: 제목을 앞뒤 공백 제거한 뒤 UTF-8 글자 수가 5 미만이거나 200 을 넘으면 등록을 거부한다.
- 근거: `register.php:41`, `register.php:49-54`
- 근거 코드:
  ```php
  if (mb_strlen($title, 'UTF-8') < 5) {
  if (mb_strlen($title, 'UTF-8') > 200) {
  ```
- 확신도: 확실
- 비고: 매직 넘버 `5`, `200`. 주석 "공백 제외 아님, 앞뒤 공백만 제거"(`register.php:48`)와 코드 일치 — 중간 공백은 글자 수에 들어간다. `200` 은 `item.title VARCHAR(200)`(`schema:23`)과 같다. 폼 라벨도 "5자 이상"(`register.php:143`).

### BR-30 난이도는 정확히 `1`~`5` 한 자리

- 규칙: 난이도가 `1`~`5` 한 자리 숫자가 아니면(빈값 포함) 등록을 거부한다.
- 근거: `register.php:44`, `register.php:70-72`
- 근거 코드:
  ```php
  if (!preg_match('/^[1-5]$/', $level)) {
      $errors[] = '난이도는 1~5 사이여야 합니다.';
  ```
- 확신도: 확실
- 비고: DB 에는 CHECK 제약이 없어(`schema:25`, 주석만 있음) 이 검증이 유일한 범위 보장이다. 검색은 같은 형식 위반을 경고로만 처리한다(BR-12).

### BR-31 지문은 필수

- 규칙: 지문을 앞뒤 공백 제거한 뒤 빈 문자열이면 등록을 거부한다.
- 근거: `register.php:42`, `register.php:56-58`
- 근거 코드:
  ```php
  if ($stem === '') {
      $errors[] = '지문을 입력해야 합니다.';
  ```
- 확신도: 확실
- 비고: 지문 최대 길이 검증은 코드에 없다(`item.stem TEXT`, `schema:24`).

### BR-32 단원은 존재하는 단원 id 여야 한다

- 규칙: 단원 id 가 `unit` 테이블 목록에 없으면(미선택 포함) 등록을 거부한다.
- 근거: `register.php:43`, `register.php:60-68`
- 근거 코드:
  ```php
  if ((string)$u['id'] === $unitId) {
  if (!$unitOk) {
      $errors[] = '단원을 선택해야 합니다.';
  ```
- 확신도: 확실
- 비고: 없는 id 를 보내도 메시지는 "선택해야 합니다"로 같다.

### BR-33 태그는 선택 사항이고, 목록에 없는 태그는 조용히 버린다

- 규칙: 태그는 0 개여도 등록되고, 보낸 태그 id 중 `tag` 테이블에 없는 것은 오류 없이 제외한다.
- 근거: `register.php:45`, `register.php:74-81`
- 근거 코드:
  ```php
  if ((string)$t['id'] === (string)$tid) {
      $validTagIds[] = (int)$tid;
  ```
- 확신도: 확실
- 비고: 주석 "태그는 목록에 있는 것만"(`register.php:73`)과 일치하지만, 거부가 아니라 제외다. `tags` 가 배열이 아니면 태그 없음으로 처리한다(`register.php:45`). 같은 태그 id 를 두 번 보내면 중복 제거가 없어 `item_tag` 복합 PK(`schema:45`) 충돌로 두 번째 INSERT 가 실패하고, 전체가 롤백돼 "저장 중 오류"가 난다(`register.php:108-109,119-122`).

### BR-34 검증 오류가 하나라도 있으면 저장하지 않는다

- 규칙: 검증 오류가 하나라도 있으면 저장하지 않고, 모든 오류 메시지를 한꺼번에 보여 준다.
- 근거: `register.php:83`, `register.php:133-138`
- 근거 코드:
  ```php
  if (empty($errors)) {
  ```
- 확신도: 확실
- 비고: 검증은 첫 오류에서 멈추지 않고 전부 수행한다.

## 5. 등록 처리 (`register.php`)

### BR-35 새 문항은 검수중(`R`)으로 저장한다

- 규칙: 새 문항은 `status = 'R'` 로 저장하므로, 등록 직후에는 검색 결과(BR-25)와 단원 문항 수(BR-26)에 나오지 않는다.
- 근거: `register.php:93-94`
- 근거 코드:
  ```php
  "INSERT INTO item (id, unit_id, title, stem, level, status, created_at, updated_at)
   VALUES (?, ?, ?, ?, ?, 'R', NOW(), NOW())"
  ```
- 확신도: 확실
- 비고: 스키마 기본값은 `'A'`(`schema:26`)지만 등록은 `'R'` 을 명시한다. "검수 완료 후 검색에 노출"(`register.php:84` 주석, `register.php:116` 안내 문구)은 **주석만 있음** — `R` → `A` 로 바꾸는 코드는 이 모듈에 없다.

### BR-36 새 문항 id 는 MAX(id)+1

- 규칙: 새 문항 id 는 현재 `item` 의 최대 id 에 1 을 더한 값이고, 문항이 하나도 없으면 1 이다.
- 근거: `register.php:87`, `register.php:90`
- 근거 코드:
  ```php
  $res = $conn->query("SELECT COALESCE(MAX(id), 0) + 1 AS next_id FROM item FOR UPDATE");
  ```
- 확신도: 확실
- 비고: `item.id` 에 AUTO_INCREMENT 가 없다(`schema:21`). 삭제(`D`) 문항도 MAX 계산에 들어간다. 동시 등록 시 동작은 미확인(`ARCHITECTURE.md` 6절).

### BR-37 등록 시각 = 수정 시각

- 규칙: 등록할 때 `created_at` 과 `updated_at` 을 모두 DB 의 현재 시각(`NOW()`)으로 채운다.
- 근거: `register.php:94`
- 근거 코드:
  ```php
  VALUES (?, ?, ?, ?, ?, 'R', NOW(), NOW())"
  ```
- 확신도: 확실
- 비고: 시각은 PHP 가 아니라 DB 서버 시각이다.

### BR-38 문항과 태그 연결은 한 트랜잭션

- 규칙: 문항 INSERT 와 태그 연결 INSERT 를 한 트랜잭션으로 처리하고, 하나라도 실패하면 모두 롤백한다.
- 근거: `register.php:85`, `register.php:99-100`, `register.php:108-109`, `register.php:114`, `register.php:119-122`
- 근거 코드:
  ```php
  $conn->begin_transaction();
  $conn->commit();
  $conn->rollback();
  ```
- 확신도: 확실
- 비고: 실패 시 사용자에게는 "저장 중 오류가 발생했습니다."만 보이고 원인은 로그로 남긴다(`register.php:121-122`). 성공하면 입력칸을 비운다(`register.php:117-118`).

## 6. 매직 넘버

| 값 | 위치 | 추정 의미 |
|---|---|---|
| `100` | `search.php:86-88`, `search.php:494` | 검색 키워드 최대 글자 수 |
| `1` | `search.php:91` | "결과가 많을 수 있음" 경고를 띄우는 키워드 글자 수 |
| `[A-Za-z][0-9]{1,2}-[0-9]{1,2}` | `search.php:111` | 단원 코드 형식(과목 영문 1자 + 학년 1~2자리 + `-` + 순번 1~2자리) |
| `5` (`level < 5`) | `search.php:215` | 난이도 미지정 시 제외되는 난이도 경계. 주석("1~5 모두 포함")과 불일치 |
| `1`~`5` (`/^[1-5]$/`) | `search.php:217`, `register.php:70`, `search.php:193-199`, `register.php:163` | 난이도 허용 범위. DB 는 주석만(`schema:25`) |
| `50` | `search.php:240-242` | 태그 이름 최대 글자 수(`tag.name VARCHAR(50)` 과 같음) |
| `20` | `search.php:349`, `search.php:523`, `search.php:553` | 한 페이지 행 수 |
| `999` | `search.php:345-347` | 최대 페이지 번호 |
| `5` | `register.php:49` | 제목 최소 글자 수 |
| `200` | `register.php:52` | 제목 최대 글자 수(`item.title VARCHAR(200)` 과 같음) |
| `'A'` | `schema:70`, `units.php:17` | 공개 상태 코드 |
| `'R'` | `register.php:94` | 검수중 상태 코드(등록 초기 상태) |
| `0` (`COALESCE(MAX(id), 0) + 1`) | `register.php:87` | 문항이 없을 때 첫 id 를 1 로 만드는 기준값 |

## 7. 주석만 있고 코드로 확인되지 않은 것

규칙으로 올리지 않았다.

| 내용 | 위치 | 비고 |
|---|---|---|
| `D` = 삭제 | `schema:26` | 모듈 코드에 `'D'` 를 쓰는 곳이 없다. `A` 가 아니라서 검색에서 빠지는 것만 BR-25 로 확인된다 |
| 검수 완료(`R` → `A`) 후 검색에 노출 | `register.php:84`, `register.php:116` | 상태를 바꾸는 코드가 이 모듈에 없다(`ERD.md` 4절) |
| 난이도 1~5 | `schema:25` | DB CHECK 없음. 범위 보장은 등록 코드(BR-30)뿐 |
| 난이도 비우면 "1~5 모두 포함" | `search.php:213` | 코드와 반대(BR-10) |
| 등록 화면도 `v_item_public` 기준 | `schema:51` | `register.php` 는 뷰를 쓰지 않는다 |

## 8. 같은 규칙이 여러 곳에 다르게 구현된 곳

| 규칙 | 구현 A | 구현 B | 차이 |
|---|---|---|---|
| 난이도 1~5 | 검색: 형식 밖이면 경고 후 `(int)` 비교(BR-12) | 등록: 형식 밖이면 거부(BR-30) | `03` · `3.5` 가 검색에선 난이도 3 으로 통한다 |
| "공개 문항" 조건 | 뷰 `v_item_public`(`schema:70`) | `units.php:17` 직접 조건 | 한쪽만 바뀔 위험 |
| 난이도 5 포함 여부 | 검색 기본: 제외(BR-10) | 단원 문항 수: 포함(BR-26) | 단원 목록 문항 수와 조건 없는 검색 건수가 다르다 |
| 단원 식별 | 검색: `unit.code`(BR-07) | 등록: `unit.id`(BR-32) | — |
| 태그 식별 | 검색: `tag.name`(BR-13) | 등록: `tag.id`(BR-33) | — |
| 태그 존재 확인 | PHP `===`(대소문자 구분, BR-16) | SQL `=`(`_ci` collation) | 경고와 실제 결과가 어긋날 수 있다(추정) |
| 단원 정렬 | `units.php:19` | `search.php:156` · `register.php:27` | 값은 같지만 세 곳에 따로 있다 |
