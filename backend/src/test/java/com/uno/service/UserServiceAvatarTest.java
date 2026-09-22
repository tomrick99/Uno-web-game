package com.uno.service;

import com.uno.entity.User;
import com.uno.repository.UserRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserServiceAvatarTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final UserService userService = new UserService(userRepository);

    @Test
    void validCroppedAvatarIsPersisted() {
        User user = new User("alice", "pw");
        user.setId(1L);
        String avatar = "data:image/png;base64,iVBORw0KGgo=";
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        User updated = userService.updateAvatar(1L, avatar);

        assertEquals(avatar, updated.getAvatarDataUrl());
        verify(userRepository).save(user);
    }

    @Test
    void unsupportedOrInvalidAvatarIsRejected() {
        User user = new User("alice", "pw");
        user.setId(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        assertThrows(IllegalArgumentException.class,
                () -> userService.updateAvatar(1L, "data:image/svg+xml;base64,PHN2Zz4="));
        assertThrows(IllegalArgumentException.class,
                () -> userService.updateAvatar(1L, "data:image/png;base64,bm90LXBuZw=="));
    }

    @Test
    void blankAvatarRestoresDefaultFallback() {
        User user = new User("alice", "pw");
        user.setId(1L);
        user.setAvatarDataUrl("data:image/png;base64,iVBORw0KGgo=");
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        User updated = userService.updateAvatar(1L, " ");

        assertNull(updated.getAvatarDataUrl());
    }
}
