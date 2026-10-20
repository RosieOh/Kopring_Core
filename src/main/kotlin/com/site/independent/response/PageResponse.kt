package com.site.independent.response

import org.springframework.data.domain.Page

/**
 * Spring Data 의 Page 를 그대로 JSON 으로 내보내면 내부 구조가 노출되고 버전마다 형태가 바뀔 수 있다.
 * (Spring Data 3.3+ 는 PageImpl 직렬화 시 경고를 남긴다) 필요한 필드만 담아 고정된 형태로 내보낸다.
 */
data class PageResponse<T>(
    val content: List<T>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int,
    val hasNext: Boolean,
)

// Page<엔티티> → PageResponse<DTO>. 변환 방법은 람다로 받는다.
fun <T, R> Page<T>.toPageResponse(transform: (T) -> R): PageResponse<R> =
    PageResponse(
        content = content.map(transform),
        page = number,
        size = size,
        totalElements = totalElements,
        totalPages = totalPages,
        hasNext = hasNext(),
    )
