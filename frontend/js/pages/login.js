const { createApp, ref, reactive } = Vue;
const apiBase = "/api";

createApp({
    setup() {
        const isRegister = ref(false);
        const errorMsg = ref("");
        const successMsg = ref("");

        const loginForm = reactive({ username: "", password: "" });
        const registerForm = reactive({ username: "", password: "" });

        const login = async () => {
            errorMsg.value = "";
            successMsg.value = "";
            if (!loginForm.username || !loginForm.password) {
                errorMsg.value = "请填写完整信息";
                return;
            }
            try {
                const res = await axios.post(`${apiBase}/user/login`, loginForm);
                if (res.data.code === 200) {
                    localStorage.setItem("userId", res.data.data.id);
                    localStorage.setItem("username", res.data.data.username);
                    window.location.href = "lobby.html";
                } else {
                    errorMsg.value = res.data.message || "登录失败";
                }
            } catch (e) {
                errorMsg.value = "登录失败，请稍后重试";
            }
        };

        const register = async () => {
            errorMsg.value = "";
            successMsg.value = "";
            if (!registerForm.username || !registerForm.password) {
                errorMsg.value = "请填写完整信息";
                return;
            }
            try {
                const res = await axios.post(`${apiBase}/user/register`, registerForm);
                if (res.data.code === 200) {
                    successMsg.value = "注册成功，请登录";
                    isRegister.value = false;
                    registerForm.username = "";
                    registerForm.password = "";
                } else {
                    errorMsg.value = res.data.message || "注册失败";
                }
            } catch (e) {
                errorMsg.value = "注册失败，请稍后重试";
            }
        };

        return { isRegister, errorMsg, successMsg, loginForm, registerForm, login, register };
    }
}).mount("#app");
