package com.example.item;

import java.util.List;

/**
 * {@code GET /api/items/search} 응답. 동작 보존 테스트의 정규화 모양({@code rows, count, message})과 같다.
 *
 * @param rows    현재 페이지의 행(최대 20건)
 * @param count   조건에 맞는 전체 건수(현재 페이지 행 수가 아님)
 * @param message 0건이면 {@code "검색 결과가 없습니다"}, 아니면 {@code null}
 */
public record ItemSearchResponse(List<ItemSearchRow> rows, long count, String message) {
}
