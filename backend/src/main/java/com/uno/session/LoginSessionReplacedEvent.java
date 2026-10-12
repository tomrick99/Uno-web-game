package com.uno.session;

public record LoginSessionReplacedEvent(
        Long userId,
        String username,
        String replacedLoginToken,
        String replacementLoginToken
) {
}
