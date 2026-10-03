package com.vendorflow.shared.web;

import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/** The list envelope of docs/API.md: {@code { items, page, size, totalItems, totalPages }}. */
public record PageResponse<T>(List<T> items, int page, int size, long totalItems, int totalPages) {

    public static final int DEFAULT_SIZE = 50;
    public static final int MAX_SIZE = 100;

    public static <T> PageResponse<T> of(Page<T> page) {
        return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements(),
                page.getTotalPages());
    }

    /** Clamps instead of rejecting: a negative page becomes 0 and the size is held to 1..100 (the API maximum). */
    public static Pageable pageable(int page, int size) {
        return PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_SIZE));
    }
}
