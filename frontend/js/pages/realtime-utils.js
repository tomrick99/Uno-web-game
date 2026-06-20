(function (global, factory) {
    const api = factory();
    if (typeof module !== "undefined" && module.exports) {
        module.exports = api;
    }
    global.UnoRealtimeUtils = api;
})(typeof window !== "undefined" ? window : globalThis, function () {
    const DEFAULT_FALLBACK_THRESHOLD_MS = 10000;

    function shouldEnableFallbackPolling(disconnectedAt, now, thresholdMs = DEFAULT_FALLBACK_THRESHOLD_MS) {
        if (!disconnectedAt || !now) {
            return false;
        }
        return now - disconnectedAt >= thresholdMs;
    }

    function resolveConnectionMode({ connected, fallbackActive }) {
        if (connected) {
            return "connected";
        }
        return fallbackActive ? "fallback" : "reconnecting";
    }

    function toVersion(value) {
        if (value === null || value === undefined || value === "") {
            return null;
        }
        const parsed = Number(value);
        return Number.isFinite(parsed) ? parsed : null;
    }

    function extractPatchVersion(patch) {
        if (!patch || typeof patch !== "object") {
            return null;
        }
        return toVersion(
            patch.version
            ?? patch.gameState?.version
            ?? patch.roomState?.version
        );
    }

    function extractLayerVersion(patch, layer) {
        if (!patch || typeof patch !== "object") {
            return null;
        }
        return toVersion(
            patch[`${layer}Version`]
            ?? patch[`${layer}State`]?.version
            ?? patch.version
        );
    }

    function preferIncomingLayer(base, patch, layer, hasIncomingLayer) {
        if (!hasIncomingLayer) {
            return false;
        }
        const hasBaseLayer = layer === "hand"
            ? base.handState !== undefined || base.handCards !== undefined
            : base[`${layer}State`] !== undefined;
        if (!hasBaseLayer) {
            return true;
        }
        const baseVersion = extractLayerVersion(base, layer);
        const patchVersion = extractLayerVersion(patch, layer);
        return baseVersion === null || patchVersion === null || patchVersion >= baseVersion;
    }

    function mergeRealtimeBatch(currentBatch, nextPatch) {
        const base = currentBatch && typeof currentBatch === "object" ? currentBatch : {};
        const patch = nextPatch && typeof nextPatch === "object" ? nextPatch : {};
        const baseVersion = extractPatchVersion(base);
        const patchVersion = extractPatchVersion(patch);
        const preferRoom = preferIncomingLayer(base, patch, "room", patch.roomState !== undefined);
        const preferGame = preferIncomingLayer(base, patch, "game", patch.gameState !== undefined);
        const hasIncomingHand = patch.handState !== undefined || patch.handCards !== undefined;
        const preferHand = preferIncomingLayer(base, patch, "hand", hasIncomingHand);
        const merged = {
            type: patch.type !== undefined ? patch.type : base.type,
            event: patch.event !== undefined ? patch.event : base.event,
            __channel: patch.__channel !== undefined ? patch.__channel : base.__channel,
            roomState: preferRoom
                ? (patch.roomState !== undefined ? patch.roomState : base.roomState)
                : base.roomState,
            gameState: preferGame
                ? (patch.gameState !== undefined ? patch.gameState : base.gameState)
                : base.gameState,
            handState: preferHand
                ? (patch.handState !== undefined ? patch.handState : base.handState)
                : base.handState,
            handCards: preferHand
                ? (patch.handCards !== undefined ? patch.handCards : base.handCards)
                : base.handCards,
            message: patch.message !== undefined ? patch.message : base.message,
            resync: Boolean(base.resync || patch.resync),
            version: patchVersion !== null && (baseVersion === null || patchVersion >= baseVersion)
                ? patchVersion
                : (baseVersion !== null ? baseVersion : undefined),
            roomVersion: preferRoom ? patch.roomVersion : base.roomVersion,
            gameVersion: preferGame ? patch.gameVersion : base.gameVersion,
            handVersion: preferHand ? patch.handVersion : base.handVersion
        };
        return merged;
    }

    function resolveHandPatchDecision({ incomingVersion, currentVersion, incomingPatchId, lastPatchId }) {
        const nextVersion = toVersion(incomingVersion);
        const appliedVersion = toVersion(currentVersion);
        if (incomingPatchId && lastPatchId && incomingPatchId === lastPatchId) {
            return { apply: false, reason: "duplicate" };
        }
        if (nextVersion === null) {
            return { apply: true, reason: "missing-version" };
        }
        if (appliedVersion !== null && nextVersion < appliedVersion) {
            return { apply: false, reason: "stale" };
        }
        return { apply: true, reason: "apply" };
    }

    return {
        DEFAULT_FALLBACK_THRESHOLD_MS,
        shouldEnableFallbackPolling,
        resolveConnectionMode,
        mergeRealtimeBatch,
        resolveHandPatchDecision
    };
});
