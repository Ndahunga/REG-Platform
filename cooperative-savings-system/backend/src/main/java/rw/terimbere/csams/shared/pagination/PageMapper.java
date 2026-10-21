package rw.terimbere.csams.shared.pagination;

import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;
import rw.terimbere.csams.shared.common.dto.PageResponse;

public final class PageMapper {

    private PageMapper() {
    }

    public static <T> PageResponse<T> toPageResponse(Page<T> page) {
        return PageResponse.<T>builder()
                .content(page.getContent())
                .page(page.getNumber())
                .size(page.getSize())
                .totalElements(page.getTotalElements())
                .totalPages(page.getTotalPages())
                .first(page.isFirst())
                .last(page.isLast())
                .build();
    }

    public static <S, T> PageResponse<T> toPageResponse(Page<S> page, Function<S, T> mapper) {
        return toPageResponse(page, page.getContent().stream().map(mapper).toList());
    }

    public static <S, T> PageResponse<T> toPageResponse(Page<S> page, List<T> mappedContent) {
        return PageResponse.<T>builder()
                .content(mappedContent)
                .page(page.getNumber())
                .size(page.getSize())
                .totalElements(page.getTotalElements())
                .totalPages(page.getTotalPages())
                .first(page.isFirst())
                .last(page.isLast())
                .build();
    }
}
