package com.uno.controller;

import com.uno.dto.request.UpdateAvatarRequest;
import com.uno.dto.response.ApiResponse;
import com.uno.entity.User;
import com.uno.repository.UserRepository;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:uno_avatar;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.show-sql=false"
})
class UserAvatarPersistenceIntegrationTest {

    @Autowired
    private UserController userController;

    @Autowired
    private UserRepository userRepository;

    @Test
    void savingAvatarPreservesRequiredAccountFields() {
        String username = "avatar-" + UUID.randomUUID();
        String storedPassword = "stored-password-hash";
        User saved = userRepository.save(new User(username, storedPassword));
        User persistedBeforeUpdate = userRepository.findById(saved.getId()).orElseThrow();
        LocalDateTime createdAt = persistedBeforeUpdate.getCreatedAt();
        assertNotNull(createdAt);

        HttpSession session = mock(HttpSession.class);
        when(session.getAttribute("userId")).thenReturn(saved.getId());
        UpdateAvatarRequest request = new UpdateAvatarRequest();
        request.setAvatarDataUrl("data:image/png;base64,iVBORw0KGgo=");

        ApiResponse<Map<String, Object>> response = userController.updateAvatar(request, session);
        User reloaded = userRepository.findById(saved.getId()).orElseThrow();

        assertEquals(200, response.getCode());
        assertEquals(username, reloaded.getUsername());
        assertEquals(storedPassword, reloaded.getPassword());
        assertEquals(createdAt, reloaded.getCreatedAt());
        assertEquals(request.getAvatarDataUrl(), reloaded.getAvatarDataUrl());
    }
}
