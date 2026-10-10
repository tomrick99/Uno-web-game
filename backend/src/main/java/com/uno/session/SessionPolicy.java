package com.uno.session;

public final class SessionPolicy {

    public static final String HTTP_SESSION_ID_ATTRIBUTE = "unoHttpSessionId";
    public static final String REPLACED_HEADER = "X-Session-Replaced";
    public static final String REPLACED_EVENT_TYPE = "SESSION_REPLACED";
    public static final String REPLACED_MESSAGE =
            "This account was signed in on another device. You have been signed out.";
    public static final int REPLACED_WEBSOCKET_CLOSE_CODE = 4001;

    private SessionPolicy() {
    }
}
