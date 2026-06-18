const { createApp, ref, onMounted, onUnmounted, computed } = Vue;
const apiBase = "/api";
const realtimeUtils = window.UnoRealtimeUtils || {};
const FALLBACK_THRESHOLD_MS = realtimeUtils.DEFAULT_FALLBACK_THRESHOLD_MS || 10000;
const websocketEndpoint = "/api/ws";

createApp({
    setup() {
        const username = ref("");
        const rooms = ref([]);
        const showCreate = ref(false);
        const maxPlayers = ref(2);
        const totalRounds = ref(8);
        const roundTimeLimitMinutes = ref(10);
        const gameMode = ref("CLASSIC");
        const language = ref(localStorage.getItem("unoLanguage") || "zh");
        const errorMsg = ref("");
        const infoMsg = ref("");
        const connectionMode = ref("reconnecting");
        const wsConnected = ref(false);

        const stompClient = ref(null);
        const lobbySubscription = ref(null);

        let reconnectTimer = null;
        let fallbackActivationTimer = null;
        let fallbackPollTimer = null;
        let refreshDebounceTimer = null;
        let disconnectedAt = null;
        let isConnecting = false;
        let shouldReconnect = true;

        const messages = {
            zh: {
                admin: "管理",
                logout: "退出",
                gameLobby: "游戏大厅",
                createRoom: "创建房间",
                emptyRooms: "暂时没有等待中的房间，创建一个开始吧。",
                waiting: "等待中",
                playing: "游戏中",
                playersUnit: "人",
                roundsUnit: "局",
                minutesUnit: "分钟",
                host: "房主",
                unknown: "未知",
                join: "加入",
                customGame: "自定义游戏",
                players: "玩家人数",
                rounds: "局数",
                roundTime: "单局时长",
                mode: "模式",
                cancel: "取消",
                create: "创建",
                failedLoadRooms: "加载房间失败",
                failedCreateRoom: "创建房间失败",
                failedJoinRoom: "加入房间失败",
                classic: "经典",
                connected: "实时已连接",
                reconnecting: "正在重连",
                fallback: "实时断开，轮询中",
                syncStatus: "同步状态"
            },
            en: {
                admin: "Admin",
                logout: "Logout",
                gameLobby: "Game Lobby",
                createRoom: "Create Room",
                emptyRooms: "No rooms are waiting. Create one to start.",
                waiting: "Waiting",
                playing: "Playing",
                playersUnit: "players",
                roundsUnit: "rounds",
                minutesUnit: "min",
                host: "Host",
                unknown: "Unknown",
                join: "Join",
                customGame: "Custom Game",
                players: "Players",
                rounds: "Rounds",
                roundTime: "Round Time",
                mode: "Mode",
                cancel: "Cancel",
                create: "Create",
                failedLoadRooms: "Failed to load rooms",
                failedCreateRoom: "Failed to create room",
                failedJoinRoom: "Failed to join room",
                classic: "Classic",
                connected: "Realtime connected",
                reconnecting: "Reconnecting",
                fallback: "Fallback polling",
                syncStatus: "Sync"
            }
        };

        const playerOptions = [2, 3, 4, 5, 6, 7, 8];
        const roundOptions = [8, 16, 32];
        const timeOptions = [5, 10, 15];

        const isAdmin = computed(() => username.value.toLowerCase() === "admin");
        const t = (key) => messages[language.value]?.[key] || messages.en[key] || key;
        const languageLabel = computed(() => language.value === "zh" ? "EN" : "中文");
        const modeLabel = (mode) => mode === "NO_MERCY" ? "No Mercy" : t("classic");
        const modeOptions = computed(() => [
            { value: "CLASSIC", label: modeLabel("CLASSIC") },
            { value: "NO_MERCY", label: modeLabel("NO_MERCY") }
        ]);
        const connectionLabel = computed(() => t(connectionMode.value));

        const applyLanguage = () => {
            document.documentElement.lang = language.value === "zh" ? "zh-CN" : "en";
        };

        const toggleLanguage = () => {
            language.value = language.value === "zh" ? "en" : "zh";
            localStorage.setItem("unoLanguage", language.value);
            applyLanguage();
        };

        const consumeLobbyNotice = () => {
            const notice = sessionStorage.getItem("lobbyNotice");
            if (!notice) return;
            infoMsg.value = notice;
            sessionStorage.removeItem("lobbyNotice");
            setTimeout(() => {
                if (infoMsg.value === notice) infoMsg.value = "";
            }, 3500);
        };

        const clearReconnectTimer = () => {
            if (reconnectTimer) {
                clearTimeout(reconnectTimer);
                reconnectTimer = null;
            }
        };

        const clearFallbackActivationTimer = () => {
            if (fallbackActivationTimer) {
                clearTimeout(fallbackActivationTimer);
                fallbackActivationTimer = null;
            }
        };

        const stopFallbackPolling = () => {
            if (fallbackPollTimer) {
                clearInterval(fallbackPollTimer);
                fallbackPollTimer = null;
                console.info("[UNO-LOBBY] fallback polling stopped");
            }
        };

        const queueRoomsRefresh = (delayMs = 300) => {
            if (refreshDebounceTimer) {
                clearTimeout(refreshDebounceTimer);
            }
            refreshDebounceTimer = setTimeout(() => {
                refreshDebounceTimer = null;
                loadRooms();
            }, delayMs);
        };

        const updateConnectionMode = () => {
            if (typeof realtimeUtils.resolveConnectionMode === "function") {
                connectionMode.value = realtimeUtils.resolveConnectionMode({
                    connected: wsConnected.value,
                    fallbackActive: Boolean(fallbackPollTimer)
                });
                return;
            }
            connectionMode.value = wsConnected.value ? "connected" : (fallbackPollTimer ? "fallback" : "reconnecting");
        };

        const startFallbackPolling = () => {
            if (fallbackPollTimer || wsConnected.value) return;
            fallbackPollTimer = setInterval(() => {
                if (!wsConnected.value) {
                    loadRooms();
                }
            }, 10000);
            console.info("[UNO-LOBBY] fallback polling started");
            updateConnectionMode();
        };

        const scheduleFallbackActivation = () => {
            clearFallbackActivationTimer();
            fallbackActivationTimer = setTimeout(() => {
                const now = Date.now();
                const shouldEnable = typeof realtimeUtils.shouldEnableFallbackPolling === "function"
                    ? realtimeUtils.shouldEnableFallbackPolling(disconnectedAt, now, FALLBACK_THRESHOLD_MS)
                    : disconnectedAt && now - disconnectedAt >= FALLBACK_THRESHOLD_MS;
                if (shouldEnable && !wsConnected.value) {
                    startFallbackPolling();
                    loadRooms();
                }
            }, FALLBACK_THRESHOLD_MS);
        };

        const cleanupSocket = () => {
            if (lobbySubscription.value) {
                lobbySubscription.value.unsubscribe();
                lobbySubscription.value = null;
            }
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
            isConnecting = false;
        };

        const subscribeLobbyTopic = () => {
            if (!stompClient.value || !wsConnected.value || lobbySubscription.value) return;
            lobbySubscription.value = stompClient.value.subscribe("/topic/lobby", (message) => {
                let payload = {};
                try {
                    payload = JSON.parse(message.body || "{}");
                } catch (error) {
                    console.error(error);
                }
                const event = {
                    type: payload.event || payload.type,
                    roomId: payload.roomId
                };
                console.info("[UNO-LOBBY] lobby event received", event.type, event.roomId);
                if (event.type === "ROOM_REMOVED") {
                    removeRoomLocally(event.roomId);
                }
                if (payload.message) {
                    infoMsg.value = payload.message;
                    setTimeout(() => {
                        if (infoMsg.value === payload.message) infoMsg.value = "";
                    }, 2500);
                }
                queueRoomsRefresh(300);
            });
        };

        const scheduleReconnect = () => {
            if (!shouldReconnect) return;
            clearReconnectTimer();
            reconnectTimer = setTimeout(() => {
                reconnectTimer = null;
                connectWebSocket();
            }, 3000);
        };

        const handleSocketDisconnected = () => {
            wsConnected.value = false;
            disconnectedAt = disconnectedAt || Date.now();
            console.warn("[UNO-LOBBY] ws disconnected, reconnecting");
            cleanupSocket();
            updateConnectionMode();
            scheduleFallbackActivation();
            scheduleReconnect();
        };

        const connectWebSocket = () => {
            if (!shouldReconnect || wsConnected.value || isConnecting) return;
            const sockJsLoaded = typeof SockJS !== "undefined";
            const stompJsLoaded = typeof StompJs !== "undefined";
            const legacyStompLoaded = typeof Stomp !== "undefined";
            console.info("[UNO-LOBBY] websocket endpoint =", websocketEndpoint);
            console.info("[UNO-LOBBY] SockJS loaded =", sockJsLoaded);
            console.info("[UNO-LOBBY] StompJs loaded =", stompJsLoaded);
            if (!sockJsLoaded || (!stompJsLoaded && !legacyStompLoaded)) {
                console.error("[UNO-LOBBY] ws connect failed", {
                    endpoint: websocketEndpoint,
                    sockJsLoaded,
                    stompJsLoaded,
                    legacyStompLoaded
                });
                connectionMode.value = "fallback";
                startFallbackPolling();
                return;
            }

            isConnecting = true;
            connectionMode.value = "reconnecting";
            console.info("[UNO-LOBBY] connecting websocket...");

            const handleConnected = async () => {
                stompClient.value = client;
                wsConnected.value = true;
                isConnecting = false;
                disconnectedAt = null;
                clearReconnectTimer();
                clearFallbackActivationTimer();
                stopFallbackPolling();
                updateConnectionMode();
                subscribeLobbyTopic();
                console.info("[UNO-LOBBY] ws connected, subscribed /topic/lobby");
                await loadRooms();
            };

            const handleConnectError = (error) => {
                console.error("[UNO-LOBBY] ws connect failed", error || { endpoint: websocketEndpoint });
                handleSocketDisconnected();
            };

            const socketFactory = () => {
                const socket = new SockJS(websocketEndpoint);
                socket.onclose = (event) => {
                    if (shouldReconnect && !wsConnected.value) {
                        console.warn("[UNO-LOBBY] ws closed", event);
                    }
                };
                return socket;
            };

            let client;
            if (window.StompJs?.Client) {
                client = new window.StompJs.Client({
                    webSocketFactory: socketFactory,
                    reconnectDelay: 0,
                    debug: () => {},
                    onConnect: handleConnected,
                    onStompError: handleConnectError,
                    onWebSocketError: handleConnectError,
                    onWebSocketClose: (event) => {
                        if (shouldReconnect && wsConnected.value) {
                            console.warn("[UNO-LOBBY] ws closed", event);
                            handleSocketDisconnected();
                        }
                    }
                });
                stompClient.value = client;
                client.activate();
                return;
            }

            const stompApi = window.Stomp || window.StompJs?.Stomp;
            const socket = socketFactory();
            client = stompApi.over(socket);
            client.debug = () => {};
            stompClient.value = client;
            client.connect({}, handleConnected, handleConnectError);
        };

        const checkLogin = async () => {
            localStorage.removeItem("userId");
            localStorage.removeItem("username");
            try {
                const res = await axios.get(`${apiBase}/user/me`);
                if (res.data.code !== 200) {
                    window.location.href = "index.html";
                    return false;
                }
                const currentUser = res.data.data || {};
                username.value = currentUser.username || "";
                if (currentUser.id) localStorage.setItem("userId", currentUser.id);
                if (currentUser.username) localStorage.setItem("username", currentUser.username);
                console.info("[UNO-LOBBY] init currentUser ok", currentUser.username || "");
                return true;
            } catch (error) {
                console.error("[UNO-LOBBY] init failed", error);
                window.location.href = "index.html";
                return false;
            }
        };

        const loadRooms = async () => {
            try {
                const res = await axios.get(`${apiBase}/room/list`);
                if (res.data.code === 200) {
                    rooms.value = res.data.data || [];
                    errorMsg.value = "";
                } else {
                    errorMsg.value = res.data.message || t("failedLoadRooms");
                }
            } catch (error) {
                errorMsg.value = t("failedLoadRooms");
                setTimeout(() => {
                    if (errorMsg.value === t("failedLoadRooms")) errorMsg.value = "";
                }, 3000);
            }
        };

        const createRoom = async () => {
            try {
                const payload = {
                    maxPlayers: maxPlayers.value,
                    totalRounds: totalRounds.value,
                    roundTimeLimitMinutes: roundTimeLimitMinutes.value,
                    gameMode: gameMode.value
                };
                const res = await axios.post(`${apiBase}/room/create`, payload);
                console.info("[UNO-LOBBY] create room response", res.data);
                if (res.data.code === 200) {
                    showCreate.value = false;
                    const nextRoomId = res.data.data?.roomId ?? res.data.data?.id;
                    if (!nextRoomId) {
                        errorMsg.value = t("failedCreateRoom");
                        return;
                    }
                    window.location.href = `game.html?roomId=${nextRoomId}`;
                } else {
                    errorMsg.value = res.data.message || t("failedCreateRoom");
                }
            } catch (error) {
                console.error("[UNO-LOBBY] init failed", error);
                errorMsg.value = error.response?.data?.message || t("failedCreateRoom");
                setTimeout(() => errorMsg.value = "", 3000);
            }
        };

        const removeRoomLocally = (roomId) => {
            if (!roomId) return;
            rooms.value = rooms.value.filter((room) => String(room.roomId ?? room.id) !== String(roomId));
        };

        const joinRoom = async (room) => {
            const targetRoomId = room?.roomId ?? room?.id;
            try {
                console.info("[UNO-LOBBY] joining room", targetRoomId);
                const res = await axios.post(`${apiBase}/game/${targetRoomId}/join`);
                console.info("[UNO-LOBBY] join response", res.data);
                if (res.data.code === 200) {
                    window.location.href = `game.html?roomId=${targetRoomId}`;
                } else {
                    errorMsg.value = res.data.message || t("failedJoinRoom");
                    removeRoomLocally(targetRoomId);
                    queueRoomsRefresh(0);
                }
            } catch (error) {
                console.error("[UNO-LOBBY] init failed", error);
                errorMsg.value = error.response?.data?.message || t("failedJoinRoom");
                if ([400, 404].includes(error.response?.status)) {
                    removeRoomLocally(targetRoomId);
                    queueRoomsRefresh(0);
                }
                setTimeout(() => errorMsg.value = "", 3000);
            }
        };

        const goToAdmin = () => {
            window.location.href = "admin.html";
        };

        const logout = async () => {
            shouldReconnect = false;
            cleanupSocket();
            stopFallbackPolling();
            clearReconnectTimer();
            clearFallbackActivationTimer();
            await axios.post(`${apiBase}/user/logout`);
            localStorage.removeItem("userId");
            localStorage.removeItem("username");
            sessionStorage.removeItem("lobbyNotice");
            window.location.href = "index.html";
        };

        onMounted(async () => {
            console.info("[UNO-LOBBY] lobby.js loaded");
            applyLanguage();
            const loggedIn = await checkLogin();
            if (!loggedIn) return;
            consumeLobbyNotice();
            await loadRooms();
            console.info("[UNO-LOBBY] initial room list loaded", rooms.value.length);
            connectWebSocket();
        });

        onUnmounted(() => {
            shouldReconnect = false;
            cleanupSocket();
            stopFallbackPolling();
            clearReconnectTimer();
            clearFallbackActivationTimer();
            if (refreshDebounceTimer) {
                clearTimeout(refreshDebounceTimer);
                refreshDebounceTimer = null;
            }
        });

        return {
            username,
            rooms,
            showCreate,
            maxPlayers,
            totalRounds,
            roundTimeLimitMinutes,
            gameMode,
            language,
            errorMsg,
            infoMsg,
            wsConnected,
            connectionMode,
            connectionLabel,
            isAdmin,
            t,
            languageLabel,
            toggleLanguage,
            modeLabel,
            playerOptions,
            roundOptions,
            timeOptions,
            modeOptions,
            createRoom,
            joinRoom,
            goToAdmin,
            logout
        };
    }
}).mount("#app");
