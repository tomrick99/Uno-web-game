const test = require("node:test");
const assert = require("node:assert/strict");

const { restoreReconnectSnapshot } = require("../js/pages/realtime-utils.js");

test("reconnect automatically applies the restored full snapshot", async () => {
    const snapshot = {
        type: "FULL_SNAPSHOT",
        version: 42,
        gameState: {
            gameId: 20,
            roomId: 10,
            currentTurn: 2,
            disconnectGraceActive: false,
            players: [
                { userId: 1, seatIndex: 0 },
                { userId: 2, seatIndex: 1 }
            ]
        },
        handCards: [
            { color: "GREEN", type: "NUMBER", value: 7 }
        ]
    };
    const requests = [];
    const httpClient = {
        post: async (url) => {
            requests.push(url);
            return { data: { code: 200, data: snapshot } };
        }
    };
    let appliedSnapshot = null;

    const restored = await restoreReconnectSnapshot({
        httpClient,
        apiBase: "/api",
        roomId: 10,
        applySnapshot: (value) => {
            appliedSnapshot = value;
        }
    });

    assert.deepEqual(requests, ["/api/game/room/10/reconnect"]);
    assert.strictEqual(restored, snapshot);
    assert.strictEqual(appliedSnapshot, snapshot);
    assert.equal(appliedSnapshot.gameState.disconnectGraceActive, false);
    assert.deepEqual(appliedSnapshot.handCards, snapshot.handCards);
    assert.equal(appliedSnapshot.gameState.currentTurn, 2);
});

test("failed reconnect response is not applied", async () => {
    let applied = false;
    const httpClient = {
        post: async () => ({ data: { code: 400, message: "Player is not in this game" } })
    };

    await assert.rejects(
        restoreReconnectSnapshot({
            httpClient,
            apiBase: "/api",
            roomId: 10,
            applySnapshot: () => {
                applied = true;
            }
        }),
        /Player is not in this game/
    );
    assert.equal(applied, false);
});
