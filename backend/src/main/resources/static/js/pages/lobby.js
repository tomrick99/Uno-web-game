const { createApp, ref, onMounted, onUnmounted, computed } = Vue;
const apiBase = "/api";
const realtimeUtils = window.UnoRealtimeUtils || {};
const FALLBACK_THRESHOLD_MS = realtimeUtils.DEFAULT_FALLBACK_THRESHOLD_MS || 10000;
const websocketEndpoint = "/api/ws";

createApp({
    setup() {
        const username = ref("");
        const avatarUrl = ref("");
        const avatarInput = ref(null);
        const cropStage = ref(null);
        const showAvatarEditor = ref(false);
        const avatarSourceUrl = ref("");
        const avatarZoom = ref(1);
        const avatarPan = ref({ x: 0, y: 0 });
        const avatarBaseSize = ref({ width: 280, height: 280, scale: 1 });
        const avatarSaving = ref(false);
        const avatarError = ref("");
        const rooms = ref([]);
        const showCreate = ref(false);
        const maxPlayers = ref(2);
        const roundTimeLimitMinutes = ref(10);
        const gameMode = ref("CLASSIC");
        const drawPileRule = ref("AUTO_REFILL");
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
                minutesUnit: "分钟",
                host: "房主",
                unknown: "未知",
                join: "加入",
                customGame: "自定义游戏",
                players: "玩家人数",
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
                syncStatus: "同步状态",
                drawPileRule: "抽牌堆耗尽规则",
                autoRefill: "自动补充",
                autoRefillDescription: "抽牌堆用完后，用弃牌堆继续游戏。",
                autoRefillWarning: "提示：该模式可能使对局持续更久。",
                finiteDrawPile: "有限抽牌堆",
                finiteDrawPileDescription: "抽牌堆用完即结束，手牌最少者胜出。",
                changeAvatar: "设置头像",
                cropAvatar: "裁剪头像",
                cropHelp: "拖动图片调整位置，使用滑块缩放。头像将保存为统一的圆形比例。",
                zoom: "缩放",
                chooseAnother: "重新选择",
                saveAvatar: "保存头像",
                savingAvatar: "保存中…",
                invalidAvatarType: "请选择 JPEG、PNG、WebP 或 GIF 图片",
                avatarTooLarge: "原图片不能超过 10 MB",
                avatarLoadFailed: "无法读取这张图片",
                avatarSaveFailed: "头像保存失败",
                avatarSaved: "头像已保存"
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
                minutesUnit: "min",
                host: "Host",
                unknown: "Unknown",
                join: "Join",
                customGame: "Custom Game",
                players: "Players",
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
                syncStatus: "Sync",
                drawPileRule: "Draw-pile exhaustion rule",
                autoRefill: "Auto Refill",
                autoRefillDescription: "Recycle the discard pile and keep playing when the draw pile runs out.",
                autoRefillWarning: "Note: this mode may result in longer games.",
                finiteDrawPile: "Finite Draw Pile",
                finiteDrawPileDescription: "End the game when the draw pile runs out; the fewest cards wins.",
                changeAvatar: "Set avatar",
                cropAvatar: "Crop avatar",
                cropHelp: "Drag to reposition and use the slider to zoom. The avatar is saved at a consistent square ratio.",
                zoom: "Zoom",
                chooseAnother: "Choose another",
                saveAvatar: "Save avatar",
                savingAvatar: "Saving…",
                invalidAvatarType: "Choose a JPEG, PNG, WebP, or GIF image",
                avatarTooLarge: "The source image must be under 10 MB",
                avatarLoadFailed: "This image could not be loaded",
                avatarSaveFailed: "Failed to save avatar",
                avatarSaved: "Avatar saved"
            }
        };

        const playerOptions = [2, 3, 4, 5, 6, 7, 8];
        const timeOptions = [5, 10, 15];

        const isAdmin = computed(() => username.value.toLowerCase() === "admin");
        const t = (key) => messages[language.value]?.[key] || messages.en[key] || key;
        const languageLabel = computed(() => language.value === "zh" ? "EN" : "中文");
        const modeLabel = (mode) => mode === "NO_MERCY" ? "No Mercy" : t("classic");
        const modeOptions = computed(() => [
            { value: "CLASSIC", label: modeLabel("CLASSIC") },
            { value: "NO_MERCY", label: modeLabel("NO_MERCY") }
        ]);
        const drawPileRuleLabel = (rule) => rule === "FINITE_DRAW_PILE" ? t("finiteDrawPile") : t("autoRefill");
        const drawPileRuleOptions = computed(() => [
            {
                value: "AUTO_REFILL",
                label: t("autoRefill"),
                description: t("autoRefillDescription"),
                warning: t("autoRefillWarning")
            },
            {
                value: "FINITE_DRAW_PILE",
                label: t("finiteDrawPile"),
                description: t("finiteDrawPileDescription")
            }
        ]);
        const connectionLabel = computed(() => t(connectionMode.value));
        const cropImageStyle = computed(() => ({
            width: `${avatarBaseSize.value.width}px`,
            height: `${avatarBaseSize.value.height}px`,
            transform: `translate(-50%, -50%) translate(${avatarPan.value.x}px, ${avatarPan.value.y}px) scale(${avatarZoom.value})`
        }));

        let selectedAvatarImage = null;
        let avatarObjectUrl = "";
        let avatarDrag = null;

        const chooseAvatarFile = () => avatarInput.value?.click();

        const revokeAvatarObjectUrl = () => {
            if (avatarObjectUrl) URL.revokeObjectURL(avatarObjectUrl);
            avatarObjectUrl = "";
        };

        const closeAvatarEditor = () => {
            showAvatarEditor.value = false;
            avatarError.value = "";
            selectedAvatarImage = null;
            revokeAvatarObjectUrl();
            avatarSourceUrl.value = "";
        };

        const handleAvatarFile = (event) => {
            const file = event.target.files?.[0];
            event.target.value = "";
            if (!file) return;
            if (!/^image\/(jpeg|png|webp|gif)$/.test(file.type)) {
                errorMsg.value = t("invalidAvatarType");
                return;
            }
            if (file.size > 10 * 1024 * 1024) {
                errorMsg.value = t("avatarTooLarge");
                return;
            }
            revokeAvatarObjectUrl();
            avatarObjectUrl = URL.createObjectURL(file);
            avatarSourceUrl.value = avatarObjectUrl;
            avatarZoom.value = 1;
            avatarPan.value = { x: 0, y: 0 };
            avatarError.value = "";
            showAvatarEditor.value = true;
        };

        const initializeAvatarCrop = (event) => {
            selectedAvatarImage = event.target;
            const naturalWidth = selectedAvatarImage.naturalWidth;
            const naturalHeight = selectedAvatarImage.naturalHeight;
            if (!naturalWidth || !naturalHeight) {
                avatarError.value = t("avatarLoadFailed");
                return;
            }
            const stageSize = cropStage.value?.clientWidth || 280;
            const scale = Math.max(stageSize / naturalWidth, stageSize / naturalHeight);
            avatarBaseSize.value = {
                width: naturalWidth * scale,
                height: naturalHeight * scale,
                scale
            };
            avatarZoom.value = 1;
            avatarPan.value = { x: 0, y: 0 };
        };

        const clampAvatarPan = () => {
            const stageSize = cropStage.value?.clientWidth || 280;
            const maxX = Math.max(0, (avatarBaseSize.value.width * avatarZoom.value - stageSize) / 2);
            const maxY = Math.max(0, (avatarBaseSize.value.height * avatarZoom.value - stageSize) / 2);
            avatarPan.value = {
                x: Math.max(-maxX, Math.min(maxX, avatarPan.value.x)),
                y: Math.max(-maxY, Math.min(maxY, avatarPan.value.y))
            };
        };

        const startAvatarDrag = (event) => {
            if (!selectedAvatarImage) return;
            event.currentTarget.setPointerCapture?.(event.pointerId);
            avatarDrag = {
                pointerId: event.pointerId,
                startX: event.clientX,
                startY: event.clientY,
                originX: avatarPan.value.x,
                originY: avatarPan.value.y
            };
        };

        const moveAvatarDrag = (event) => {
            if (!avatarDrag || avatarDrag.pointerId !== event.pointerId) return;
            avatarPan.value = {
                x: avatarDrag.originX + event.clientX - avatarDrag.startX,
                y: avatarDrag.originY + event.clientY - avatarDrag.startY
            };
            clampAvatarPan();
        };

        const endAvatarDrag = (event) => {
            if (!avatarDrag || avatarDrag.pointerId !== event.pointerId) return;
            event.currentTarget.releasePointerCapture?.(event.pointerId);
            avatarDrag = null;
        };

        const canvasToBlob = (canvas, quality) => new Promise((resolve) => canvas.toBlob(resolve, "image/jpeg", quality));

        const saveAvatar = async () => {
            if (!selectedAvatarImage || avatarSaving.value) return;
            avatarSaving.value = true;
            avatarError.value = "";
            try {
                const outputSize = 256;
                const stageSize = cropStage.value?.clientWidth || 280;
                const canvas = document.createElement("canvas");
                canvas.width = outputSize;
                canvas.height = outputSize;
                const context = canvas.getContext("2d");
                context.fillStyle = "#ffffff";
                context.fillRect(0, 0, outputSize, outputSize);
                const outputScale = avatarBaseSize.value.scale * avatarZoom.value * outputSize / stageSize;
                const width = selectedAvatarImage.naturalWidth * outputScale;
                const height = selectedAvatarImage.naturalHeight * outputScale;
                const x = outputSize / 2 + avatarPan.value.x * outputSize / stageSize - width / 2;
                const y = outputSize / 2 + avatarPan.value.y * outputSize / stageSize - height / 2;
                context.drawImage(selectedAvatarImage, x, y, width, height);
                let blob = await canvasToBlob(canvas, 0.88);
                if (blob?.size > 400 * 1024) blob = await canvasToBlob(canvas, 0.75);
                if (!blob) throw new Error("canvas-export-failed");

                const form = new FormData();
                form.append("avatar", blob, "avatar.jpg");
                const response = await axios.post(`${apiBase}/user/avatar`, form);
                if (response.data.code !== 200 || !response.data.data?.avatarUrl) {
                    throw new Error(response.data.message || t("avatarSaveFailed"));
                }
                avatarUrl.value = response.data.data.avatarUrl;
                infoMsg.value = t("avatarSaved");
                closeAvatarEditor();
                await loadRooms();
                setTimeout(() => {
                    if (infoMsg.value === t("avatarSaved")) infoMsg.value = "";
                }, 3000);
            } catch (error) {
                avatarError.value = error.response?.data?.message || error.message || t("avatarSaveFailed");
            } finally {
                avatarSaving.value = false;
            }
        };

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
                avatarUrl.value = currentUser.avatarUrl || "";
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
                    roundTimeLimitMinutes: roundTimeLimitMinutes.value,
                    gameMode: gameMode.value,
                    drawPileRule: gameMode.value === "NO_MERCY" ? drawPileRule.value : "AUTO_REFILL"
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
            avatarUrl,
            avatarInput,
            cropStage,
            showAvatarEditor,
            avatarSourceUrl,
            avatarZoom,
            avatarSaving,
            avatarError,
            cropImageStyle,
            rooms,
            showCreate,
            maxPlayers,
            roundTimeLimitMinutes,
            gameMode,
            drawPileRule,
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
            drawPileRuleLabel,
            playerOptions,
            timeOptions,
            modeOptions,
            drawPileRuleOptions,
            chooseAvatarFile,
            handleAvatarFile,
            initializeAvatarCrop,
            clampAvatarPan,
            startAvatarDrag,
            moveAvatarDrag,
            endAvatarDrag,
            closeAvatarEditor,
            saveAvatar,
            createRoom,
            joinRoom,
            goToAdmin,
            logout
        };
    }
}).mount("#app");
