package com.uno.auth;

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
    void firstLoginIsActiveAndSecondLoginReplacesOnlyTheSameAccount() {
        List<Object> events = new ArrayList<>();
        ActiveLoginService service = new ActiveLoginService(events::add);
        User alice = user(1L, "alice");
        User bob = user(2L, "bob");
        MockHttpSession aliceDeviceA = new MockHttpSession();
        MockHttpSession bobDevice = new MockHttpSession();

        service.activate(alice, aliceDeviceA);
        service.activate(bob, bobDevice);

        assertTrue(service.isActive(aliceDeviceA));
        assertTrue(service.isActive(bobDevice));
        assertTrue(events.isEmpty());

        MockHttpSession aliceDeviceB = new MockHttpSession();
        service.activate(alice, aliceDeviceB);

        assertFalse(service.isActive(aliceDeviceA));
        assertTrue(service.isReplaced(aliceDeviceA));
        assertTrue(service.isActive(aliceDeviceB));
        assertTrue(service.isActive(bobDevice), "another account must remain signed in");
        assertEquals(1, events.size());
        LoginSessionReplacedEvent event = (LoginSessionReplacedEvent) events.get(0);
        assertEquals(1L, event.userId());
        assertEquals("alice", event.username());
        assertEquals(aliceDeviceA.getId(), event.replacedHttpSessionId());
    }

    @Test
    void loggingOutAnOldDeviceCannotLogOutTheNewDevice() {
        ActiveLoginService service = new ActiveLoginService(ignored -> { });
        User alice = user(1L, "alice");
        MockHttpSession deviceA = new MockHttpSession();
        MockHttpSession deviceB = new MockHttpSession();
        service.activate(alice, deviceA);
        service.activate(alice, deviceB);

        service.deactivate(deviceA);

        assertTrue(service.isActive(deviceB));
        assertTrue(service.isReplaced(deviceA));

        service.deactivate(deviceB);
        assertTrue(service.isReplaced(deviceA),
                "the kicked device must not revive after the replacement logs out");
    }

    private static User user(Long id, String username) {
        User user = new User(username, "encoded");
        user.setId(id);
        return user;
    }
}
