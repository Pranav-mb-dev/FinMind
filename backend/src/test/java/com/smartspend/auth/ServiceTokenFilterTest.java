package com.smartspend.auth;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ServiceTokenFilterTest {

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private boolean authenticatedAsService(String configured, String path, String header) throws Exception {
        ServiceTokenFilter filter = new ServiceTokenFilter(configured);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setRequestURI(path);
        if (header != null) {
            request.addHeader(ServiceTokenFilter.HEADER, header);
        }
        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        verify(chain).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_" + ServiceTokenFilter.ROLE));
    }

    @Test
    void correctTokenOnInternalPathGrantsServiceRole() throws Exception {
        assertThat(authenticatedAsService("secret", "/internal/recurring/scan", "secret")).isTrue();
    }

    @Test
    void wrongTokenIsNotAuthenticated() throws Exception {
        assertThat(authenticatedAsService("secret", "/internal/recurring/scan", "nope")).isFalse();
    }

    @Test
    void missingHeaderIsNotAuthenticated() throws Exception {
        assertThat(authenticatedAsService("secret", "/internal/recurring/scan", null)).isFalse();
    }

    @Test
    void unconfiguredTokenDisablesEndpointEvenForEmptyHeader() throws Exception {
        assertThat(authenticatedAsService("", "/internal/recurring/scan", "")).isFalse();
        assertThat(authenticatedAsService(null, "/internal/recurring/scan", "anything")).isFalse();
    }

    @Test
    void tokenIsIgnoredOutsideInternalPaths() throws Exception {
        assertThat(authenticatedAsService("secret", "/transactions", "secret")).isFalse();
    }
}
