package com.uno.websocket;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameMobileControlsContractTest {

    @Test
    void mobileControlsExposeExistingPlayDrawAndPenaltyActions() throws IOException {
        String html = readResource("static/game.html");

        assertTrue(html.contains("viewport-fit=cover"),
                "edge-to-edge iOS layouts must expose safe-area inset values");
        assertTrue(html.contains("class=\"mobile-action-dock\""));
        assertTrue(html.contains("id=\"mobileDrawCardButton\""));
        assertTrue(html.contains("@click=\"drawCardAction\""),
                "the persistent Draw control must use the existing draw action");
        assertTrue(html.contains("id=\"mobilePlaySelectedCardButton\""));
        assertTrue(html.contains("@click=\"playSelectedCard\""),
                "the persistent Play control must use the existing play action");
        assertTrue(html.contains("id=\"mobileDrawPenaltyButton\""));
        assertTrue(html.contains("@click=\"drawPenaltyAction()\""),
                "the existing penalty action must remain reachable from the tray");
        assertTrue(html.contains(":aria-pressed=\"selectedCard === idx\""),
                "selected hand cards must expose their selected state");
    }

    @Test
    void touchLayoutsPinTheHandAndReserveTheSystemGestureArea() throws IOException {
        String css = readResource("static/css/game.css").replace("\r\n", "\n");

        int hiddenByDefault = css.indexOf(".mobile-action-dock {\n    display: none;");
        int touchBreakpoint = css.indexOf("@media (max-width: 1024px), (max-width: 1366px) and (any-pointer: coarse)");
        assertTrue(hiddenByDefault >= 0 && touchBreakpoint > hiddenByDefault,
                "desktop must keep its existing controls while phone and tablet touch layouts opt into the tray");

        String touchRules = css.substring(touchBreakpoint);
        assertTrue(touchRules.contains(".my-hand-area {"));
        assertTrue(touchRules.contains("position: fixed;"));
        assertTrue(touchRules.contains("bottom: 0;"));
        assertTrue(touchRules.contains("env(safe-area-inset-bottom, 0px)"),
                "bottom controls must reserve iPhone and iPad gesture safe area");
        assertTrue(touchRules.contains("touch-action: pan-x;"),
                "large hands must remain horizontally browseable");
        assertTrue(touchRules.contains("orientation: landscape") && touchRules.contains("max-height: 600px"),
                "short landscape viewports must receive a compact tray");
    }

    private String readResource(String path) throws IOException {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(path)) {
            assertNotNull(input, "served frontend resource must exist: " + path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
