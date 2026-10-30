package com.fitclub.common.web;

import java.util.List;
import java.util.function.Function;

/**
 * Единый формат страницы списка во всех API:
 * { "items": [...], "page": 0, "size": 20, "total_elements": 42, "total_pages": 3 }.
 */
public record PageResponse<T>(List<T> items, int page, int size, long totalElements, int totalPages) {

    public static final int MAX_SIZE = 100;

    public <R> PageResponse<R> map(Function<T, R> mapper) {
        return new PageResponse<>(items.stream().map(mapper).toList(), page, size, totalElements, totalPages);
    }

    public static <T> PageResponse<T> of(List<T> items, int page, int size, long totalElements) {
        int totalPages = size == 0 ? 0 : (int) ((totalElements + size - 1) / size);
        return new PageResponse<>(List.copyOf(items), page, size, totalElements, totalPages);
    }
}
