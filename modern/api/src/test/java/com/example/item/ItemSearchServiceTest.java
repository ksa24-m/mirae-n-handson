package com.example.item;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;

@ExtendWith(MockitoExtension.class)
@ActiveProfiles("test")
class ItemSearchServiceTest {

    @Mock
    private ItemRepository itemRepository;

    @InjectMocks
    private ItemSearchService itemSearchService;

    private final Unit unit = ItemFixtures.unit(1, "M5-1", "분수의 덧셈과 뺄셈", 5);

    private static ItemSearchCondition noCondition() {
        return ItemSearchCondition.from(new LinkedMultiValueMap<>());
    }

    @SuppressWarnings("unchecked")
    private static Specification<Item> anySpec() {
        return any(Specification.class);
    }

    @Test
    @DisplayName("search: 페이지 순서를 유지하고 정규화 필드만 담는다")
    void searchKeepsPageOrder() {
        Item hard = ItemFixtures.item(18, unit, "분수의 뺄셈 문장제", 4, ItemStatus.ACTIVE,
            ItemFixtures.tag(2, "문장제"), ItemFixtures.tag(4, "오답률높음"));
        Item easy = ItemFixtures.item(1, unit, "분모가 같은 분수의 덧셈", 1, ItemStatus.ACTIVE);
        when(itemRepository.findAll(anySpec(), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(hard, easy), Pageable.ofSize(20), 2));
        // 상세 조회는 순서가 달라도 된다
        when(itemRepository.findWithDetailsByIdIn(List.of(18, 1))).thenReturn(List.of(easy, hard));

        ItemSearchResponse response = itemSearchService.search(noCondition());

        assertThat(response.count()).isEqualTo(2);
        assertThat(response.message()).isNull();
        assertThat(response.rows()).containsExactly(
            new ItemSearchRow(18, "분수의 뺄셈 문장제", "M5-1", 4, List.of("문장제", "오답률높음")),
            new ItemSearchRow(1, "분모가 같은 분수의 덧셈", "M5-1", 1, List.of()));
    }

    @Test
    @DisplayName("search: 0건이면 안내 문구 \"검색 결과가 없습니다\"")
    void searchEmptyHasMessage() {
        when(itemRepository.findAll(anySpec(), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(), Pageable.ofSize(20), 0));

        ItemSearchResponse response = itemSearchService.search(noCondition());

        assertThat(response.rows()).isEmpty();
        assertThat(response.count()).isZero();
        assertThat(response.message()).isEqualTo("검색 결과가 없습니다");
        verify(itemRepository, never()).findWithDetailsByIdIn(anyCollection());
    }

    @Test
    @DisplayName("search: 범위 밖 페이지는 전체 건수만 있고 안내 문구가 없다")
    void searchOutOfRangePageHasNoMessage() {
        when(itemRepository.findAll(anySpec(), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(), Pageable.ofSize(20).withPage(1), 20));

        ItemSearchResponse response = itemSearchService.search(noCondition());

        assertThat(response.rows()).isEmpty();
        assertThat(response.count()).isEqualTo(20);
        assertThat(response.message()).isNull();
    }

    @Test
    @DisplayName("search: 페이지 크기 20, 페이지 번호는 0부터 센 값으로 넘긴다")
    void searchUsesPageSize20() {
        LinkedMultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("page", "3");
        when(itemRepository.findAll(anySpec(), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(), Pageable.ofSize(20), 0));

        itemSearchService.search(ItemSearchCondition.from(params));

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(itemRepository).findAll(anySpec(), pageable.capture());
        assertThat(pageable.getValue().getPageSize()).isEqualTo(20);
        assertThat(pageable.getValue().getPageNumber()).isEqualTo(2);
    }
}
