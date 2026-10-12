package com.uno.session;

import com.uno.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.web.MockHttpSession;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActiveLoginServiceTest {

    @Test
    void secondLoginReplacesOnlyTheSameAccountsFirstSession() {
        List<Object> events = new ArrayList<>();
        ActiveLoginService service = new ActiveLoginService(events::add);
        User alice = user(1L, "alice");
        User bob = user(2L, "bob");
        MockHttpSession aliceDeviceA = new MockHttpSession();
        MockHttpSession aliceDeviceB = new MockHttpSession();
        MockHttpSession bobDevice = new MockHttpSession();

        service.activate(alice, aliceDeviceA);
        service.activate(bob, bobDevice);

        assertTrue(service.isActive(aliceDeviceA));
        assertTrue(service.isActive(bobDevice));
        assertEquals(2, service.activeLoginCount());

        service.activate(alice, aliceDeviceB);

        assertFalse(service.isActive(aliceDeviceA));
        assertEquals(ActiveLoginService.REPLACED_MESSAGE, service.getInactiveReason(aliceDeviceA));
        assertTrue(service.isActive(aliceDeviceB));
        assertTrue(service.isActive(bobDevice), "another account must remain active");
        assertEquals(2, service.activeLoginCount(), "one active login per account is retained");

        LoginSessionReplacedEvent event = (LoginSessionReplacedEvent) events.get(0);
        assertEquals(alice.getId(), event.userId());
        assertEquals(alice.getUsername(), event.username());
        assertFalse(event.replacedLoginToken().equals(event.replacementLoginToken()));
    }

    @Test
    void repeatedLoginInTheSameHttpSessionKeepsItsExistingDeviceIdentity() {
        List<Object> events = new ArrayList<>();
        ActiveLoginService service = new ActiveLoginService(events::add);
        User alice = user(1L, "alice");
        MockHttpSession session = new MockHttpSession();

        ActiveLoginService.ActiveLogin first = service.activate(alice, session);
        ActiveLoginService.ActiveLogin second = service.activate(alice, session);

        assertEquals(first.loginToken(), second.loginToken());
        assertTrue(service.isActive(session));
        assertTrue(events.isEmpty());
    }

    private User user(Long id, String username) {
        User user = new User(username, "encoded");
        user.setId(id);
        return user;
    }
}
