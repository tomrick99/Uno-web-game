const { createApp, ref, computed, onMounted, onUnmounted } = Vue;

const apiBase = "/api";

createApp({
    setup() {
        const roomId = ref(new URLSearchParams(window.location.search).get("roomId"));
        const roomCode = ref("");
        const roomStatus = ref("WAITING");
        const userId = ref(localStorage.getItem("userId"));
        const username = ref(localStorage.getItem("username") || "");

        const gameId = ref(null);
        const gameStatus = ref("WAITING");
        const currentTurn = ref(null);
        const clockwise = ref(true);
        const direction = ref(1);
        const currentColor = ref("RED");
        const pendingDrawCount = ref(0);
        const pendingDrawType = ref("NONE");
        const lastPenaltyPlayerId = ref(null);
        const rematchReadyPlayerIds = ref([]);
        const drawPileSize = ref(0);
        const topCard = ref(null);
        const handCards = ref([]);
        const opponents = ref([]);
        const currentPlayerName = ref("等待玩家加入");
        const selectedCard = ref(null);
        const needsColorPick = ref(false);
        const chosenColor = ref("");
        const gameLog = ref([]);
        const logExpanded = ref(false);
        const gameResult = ref(null);
        const toastMsg = ref("");
        const wsConnected = ref(false);
        const leavingRoom = ref(false);
        const restartingGame = ref(false);
        const drawingPenalty = ref(false);
        const hasLoadedHand = ref(false);
        const autoPenaltyInProgress = ref(false);
        const lastAutoPenaltyKey = ref("");
        const lastPendingDrawLogKey = ref("");
        const currentRoomState = ref(null);
        const currentGameState = ref(null);

        const stompClient = ref(null);
        const roomSubscription = ref(null);
        const gameSubscription = ref(null);
        const handSubscription = ref(null);
        const subscribedGameId = ref(null);

        let reconnectTimer = null;
        let pollTimer = null;
        let delegatedButtonHandler = null;
        let beforeUnloadHandler = null;

        const colorMap = {
            RED: "#E74C3C",
            BLUE: "#3498DB",
            GREEN: "#2ECC71",
            YELLOW: "#F39C12"
        };

        const isWildType = (type) => type === "WILD" || type === "WILD_DRAW_FOUR";
        const isNumberType = (type) => type === "NUMBER";

        const isMyTurn = computed(() =>
            gameStatus.value === "PLAYING" && String(currentTurn.value) === String(userId.value)
        );

        const isPendingDrawStack = computed(() =>
            Number(pendingDrawCount.value) > 0 && pendingDrawType.value !== "NONE"
        );

        const canDraw = computed(() =>
            gameStatus.value === "PLAYING" && isMyTurn.value && !isPendingDrawStack.value
        );

        const getGameStateStatus = (state) =>
            String(state?.status || state?.phase || state?.gameStatus || "").toUpperCase();

        const getRoomStateStatus = (stateOrEvent) =>
            String(stateOrEvent?.status || stateOrEvent?.roomStatus || "").toUpperCase();

        const getEventType = (payload) =>
            String(payload?.type || payload?.event || "").toUpperCase();

        const isGameFinished = (state) => getGameStateStatus(state) === "FINISHED";

        const isRoomClosed = (stateOrEvent) =>
            getRoomStateStatus(stateOrEvent) === "CLOSED"
            || getEventType(stateOrEvent) === "ROOM_CLOSED"
            || getEventType(stateOrEvent) === "PLAYER_LEFT"
            || getEventType(stateOrEvent) === "ROOM_DELETED";

        const directionLabel = computed(() => (direction.value === 1 ? "顺时针" : "逆时针"));

        const currentColorLabel = computed(() => {
            if (currentColor.value === "RED") return "红色";
            if (currentColor.value === "YELLOW") return "黄色";
            if (currentColor.value === "GREEN") return "绿色";
            if (currentColor.value === "BLUE") return "蓝色";
            return currentColor.value;
        });

        const connectionLabel = computed(() =>
            wsConnected.value ? "实时同步已连接" : "实时同步已断开，使用轮询兜底"
        );

        const getCardDisplay = (type, value) => {
            if (type === "NUMBER") return String(value);
            if (type === "SKIP") return "SKIP";
            if (type === "REVERSE") return "REV";
            if (type === "DRAW_TWO") return "+2";
            if (type === "WILD") return "WILD";
            if (type === "WILD_DRAW_FOUR") return "+4";
            return "?";
        };

        const formatCard = (card) => {
            if (!card) {
                return "null";
            }
            return `${card.color}_${card.type}_${card.value}`;
        };

        const colorRank = (color) => {
            if (color === "RED") return 0;
            if (color === "YELLOW") return 1;
            if (color === "GREEN") return 2;
            if (color === "BLUE") return 3;
            return -1;
        };

        const typeRank = (type) => {
            if (type === "WILD") return 0;
            if (type === "WILD_DRAW_FOUR") return 1;
            if (type === "DRAW_TWO") return 2;
            if (type === "SKIP") return 3;
            if (type === "REVERSE") return 4;
            return 10;
        };

        const sortHandCards = (cards) => {
            return [...(cards || [])].sort((left, right) => {
                const leftGroup = isWildType(left.type) ? 0 : 1;
                const rightGroup = isWildType(right.type) ? 0 : 1;
                if (leftGroup !== rightGroup) {
                    return leftGroup - rightGroup;
                }

                const colorDiff = colorRank(left.color) - colorRank(right.color);
                if (colorDiff !== 0) {
                    return colorDiff;
                }

                const typeDiff = typeRank(left.type) - typeRank(right.type);
                if (typeDiff !== 0) {
                    return typeDiff;
                }

                return Number(left.value) - Number(right.value);
            });
        };

        const getPendingReason = (card) => {
            if (pendingDrawType.value === "WILD_DRAW_FOUR_CHAIN") {
                if (card.type === "WILD_DRAW_FOUR") {
                    return { canPlay: true, reason: "pending +4 chain allows +4" };
                }
                return { canPlay: false, reason: "pending +4 chain only allows +4" };
            }

            if (pendingDrawType.value === "DRAW_TWO_CHAIN" || pendingDrawType.value === "DRAW_STACK") {
                if (card.type === "DRAW_TWO") {
                    return { canPlay: true, reason: "pending +2 chain allows +2" };
                }
                if (card.type === "WILD_DRAW_FOUR") {
                    return { canPlay: true, reason: "pending +2 chain allows +4" };
                }
                return { canPlay: false, reason: "pending +2 chain only allows +2 or +4" };
            }

            return { canPlay: false, reason: "unknown pending draw state" };
        };

        const hasStackablePenaltyCard = (cards, nextPendingDrawType) => {
            if (!Array.isArray(cards) || !cards.length) {
                return false;
            }

            if (nextPendingDrawType === "WILD_DRAW_FOUR_CHAIN") {
                return cards.some((card) => card?.type === "WILD_DRAW_FOUR");
            }

            if (nextPendingDrawType === "DRAW_TWO_CHAIN" || nextPendingDrawType === "DRAW_STACK") {
                return cards.some((card) => card?.type === "DRAW_TWO" || card?.type === "WILD_DRAW_FOUR");
            }

            return false;
        };

        const getPendingDrawStateKey = () => {
            if (!isPendingDrawStack.value) {
                return "";
            }
            return [
                gameId.value ?? "no-game",
                currentTurn.value ?? "no-turn",
                pendingDrawType.value || "NONE",
                Number(pendingDrawCount.value || 0),
                formatCard(topCard.value)
            ].join("|");
        };

        const evaluatePlayability = (card, discardTopCard, activeColor) => {
            if (!card || !card.type || !card.color) {
                return { canPlay: false, reason: "invalid card" };
            }
            if (gameStatus.value !== "PLAYING") {
                return { canPlay: false, reason: "game not playing" };
            }
            if (!isMyTurn.value) {
                return { canPlay: false, reason: "not current player" };
            }
            if (isPendingDrawStack.value) {
                return getPendingReason(card);
            }
            if (card.type === "WILD") {
                return { canPlay: true, reason: "wild card" };
            }
            if (card.type === "WILD_DRAW_FOUR") {
                return { canPlay: true, reason: "wild draw four" };
            }
            if (card.color === activeColor) {
                return { canPlay: true, reason: "color match" };
            }
            if (!discardTopCard) {
                return { canPlay: true, reason: "no top card" };
            }
            if (isNumberType(card.type) && isNumberType(discardTopCard.type) && Number(card.value) === Number(discardTopCard.value)) {
                return { canPlay: true, reason: "number match" };
            }
            if (!isNumberType(card.type) && !isNumberType(discardTopCard.type) && card.type === discardTopCard.type) {
                return { canPlay: true, reason: "type match" };
            }
            return { canPlay: false, reason: "color/number/type mismatch" };
        };

        const canPlayCard = (card, discardTopCard, activeColor) =>
            evaluatePlayability(card, discardTopCard, activeColor).canPlay;

        const getCardStateClass = (card) => {
            const result = evaluatePlayability(card, topCard.value, currentColor.value);
            if (gameStatus.value !== "PLAYING" || !isMyTurn.value) {
                return "disabled";
            }
            return result.canPlay ? "playable" : "unplayable";
        };

        const getCardHint = (card) => {
            const result = evaluatePlayability(card, topCard.value, currentColor.value);
            if (gameStatus.value !== "PLAYING") {
                return "游戏尚未开始或已经结束";
            }
            if (!isMyTurn.value) {
                return "不是你的回合";
            }
            if (result.canPlay) {
                return "可以出牌";
            }
            return result.reason;
        };

        const decorateCard = (card) => ({
            ...card,
            display: getCardDisplay(card.type, card.value),
            stateClass: getCardStateClass(card),
            hint: getCardHint(card)
        });

        const getPenaltyNoticeText = () => {
            if (!isPendingDrawStack.value || !isMyTurn.value) {
                return "";
            }
            if (!hasLoadedHand.value) {
                return "";
            }
            if (!hasPlayablePenaltyResponse.value) {
                return `你没有可叠加的牌，自动抽取 ${pendingDrawCount.value} 张`;
            }
            if (pendingDrawType.value === "WILD_DRAW_FOUR_CHAIN") {
                return `你需要响应累计惩罚：抽 ${pendingDrawCount.value} 张，或继续打出 +4`;
            }
            return `你需要响应累计惩罚：抽 ${pendingDrawCount.value} 张，或继续打出 +2 / +4`;
        };

        const showPenaltyNotice = computed(() => Boolean(getPenaltyNoticeText()));
        const penaltyNoticeText = computed(() => getPenaltyNoticeText());
        const showDrawPenaltyButton = computed(() => showPenaltyNotice.value && hasPlayablePenaltyResponse.value);
        const drawPenaltyButtonText = computed(() =>
            drawingPenalty.value ? "抽牌中..." : `接受惩罚，抽 ${pendingDrawCount.value} 张`
        );

        const hasPlayablePenaltyResponse = computed(() =>
            hasStackablePenaltyCard(handCards.value, pendingDrawType.value)
        );

        const canPlaySelected = computed(() => {
            if (selectedCard.value === null) {
                return false;
            }
            const card = handCards.value[selectedCard.value];
            if (!card) {
                return false;
            }
            const result = evaluatePlayability(card, topCard.value, currentColor.value);
            return result.canPlay && (!needsColorPick.value || Boolean(chosenColor.value));
        });

        const turnLabel = computed(() => {
            if (gameStatus.value === "FINISHED") {
                return "本局已结束";
            }
            if (gameStatus.value !== "PLAYING") {
                return `等待玩家加入 (${opponents.value.length + 1}/2)`;
            }
            if (showPenaltyNotice.value) {
                return hasPlayablePenaltyResponse.value
                    ? `轮到你响应惩罚链 (${pendingDrawCount.value})`
                    : `你需要抽 ${pendingDrawCount.value} 张牌`;
            }
            return isMyTurn.value ? "轮到你了" : `轮到 ${currentPlayerName.value}`;
        });

        const showToast = (message) => {
            if (!message) {
                return;
            }
            toastMsg.value = message;
            window.setTimeout(() => {
                if (toastMsg.value === message) {
                    toastMsg.value = "";
                }
            }, 3000);
        };

        const addLog = (message) => {
            if (!message) {
                return;
            }
            gameLog.value.unshift(message);
            if (gameLog.value.length > 50) {
                gameLog.value.pop();
            }
        };

        const clearCardSelection = () => {
            selectedCard.value = null;
            needsColorPick.value = false;
            chosenColor.value = "";
        };

        const refreshHandPlayability = () => {
            handCards.value = sortHandCards(handCards.value).map(decorateCard);
            if (selectedCard.value === null) {
                return;
            }

            const selected = handCards.value[selectedCard.value];
            if (!selected || selected.stateClass !== "playable") {
                clearCardSelection();
                return;
            }

            needsColorPick.value = isWildType(selected.type);
            if (!needsColorPick.value) {
                chosenColor.value = "";
            }
        };

        const syncPendingDrawUiState = () => {
            if (!isPendingDrawStack.value || Number(pendingDrawCount.value) <= 0 || pendingDrawType.value === "NONE") {
                autoPenaltyInProgress.value = false;
                lastAutoPenaltyKey.value = "";
                lastPendingDrawLogKey.value = "";
                return;
            }

            if (!hasLoadedHand.value || gameStatus.value === "FINISHED" || !isMyTurn.value) {
                return;
            }

            const pendingKey = getPendingDrawStateKey();
            if (hasPlayablePenaltyResponse.value) {
                const logKey = `${pendingKey}|stackable`;
                if (lastPendingDrawLogKey.value !== logKey) {
                    console.info(
                        `[UNO-FE] pending draw: stackable card exists, waiting for player choice drawCount=${pendingDrawCount.value}`
                    );
                    lastPendingDrawLogKey.value = logKey;
                }
                return;
            }

            if (autoPenaltyInProgress.value || lastAutoPenaltyKey.value === pendingKey) {
                console.info(`[UNO-FE] pending draw: auto penalty already triggered key=${pendingKey}`);
                return;
            }

            console.info(`[UNO-FE] pending draw: no stackable card, auto drawPenalty drawCount=${pendingDrawCount.value}`);
            triggerAutoPenaltyDraw(pendingKey);
        };

        const applyHandCards = (cards) => {
            hasLoadedHand.value = true;
            handCards.value = sortHandCards(cards).map(decorateCard);
            refreshHandPlayability();
            syncPendingDrawUiState();
        };

        const buildGameResult = (gameState) => {
            const players = gameState.players || [];
            const winner = players.find((player) => String(player.userId) === String(gameState.winnerId));
            const readyIds = Array.isArray(gameState.rematchReadyPlayerIds) ? gameState.rematchReadyPlayerIds : [];
            const isReady = readyIds.some((readyUserId) => String(readyUserId) === String(userId.value));
            const otherReady = readyIds.some((readyUserId) => String(readyUserId) !== String(userId.value));

            let statusText = "双方都点击“再来一局”后才会开始新一局。";
            if (isReady && otherReady) {
                statusText = "双方已同意，正在重新开始...";
            } else if (isReady) {
                statusText = "我已准备再来一局，等待对方同意...";
            } else if (otherReady) {
                statusText = "对方已准备，再来一局需要双方同意。";
            }

            return {
                win: String(gameState.winnerId) === String(userId.value),
                title: winner ? `${winner.username} wins!` : "游戏结束",
                statusText,
                rematchButtonDisabled: restartingGame.value || isReady,
                rematchButtonText: isReady ? "已准备，等待对方" : (restartingGame.value ? "提交中..." : "再来一局")
            };
        };

        const handleGameFinished = (gameState, source = "unknown") => {
            if (!gameState) {
                return;
            }
            console.info(
                `[UNO-FE] handleGameFinished winner=${gameState.winnerId ?? "none"} source=${source}`
            );
            clearCardSelection();
            gameStatus.value = "FINISHED";
            currentTurn.value = null;
            pendingDrawCount.value = 0;
            pendingDrawType.value = "NONE";
            autoPenaltyInProgress.value = false;
            gameResult.value = buildGameResult(gameState);
        };

        const resetLocalState = () => {
            roomId.value = null;
            roomCode.value = "";
            roomStatus.value = "WAITING";
            gameId.value = null;
            gameStatus.value = "WAITING";
            currentTurn.value = null;
            clockwise.value = true;
            direction.value = 1;
            currentColor.value = "RED";
            pendingDrawCount.value = 0;
            pendingDrawType.value = "NONE";
            lastPenaltyPlayerId.value = null;
            rematchReadyPlayerIds.value = [];
            drawPileSize.value = 0;
            topCard.value = null;
            handCards.value = [];
            opponents.value = [];
            currentPlayerName.value = "等待玩家加入";
            clearCardSelection();
            gameResult.value = null;
            toastMsg.value = "";
            leavingRoom.value = false;
            restartingGame.value = false;
            drawingPenalty.value = false;
            hasLoadedHand.value = false;
            autoPenaltyInProgress.value = false;
            lastAutoPenaltyKey.value = "";
            lastPendingDrawLogKey.value = "";
            currentRoomState.value = null;
            currentGameState.value = null;
        };

        const storeLobbyNotice = (message) => {
            if (message) {
                sessionStorage.setItem("lobbyNotice", message);
            }
        };

        const cleanupRealtime = () => {
            if (pollTimer) {
                clearInterval(pollTimer);
                pollTimer = null;
            }
            if (reconnectTimer) {
                clearTimeout(reconnectTimer);
                reconnectTimer = null;
            }
            if (roomSubscription.value) {
                roomSubscription.value.unsubscribe();
                roomSubscription.value = null;
            }
            if (gameSubscription.value) {
                gameSubscription.value.unsubscribe();
                gameSubscription.value = null;
            }
            if (handSubscription.value) {
                handSubscription.value.unsubscribe();
                handSubscription.value = null;
            }
            subscribedGameId.value = null;
            wsConnected.value = false;

            if (stompClient.value) {
                try {
                    if (typeof stompClient.value.deactivate === "function") {
                        stompClient.value.deactivate();
                    } else if (typeof stompClient.value.disconnect === "function") {
                        stompClient.value.disconnect(() => {});
                    }
                } catch (error) {
                    console.error(error);
                }
                stompClient.value = null;
            }
        };

        const renderRoom = (roomState) => {
            if (!roomState) {
                return;
            }
            currentRoomState.value = roomState;
            roomId.value = roomState.roomId || roomId.value;
            roomCode.value = roomState.roomCode || roomCode.value;
            roomStatus.value = roomState.status || roomStatus.value;

            if (roomState.gameId) {
                gameId.value = roomState.gameId;
                subscribeGameChannels(roomState.gameId);
            }

            const players = roomState.players || [];
            opponents.value = players
                .filter((player) => String(player.userId) !== String(userId.value))
                .map((player) => ({
                    userId: player.userId,
                    username: player.username,
                    seatIndex: player.seatIndex,
                    handCount: player.handCount ?? 0,
                    saidUno: Boolean(player.saidUno)
                }));
        };

        const renderGame = (gameState) => {
            if (!gameState) {
                return;
            }

            currentGameState.value = gameState;
            roomId.value = gameState.roomId || roomId.value;
            roomCode.value = gameState.roomCode || roomCode.value;
            roomStatus.value = gameState.roomStatus || roomStatus.value;
            gameId.value = gameState.gameId;
            gameStatus.value = getGameStateStatus(gameState) || "WAITING";
            currentTurn.value = gameState.currentTurn;
            clockwise.value = gameState.clockwise !== false;
            direction.value = Number(gameState.direction || (clockwise.value ? 1 : -1));
            currentColor.value = gameState.currentColor || "RED";
            pendingDrawCount.value = Number(gameState.pendingDrawCount || 0);
            pendingDrawType.value = gameState.pendingDrawType || "NONE";
            lastPenaltyPlayerId.value = gameState.lastPenaltyPlayerId ?? null;
            rematchReadyPlayerIds.value = Array.isArray(gameState.rematchReadyPlayerIds)
                ? gameState.rematchReadyPlayerIds
                : [];
            drawPileSize.value = Number(gameState.drawPileSize || 0);

            topCard.value = gameState.topCard
                ? {
                    color: gameState.topCard.color,
                    type: gameState.topCard.type,
                    value: gameState.topCard.value,
                    display: getCardDisplay(gameState.topCard.type, gameState.topCard.value)
                }
                : null;

            const players = gameState.players || [];
            opponents.value = players
                .filter((player) => String(player.userId) !== String(userId.value))
                .map((player) => ({
                    userId: player.userId,
                    username: player.username,
                    seatIndex: player.seatIndex,
                    handCount: player.handCount ?? 0,
                    saidUno: Boolean(player.saidUno)
                }));

            const turnPlayer = players.find((player) => String(player.userId) === String(gameState.currentTurn));
            currentPlayerName.value = turnPlayer?.username || "等待玩家加入";

            if (isGameFinished(gameState)) {
                handleGameFinished(gameState, "renderGame");
            } else {
                gameResult.value = null;
                restartingGame.value = false;
            }

            subscribeGameChannels(gameState.gameId);
            refreshHandPlayability();
            syncPendingDrawUiState();
        };

        const renderSnapshot = (snapshot) => {
            if (!snapshot) {
                return;
            }
            if (snapshot.roomState) {
                renderRoom(snapshot.roomState);
            }
            if (snapshot.gameState) {
                renderGame(snapshot.gameState);
            }
            if (Array.isArray(snapshot.handCards)) {
                applyHandCards(snapshot.handCards);
            }
        };

        const handleRoomDeleted = (payload) => {
            const message = payload?.message || "房间已关闭，请返回大厅";
            console.info(
                `[UNO-FE] opponent left, returning to lobby notifyServer=false reason=${getEventType(payload) || "ROOM_DELETED"}`
            );
            returnToLobby({
                force: true,
                notifyServer: false,
                notice: payload?.message || "房间已关闭，请返回大厅",
                skipConfirm: true
            });
            return;
            /*
            {
            const message = payload?.message || "房间已关闭";
            storeLobbyNotice(message);
            cleanupRealtime();
            resetLocalState();
            window.location.replace("lobby.html");
            }
            */
        };

        const handleMissingRoomOrGame = (error, fallbackMessage) => {
            const message = error?.response?.data?.message || fallbackMessage;
            if ([400, 404].includes(error?.response?.status)) {
                storeLobbyNotice(message || "当前房间已不存在");
                cleanupRealtime();
                resetLocalState();
                window.location.replace("lobby.html");
                return true;
            }
            return false;
        };

        const handleRoomUnavailable = (message, reason = "PLAYER_LEFT") => {
            const notice = message || "对方已离开房间，请返回大厅";
            console.info(`[UNO-FE] opponent left, returning to lobby notifyServer=false reason=${reason}`);
            returnToLobby({ force: true, notifyServer: false, notice, skipConfirm: true });
        };

        const syncHand = async () => {
            if (!roomId.value) {
                return;
            }
            try {
                const response = await axios.get(`${apiBase}/game/room/${roomId.value}/hand`);
                if (response.data.code === 200) {
                    applyHandCards(response.data.data);
                }
            } catch (error) {
                if (!handleMissingRoomOrGame(error, "房间已不可用")) {
                    console.error("同步手牌失败", error);
                }
            }
        };

        const refreshFromServer = async () => {
            if (!roomId.value) {
                return;
            }
            try {
                const response = await axios.get(`${apiBase}/game/room/${roomId.value}/state`);
                if (response.data.code === 200) {
                    renderGame(response.data.data);
                    await syncHand();
                }
            } catch (error) {
                if (!handleMissingRoomOrGame(error, "房间已不存在")) {
                    console.error("刷新游戏状态失败", error);
                }
            }
        };

        const subscribeRoomTopic = () => {
            if (!stompClient.value || !wsConnected.value || roomSubscription.value || !roomId.value) {
                return;
            }

            roomSubscription.value = stompClient.value.subscribe(`/topic/rooms/${roomId.value}`, (message) => {
                const payload = JSON.parse(message.body);
                console.info(
                    `[UNO-WS] room event type=${getEventType(payload) || "ROOM_STATE"} status=${getRoomStateStatus(payload.roomState || payload) || "none"}`
                );
                if (payload.type === "ROOM_DELETED") {
                    handleRoomDeleted(payload);
                    return;
                }
                if (payload.roomState) {
                    console.info(
                        `[UNO-WS] room update roomId=${payload.roomState.roomId} players=${payload.roomState.playerCount ?? (payload.roomState.players || []).length}`
                    );
                    renderRoom(payload.roomState);
                    if (payload.event === "PLAYER_LEFT" || (isRoomClosed(payload.roomState) && payload.event !== "GAME_FINISHED")) {
                        handleRoomUnavailable(payload.message, payload.event || payload.type || "PLAYER_LEFT");
                        return;
                    }
                    if (payload.roomState.gameId) {
                        refreshFromServer();
                    }
                }
                if (payload.message) {
                    addLog(payload.message);
                }
            });
        };

        const subscribeGameChannels = (nextGameId) => {
            if (!nextGameId || !stompClient.value || !wsConnected.value) {
                return;
            }
            if (
                String(subscribedGameId.value) === String(nextGameId)
                && gameSubscription.value
                && handSubscription.value
            ) {
                return;
            }

            if (gameSubscription.value) {
                gameSubscription.value.unsubscribe();
                gameSubscription.value = null;
            }
            if (handSubscription.value) {
                handSubscription.value.unsubscribe();
                handSubscription.value = null;
            }

            subscribedGameId.value = String(nextGameId);

            gameSubscription.value = stompClient.value.subscribe(`/topic/games/${nextGameId}`, (message) => {
                const payload = JSON.parse(message.body);
                if (payload.type === "ROOM_DELETED") {
                    handleRoomDeleted(payload);
                    return;
                }
                if (payload.gameState) {
                    console.info(
                        `[UNO-WS] game update status=${getGameStateStatus(payload.gameState) || "unknown"} phase=${String(payload.gameState?.phase || "none").toUpperCase()} winner=${payload.gameState.winnerId ?? payload.winnerId ?? "none"}`
                    );
                    renderGame(payload.gameState);
                }
                if (payload.message) {
                    addLog(payload.message);
                }
            });

            handSubscription.value = stompClient.value.subscribe(
                `/topic/games/${nextGameId}/hands/${userId.value}`,
                (message) => {
                    const payload = JSON.parse(message.body);
                    if (Array.isArray(payload.handCards)) {
                        applyHandCards(payload.handCards);
                    }
                }
            );
        };

        const connectWebSocket = () => {
            const stompApi = window.Stomp || window.StompJs?.Stomp;
            if (!stompApi) {
                console.error("STOMP client not loaded");
                return;
            }

            const socket = new SockJS("/api/ws");
            stompClient.value = stompApi.over(socket);
            stompClient.value.debug = () => {};

            stompClient.value.connect(
                {},
                () => {
                    wsConnected.value = true;
                    subscribeRoomTopic();
                    if (gameId.value) {
                        subscribeGameChannels(gameId.value);
                    }
                    addLog("实时连接已建立");
                },
                () => {
                    wsConnected.value = false;
                    roomSubscription.value = null;
                    gameSubscription.value = null;
                    handSubscription.value = null;
                    subscribedGameId.value = null;
                    if (reconnectTimer) {
                        clearTimeout(reconnectTimer);
                    }
                    reconnectTimer = setTimeout(connectWebSocket, 3000);
                }
            );
        };

        const joinAndLoad = async () => {
            try {
                const response = await axios.post(`${apiBase}/game/${roomId.value}/join`);
                if (response.data.code === 200) {
                    renderSnapshot(response.data.data);
                } else {
                    showToast(response.data.message || "加入游戏失败");
                }
            } catch (error) {
                if (!handleMissingRoomOrGame(error, "房间已不存在")) {
                    showToast(error.response?.data?.message || "加入游戏失败");
                }
            }
        };

        const selectCard = (index) => {
            const card = handCards.value[index];
            if (!card) {
                return;
            }

            const result = evaluatePlayability(card, topCard.value, currentColor.value);
            console.info(
                `[UNO-FE] click card=${formatCard(card)} topCard=${formatCard(topCard.value)} currentColor=${currentColor.value} pendingDrawCount=${pendingDrawCount.value} pendingDrawType=${pendingDrawType.value} isMyTurn=${isMyTurn.value} canPlay=${result.canPlay} reason=${result.reason}`
            );

            if (gameStatus.value !== "PLAYING") {
                showToast("游戏尚未开始或已经结束");
                return;
            }
            if (!isMyTurn.value) {
                showToast("不是你的回合");
                return;
            }
            if (!result.canPlay) {
                showToast(result.reason);
                return;
            }

            if (selectedCard.value === index) {
                clearCardSelection();
                return;
            }

            selectedCard.value = index;
            needsColorPick.value = isWildType(card.type);
            if (!needsColorPick.value) {
                chosenColor.value = "";
            }
        };

        const pickColor = (color) => {
            chosenColor.value = color;
        };

        const playSelectedCard = async () => {
            if (selectedCard.value === null) {
                return;
            }

            const card = handCards.value[selectedCard.value];
            if (!card) {
                return;
            }

            const result = evaluatePlayability(card, topCard.value, currentColor.value);
            console.info(
                `[UNO-FE] click card=${formatCard(card)} topCard=${formatCard(topCard.value)} currentColor=${currentColor.value} pendingDrawCount=${pendingDrawCount.value} pendingDrawType=${pendingDrawType.value} isMyTurn=${isMyTurn.value} canPlay=${result.canPlay} reason=${result.reason} chosenColor=${chosenColor.value || "none"}`
            );

            if (needsColorPick.value && !chosenColor.value) {
                showToast("请选择颜色");
                return;
            }
            if (!canPlaySelected.value) {
                showToast(result.reason || "当前选择的牌不能出");
                return;
            }

            try {
                const params = new URLSearchParams();
                params.append("cardIndex", String(selectedCard.value));
                if (chosenColor.value) {
                    params.append("chosenColor", chosenColor.value);
                }

                const response = await axios.post(`${apiBase}/game/${gameId.value}/play?${params.toString()}`);
                if (response.data.code === 200) {
                    renderSnapshot(response.data.data);
                    clearCardSelection();
                } else {
                    showToast(response.data.message || "出牌失败");
                    await refreshFromServer();
                }
            } catch (error) {
                showToast(error.response?.data?.message || "出牌失败");
                await refreshFromServer();
            }
        };

        const drawCardAction = async () => {
            if (!canDraw.value) {
                showToast(isPendingDrawStack.value ? "当前必须先响应惩罚链" : "不是你的回合");
                return;
            }

            try {
                const response = await axios.post(`${apiBase}/game/${gameId.value}/draw`);
                if (response.data.code === 200) {
                    renderSnapshot(response.data.data);
                    clearCardSelection();
                } else {
                    showToast(response.data.message || "抽牌失败");
                }
            } catch (error) {
                showToast(error.response?.data?.message || "抽牌失败");
            }
        };

        const triggerAutoPenaltyDraw = async (pendingKey) => {
            if (!pendingKey) {
                return;
            }
            if (autoPenaltyInProgress.value || lastAutoPenaltyKey.value === pendingKey) {
                console.info(`[UNO-FE] pending draw: auto penalty already triggered key=${pendingKey}`);
                return;
            }

            autoPenaltyInProgress.value = true;
            lastAutoPenaltyKey.value = pendingKey;
            drawingPenalty.value = true;

            try {
                const response = await axios.post(`${apiBase}/game/${gameId.value}/draw-penalty`);
                if (response.data.code === 200) {
                    renderSnapshot(response.data.data);
                    clearCardSelection();
                } else {
                    showToast(response.data.message || "鎺ュ彈鎯╃綒澶辫触");
                    console.error("[UNO-FE] pending draw auto drawPenalty failed", response.data);
                    lastAutoPenaltyKey.value = "";
                }
            } catch (error) {
                showToast(error.response?.data?.message || "鎺ュ彈鎯╃綒澶辫触");
                console.error("[UNO-FE] pending draw auto drawPenalty failed", error);
                lastAutoPenaltyKey.value = "";
            } finally {
                drawingPenalty.value = false;
                autoPenaltyInProgress.value = false;
            }
        };

        const drawPenaltyAction = async ({ autoTriggered = false, pendingKey = "" } = {}) => {
            if (!autoTriggered && (!showDrawPenaltyButton.value || drawingPenalty.value)) {
                return;
            }
            if (autoTriggered) {
                const resolvedPendingKey = pendingKey || getPendingDrawStateKey();
                if (!resolvedPendingKey) {
                    return;
                }
                if (autoPenaltyInProgress.value || lastAutoPenaltyKey.value === resolvedPendingKey) {
                    console.info(`[UNO-FE] pending draw: auto penalty already triggered key=${resolvedPendingKey}`);
                    return;
                }
                autoPenaltyInProgress.value = true;
                lastAutoPenaltyKey.value = resolvedPendingKey;
                pendingKey = resolvedPendingKey;
            }

            drawingPenalty.value = true;
            try {
                const response = await axios.post(`${apiBase}/game/${gameId.value}/draw-penalty`);
                if (response.data.code === 200) {
                    renderSnapshot(response.data.data);
                    clearCardSelection();
                } else {
                    showToast(response.data.message || "接受惩罚失败");
                    await refreshFromServer();
                }
            } catch (error) {
                showToast(error.response?.data?.message || "接受惩罚失败");
                await refreshFromServer();
            } finally {
                drawingPenalty.value = false;
            }
        };

        const restartGameAction = async () => {
            if (!gameId.value || restartingGame.value) {
                return;
            }

            restartingGame.value = true;
            try {
                const response = await axios.post(`${apiBase}/game/${gameId.value}/rematch-ready`);
                if (response.data.code === 200) {
                    renderSnapshot(response.data.data);
                    addLog("已提交再来一局请求");
                } else {
                    showToast(response.data.message || "再来一局失败");
                }
            } catch (error) {
                showToast(error.response?.data?.message || "再来一局失败");
            } finally {
                if (gameStatus.value === "FINISHED") {
                    restartingGame.value = false;
                }
            }
        };

        const returnToLobby = async ({ force = false, notifyServer = true, notice = "", skipConfirm = false } = {}) => {
            if (leavingRoom.value) {
                return;
            }

            if (!skipConfirm && !force && gameStatus.value === "PLAYING") {
                const confirmed = window.confirm("返回大厅后，你将停止接收当前房间的实时消息。确定返回大厅吗？");
                if (!confirmed) {
                    return;
                }
            }

            leavingRoom.value = true;
            try {
                if (notifyServer && roomId.value) {
                    const response = await axios.post(`${apiBase}/room/${roomId.value}/leave`);
                    const message = response.data?.data?.message || response.data?.message;
                    if (message) {
                        storeLobbyNotice(message);
                    }
                } else if (notice) {
                    storeLobbyNotice(notice);
                }
            } catch (error) {
                const message = error.response?.data?.message || notice;
                if (message) {
                    storeLobbyNotice(message);
                }
            } finally {
                cleanupRealtime();
                resetLocalState();
                window.location.replace("lobby.html");
            }
        };

        const handleDelegatedButtonClick = (event) => {
            const button = event.target.closest("#backToLobbyButton, #endReturnLobbyBtn, #restartGameBtn, #drawPenaltyButton");
            if (!button || button.disabled) {
                return;
            }

            if (button.id === "backToLobbyButton") {
                event.preventDefault();
                returnToLobby({ force: false, notifyServer: true });
                return;
            }
            if (button.id === "endReturnLobbyBtn") {
                event.preventDefault();
                returnToLobby({ force: true, notifyServer: true });
                return;
            }
            if (button.id === "restartGameBtn") {
                event.preventDefault();
                restartGameAction();
                return;
            }
            if (button.id === "drawPenaltyButton") {
                event.preventDefault();
                drawPenaltyAction();
            }
        };

        onMounted(async () => {
            try {
                const response = await axios.get(`${apiBase}/user/me`);
                if (response.data.code !== 200) {
                    window.location.replace("index.html");
                    return;
                }
                if (response.data.data?.id) {
                    userId.value = response.data.data.id;
                    localStorage.setItem("userId", response.data.data.id);
                }
                if (response.data.data?.username) {
                    username.value = response.data.data.username;
                    localStorage.setItem("username", response.data.data.username);
                }
            } catch (error) {
                window.location.replace("index.html");
                return;
            }

            beforeUnloadHandler = () => cleanupRealtime();
            window.addEventListener("beforeunload", beforeUnloadHandler);

            delegatedButtonHandler = handleDelegatedButtonClick;
            document.addEventListener("click", delegatedButtonHandler);

            connectWebSocket();
            await joinAndLoad();

            pollTimer = setInterval(() => {
                if (!wsConnected.value) {
                    refreshFromServer();
                }
            }, 4000);
        });

        onUnmounted(() => {
            cleanupRealtime();
            if (beforeUnloadHandler) {
                window.removeEventListener("beforeunload", beforeUnloadHandler);
                beforeUnloadHandler = null;
            }
            if (delegatedButtonHandler) {
                document.removeEventListener("click", delegatedButtonHandler);
                delegatedButtonHandler = null;
            }
        });

        return {
            roomId,
            roomCode,
            roomStatus,
            currentTurn,
            clockwise,
            direction,
            currentColor,
            pendingDrawCount,
            pendingDrawType,
            lastPenaltyPlayerId,
            drawPileSize,
            topCard,
            handCards,
            opponents,
            isMyTurn,
            turnLabel,
            selectedCard,
            needsColorPick,
            chosenColor,
            gameLog,
            logExpanded,
            gameResult,
            toastMsg,
            colorMap,
            wsConnected,
            leavingRoom,
            restartingGame,
            drawingPenalty,
            canDraw,
            canPlaySelected,
            showPenaltyNotice,
            showDrawPenaltyButton,
            penaltyNoticeText,
            drawPenaltyButtonText,
            directionLabel,
            currentColorLabel,
            connectionLabel,
            selectCard,
            pickColor,
            playSelectedCard,
            drawCardAction,
            drawPenaltyAction,
            restartGameAction,
            returnToLobby
        };
    }
}).mount("#app");
