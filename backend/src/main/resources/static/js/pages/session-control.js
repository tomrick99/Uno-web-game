(function () {
    const REPLACED_TYPE = "SESSION_REPLACED";
    const REPLACED_MESSAGE = "This account was signed in on another device. You have been signed out.";
    const NOTICE_KEY = "unoSessionReplacementNotice";
    const CLOSE_CODE = 4001;
    let handlingReplacement = false;

    const handleReplacement = (message) => {
        if (handlingReplacement) return true;
        handlingReplacement = true;
        const notice = message || REPLACED_MESSAGE;
        sessionStorage.setItem(NOTICE_KEY, notice);
        localStorage.removeItem("userId");
        localStorage.removeItem("username");
        window.location.replace("index.html");
        return true;
    };

    const installAxiosInterceptor = (axiosInstance) => {
        if (!axiosInstance?.interceptors?.response || axiosInstance.__unoSessionControlInstalled) return;
        axiosInstance.__unoSessionControlInstalled = true;
        axiosInstance.interceptors.response.use(
            (response) => response,
            (error) => {
                const response = error?.response;
                const replaced = response?.status === 401
                    && String(response?.headers?.["x-session-replaced"] || "").toLowerCase() === "true";
                if (replaced) handleReplacement(response?.data?.message);
                return Promise.reject(error);
            }
        );
    };

    const subscribe = (client) => client.subscribe("/user/queue/session", (frame) => {
        try {
            const payload = JSON.parse(frame.body || "{}");
            if (payload.type === REPLACED_TYPE) handleReplacement(payload.message);
        } catch (error) {
            console.error("[UNO-SESSION] invalid replacement event", error);
        }
    });

    const handleSocketClose = (event) => {
        if (Number(event?.code) !== CLOSE_CODE) return false;
        return handleReplacement(event?.reason || REPLACED_MESSAGE);
    };

    const consumeNotice = () => {
        const notice = sessionStorage.getItem(NOTICE_KEY);
        if (notice) sessionStorage.removeItem(NOTICE_KEY);
        return notice;
    };

    window.UnoSessionControl = {
        installAxiosInterceptor,
        subscribe,
        handleSocketClose,
        consumeNotice
    };
})();
