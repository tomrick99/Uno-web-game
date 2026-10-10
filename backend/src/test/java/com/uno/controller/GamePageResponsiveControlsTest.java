package com.uno.controller;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

class GamePageResponsiveControlsTest {

    @Test
    void touchActionDockKeepsPlayDrawPenaltyAndSelectionWiredToExistingActions() throws IOException {
        String html = resource("static/game.html");

        assertTrue(html.contains("id=\"mobilePlaySelectedCardButton\""));
        assertTrue(html.contains(":disabled=\"selectedCard === null || !canPlaySelected || playingCard\""));
        assertTrue(html.contains("@click=\"playSelectedCard\""));
        assertTrue(html.contains("id=\"mobileDrawCardButton\""));
        assertTrue(html.contains(":disabled=\"!canDraw || drawingCard\""));
        assertTrue(html.contains("@click=\"drawCardAction\""));
        assertTrue(html.contains("id=\"mobileDrawPenaltyButton\""));
        assertTrue(html.contains("@click=\"drawPenaltyAction\""));
        assertTrue(html.contains("{ selected: selectedCard === idx }"));
        assertTrue(html.contains("selectedCardInfo ? `${t(\"selectedCard\")}: ${selectedCardInfo.display}`"));
    }

    @Test
    void touchLayoutsAreFixedForPortraitLandscapeAndProtectTheBottomSafeArea() throws IOException {
        String html = resource("static/game.html");
        String css = resource("static/css/game.css");
        int touchRules = css.indexOf("/* Persistent touch controls");
        assertTrue(touchRules >= 0, "Touch-specific layout rules must be present");
        String touchCss = css.substring(touchRules);

        assertTrue(html.contains("viewport-fit=cover"));
        assertTrue(touchCss.contains("@media (max-width: 1024px), (hover: none) and (pointer: coarse)"));
        assertTrue(touchCss.contains("position: fixed;"));
        assertTrue(touchCss.contains("bottom: 0;"));
        assertTrue(touchCss.contains("env(safe-area-inset-bottom, 0px)"));
        assertTrue(touchCss.contains("min-height: 48px;"));
        assertTrue(touchCss.contains("@media (max-width: 600px) and (orientation: portrait)"));
        assertTrue(touchCss.contains("(orientation: landscape) and (max-height: 820px)"));
        assertTrue(touchCss.contains("grid-template-columns: minmax(0, 1fr) minmax(240px, 38vw)"));
    }

    @Test
    void desktopControlsRemainInPlaceAndTouchDockIsHiddenByDefault() throws IOException {
        String html = resource("static/game.html");
        String css = resource("static/css/game.css");
        int touchRules = css.indexOf("/* Persistent touch controls");
        int defaultMobileDock = css.indexOf(".mobile-action-dock {");
        int desktopHand = css.indexOf(".my-hand-area {");

        assertTrue(html.contains("id=\"playSelectedCardButton\""));
        assertTrue(html.contains("class=\"action-buttons desktop-action-buttons\""));
        assertTrue(defaultMobileDock >= 0 && defaultMobileDock < touchRules
                        && css.substring(defaultMobileDock, touchRules).contains("display: none;"),
                "The touch dock must not alter the default desktop layout");
        assertTrue(desktopHand >= 0 && desktopHand < touchRules
                        && css.substring(desktopHand, touchRules).contains("position: relative;"),
                "The desktop hand must retain its original document layout");
    }

    private String resource(String path) throws IOException {
        return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
    }
}
