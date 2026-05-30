const { createApp, ref, onMounted, onUnmounted, computed } = Vue;
const apiBase = '/api';

createApp({
    setup() {
        const username = ref(localStorage.getItem('username') || '');
        const rooms = ref([]);
        const showCreate = ref(false);
        const maxPlayers = ref(2);
        const errorMsg = ref('');
        const infoMsg = ref('');
        let roomsTimer = null;

        const isAdmin = computed(() => username.value.toLowerCase() === 'admin');

        const consumeLobbyNotice = () => {
            const notice = sessionStorage.getItem('lobbyNotice');
            if (notice) {
                infoMsg.value = notice;
                sessionStorage.removeItem('lobbyNotice');
                setTimeout(() => {
                    if (infoMsg.value === notice) {
                        infoMsg.value = '';
                    }
                }, 3500);
            }
        };

        const checkLogin = async () => {
            try {
                const res = await axios.get(`${apiBase}/user/me`);
                if (res.data.code !== 200) {
                    window.location.href = 'index.html';
                    return;
                }
                if (res.data.data?.username) {
                    username.value = res.data.data.username;
                    localStorage.setItem('username', res.data.data.username);
                }
                if (res.data.data?.id) {
                    localStorage.setItem('userId', res.data.data.id);
                }
            } catch (error) {
                window.location.href = 'index.html';
            }
        };

        const loadRooms = async () => {
            try {
                const res = await axios.get(`${apiBase}/room/list`);
                if (res.data.code === 200) {
                    rooms.value = res.data.data;
                } else {
                    errorMsg.value = res.data.message || '加载房间列表失败';
                }
            } catch (error) {
                errorMsg.value = '加载房间列表失败';
                setTimeout(() => errorMsg.value = '', 3000);
            }
        };

        const createRoom = async () => {
            try {
                const res = await axios.post(`${apiBase}/room/create`, { maxPlayers: maxPlayers.value });
                if (res.data.code === 200) {
                    showCreate.value = false;
                    window.location.href = `game.html?roomId=${res.data.data.id}`;
                } else {
                    errorMsg.value = res.data.message;
                }
            } catch (error) {
                errorMsg.value = '创建房间失败';
                setTimeout(() => errorMsg.value = '', 3000);
            }
        };

        const joinRoom = async (room) => {
            try {
                const res = await axios.post(`${apiBase}/game/${room.id}/join`);
                if (res.data.code === 200) {
                    window.location.href = `game.html?roomId=${room.id}`;
                } else {
                    errorMsg.value = res.data.message || '加入房间失败';
                }
            } catch (error) {
                errorMsg.value = error.response?.data?.message || '加入房间失败';
                setTimeout(() => errorMsg.value = '', 3000);
            }
        };

        const goToAdmin = () => {
            window.location.href = 'admin.html';
        };

        const logout = async () => {
            await axios.post(`${apiBase}/user/logout`);
            localStorage.removeItem('userId');
            localStorage.removeItem('username');
            sessionStorage.removeItem('lobbyNotice');
            window.location.href = 'index.html';
        };

        onMounted(async () => {
            await checkLogin();
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
            errorMsg,
            infoMsg,
            isAdmin,
            createRoom,
            joinRoom,
            goToAdmin,
            logout
        };
    }
}).mount('#app');
