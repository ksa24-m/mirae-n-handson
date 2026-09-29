package com.example.item;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/** 검색 파라미터 해석 — 레거시 search.php 의 보정 규칙(BR-02 ~ BR-23). */
class ItemSearchConditionTest {

    private static ItemSearchCondition parse(String... keyValues) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            params.add(keyValues[i], keyValues[i + 1]);
        }
        return ItemSearchCondition.from(params);
    }

    @Test
    @DisplayName("from: 파라미터가 없으면 조건 없음 · 난이도 비어 있음 · 1페이지 · 기본 정렬")
    void emptyParams() {
        ItemSearchCondition c = parse();

        assertThat(c.keyword()).isNull();
        assertThat(c.unitCode()).isNull();
        assertThat(c.level()).isNull();
        assertThat(c.tag()).isNull();
        assertThat(c.page()).isEqualTo(1);
        assertThat(c.toSort()).isEqualTo(Sort.by(Sort.Order.desc("level"), Sort.Order.asc("id")));
    }

    @Test
    @DisplayName("from: 빈 문자열 · 공백만 있는 값은 누락과 같다")
    void blankParams() {
        ItemSearchCondition c = parse("q", "   ", "unit", "", "level", " ", "tag", " ", "page", "");

        assertThat(c.keyword()).isNull();
        assertThat(c.unitCode()).isNull();
        assertThat(c.level()).isNull();
        assertThat(c.tag()).isNull();
        assertThat(c.page()).isEqualTo(1);
    }

    @Test
    @DisplayName("from: 키워드는 trim 후 앞 100자(코드 포인트 기준)만 쓴다")
    void keywordTruncatedTo100CodePoints() {
        assertThat(parse("q", "%".repeat(99) + "★").keyword()).hasSize(100).endsWith("★");
        assertThat(parse("q", "%".repeat(100) + "★").keyword()).isEqualTo("%".repeat(100));
        assertThat(parse("q", "😀".repeat(101)).keyword()).isEqualTo("😀".repeat(100));
        assertThat(parse("q", "  분수  ").keyword()).isEqualTo("분수");
    }

    @Test
    @DisplayName("from: 태그는 trim 후 앞 50자만 쓴다")
    void tagTruncatedTo50() {
        assertThat(parse("tag", "가".repeat(60)).tag()).isEqualTo("가".repeat(50));
        assertThat(parse("tag", " 문장제 ").tag()).isEqualTo("문장제");
    }

    @Test
    @DisplayName("from: 단원 코드는 trim 만 하고 대소문자 · 형식은 그대로 둔다")
    void unitCodeKeptAsIs() {
        assertThat(parse("unit", " m5-1 ").unitCode()).isEqualTo("m5-1");
        assertThat(parse("unit", "Z9-99").unitCode()).isEqualTo("Z9-99");
    }

    @Test
    @DisplayName("from: 난이도 1~5 는 그대로, 형식 밖 값은 PHP (int) 변환 값")
    void levelParsedLikePhpIntCast() {
        assertThat(parse("level", "5").level()).isEqualTo(5);
        assertThat(parse("level", "3abc").level()).isEqualTo(3);
        assertThat(parse("level", "3.5").level()).isEqualTo(3);
        assertThat(parse("level", "03").level()).isEqualTo(3);
        assertThat(parse("level", "-1").level()).isEqualTo(-1);
        assertThat(parse("level", "abc").level()).isEqualTo(0);
        assertThat(parse("level", "1e1").level()).isEqualTo(10);
        assertThat(parse("level", "99999999999999999999").level()).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    @DisplayName("from: 페이지는 숫자 아님 → 1, 1 미만 → 1, 999 초과 → 999, 끝 줄바꿈 하나는 숫자로 본다")
    void pageCorrected() {
        assertThat(parse("page", "0").page()).isEqualTo(1);
        assertThat(parse("page", "abc").page()).isEqualTo(1);
        assertThat(parse("page", "-1").page()).isEqualTo(1);
        assertThat(parse("page", "1000").page()).isEqualTo(999);
        assertThat(parse("page", "99999999999999999999").page()).isEqualTo(999);
        assertThat(parse("page", "2\n").page()).isEqualTo(2);
        assertThat(parse("page", " 2").page()).isEqualTo(1);
    }

    @Test
    @DisplayName("from: 같은 이름이 반복되면 마지막 값, name[] 배열이면 첫 값")
    void repeatedAndArrayParams() {
        assertThat(parse("unit", "M5-1", "unit", "M5-2").unitCode()).isEqualTo("M5-2");
        assertThat(parse("unit[]", "M5-1", "unit[]", "M5-2").unitCode()).isEqualTo("M5-1");
    }

    @Test
    @DisplayName("toSort: 대문자 기준은 소문자로 비교하고 잘못된 방향은 기준별 기본 방향")
    void sortCaseInsensitiveWithDefaultDirection() {
        assertThat(parse("sort", "LEVEL", "dir", "up").toSort())
            .isEqualTo(Sort.by(Sort.Order.desc("level"), Sort.Order.asc("id")));
        assertThat(parse("sort", "title").toSort())
            .isEqualTo(Sort.by(Sort.Order.asc("title"), Sort.Order.asc("id")));
        assertThat(parse("sort", "created").toSort())
            .isEqualTo(Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("id")));
        assertThat(parse("sort", "id", "dir", "DESC").toSort())
            .isEqualTo(Sort.by(Sort.Order.desc("id")));
    }

    @Test
    @DisplayName("toSort: 단원 정렬은 방향과 관계없이 난이도 내림차순 · id 오름차순이 뒤에 붙는다")
    void unitSortKeepsSecondaryKeys() {
        assertThat(parse("sort", "unit", "dir", "desc").toSort())
            .isEqualTo(Sort.by(Sort.Order.desc("unit.code"), Sort.Order.desc("level"), Sort.Order.asc("id")));
    }

    @Test
    @DisplayName("toSort: 모르는 정렬 기준은 기본 정렬")
    void unknownSortFallsBack() {
        assertThat(parse("sort", "stem", "dir", "asc").toSort())
            .isEqualTo(Sort.by(Sort.Order.desc("level"), Sort.Order.asc("id")));
    }

    @Test
    @DisplayName("trim: PHP trim 과 같이 \\f 는 지우지 않는다")
    void phpTrimKeepsFormFeed() {
        assertThat(LegacyPhpStrings.trim(" \t\n\r\0\u000B문장제\u000B ")).isEqualTo("문장제");
        assertThat(LegacyPhpStrings.trim("\f문장제")).isEqualTo("\f문장제");
    }
}
