const { createApp, ref, reactive, onMounted, onUnmounted } = Vue;
const apiBase = "/api";

createApp({
    setup() {
        const username = ref("");
        const rooms = ref([]);
        const errorMsg = ref("");
        const successMsg = ref("");
        const showEdit = ref(false);
        const editingRoom = ref(null);
        const savingEdit = ref(false);
        const selectedRoomIds = ref([]);
        const deletingRooms = ref(false);
        const playerOptions = [2, 3, 4, 5, 6, 7, 8];
        const timeOptions = [5, 10, 15];
        const editForm = reactive({
            maxPlayers: 2,
            roundTimeLimitMinutes: 10,
            gameMode: "CLASSIC"
        });
        let refreshTimer = null;

        const clearMessages = () => {
            errorMsg.value = "";
            successMsg.value = "";
        };

        const flashSuccess = (message) => {
            successMsg.value = message;
            setTimeout(() => {
                if (successMsg.value === message) successMsg.value = "";
            }, 2500);
        };

        const checkAdmin = async () => {
            localStorage.removeItem("userId");
            localStorage.removeItem("username");
            try {
                const res = await axios.get(`${apiBase}/user/me`);
                if (res.data.code !== 200 || String(res.data.data?.username || "").toLowerCase() !== "admin") {
                    window.location.href = "lobby.html";
                    return false;
                }
                username.value = res.data.data.username;
                localStorage.setItem("userId", res.data.data.id);
                localStorage.setItem("username", res.data.data.username);
                return true;
            } catch (error) {
                window.location.href = "index.html";
                return false;
            }
        };

        const formatTime = (value) => {
            if (!value) return "-";
            return String(value).replace("T", " ").slice(0, 19);
        };

        const statusLabel = (status) => {
            if (status === "WAITING") return "等待中";
            if (status === "PLAYING") return "游戏中";
            if (status === "CLOSED") return "已关闭";
            return status || "-";
        };

        const modeLabel = (mode) => mode === "NO_MERCY" ? "No Mercy" : "经典";

        const loadRooms = async () => {
            try {
                const res = await axios.get(`${apiBase}/admin/rooms`);
                if (res.data.code === 200) {
                    rooms.value = res.data.data || [];
                    const roomIds = new Set(rooms.value.map((room) => String(room.roomId)));
                    selectedRoomIds.value = selectedRoomIds.value.filter((roomId) => roomIds.has(String(roomId)));
                    errorMsg.value = "";
                } else {
                    errorMsg.value = res.data.message || "加载房间失败";
                }
            } catch (error) {
                errorMsg.value = error.response?.data?.message || "加载房间失败";
                if (error.response?.status === 403) {
                    setTimeout(() => backToLobby(), 1000);
                }
            }
        };

        const openEditRoom = (room) => {
            clearMessages();
            if (room.status !== "WAITING") {
                errorMsg.value = "只有等待中的房间可以编辑";
                return;
            }
            editingRoom.value = room;
            editForm.maxPlayers = Number(room.maxPlayers || 2);
            editForm.roundTimeLimitMinutes = Number(room.roundTimeLimitMinutes || 10);
            editForm.gameMode = room.gameMode || "CLASSIC";
            showEdit.value = true;
        };

        const closeEditRoom = () => {
            showEdit.value = false;
            editingRoom.value = null;
            savingEdit.value = false;
        };

        const saveRoomEdit = async () => {
            if (!editingRoom.value || savingEdit.value) return;
            clearMessages();
            savingEdit.value = true;
            try {
                const res = await axios.put(`${apiBase}/admin/rooms/${editingRoom.value.roomId}`, {
                    maxPlayers: editForm.maxPlayers,
                    roundTimeLimitMinutes: editForm.roundTimeLimitMinutes,
                    gameMode: editForm.gameMode
                });
                if (res.data.code === 200) {
                    closeEditRoom();
                    flashSuccess("房间配置已更新");
                    await loadRooms();
                } else {
                    errorMsg.value = res.data.message || "更新房间失败";
                }
            } catch (error) {
                errorMsg.value = error.response?.data?.message || "更新房间失败";
            } finally {
                savingEdit.value = false;
            }
        };

        const deleteRoom = async (room) => {
            const confirmed = window.confirm(`确定删除房间 ${room.roomCode} 吗？`);
            if (!confirmed) return;
            clearMessages();

            try {
                const res = await axios.delete(`${apiBase}/admin/rooms/${room.roomId}`);
                if (res.data.code === 200) {
                    flashSuccess(`房间 ${room.roomCode} 已删除`);
                    await loadRooms();
                } else {
                    errorMsg.value = res.data.message || "删除房间失败";
                }
            } catch (error) {
                errorMsg.value = error.response?.data?.message || "删除房间失败";
            }
        };

        const deleteRoomBatch = async (targets, message) => {
            if (!targets.length || deletingRooms.value || !window.confirm(message)) return;
            clearMessages();
            deletingRooms.value = true;
            try {
                for (const room of targets) {
                    const res = await axios.delete(`${apiBase}/admin/rooms/${room.roomId}`);
                    if (res.data.code !== 200) throw new Error(res.data.message || "删除房间失败");
                }
                selectedRoomIds.value = [];
                flashSuccess(`已删除 ${targets.length} 个房间`);
            } catch (error) {
                errorMsg.value = error.response?.data?.message || error.message || "删除房间失败";
            } finally {
                deletingRooms.value = false;
                await loadRooms();
            }
        };

        const deleteSelectedRooms = () => {
            const selected = new Set(selectedRoomIds.value.map(String));
            return deleteRoomBatch(rooms.value.filter((room) => selected.has(String(room.roomId))), "确定删除选中的房间吗？");
        };
        const deleteAllRooms = () => deleteRoomBatch([...rooms.value], "确定删除全部房间吗？");

        const backToLobby = () => {
            window.location.href = "lobby.html";
        };

        onMounted(async () => {
            const allowed = await checkAdmin();
            if (!allowed) return;
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
            showEdit,
            editingRoom,
            savingEdit,
            selectedRoomIds,
            deletingRooms,
            editForm,
            playerOptions,
            timeOptions,
            formatTime,
            statusLabel,
            modeLabel,
            loadRooms,
            openEditRoom,
            closeEditRoom,
            saveRoomEdit,
            deleteRoom,
            deleteSelectedRooms,
            deleteAllRooms,
            backToLobby
        };
    }
}).mount("#app");
