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
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ActiveSessionFilter extends OncePerRequestFilter {

    private final ActiveLoginService activeLoginService;
    private final ObjectMapper objectMapper;

    public ActiveSessionFilter(ActiveLoginService activeLoginService, ObjectMapper objectMapper) {
        this.activeLoginService = activeLoginService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !path.startsWith("/api/")
                || path.equals("/api/user/login")
                || path.equals("/api/user/register")
                || path.startsWith("/api/ws");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        HttpSession session = request.getSession(false);
        if (session != null
                && session.getAttribute(ActiveLoginService.USER_ID_ATTRIBUTE) != null
                && !activeLoginService.isActive(session)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setCharacterEncoding("UTF-8");
            response.setContentType("application/json");
            objectMapper.writeValue(
                    response.getWriter(),
                    ApiResponse.error(401, activeLoginService.getInactiveReason(session))
            );
            return;
        }
        filterChain.doFilter(request, response);
    }
}
