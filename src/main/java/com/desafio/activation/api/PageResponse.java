package com.desafio.activation.api;

import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/** Formato estável de paginação, sem expor a serialização interna do {@link Page}. */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

    static <E, T> PageResponse<T> from(Page<E> page, Function<E, T> mapper) {
        return new PageResponse<>(page.map(mapper).getContent(), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }
}
