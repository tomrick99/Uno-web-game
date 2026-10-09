package com.uno.auth;

public record LoginSessionReplacedEvent(
        Long userId,
        String username,
        String replacedLoginToken,
        String replacedHttpSessionId
) {
}
