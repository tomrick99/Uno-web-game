package com.uno.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.uno.dto.response.ApiResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class SingleActiveSessionFilter extends OncePerRequestFilter {

    private final ActiveSessionRegistry activeSessions;
    private final ObjectMapper objectMapper;

    public SingleActiveSessionFilter(ActiveSessionRegistry activeSessions, ObjectMapper objectMapper) {
        this.activeSessions = activeSessions;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !path.startsWith("/api/")
                || path.equals("/api/user/login")
                || path.equals("/api/user/register");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String requestedSessionId = request.getRequestedSessionId();
        if (activeSessions.wasReplaced(requestedSessionId)) {
            rejectReplaced(response);
            return;
        }

        HttpSession session = request.getSession(false);
        if (session != null) {
            Long userId = (Long) session.getAttribute("userId");
            if (userId != null && !activeSessions.isActive(userId, session.getId())) {
                rejectReplaced(response);
                return;
            }
        }

        filterChain.doFilter(request, response);
    }

    private void rejectReplaced(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setHeader(SessionPolicy.REPLACED_HEADER, "true");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(),
                ApiResponse.error(HttpServletResponse.SC_UNAUTHORIZED, SessionPolicy.REPLACED_MESSAGE));
    }
}
