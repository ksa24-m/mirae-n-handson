package com.example.item;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.util.LinkedMultiValueMap;

/** 레거시 search.php 의 PHP 7.4 문자열 처리 재현. */
class LegacyPhpStringsTest {

    @Test
    @DisplayName("trim: 앞뒤의 공백 · \\t · \\n · \\r · \\0 · \\x0B 만 지우고 가운데 공백은 남긴다")
    void trimOnlyPhpChars() {
        assertThat(LegacyPhpStrings.trim("\0 분수 덧셈 \r\n")).isEqualTo("분수 덧셈");
        assertThat(LegacyPhpStrings.trim("문장제\f")).isEqualTo("문장제\f");
        assertThat(LegacyPhpStrings.trim("　문장제")).isEqualTo("　문장제");
        assertThat(LegacyPhpStrings.trim(" \t ")).isEmpty();
    }

    @Test
    @DisplayName("length: 코드 포인트 수를 센다(서로게이트 쌍은 한 글자)")
    void lengthCountsCodePoints() {
        assertThat(LegacyPhpStrings.length("")).isZero();
        assertThat(LegacyPhpStrings.length("분수")).isEqualTo(2);
        assertThat(LegacyPhpStrings.length("😀😀")).isEqualTo(2);
    }

    @Test
    @DisplayName("head: 코드 포인트 기준으로 앞부분을 자르고 짧으면 그대로 둔다")
    void headByCodePoints() {
        assertThat(LegacyPhpStrings.head("분수", 5)).isEqualTo("분수");
        assertThat(LegacyPhpStrings.head("분수의덧셈", 2)).isEqualTo("분수");
        assertThat(LegacyPhpStrings.head("😀a😀", 2)).isEqualTo("😀a");
    }

    @Test
    @DisplayName("toLowerAscii: ASCII 대문자만 소문자로 바꾸고 그 밖의 글자는 그대로 둔다")
    void toLowerAsciiOnly() {
        assertThat(LegacyPhpStrings.toLowerAscii("LeVeL")).isEqualTo("level");
        assertThat(LegacyPhpStrings.toLowerAscii("ÄÉ단원Z")).isEqualTo("ÄÉ단원z");
    }

    @Test
    @DisplayName("toInt: 앞 공백(\\f 포함)을 건너뛰고 숫자로 읽을 수 있는 앞부분만 쓴다")
    void toIntReadsNumericPrefix() {
        assertThat(LegacyPhpStrings.toInt("3abc")).isEqualTo(3L);
        assertThat(LegacyPhpStrings.toInt("abc")).isZero();
        assertThat(LegacyPhpStrings.toInt("")).isZero();
        assertThat(LegacyPhpStrings.toInt(" \f\t4")).isEqualTo(4L);
        assertThat(LegacyPhpStrings.toInt("+7")).isEqualTo(7L);
        assertThat(LegacyPhpStrings.toInt("-2x")).isEqualTo(-2L);
        assertThat(LegacyPhpStrings.toInt("007")).isEqualTo(7L);
    }

    @Test
    @DisplayName("toInt: 소수 · 지수는 실수로 읽어 0 쪽으로 버린다")
    void toIntTruncatesTowardZero() {
        assertThat(LegacyPhpStrings.toInt("3.9")).isEqualTo(3L);
        assertThat(LegacyPhpStrings.toInt("-3.9")).isEqualTo(-3L);
        assertThat(LegacyPhpStrings.toInt(".5")).isZero();
        assertThat(LegacyPhpStrings.toInt("1e1")).isEqualTo(10L);
        assertThat(LegacyPhpStrings.toInt("2.5E+1")).isEqualTo(25L);
        assertThat(LegacyPhpStrings.toInt("15e-1")).isEqualTo(1L);
        assertThat(LegacyPhpStrings.toInt("1e")).isEqualTo(1L);
    }

    @Test
    @DisplayName("toInt: long 범위를 넘는 유한한 값은 끝값에 붙는다")
    void toIntCapsFiniteOverflow() {
        assertThat(LegacyPhpStrings.toInt("999999999999999999")).isEqualTo(999_999_999_999_999_999L);
        assertThat(LegacyPhpStrings.toInt("99999999999999999999")).isEqualTo(Long.MAX_VALUE);
        assertThat(LegacyPhpStrings.toInt("-99999999999999999999")).isEqualTo(Long.MIN_VALUE);
        assertThat(LegacyPhpStrings.toInt("1e300")).isEqualTo(Long.MAX_VALUE);
        assertThat(LegacyPhpStrings.toInt("-1e300")).isEqualTo(Long.MIN_VALUE);
    }

    @Test
    @DisplayName("toInt: 무한대가 되는 값은 PHP zend_dval_to_lval_cap 처럼 0 이다")
    void toIntInfinityIsZero() {
        assertThat(LegacyPhpStrings.toInt("1e999")).isZero();
        assertThat(LegacyPhpStrings.toInt("-1e999")).isZero();
        assertThat(LegacyPhpStrings.toInt("1" + "0".repeat(400))).isZero();
    }

    @Test
    @DisplayName("clampToInt: int 범위 안은 그대로, 밖은 끝값")
    void clampToInt() {
        assertThat(LegacyPhpStrings.clampToInt(3L)).isEqualTo(3);
        assertThat(LegacyPhpStrings.clampToInt(Long.MAX_VALUE)).isEqualTo(Integer.MAX_VALUE);
        assertThat(LegacyPhpStrings.clampToInt(Long.MIN_VALUE)).isEqualTo(Integer.MIN_VALUE);
    }

    @Test
    @DisplayName("from: 무한대가 되는 난이도 값은 0 으로 보정되어 0건 조건이 된다")
    void levelInfinityBecomesZero() {
        LinkedMultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("level", "1e999");

        assertThat(ItemSearchCondition.from(params).level()).isZero();
    }
}
