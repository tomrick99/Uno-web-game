package com.uno.session;

import com.uno.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActiveSessionRegistryTest {

    @Test
    void secondLoginReplacesAndInvalidatesFirstSession() {
        ActiveSessionRegistry registry = new ActiveSessionRegistry();
        User alice = user(1L, "alice");
        MockHttpSession deviceA = new MockHttpSession();
        MockHttpSession deviceB = new MockHttpSession();

        assertTrue(registry.activate(alice, deviceA).isEmpty());
        assertTrue(registry.isActive(alice.getId(), deviceA.getId()));

        ActiveSessionRegistry.ReplacedSession replaced = registry.activate(alice, deviceB).orElseThrow();

        assertTrue(registry.wasReplaced(replaced.sessionId()));
        assertFalse(registry.isActive(alice.getId(), replaced.sessionId()));
        assertTrue(registry.isActive(alice.getId(), deviceB.getId()));
        assertThrows(IllegalStateException.class, deviceA::getCreationTime);
    }

    @Test
    void differentAccountsRemainIndependent() {
        ActiveSessionRegistry registry = new ActiveSessionRegistry();
        User alice = user(1L, "alice");
        User bob = user(2L, "bob");
        MockHttpSession aliceSession = new MockHttpSession();
        MockHttpSession bobSession = new MockHttpSession();

        registry.activate(alice, aliceSession);
        registry.activate(bob, bobSession);

        assertTrue(registry.isActive("alice", aliceSession.getId()));
        assertTrue(registry.isActive("bob", bobSession.getId()));
        assertFalse(registry.wasReplaced(aliceSession.getId()));
        assertFalse(registry.wasReplaced(bobSession.getId()));
    }

    @Test
    void loggingInAgainOnTheSameSessionDoesNotReplaceItself() {
        ActiveSessionRegistry registry = new ActiveSessionRegistry();
        User alice = user(1L, "alice");
        MockHttpSession session = new MockHttpSession();

        registry.activate(alice, session);

        assertTrue(registry.activate(alice, session).isEmpty());
        assertTrue(registry.isActive(alice.getId(), session.getId()));
    }

    private User user(Long id, String username) {
        User user = new User(username, "password");
        user.setId(id);
        return user;
    }
}
