package com.example.item;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * 검색 조건 → 실제 SQL(H2 MariaDB 모드).
 * H2 는 대소문자를 구분하므로 소문자 단원 코드(BR-08) 결과는 여기서 확인하지 않는다 — MariaDB 대상 동작 보존 테스트가 본다.
 */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class ItemSearchSpecsTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private ItemRepository itemRepository;

    @BeforeEach
    void seed() {
        Unit fraction = entityManager.persist(ItemFixtures.unit(1, "M5-1", "분수의 덧셈과 뺄셈", 5));
        Unit decimal = entityManager.persist(ItemFixtures.unit(3, "M5-3", "소수의 곱셈", 5));
        Tag calc = entityManager.persist(ItemFixtures.tag(1, "계산"));
        Tag word = entityManager.persist(ItemFixtures.tag(2, "문장제"));

        entityManager.persist(ItemFixtures.item(null, fraction, "분모가 같은 분수의 덧셈", 1, ItemStatus.ACTIVE, calc));
        entityManager.persist(ItemFixtures.item(null, fraction, "분수 문장제", 4, ItemStatus.ACTIVE, calc, word));
        entityManager.persist(ItemFixtures.item(null, fraction, "검수 중 분수", 4, ItemStatus.REVIEWING, word));
        entityManager.persist(ItemFixtures.item(null, fraction, "삭제된 분수", 3, ItemStatus.DELETED));
        entityManager.persist(ItemFixtures.item(null, fraction, "난이도 5 분수", 5, ItemStatus.ACTIVE, word));
        entityManager.persist(ItemFixtures.item(null, decimal, "소수 곱셈", 1, ItemStatus.ACTIVE));
        entityManager.flush();
        entityManager.clear();
    }

    private List<String> titles(String... keyValues) {
        return search(keyValues).getContent().stream().map(Item::getTitle).toList();
    }

    private Page<Item> search(String... keyValues) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            params.add(keyValues[i], keyValues[i + 1]);
        }
        ItemSearchCondition condition = ItemSearchCondition.from(params);
        return itemRepository.findAll(ItemSearchSpecs.matching(condition),
            PageRequest.of(condition.page() - 1, ItemSearchService.PAGE_SIZE, condition.toSort()));
    }

    @Test
    @DisplayName("matching: 조건이 없으면 공개 문항 중 난이도 5 를 빼고 난이도 내림차순 · id 오름차순")
    void noConditionExcludesLevel5AndNonActive() {
        assertThat(titles()).containsExactly("분수 문장제", "분모가 같은 분수의 덧셈", "소수 곱셈");
    }

    @Test
    @DisplayName("matching: 난이도 5 를 명시하면 난이도 5 공개 문항이 나온다")
    void explicitLevel5() {
        assertThat(titles("level", "5")).containsExactly("난이도 5 분수");
    }

    @Test
    @DisplayName("matching: 키워드는 제목 또는 지문 부분 일치")
    void keywordMatchesTitleOrStem() {
        assertThat(titles("q", "문장제")).containsExactly("분수 문장제");
        // 지문은 "<제목> 문제 본문"
        assertThat(titles("q", "문제 본문")).hasSize(3);
    }

    @Test
    @DisplayName("matching: 키워드의 _ 는 한 글자 와일드카드")
    void underscoreIsWildcard() {
        assertThat(titles("q", "소_ 곱셈")).containsExactly("소수 곱셈");
    }

    @Test
    @DisplayName("matching: 단원 · 난이도 · 태그는 AND 로 결합한다")
    void conditionsAreAnded() {
        assertThat(titles("unit", "M5-1", "level", "1")).containsExactly("분모가 같은 분수의 덧셈");
        assertThat(titles("tag", "문장제")).containsExactly("분수 문장제");
        assertThat(titles("tag", "계산", "unit", "M5-3")).isEmpty();
    }

    @Test
    @DisplayName("matching: 없는 단원 · 없는 태그 · 범위 밖 난이도는 오류 없이 0건")
    void unknownValuesGiveEmptyResult() {
        assertThat(search("unit", "Z9-99").getTotalElements()).isZero();
        assertThat(search("tag", "없는태그").getTotalElements()).isZero();
        assertThat(search("level", "-1").getTotalElements()).isZero();
    }

    @Test
    @DisplayName("matching: 범위 밖 페이지는 전체 건수는 그대로이고 행이 비어 있다")
    void pageOutOfRange() {
        Page<Item> page = search("page", "2");

        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isEqualTo(3);
    }

    @Test
    @DisplayName("matching: 단원 정렬 내림차순도 2차 키(난이도 내림차순 · id 오름차순)는 뒤집지 않는다")
    void unitSortDescending() {
        assertThat(titles("sort", "unit", "dir", "desc"))
            .containsExactly("소수 곱셈", "분수 문장제", "분모가 같은 분수의 덧셈");
    }

    @Test
    @DisplayName("findWithDetailsByIdIn: 단원 · 태그를 함께 읽고 태그는 id 순")
    void findWithDetailsByIdIn() {
        List<Integer> ids = search().getContent().stream().map(Item::getId).toList();
        entityManager.clear();

        List<Item> items = itemRepository.findWithDetailsByIdIn(ids);

        assertThat(items).hasSize(3);
        Item word = items.stream().filter(i -> i.getTitle().equals("분수 문장제")).findFirst().orElseThrow();
        assertThat(word.getUnit().getCode()).isEqualTo("M5-1");
        assertThat(word.getTags()).extracting(Tag::getName).containsExactly("계산", "문장제");
    }
}
