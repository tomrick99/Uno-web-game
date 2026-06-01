const { createApp, ref, onMounted, onUnmounted, computed } = Vue;
const apiBase = "/api";

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
        let roomsTimer = null;

        const messages = {
            zh: {
                admin: "管理",
                logout: "退出",
                gameLobby: "游戏大厅",
                createRoom: "创建房间",
                emptyRooms: "暂无等待中的房间，创建一个开始游戏。",
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
                roundTime: "单局时间",
                mode: "模式",
                cancel: "取消",
                create: "创建",
                failedLoadRooms: "加载房间失败",
                failedCreateRoom: "创建房间失败",
                failedJoinRoom: "加入房间失败",
                classic: "经典"
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
                classic: "Classic"
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
                return true;
            } catch (error) {
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
                setTimeout(() => errorMsg.value = "", 3000);
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
                if (res.data.code === 200) {
                    showCreate.value = false;
                    window.location.href = `game.html?roomId=${res.data.data.id}`;
                } else {
                    errorMsg.value = res.data.message || t("failedCreateRoom");
                }
            } catch (error) {
                errorMsg.value = error.response?.data?.message || t("failedCreateRoom");
                setTimeout(() => errorMsg.value = "", 3000);
            }
        };

        const joinRoom = async (room) => {
            try {
                const res = await axios.post(`${apiBase}/game/${room.id}/join`);
                if (res.data.code === 200) {
                    window.location.href = `game.html?roomId=${room.id}`;
                } else {
                    errorMsg.value = res.data.message || t("failedJoinRoom");
                }
            } catch (error) {
                errorMsg.value = error.response?.data?.message || t("failedJoinRoom");
                setTimeout(() => errorMsg.value = "", 3000);
            }
        };

        const goToAdmin = () => {
            window.location.href = "admin.html";
        };

        const logout = async () => {
            await axios.post(`${apiBase}/user/logout`);
            localStorage.removeItem("userId");
            localStorage.removeItem("username");
            sessionStorage.removeItem("lobbyNotice");
            window.location.href = "index.html";
        };

        onMounted(async () => {
            applyLanguage();
            const loggedIn = await checkLogin();
            if (!loggedIn) return;
            consumeLobbyNotice();
            await loadRooms();
            roomsTimer = setInterval(loadRooms, 3000);
        });

        onUnmounted(() => {
            if (roomsTimer) {
                clearInterval(roomsTimer);
                roomsTimer = null;
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
