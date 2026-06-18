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

    function mergeRealtimeBatch(currentBatch, nextPatch) {
        const base = currentBatch && typeof currentBatch === "object" ? currentBatch : {};
        const patch = nextPatch && typeof nextPatch === "object" ? nextPatch : {};
        const baseVersion = extractPatchVersion(base);
        const patchVersion = extractPatchVersion(patch);
        const preferPatchState = patchVersion === null || baseVersion === null || patchVersion >= baseVersion;
        const merged = {
            type: patch.type !== undefined ? patch.type : base.type,
            event: patch.event !== undefined ? patch.event : base.event,
            __channel: patch.__channel !== undefined ? patch.__channel : base.__channel,
            roomState: preferPatchState
                ? (patch.roomState !== undefined ? patch.roomState : base.roomState)
                : base.roomState,
            gameState: preferPatchState
                ? (patch.gameState !== undefined ? patch.gameState : base.gameState)
                : base.gameState,
            handCards: patch.handCards !== undefined ? patch.handCards : base.handCards,
            message: patch.message !== undefined ? patch.message : base.message,
            resync: Boolean(base.resync || patch.resync),
            version: patchVersion !== null
                ? patchVersion
                : (baseVersion !== null ? baseVersion : undefined)
        };
        return merged;
    }

    function resolveHandPatchDecision({ incomingVersion, currentVersion, incomingPatchId, lastPatchId }) {
        const nextVersion = toVersion(incomingVersion);
        const appliedVersion = toVersion(currentVersion);
        if (nextVersion === null) {
            return { apply: false, reason: "missing-version" };
        }
        if (incomingPatchId && lastPatchId && incomingPatchId === lastPatchId) {
            return { apply: false, reason: "duplicate" };
        }
        if (appliedVersion !== null && nextVersion < appliedVersion) {
            return { apply: false, reason: "stale" };
        }
        if (appliedVersion !== null && nextVersion === appliedVersion) {
            return { apply: false, reason: "duplicate" };
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
