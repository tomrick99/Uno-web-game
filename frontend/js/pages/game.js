const { createApp, ref, onMounted, onUnmounted, computed } = Vue;
const apiBase = '/api';

createApp({
    setup() {
        const roomId = ref(new URLSearchParams(window.location.search).get('roomId'));
        const roomCode = ref('');
        const roomStatus = ref('WAITING');
        const userId = ref(localStorage.getItem('userId'));
        const username = ref(localStorage.getItem('username') || '');

        const gameId = ref(null);
        const gameStatus = ref('WAITING');
        const currentTurn = ref(null);
        const clockwise = ref(true);
        const currentColor = ref('RED');
        const drawPileSize = ref(0);
        const topCard = ref(null);
        const handCards = ref([]);
        const opponents = ref([]);
        const currentPlayerName = ref('等待玩家加入');
        const selectedCard = ref(null);
        const needsColorPick = ref(false);
        const chosenColor = ref('');
        const gameLog = ref([]);
        const logExpanded = ref(false);
        const gameResult = ref(null);
        const toastMsg = ref('');
        const wsConnected = ref(false);

        const stompClient = ref(null);
        const roomSubscription = ref(null);
        const gameSubscription = ref(null);
        const handSubscription = ref(null);
        const subscribedGameId = ref(null);

        let reconnectTimer = null;
        let pollTimer = null;
        let unloadHandlerBound = false;

        const colorMap = {
            RED: '#E74C3C',
            BLUE: '#3498DB',
            GREEN: '#2ECC71',
            YELLOW: '#F39C12'
        };

        const isMyTurn = computed(() =>
            gameStatus.value === 'PLAYING' && String(currentTurn.value) === String(userId.value)
        );
        const canDraw = computed(() => gameStatus.value === 'PLAYING' && isMyTurn.value);
        const canPlaySelected = computed(() =>
            gameStatus.value === 'PLAYING'
            && isMyTurn.value
            && selectedCard.value !== null
            && !!handCards.value[selectedCard.value]
            && !!handCards.value[selectedCard.value].playable
            && (!needsColorPick.value || !!chosenColor.value)
        );
        const directionLabel = computed(() => clockwise.value ? '顺时针' : '逆时针');
        const currentColorLabel = computed(() => {
            if (currentColor.value === 'RED') return '红色';
            if (currentColor.value === 'YELLOW') return '黄色';
            if (currentColor.value === 'GREEN') return '绿色';
            if (currentColor.value === 'BLUE') return '蓝色';
            return currentColor.value;
        });
        const connectionLabel = computed(() => wsConnected.value ? '实时同步已连接' : '实时同步已断开，使用备用同步');
        const turnLabel = computed(() => {
            if (gameStatus.value === 'FINISHED') {
                return '本局已结束';
            }
            if (gameStatus.value !== 'PLAYING') {
                return `等待玩家加入 (${opponents.value.length + 1}/2)`;
            }
            return isMyTurn.value ? '轮到你了' : `轮到 ${currentPlayerName.value}`;
        });

        const showToast = (msg) => {
            if (!msg) return;
            toastMsg.value = msg;
            setTimeout(() => {
                if (toastMsg.value === msg) {
                    toastMsg.value = '';
                }
            }, 3000);
        };

        const addLog = (msg) => {
            if (!msg) return;
            gameLog.value.unshift(msg);
            if (gameLog.value.length > 50) {
                gameLog.value.pop();
            }
        };

        const getCardDisplay = (type, value) => {
            if (type === 'NUMBER') return String(value);
            if (type === 'SKIP') return 'SKIP';
            if (type === 'REVERSE') return 'REV';
            if (type === 'DRAW_TWO') return '+2';
            if (type === 'WILD') return 'WILD';
            if (type === 'WILD_DRAW_FOUR') return '+4';
            return '?';
        };

        const colorRank = (color) => {
            if (color === 'RED') return 0;
            if (color === 'YELLOW') return 1;
            if (color === 'GREEN') return 2;
            if (color === 'BLUE') return 3;
            return -1;
        };

        const typeRank = (type) => {
            if (type === 'WILD') return 0;
            if (type === 'WILD_DRAW_FOUR') return 1;
            if (type === 'DRAW_TWO') return 2;
            if (type === 'SKIP') return 3;
            if (type === 'REVERSE') return 4;
            return 10;
        };

        const sortHandCards = (cards) => {
            return [...(cards || [])].sort((a, b) => {
                const aGroup = (a.type === 'WILD' || a.type === 'WILD_DRAW_FOUR') ? 0 : 1;
                const bGroup = (b.type === 'WILD' || b.type === 'WILD_DRAW_FOUR') ? 0 : 1;
                if (aGroup !== bGroup) return aGroup - bGroup;

                const colorDiff = colorRank(a.color) - colorRank(b.color);
                if (colorDiff !== 0) return colorDiff;

                const typeDiff = typeRank(a.type) - typeRank(b.type);
                if (typeDiff !== 0) return typeDiff;

                return Number(a.value) - Number(b.value);
            });
        };

        const isPlayableCard = (card) => {
            if (!card || gameStatus.value !== 'PLAYING' || !isMyTurn.value) return false;
            if (card.type === 'WILD' || card.type === 'WILD_DRAW_FOUR') return true;
            if (card.color === currentColor.value) return true;
            if (!topCard.value) return true;
            return card.type === topCard.value.type
                || (card.type === 'NUMBER'
                    && topCard.value.type === 'NUMBER'
                    && Number(card.value) === Number(topCard.value.value));
        };

        const normalizeCard = (card) => ({
            ...card,
            display: getCardDisplay(card.type, card.value),
            playable: isPlayableCard(card)
        });

        const refreshHandPlayability = () => {
            handCards.value = sortHandCards(handCards.value).map(normalizeCard);
            if (selectedCard.value !== null && !handCards.value[selectedCard.value]?.playable) {
                selectedCard.value = null;
                needsColorPick.value = false;
                chosenColor.value = '';
            }
        };

        const applyHandCards = (cards) => {
            handCards.value = sortHandCards(cards).map(normalizeCard);
            refreshHandPlayability();
        };

        const resetLocalState = () => {
            gameId.value = null;
            roomCode.value = '';
            roomStatus.value = 'WAITING';
            gameStatus.value = 'WAITING';
            currentTurn.value = null;
            currentColor.value = 'RED';
            clockwise.value = true;
            drawPileSize.value = 0;
            topCard.value = null;
            handCards.value = [];
            opponents.value = [];
            currentPlayerName.value = '等待玩家加入';
            selectedCard.value = null;
            needsColorPick.value = false;
            chosenColor.value = '';
            gameResult.value = null;
        };

        const storeLobbyNotice = (message) => {
            if (message) {
                sessionStorage.setItem('lobbyNotice', message);
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
                    stompClient.value.disconnect(() => {});
                } catch (error) {
                    console.error(error);
                }
                stompClient.value = null;
            }
        };

        const renderRoom = (roomState) => {
            if (!roomState) return;
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
                    saidUno: !!player.saidUno
                }));
        };

        const renderGame = (gameState) => {
            if (!gameState) return;

            gameId.value = gameState.gameId;
            roomCode.value = gameState.roomCode || roomCode.value;
            roomStatus.value = gameState.roomStatus || roomStatus.value;
            gameStatus.value = gameState.status || 'WAITING';
            currentTurn.value = gameState.currentTurn;
            clockwise.value = gameState.clockwise !== false;
            currentColor.value = gameState.currentColor || 'RED';
            drawPileSize.value = gameState.drawPileSize || 0;

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
                    saidUno: !!player.saidUno
                }));

            const turnPlayer = players.find((player) => String(player.userId) === String(gameState.currentTurn));
            currentPlayerName.value = turnPlayer?.username || '等待玩家加入';

            if (gameState.status === 'FINISHED') {
                const winner = players.find((player) => String(player.userId) === String(gameState.winnerId));
                gameResult.value = {
                    win: String(gameState.winnerId) === String(userId.value),
                    title: winner ? `${winner.username} wins!` : '游戏结束'
                };
            } else {
                gameResult.value = null;
            }

            subscribeGameChannels(gameState.gameId);
            refreshHandPlayability();
        };

        const renderSnapshot = (snapshot) => {
            if (!snapshot) return;
            if (snapshot.roomState) renderRoom(snapshot.roomState);
            if (snapshot.gameState) renderGame(snapshot.gameState);
            if (Array.isArray(snapshot.handCards)) applyHandCards(snapshot.handCards);
        };

        const handleRoomDeleted = (payload) => {
            const message = payload?.message || '房间已被关闭';
            storeLobbyNotice(message);
            cleanupRealtime();
            resetLocalState();
            window.location.replace('lobby.html');
        };

        const handleMissingRoomOrGame = (error, fallbackMessage) => {
            const message = error?.response?.data?.message || fallbackMessage;
            if ([400, 404].includes(error?.response?.status) || message?.includes('房间') || message?.includes('游戏')) {
                storeLobbyNotice(message || '当前房间已不存在');
                cleanupRealtime();
                resetLocalState();
                window.location.replace('lobby.html');
                return true;
            }
            return false;
        };

        const syncHand = async () => {
            if (!roomId.value) return;
            try {
                const res = await axios.get(`${apiBase}/game/room/${roomId.value}/hand`);
                if (res.data.code === 200) {
                    applyHandCards(res.data.data);
                }
            } catch (error) {
                if (!handleMissingRoomOrGame(error, '房间已不可用')) {
                    console.error('同步手牌失败', error);
                }
            }
        };

        const refreshFromServer = async () => {
            if (!roomId.value) return;
            try {
                const stateRes = await axios.get(`${apiBase}/game/room/${roomId.value}/state`);
                if (stateRes.data.code === 200) {
                    renderGame(stateRes.data.data);
                    await syncHand();
                } else if (stateRes.data.message) {
                    handleMissingRoomOrGame({ response: { status: stateRes.data.code, data: stateRes.data } }, '房间已不存在');
                }
            } catch (error) {
                if (!handleMissingRoomOrGame(error, '房间已不存在')) {
                    console.error('刷新游戏状态失败', error);
                }
            }
        };

        const subscribeRoomTopic = () => {
            if (!stompClient.value || !wsConnected.value || roomSubscription.value) return;

            roomSubscription.value = stompClient.value.subscribe(`/topic/rooms/${roomId.value}`, (message) => {
                const payload = JSON.parse(message.body);
                if (payload.type === 'ROOM_DELETED') {
                    handleRoomDeleted(payload);
                    return;
                }
                if (payload.roomState) {
                    renderRoom(payload.roomState);
                }
                if (payload.message) {
                    addLog(payload.message);
                }
            });
        };

        const subscribeGameChannels = (nextGameId) => {
            if (!nextGameId) return;
            gameId.value = nextGameId;

            if (!stompClient.value || !wsConnected.value) {
                return;
            }

            if (String(subscribedGameId.value) === String(nextGameId) && gameSubscription.value && handSubscription.value) {
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
                if (payload.type === 'ROOM_DELETED') {
                    handleRoomDeleted(payload);
                    return;
                }
                if (payload.gameState) {
                    renderGame(payload.gameState);
                }
                if (payload.message) {
                    addLog(payload.message);
                }
            });

            handSubscription.value = stompClient.value.subscribe(`/topic/games/${nextGameId}/hands/${userId.value}`, (message) => {
                const payload = JSON.parse(message.body);
                if (Array.isArray(payload.handCards)) {
                    applyHandCards(payload.handCards);
                }
            });
        };

        const connectWebSocket = () => {
            const stompApi = window.Stomp || window.StompJs?.Stomp;
            if (!stompApi) {
                console.error('STOMP 客户端未加载');
                return;
            }

            const socket = new SockJS('/api/ws');
            stompClient.value = stompApi.over(socket);
            stompClient.value.debug = () => {};

            stompClient.value.connect({}, () => {
                wsConnected.value = true;
                subscribeRoomTopic();
                if (gameId.value) {
                    subscribeGameChannels(gameId.value);
                }
                addLog('实时连接已建立');
            }, () => {
                wsConnected.value = false;
                roomSubscription.value = null;
                gameSubscription.value = null;
                handSubscription.value = null;
                subscribedGameId.value = null;
                if (reconnectTimer) {
                    clearTimeout(reconnectTimer);
                }
                reconnectTimer = setTimeout(connectWebSocket, 3000);
            });
        };

        const joinAndLoad = async () => {
            try {
                const res = await axios.post(`${apiBase}/game/${roomId.value}/join`);
                if (res.data.code === 200) {
                    renderSnapshot(res.data.data);
                } else {
                    showToast(res.data.message || '加入游戏失败');
                }
            } catch (error) {
                if (!handleMissingRoomOrGame(error, '房间已不存在')) {
                    showToast(error.response?.data?.message || '加入游戏失败');
                }
            }
        };

        const selectCard = (idx) => {
            if (!isMyTurn.value) {
                showToast('还没轮到你');
                return;
            }

            const card = handCards.value[idx];
            if (!card?.playable) {
                showToast('这张牌现在不能出');
                return;
            }

            if (selectedCard.value === idx) {
                selectedCard.value = null;
                needsColorPick.value = false;
                chosenColor.value = '';
                return;
            }

            selectedCard.value = idx;
            needsColorPick.value = card.type === 'WILD' || card.type === 'WILD_DRAW_FOUR';
            if (!needsColorPick.value) {
                chosenColor.value = '';
            }
        };

        const pickColor = (color) => {
            chosenColor.value = color;
        };

        const playSelectedCard = async () => {
            if (!canPlaySelected.value) {
                showToast('当前选择的牌不能出');
                return;
            }

            try {
                const params = new URLSearchParams();
                params.append('cardIndex', selectedCard.value);
                if (chosenColor.value) {
                    params.append('chosenColor', chosenColor.value);
                }

                const res = await axios.post(`${apiBase}/game/${gameId.value}/play?${params.toString()}`);
                if (res.data.code === 200) {
                    renderSnapshot(res.data.data);
                    selectedCard.value = null;
                    needsColorPick.value = false;
                    chosenColor.value = '';
                } else {
                    showToast(res.data.message || '出牌失败');
                }
            } catch (error) {
                showToast(error.response?.data?.message || '出牌失败');
            }
        };

        const drawCardAction = async () => {
            if (!canDraw.value) {
                showToast('还没轮到你');
                return;
            }

            try {
                const res = await axios.post(`${apiBase}/game/${gameId.value}/draw`);
                if (res.data.code === 200) {
                    renderSnapshot(res.data.data);
                    selectedCard.value = null;
                    needsColorPick.value = false;
                    chosenColor.value = '';
                } else {
                    showToast(res.data.message || '抽牌失败');
                }
            } catch (error) {
                showToast(error.response?.data?.message || '抽牌失败');
            }
        };

        const restartGameAction = async () => {
            try {
                const res = await axios.post(`${apiBase}/game/${gameId.value}/restart`);
                if (res.data.code === 200) {
                    renderSnapshot(res.data.data);
                    selectedCard.value = null;
                    needsColorPick.value = false;
                    chosenColor.value = '';
                    addLog('再来一局开始');
                } else {
                    showToast(res.data.message || '再来一局失败');
                }
            } catch (error) {
                showToast(error.response?.data?.message || '再来一局失败');
            }
        };

        const backToLobby = (force = false) => {
            if (!force && gameStatus.value === 'PLAYING') {
                const confirmed = window.confirm('离开后不会影响后端游戏状态，但你会停止接收当前对局的实时消息。确定返回大厅吗？');
                if (!confirmed) {
                    return;
                }
            }
            cleanupRealtime();
            resetLocalState();
            window.location.replace('lobby.html');
        };

        onMounted(async () => {
            try {
                const res = await axios.get(`${apiBase}/user/me`);
                if (res.data.code !== 200) {
                    window.location.href = 'index.html';
                    return;
                }
                if (res.data.data?.id) {
                    userId.value = res.data.data.id;
                    localStorage.setItem('userId', res.data.data.id);
                }
                if (res.data.data?.username) {
                    username.value = res.data.data.username;
                    localStorage.setItem('username', res.data.data.username);
                }
            } catch (error) {
                window.location.href = 'index.html';
                return;
            }

            connectWebSocket();
            await joinAndLoad();

            if (!unloadHandlerBound) {
                window.addEventListener('beforeunload', cleanupRealtime);
                unloadHandlerBound = true;
            }

            pollTimer = setInterval(() => {
                if (!wsConnected.value) {
                    refreshFromServer();
                }
            }, 4000);
        });

        onUnmounted(() => {
            cleanupRealtime();
            if (unloadHandlerBound) {
                window.removeEventListener('beforeunload', cleanupRealtime);
                unloadHandlerBound = false;
            }
        });

        return {
            roomId,
            roomCode,
            roomStatus,
            currentTurn,
            clockwise,
            currentColor,
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
            canDraw,
            canPlaySelected,
            directionLabel,
            currentColorLabel,
            connectionLabel,
            selectCard,
            pickColor,
            playSelectedCard,
            drawCardAction,
            restartGameAction,
            backToLobby
        };
    }
}).mount('#app');
