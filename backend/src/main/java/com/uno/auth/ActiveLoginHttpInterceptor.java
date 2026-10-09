package com.uno.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.uno.dto.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Component
public class ActiveLoginHttpInterceptor implements HandlerInterceptor {

    private final ActiveLoginService activeLoginService;
    private final ObjectMapper objectMapper;

    public ActiveLoginHttpInterceptor(ActiveLoginService activeLoginService, ObjectMapper objectMapper) {
        this.activeLoginService = activeLoginService;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        HttpSession session = request.getSession(false);
        if (!activeLoginService.isReplaced(session)) {
            return true;
        }

        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(),
                ApiResponse.error(401, ActiveLoginService.SESSION_REPLACED_MESSAGE));
        return false;
    }
}
