const test = require("node:test");
const assert = require("node:assert/strict");
const { shouldShowLobbyActivity } = require("./realtime-utils.js");

function activity(event, actorId, players) {
    return {
        type: "LOBBY_ACTIVITY",
        event,
        roomId: 10,
        roomCode: "ROOM-X",
        actor: { userId: actorId, username: `user-${actorId}` },
        roomState: {
            players: players.map((userId) => ({ userId, username: `user-${userId}` }))
        }
    };
}

test("A and C see B create Room X while B is excluded", () => {
    const created = activity("ROOM_CREATED", 2, [2]);

    assert.equal(shouldShowLobbyActivity(created, 1), true);
    assert.equal(shouldShowLobbyActivity(created, 3), true);
    assert.equal(shouldShowLobbyActivity(created, 2), false);
});

test("only A sees C join Room X once B and C are participants", () => {
    const joined = activity("PLAYER_JOINED", 3, [2, 3]);

    assert.equal(shouldShowLobbyActivity(joined, 1), true);
    assert.equal(shouldShowLobbyActivity(joined, 2), false);
    assert.equal(shouldShowLobbyActivity(joined, 3), false);
});
