package com.scrumceremonies.util;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Lightweight tests for ClientIpResolver: X-Forwarded-For handling,
 * fallback to remoteAddr, and edge cases.
 */
class ClientIpResolverTest {

    private ClientIpResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new ClientIpResolver();
    }

    @Test
    void xForwardedFor_singleIp_returnsThatIp() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Forwarded-For")).thenReturn("10.1.1.1");
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");

        assertEquals("10.1.1.1", resolver.resolve(request));
    }

    @Test
    void xForwardedFor_multipleIps_commaSeparated_returnsLastIp() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Forwarded-For")).thenReturn("203.0.113.50, 198.51.100.18, 192.0.2.178");
        when(request.getRemoteAddr()).thenReturn("10.0.0.1");

        assertEquals("192.0.2.178", resolver.resolve(request));
    }

    @Test
    void missingHeader_returnsRemoteAddr() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Forwarded-For")).thenReturn(null);
        when(request.getRemoteAddr()).thenReturn("192.168.1.100");

        assertEquals("192.168.1.100", resolver.resolve(request));
    }

    @Test
    void emptyHeader_returnsRemoteAddr() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Forwarded-For")).thenReturn("");
        when(request.getRemoteAddr()).thenReturn("172.16.0.1");

        assertEquals("172.16.0.1", resolver.resolve(request));
    }

    @Test
    void xForwardedFor_whitespaceTrimming_returnsTrimmedLastIp() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Forwarded-For")).thenReturn("  10.2.3.4  , 10.5.6.7");
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");

        assertEquals("10.5.6.7", resolver.resolve(request));
    }

    @Test
    void nullRequest_returnsUnknown() {
        assertEquals("unknown", resolver.resolve(null));
    }

    @Test
    void xForwardedFor_firstSegmentEmpty_returnsLastIp() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Forwarded-For")).thenReturn("  , 10.1.1.1");
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");

        assertEquals("10.1.1.1", resolver.resolve(request));
    }

    @Test
    void remoteAddrNull_returnsUnknown() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Forwarded-For")).thenReturn(null);
        when(request.getRemoteAddr()).thenReturn(null);

        assertEquals("unknown", resolver.resolve(request));
    }

    @Test
    void xForwardedForPresent_remoteAddrNull_returnsForwardedIp() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Forwarded-For")).thenReturn("10.9.8.7");
        when(request.getRemoteAddr()).thenReturn(null);

        assertEquals("10.9.8.7", resolver.resolve(request));
    }
}
