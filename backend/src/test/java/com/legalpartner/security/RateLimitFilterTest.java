package com.legalpartner.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RateLimitFilterTest {

    private RateLimitFilter filter;

    @Mock private FilterChain chain;

    @BeforeEach
    void setUp() throws Exception {
        filter = new RateLimitFilter();
        setField("aiRequestsPerMinute", 3);
        setField("loginPerMinute", 2);
        setField("registerPerMinute", 2);
        setField("resetPerHour", 1);
    }

    @Test
    void getRequestsPassThrough() throws Exception {
        var request = mock(HttpServletRequest.class);
        var response = mock(HttpServletResponse.class);
        when(request.getRequestURI()).thenReturn("/api/v1/ai/drafts");
        when(request.getMethod()).thenReturn("GET");

        filter.doFilter(request, response, chain);
        verify(chain).doFilter(request, response);
    }

    @Test
    void loginRateLimited() throws Exception {
        StringWriter sw = new StringWriter();

        // 2 requests pass
        for (int i = 0; i < 2; i++) {
            var req = mock(HttpServletRequest.class);
            var resp = mock(HttpServletResponse.class);
            var ch = mock(FilterChain.class);
            when(req.getRequestURI()).thenReturn("/api/v1/auth/login");
            when(req.getMethod()).thenReturn("POST");
            when(req.getRemoteAddr()).thenReturn("192.168.1.100");
            filter.doFilter(req, resp, ch);
            verify(ch).doFilter(req, resp);
        }

        // 3rd blocked
        var req3 = mock(HttpServletRequest.class);
        var resp3 = mock(HttpServletResponse.class);
        var ch3 = mock(FilterChain.class);
        when(req3.getRequestURI()).thenReturn("/api/v1/auth/login");
        when(req3.getMethod()).thenReturn("POST");
        when(req3.getRemoteAddr()).thenReturn("192.168.1.100");
        when(resp3.getWriter()).thenReturn(new PrintWriter(sw));
        filter.doFilter(req3, resp3, ch3);
        verify(resp3).setStatus(429);
    }

    @Test
    void differentIPsIndependent() throws Exception {
        StringWriter sw = new StringWriter();

        // Exhaust IP1
        for (int i = 0; i < 3; i++) {
            var req = mock(HttpServletRequest.class);
            var resp = mock(HttpServletResponse.class);
            lenient().when(resp.getWriter()).thenReturn(new PrintWriter(sw));
            when(req.getRequestURI()).thenReturn("/api/v1/auth/login");
            when(req.getMethod()).thenReturn("POST");
            when(req.getRemoteAddr()).thenReturn("10.0.0.1");
            filter.doFilter(req, resp, mock(FilterChain.class));
        }

        // IP2 should still work
        var req2 = mock(HttpServletRequest.class);
        var resp2 = mock(HttpServletResponse.class);
        var ch2 = mock(FilterChain.class);
        when(req2.getRequestURI()).thenReturn("/api/v1/auth/login");
        when(req2.getMethod()).thenReturn("POST");
        when(req2.getRemoteAddr()).thenReturn("10.0.0.2");
        filter.doFilter(req2, resp2, ch2);
        verify(ch2).doFilter(req2, resp2);
    }

    private void setField(String name, Object value) throws Exception {
        var field = RateLimitFilter.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(filter, value);
    }
}
