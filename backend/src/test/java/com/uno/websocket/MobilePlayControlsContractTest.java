package com.uno.websocket;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MobilePlayControlsContractTest {

    @Test
    void mobileControlsReuseExistingPlayDrawAndPenaltyActions() throws IOException {
        String page = readResource("static/game.html");

        assertTrue(page.contains("class=\"mobile-play-controls\""));
        assertTrue(page.contains("'has-selection': selectedCard !== null"));
        assertTrue(page.contains("'has-color-actions': selectedCard !== null && needsColorPick"));
        assertTrue(page.contains("id=\"mobilePlaySelectedCardButton\""));
        assertTrue(page.contains("v-if=\"selectedCard !== null\""));
        assertTrue(page.contains(":disabled=\"!canPlaySelected || playingCard\""));
        assertTrue(page.contains("@click=\"playSelectedCard\""));

        assertTrue(page.contains("id=\"mobileDrawCardButton\""));
        assertTrue(page.contains("v-if=\"!showDrawPenaltyButton\""),
                "the disabled normal draw action must yield its slot to the required penalty action");
        assertTrue(page.contains(":disabled=\"!canDraw || drawingCard\""));
        assertTrue(page.contains("@click=\"drawCardAction\""));

        assertTrue(page.contains("id=\"mobileDrawPenaltyButton\""));
        assertTrue(page.contains("v-if=\"showDrawPenaltyButton\""));
        assertTrue(page.contains("@click=\"drawPenaltyAction()\""));
        assertTrue(page.contains(":class=\"{ selected: chosenColor === 'RED' }\""),
                "wild-card color selection must remain visible in the persistent controls");
    }

    @Test
    void responsiveLayoutKeepsTheHandAndControlsInsideTheMobileViewport() throws IOException {
        String page = readResource("static/game.html");
        String stylesheet = readResource("static/css/game.css");

        int desktopDefault = stylesheet.indexOf(".mobile-play-controls {\n    display: none;");
        int tabletBreakpoint = stylesheet.indexOf("@media (max-width: 1024px),");
        int tabletSafeAreaBreakpoint = stylesheet.indexOf(
                "@media (min-width: 481px) and (max-width: 1024px),");
        int landscapeBreakpoint = stylesheet.indexOf(
                "@media (max-width: 1024px) and (orientation: landscape) and (max-height: 500px)");

        assertTrue(desktopDefault >= 0, "persistent controls must not change the desktop layout");
        assertTrue(page.contains("width=device-width, initial-scale=1.0, viewport-fit=cover"),
                "iOS must expose safe-area insets to the responsive controls");
        assertTrue(tabletBreakpoint > desktopDefault, "mobile and tablet controls need a bounded breakpoint");
        assertTrue(stylesheet.indexOf(
                        "(hover: none) and (pointer: coarse) and (max-width: 1366px)", tabletBreakpoint)
                        > tabletBreakpoint,
                "landscape iPads wider than 1024px must still use the bounded game layout");
        assertTrue(stylesheet.indexOf("height: 100dvh;", tabletBreakpoint) > tabletBreakpoint,
                "the mobile game surface must follow the dynamic viewport height");
        assertTrue(stylesheet.indexOf(
                        "grid-template-rows: auto auto minmax(0, 1fr) auto auto;", tabletBreakpoint)
                        > tabletBreakpoint,
                "mobile status, table, hand, and actions must share one bounded viewport grid");
        assertTrue(stylesheet.indexOf("html,\n    body {\n        min-height: 0;", tabletBreakpoint)
                        > tabletBreakpoint,
                "the responsive page must remove the global 100vh minimum that can exceed iPad's visual viewport");
        assertTrue(stylesheet.indexOf("overscroll-behavior: none;", tabletBreakpoint)
                        > tabletBreakpoint,
                "the responsive game surface must not expose document scrolling");
        assertTrue(stylesheet.indexOf("#app[v-cloak] {\n        display: none;", tabletBreakpoint)
                        > tabletBreakpoint,
                "the viewport grid must preserve Vue's no-flash cloak");
        assertTrue(stylesheet.indexOf("position: relative;", tabletBreakpoint) > tabletBreakpoint,
                "controls must occupy their own persistent grid row instead of covering the hand");
        assertTrue(tabletSafeAreaBreakpoint > tabletBreakpoint,
                "tablet safe-area handling must be isolated from the already verified narrow mobile layout");
        assertTrue(stylesheet.indexOf(
                        "padding-bottom: calc(24px + env(safe-area-inset-bottom, 0px));",
                        tabletSafeAreaBreakpoint)
                        > tabletSafeAreaBreakpoint,
                "tablet controls must retain a gesture buffer inside the bounded viewport");
        assertTrue(stylesheet.indexOf("margin-bottom: 0;", tabletSafeAreaBreakpoint)
                        > tabletSafeAreaBreakpoint,
                "the tablet safe area must not increase the grid's external height");
        assertTrue(stylesheet.indexOf(".hand-action-buttons {\n        display: none;", tabletBreakpoint)
                        > tabletBreakpoint,
                "the original below-hand action row must not duplicate mobile controls");
        assertTrue(landscapeBreakpoint > tabletBreakpoint,
                "short landscape viewports need a compact control layout");
        assertTrue(stylesheet.contains(".hand-cards .card.selected"),
                "the existing selected-card treatment must remain available");
        assertTrue(stylesheet.contains(".hand-cards .card.selected {\n    z-index: 2;"),
                "the selected card must remain visually above adjacent cards");
    }

    @Test
    void narrowMobileCardsKeepDistinctTouchTargetsAboveTheControlDock() throws IOException {
        String stylesheet = readResource("static/css/game.css");

        int narrowBreakpoint = stylesheet.indexOf("@media (max-width: 480px)");
        int landscapeBreakpoint = stylesheet.indexOf(
                "@media (max-width: 1024px) and (orientation: landscape) and (max-height: 500px)");
        String narrowRules = stylesheet.substring(narrowBreakpoint, landscapeBreakpoint);

        assertTrue(narrowBreakpoint >= 0, "narrow mobile layouts need explicit hand sizing");
        assertTrue(narrowRules.contains("gap: 8px;"),
                "cards need enough separation for distinct touch targets");
        assertTrue(narrowRules.contains("min-width: 64px;"),
                "cards must scroll horizontally instead of compressing below a usable width");
        assertTrue(narrowRules.contains("height: 96px;"));
        assertTrue(narrowRules.contains("scroll-snap-type: x proximity;"));
        assertTrue(narrowRules.contains(
                        "margin-bottom: calc(16px + env(safe-area-inset-bottom, 0px));"),
                "narrow controls must remain above the device gesture area");
        assertTrue(narrowRules.contains("grid-column: span 3;")
                        || stylesheet.contains("grid-column: span 3;"),
                "wild-card controls must fit alongside Play and Draw without forcing page scrolling");
    }

    private String readResource(String path) throws IOException {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(path)) {
            assertNotNull(input, "resource must exist: " + path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        }
    }
}
