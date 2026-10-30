package com.fitclub.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.fitclub.common.web.RequestIdFilter;
import com.fitclub.common.web.RequestIds;

class RequestIdFilterTest {

    @Test
    void requestIdIsAvailableInsideRequestAndClearedAfterwards() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/x");
        request.addHeader(RequestIds.HEADER, "req-0000-1111");
        AtomicReference<String> seenInside = new AtomicReference<>();

        new RequestIdFilter().doFilter(request, new MockHttpServletResponse(),
                (req, res) -> seenInside.set(MDC.get(RequestIds.MDC_KEY)));

        assertThat(seenInside.get()).isEqualTo("req-0000-1111");
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    void mdcIsClearedEvenWhenRequestFails() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/x");
        try {
            new RequestIdFilter().doFilter(request, new MockHttpServletResponse(), (req, res) -> {
                throw new IllegalStateException("boom");
            });
        } catch (Exception expected) {
            // ожидаемо
        }
        assertThat(MDC.get(RequestIds.MDC_KEY)).isNull();
    }

    @Test
    void acceptsOnlySafeIdentifiers() {
        assertThat(RequestIds.acceptOrGenerate("abcDEF-123")).isEqualTo("abcDEF-123");
        assertThat(RequestIds.acceptOrGenerate("short")).isNotEqualTo("short");
        assertThat(RequestIds.acceptOrGenerate("x".repeat(65))).hasSize(36);
        assertThat(RequestIds.acceptOrGenerate("line\nbreak-inject")).doesNotContain("\n");
        assertThat(RequestIds.acceptOrGenerate(null)).hasSize(36);
    }
}
