package com.uno.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.uno.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActiveLoginHttpInterceptorTest {

    @Test
    void replacedDeviceCannotContinueHttpActionsButNewDeviceCan() throws Exception {
        ActiveLoginService service = new ActiveLoginService(ignored -> { });
        ActiveLoginHttpInterceptor interceptor = new ActiveLoginHttpInterceptor(service, new ObjectMapper());
        User alice = new User("alice", "encoded");
        alice.setId(1L);
        MockHttpSession deviceA = new MockHttpSession();
        MockHttpSession deviceB = new MockHttpSession();
        service.activate(alice, deviceA);
        service.activate(alice, deviceB);

        MockHttpServletRequest oldRequest = new MockHttpServletRequest("POST", "/api/game/10/play");
        oldRequest.setSession(deviceA);
        MockHttpServletResponse oldResponse = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(oldRequest, oldResponse, new Object()));
        assertEquals(401, oldResponse.getStatus());
        assertTrue(oldResponse.getContentAsString().contains(ActiveLoginService.SESSION_REPLACED_MESSAGE));

        MockHttpServletRequest newRequest = new MockHttpServletRequest("POST", "/api/game/10/play");
        newRequest.setSession(deviceB);
        assertTrue(interceptor.preHandle(newRequest, new MockHttpServletResponse(), new Object()));
    }
}
