const { createApp, ref, onMounted, onUnmounted } = Vue;
const apiBase = '/api';

createApp({
    setup() {
        const username = ref(localStorage.getItem('username') || '');
        const rooms = ref([]);
        const errorMsg = ref('');
        const successMsg = ref('');
        let refreshTimer = null;

        const formatTime = (value) => {
            if (!value) return '-';
            return String(value).replace('T', ' ').slice(0, 19);
        };

        const loadRooms = async () => {
            try {
                const res = await axios.get(`${apiBase}/admin/rooms`);
                if (res.data.code === 200) {
                    rooms.value = res.data.data;
                } else {
                    errorMsg.value = res.data.message || '加载房间失败';
                }
            } catch (error) {
                errorMsg.value = error.response?.data?.message || '加载房间失败';
                if (error.response?.status === 403) {
                    setTimeout(() => backToLobby(), 1000);
                }
            }
        };

        const deleteRoom = async (room) => {
            const confirmed = window.confirm(`确定删除房间 ${room.roomCode} 吗？`);
            if (!confirmed) return;

            try {
                const res = await axios.delete(`${apiBase}/admin/rooms/${room.roomId}`);
                if (res.data.code === 200) {
                    successMsg.value = `房间 ${room.roomCode} 已删除`;
                    await loadRooms();
                    setTimeout(() => {
                        if (successMsg.value.includes(room.roomCode)) {
                            successMsg.value = '';
                        }
                    }, 2500);
                } else {
                    errorMsg.value = res.data.message || '删除房间失败';
                }
            } catch (error) {
                errorMsg.value = error.response?.data?.message || '删除房间失败';
            }
        };

        const backToLobby = () => {
            window.location.href = 'lobby.html';
        };

        onMounted(async () => {
            await loadRooms();
            refreshTimer = setInterval(loadRooms, 5000);
        });

        onUnmounted(() => {
            if (refreshTimer) {
                clearInterval(refreshTimer);
                refreshTimer = null;
            }
        });

        return {
            username,
            rooms,
            errorMsg,
            successMsg,
            formatTime,
            loadRooms,
            deleteRoom,
            backToLobby
        };
    }
}).mount('#app');
