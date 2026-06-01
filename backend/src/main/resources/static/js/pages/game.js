const { createApp, ref, computed, onMounted, onUnmounted } = Vue;
const apiBase = "/api";

createApp({
    setup() {
        const roomId = ref(new URLSearchParams(window.location.search).get("roomId"));
        const roomCode = ref("");
        const roomStatus = ref("WAITING");
        const maxPlayers = ref(2);
        const totalRounds = ref(8);
        const roundTimeLimitMinutes = ref(10);
        const gameMode = ref("CLASSIC");
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
        const drawPileSize = ref(0);
        const topCard = ref(null);
        const handCards = ref([]);
        const tablePlayers = ref([]);
        const opponents = ref([]);
        const currentPlayerName = ref("");
        const selectedCard = ref(null);
        const needsColorPick = ref(false);
        const chosenColor = ref("");
        const gameLog = ref([]);
        const logExpanded = ref(false);
        const rulesExpanded = ref(false);
        const gameResult = ref(null);
        const toastMsg = ref("");
        const language = ref(localStorage.getItem("unoLanguage") || "zh");
        const wsConnected = ref(false);
        const leavingRoom = ref(false);
        const restartingGame = ref(false);
        const drawingPenalty = ref(false);
        const hasLoadedHand = ref(false);
        const autoPenaltyInProgress = ref(false);
        const lastAutoPenaltyKey = ref("");

        const stompClient = ref(null);
        const roomSubscription = ref(null);
        const gameSubscription = ref(null);
        const handSubscription = ref(null);
        const subscribedGameId = ref(null);

        let reconnectTimer = null;
        let pollTimer = null;
        let autoPenaltyTimer = null;
        let delegatedButtonHandler = null;
        let beforeUnloadHandler = null;

        const colorMap = {
            RED: "#E74C3C",
            BLUE: "#3498DB",
            GREEN: "#2ECC71",
            YELLOW: "#F39C12"
        };

        const messages = {
            zh: {
                room: "房间",
                direction: "方向",
                mode: "模式",
                players: "玩家",
                cardsUnit: "张",
                me: "我",
                rounds: "局数",
                currentColor: "当前颜色",
                backToLobby: "返回大厅",
                canDraw: "可以抽牌",
                cannotDraw: "现在不能抽牌",
                clickDraw: "点击抽牌",
                drawPile: "抽牌堆",
                discardPile: "弃牌堆",
                waitingStart: "等待开始...",
                selectedCard: "选中牌",
                rules: "规则",
                gameLog: "游戏日志",
                logHelp: "日志只记录最近动作，方便回看和排查；具体规则以上方说明为准。",
                playCard: "出牌",
                winSubtitle: "本局获胜，准备再来一局吗？",
                loseSubtitle: "本局结束，可以返回大厅或再来一局。",
                connected: "实时已连接",
                polling: "实时离线，轮询中",
                clockwise: "顺时针",
                counterClockwise: "逆时针",
                classic: "经典",
                red: "红",
                yellow: "黄",
                green: "绿",
                blue: "蓝",
                playable: "可以出",
                notPlayable: "不能出",
                chooseColor: "请选择颜色后出牌。",
                gameNotPlaying: "游戏尚未开始",
                notYourTurn: "还没轮到你",
                yourTurn: "你的回合",
                waitingPlayers: "等待玩家",
                turn: "轮到",
                gameFinished: "游戏结束",
                drawStack: "回应罚牌",
                drawCards: "抽牌",
                drawing: "抽牌中...",
                acceptPenalty: "接受罚牌",
                noStackable: "没有可叠加的罚牌，将自动抽 {count} 张。",
                pendingEqualHigher: "待罚 {count} 张；只能叠加不小于上一张罚牌点数的罚牌。",
                pendingPlus4: "待罚 {count} 张；只能叠加 +4。",
                pendingPlus2: "待罚 {count} 张；只能叠加 +2。",
                rematchReadyBoth: "双方都已准备，正在重开...",
                rematchReadyYou: "你已准备，等待对方。",
                rematchReadyOther: "对方已准备。",
                rematchNeedBoth: "双方都选择再来一局后才会开始。",
                ready: "已准备",
                submitting: "提交中...",
                rematch: "再来一局",
                gameOver: "游戏结束",
                wins: "获胜",
                cardNumber: "数字牌：颜色相同或数字相同即可出。",
                cardSkip: "跳过下一位玩家。",
                cardReverse: "反转出牌方向。",
                cardDrawTwo: "+2：下一位抽 2 张；Classic 中可按当前规则叠加。",
                cardDrawFour: "+4：No Mercy 彩色罚牌，下一位抽 4 张。",
                cardDiscardAll: "DROP：打出后丢弃你手中所有同色牌。",
                cardSkipAll: "SKIP ALL：跳过其他所有玩家，立刻回到你。",
                cardWild: "万能牌：选择下一种颜色。",
                cardWildDrawFour: "+4 黑牌：选择颜色，下一位抽 4 张。",
                cardWildDrawSix: "+6 黑牌：选择颜色，下一位抽 6 张。",
                cardWildDrawTen: "+10 黑牌：选择颜色，下一位抽 10 张。",
                cardWildReverseDrawFour: "+4 REV 黑牌：选择颜色，反转方向，下一位抽 4 张。",
                classicRule1: "数字牌按颜色或数字匹配。",
                classicRule2: "功能牌按颜色或同类型匹配，万能牌需要选色。",
                classicRule3: "+2 和 +4 使用 Classic 叠加规则，Classic 不包含 No Mercy 新牌。",
                noMercyRule1: "新增 DROP、SKIP ALL、彩色 +4，以及黑色 +6、+10、+4 REV。",
                noMercyRule2: "黑色罚牌点击后需要选择颜色，牌面只显示点数。",
                noMercyRule3: "罚牌叠加必须不小于上一张罚牌：+6 只能接 +6/+10，+10 只能接 +10。"
            },
            en: {
                room: "Room",
                direction: "Direction",
                mode: "Mode",
                players: "Players",
                cardsUnit: "cards",
                me: "Me",
                rounds: "Rounds",
                currentColor: "Color",
                backToLobby: "Back to Lobby",
                canDraw: "You can draw",
                cannotDraw: "You cannot draw now",
                clickDraw: "Click to draw",
                drawPile: "Draw pile",
                discardPile: "Discard pile",
                waitingStart: "Waiting...",
                selectedCard: "Selected card",
                rules: "Rules",
                gameLog: "Game Log",
                logHelp: "The log records recent actions for review; use the rules panel above for card meaning.",
                playCard: "Play",
                winSubtitle: "You won this game. Ready for another round?",
                loseSubtitle: "Game over. You can return to the lobby or rematch.",
                connected: "Realtime connected",
                polling: "Realtime offline, polling",
                clockwise: "Clockwise",
                counterClockwise: "Counter-clockwise",
                classic: "Classic",
                red: "Red",
                yellow: "Yellow",
                green: "Green",
                blue: "Blue",
                playable: "Playable",
                notPlayable: "Not playable",
                chooseColor: "Choose a color before playing.",
                gameNotPlaying: "Game is not playing",
                notYourTurn: "Not your turn",
                yourTurn: "Your turn",
                waitingPlayers: "Waiting for players",
                turn: "Turn",
                gameFinished: "Game finished",
                drawStack: "Respond to draw stack",
                drawCards: "Draw cards",
                drawing: "Drawing...",
                acceptPenalty: "Accept penalty",
                noStackable: "No stackable draw card. Drawing {count} cards automatically.",
                pendingEqualHigher: "Pending draw: {count}. Stack a draw card equal to or higher than the last penalty.",
                pendingPlus4: "Pending draw: {count}. Stack +4 only.",
                pendingPlus2: "Pending draw: {count}. Stack +2 only.",
                rematchReadyBoth: "Both players are ready. Restarting...",
                rematchReadyYou: "You are ready. Waiting for the other player.",
                rematchReadyOther: "The other player is ready.",
                rematchNeedBoth: "Both players must choose rematch before the next game starts.",
                ready: "Ready",
                submitting: "Submitting...",
                rematch: "Rematch",
                gameOver: "Game over",
                wins: "wins",
                cardNumber: "Number: play on matching color or matching number.",
                cardSkip: "Skip the next player.",
                cardReverse: "Reverse the play direction.",
                cardDrawTwo: "+2: next player draws 2; Classic stacking stays unchanged.",
                cardDrawFour: "+4: No Mercy colored penalty; next player draws 4.",
                cardDiscardAll: "DROP: discard every card of this color from your hand.",
                cardSkipAll: "SKIP ALL: skip everyone else and return the turn to you.",
                cardWild: "Wild: choose the next color.",
                cardWildDrawFour: "+4 black card: choose a color; next player draws 4.",
                cardWildDrawSix: "+6 black card: choose a color; next player draws 6.",
                cardWildDrawTen: "+10 black card: choose a color; next player draws 10.",
                cardWildReverseDrawFour: "+4 REV black card: choose a color, reverse direction, next player draws 4.",
                classicRule1: "Number cards match by color or number.",
                classicRule2: "Action cards match by color or type; wild cards require a color.",
                classicRule3: "+2 and +4 use Classic stacking; Classic does not include No Mercy cards.",
                noMercyRule1: "Adds DROP, SKIP ALL, colored +4, and black +6, +10, +4 REV.",
                noMercyRule2: "Black penalty cards require color selection; the card face only shows the penalty.",
                noMercyRule3: "Draw stacks must be equal or higher than the last penalty: +6 accepts +6/+10, +10 accepts +10."
            }
        };

        const t = (key, params = {}) => {
            let text = messages[language.value]?.[key] || messages.en[key] || key;
            for (const [name, value] of Object.entries(params)) {
                text = text.replace(`{${name}}`, value);
            }
            return text;
        };

        const applyLanguage = () => {
            document.documentElement.lang = language.value === "zh" ? "zh-CN" : "en";
        };

        const toggleLanguage = () => {
            language.value = language.value === "zh" ? "en" : "zh";
            localStorage.setItem("unoLanguage", language.value);
            applyLanguage();
            refreshHandPlayability();
        };

        const wildTypes = new Set([
            "WILD",
            "WILD_DRAW_FOUR",
            "WILD_DRAW_SIX",
            "WILD_DRAW_TEN",
            "WILD_REVERSE_DRAW_FOUR"
        ]);
        const drawPenaltyTypes = new Set([
            "DRAW_TWO",
            "DRAW_FOUR",
            "WILD_DRAW_FOUR",
            "WILD_DRAW_SIX",
            "WILD_DRAW_TEN",
            "WILD_REVERSE_DRAW_FOUR"
        ]);

        const isWildType = (type) => wildTypes.has(type);
        const isDrawPenaltyType = (type) => drawPenaltyTypes.has(type);
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
        const playerCount = computed(() =>
            tablePlayers.value.length || opponents.value.length + 1
        );

        const languageLabel = computed(() => language.value === "zh" ? "EN" : "中文");
        const directionLabel = computed(() => direction.value === 1 ? t("clockwise") : t("counterClockwise"));
        const gameModeLabel = computed(() => gameMode.value === "NO_MERCY" ? "No Mercy" : t("classic"));
        const colorName = (color) => {
            if (color === "RED") return t("red");
            if (color === "YELLOW") return t("yellow");
            if (color === "GREEN") return t("green");
            if (color === "BLUE") return t("blue");
            return color;
        };
        const currentColorLabel = computed(() => colorName(currentColor.value));
        const connectionLabel = computed(() =>
            wsConnected.value ? t("connected") : t("polling")
        );

        const getCardDisplay = (type, value) => {
            if (type === "NUMBER") return String(value);
            if (type === "SKIP") return "SKIP";
            if (type === "REVERSE") return "REV";
            if (type === "DRAW_TWO") return "+2";
            if (type === "DRAW_FOUR") return "+4";
            if (type === "DISCARD_ALL_COLOR") return "DROP";
            if (type === "SKIP_ALL") return "SKIP ALL";
            if (type === "WILD") return "WILD";
            if (type === "WILD_DRAW_FOUR") return "+4";
            if (type === "WILD_DRAW_SIX") return "+6";
            if (type === "WILD_DRAW_TEN") return "+10";
            if (type === "WILD_REVERSE_DRAW_FOUR") return "+4 REV";
            return "?";
        };

        const getPenaltyValue = (type) => {
            if (type === "DRAW_TWO") return 2;
            if (type === "DRAW_FOUR" || type === "WILD_DRAW_FOUR" || type === "WILD_REVERSE_DRAW_FOUR") return 4;
            if (type === "WILD_DRAW_SIX") return 6;
            if (type === "WILD_DRAW_TEN") return 10;
            return 0;
        };

        const canStackNoMercyPenalty = (card, discardTopCard) => {
            const candidate = getPenaltyValue(card?.type);
            if (candidate <= 0) return false;
            return candidate >= getPenaltyValue(discardTopCard?.type);
        };

        const formatCard = (card) => {
            if (!card) return "none";
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
            if (type === "WILD_DRAW_SIX") return 2;
            if (type === "WILD_DRAW_TEN") return 3;
            if (type === "WILD_REVERSE_DRAW_FOUR") return 4;
            if (type === "DRAW_TWO") return 5;
            if (type === "DRAW_FOUR") return 6;
            if (type === "DISCARD_ALL_COLOR") return 7;
            if (type === "SKIP_ALL") return 8;
            if (type === "SKIP") return 9;
            if (type === "REVERSE") return 10;
            return 20;
        };

        const sortHandCards = (cards) => [...(cards || [])].sort((left, right) => {
            const leftGroup = isWildType(left.type) ? 0 : 1;
            const rightGroup = isWildType(right.type) ? 0 : 1;
            if (leftGroup !== rightGroup) return leftGroup - rightGroup;

            const colorDiff = colorRank(left.color) - colorRank(right.color);
            if (colorDiff !== 0) return colorDiff;

            const typeDiff = typeRank(left.type) - typeRank(right.type);
            if (typeDiff !== 0) return typeDiff;

            return Number(left.value) - Number(right.value);
        });

        const getPendingReason = (card) => {
            if (pendingDrawType.value === "WILD_DRAW_FOUR_CHAIN") {
                return card.type === "WILD_DRAW_FOUR"
                    ? { canPlay: true, reason: "pending +4 chain allows +4" }
                    : { canPlay: false, reason: "pending +4 chain only allows +4" };
            }
            if (pendingDrawType.value === "DRAW_STACK") {
                return canStackNoMercyPenalty(card, topCard.value)
                    ? { canPlay: true, reason: "pending draw allows equal or higher draw penalty card" }
                    : { canPlay: false, reason: "pending draw only allows equal or higher draw penalty cards" };
            }
            if (pendingDrawType.value === "DRAW_TWO_CHAIN") {
                if (card.type === "DRAW_TWO") return { canPlay: true, reason: "pending +2 chain allows +2" };
                return { canPlay: false, reason: "pending +2 chain only allows +2" };
            }
            return { canPlay: false, reason: "unknown pending draw state" };
        };

        const hasStackablePenaltyCard = (cards, type) => {
            if (!Array.isArray(cards) || !cards.length) return false;
            if (type === "WILD_DRAW_FOUR_CHAIN") {
                return cards.some((card) => card?.type === "WILD_DRAW_FOUR");
            }
            if (type === "DRAW_STACK") {
                return cards.some((card) => canStackNoMercyPenalty(card, topCard.value));
            }
            if (type === "DRAW_TWO_CHAIN") {
                return cards.some((card) => card?.type === "DRAW_TWO");
            }
            return false;
        };

        const getPendingDrawStateKey = () => {
            if (!isPendingDrawStack.value) return "";
            return [
                gameId.value ?? "no-game",
                currentTurn.value ?? "no-turn",
                pendingDrawType.value,
                Number(pendingDrawCount.value || 0),
                formatCard(topCard.value)
            ].join("|");
        };

        const evaluatePlayability = (card, discardTopCard, activeColor) => {
            if (!card || !card.type || !card.color) return { canPlay: false, reason: "invalid card" };
            if (gameStatus.value !== "PLAYING") return { canPlay: false, reason: "game not playing" };
            if (!isMyTurn.value) return { canPlay: false, reason: "not current player" };
            if (isPendingDrawStack.value) return getPendingReason(card);
            if (isWildType(card.type)) return { canPlay: true, reason: "wild card" };
            if (card.color === activeColor) return { canPlay: true, reason: "color match" };
            if (!discardTopCard) return { canPlay: true, reason: "no top card" };
            if (isNumberType(card.type) && isNumberType(discardTopCard.type)
                    && Number(card.value) === Number(discardTopCard.value)) {
                return { canPlay: true, reason: "number match" };
            }
            if (!isNumberType(card.type) && !isNumberType(discardTopCard.type) && card.type === discardTopCard.type) {
                return { canPlay: true, reason: "type match" };
            }
            return { canPlay: false, reason: "color/number/type mismatch" };
        };

        const getCardStateClass = (card) => {
            if (gameStatus.value !== "PLAYING" || !isMyTurn.value) return "disabled";
            return evaluatePlayability(card, topCard.value, currentColor.value).canPlay ? "playable" : "unplayable";
        };

        const getCardHint = (card) => {
            const result = evaluatePlayability(card, topCard.value, currentColor.value);
            if (gameStatus.value !== "PLAYING") return t("gameNotPlaying");
            if (!isMyTurn.value) return t("notYourTurn");
            return result.canPlay ? t("playable") : getReasonText(result.reason);
        };

        const getReasonText = (reason) => {
            const reasonMap = {
                "invalid card": language.value === "zh" ? "无效卡牌" : "Invalid card",
                "game not playing": t("gameNotPlaying"),
                "not current player": t("notYourTurn"),
                "pending +4 chain allows +4": language.value === "zh" ? "可以叠加 +4" : "You can stack +4",
                "pending +4 chain only allows +4": language.value === "zh" ? "当前只能叠加 +4" : "Only +4 can be stacked now",
                "pending draw allows equal or higher draw penalty card": language.value === "zh" ? "可以叠加不小于上一张的罚牌" : "You can stack an equal or higher draw penalty",
                "pending draw only allows equal or higher draw penalty cards": language.value === "zh" ? "只能叠加不小于上一张的罚牌" : "Only equal or higher draw penalties can be stacked",
                "pending +2 chain allows +2": language.value === "zh" ? "可以叠加 +2" : "You can stack +2",
                "pending +2 chain only allows +2": language.value === "zh" ? "当前只能叠加 +2" : "Only +2 can be stacked now",
                "unknown pending draw state": language.value === "zh" ? "未知罚牌状态" : "Unknown draw state",
                "wild card": t("playable"),
                "color match": language.value === "zh" ? "颜色匹配" : "Color match",
                "no top card": t("playable"),
                "number match": language.value === "zh" ? "数字匹配" : "Number match",
                "type match": language.value === "zh" ? "类型匹配" : "Type match",
                "color/number/type mismatch": language.value === "zh" ? "颜色、数字或类型不匹配" : "Color, number, or type does not match"
            };
            return reasonMap[reason] || reason || t("notPlayable");
        };

        const getCardDescription = (card) => {
            if (!card) return "";
            if (card.type === "NUMBER") return t("cardNumber");
            if (card.type === "SKIP") return t("cardSkip");
            if (card.type === "REVERSE") return t("cardReverse");
            if (card.type === "DRAW_TWO") return t("cardDrawTwo");
            if (card.type === "DRAW_FOUR") return t("cardDrawFour");
            if (card.type === "DISCARD_ALL_COLOR") return t("cardDiscardAll");
            if (card.type === "SKIP_ALL") return t("cardSkipAll");
            if (card.type === "WILD") return t("cardWild");
            if (card.type === "WILD_DRAW_FOUR") return t("cardWildDrawFour");
            if (card.type === "WILD_DRAW_SIX") return t("cardWildDrawSix");
            if (card.type === "WILD_DRAW_TEN") return t("cardWildDrawTen");
            if (card.type === "WILD_REVERSE_DRAW_FOUR") return t("cardWildReverseDrawFour");
            return "";
        };

        const decorateCard = (card) => ({
            ...card,
            display: getCardDisplay(card.type, card.value),
            stateClass: getCardStateClass(card),
            hint: getCardHint(card)
        });

        const modeRuleLines = computed(() => gameMode.value === "NO_MERCY"
            ? [t("noMercyRule1"), t("noMercyRule2"), t("noMercyRule3")]
            : [t("classicRule1"), t("classicRule2"), t("classicRule3")]
        );

        const selectedCardInfo = computed(() => {
            if (selectedCard.value === null) return null;
            const card = handCards.value[selectedCard.value];
            if (!card) return null;
            const result = evaluatePlayability(card, topCard.value, currentColor.value);
            return {
                display: getCardDisplay(card.type, card.value),
                description: getCardDescription(card),
                canPlay: result.canPlay,
                playHint: result.canPlay ? t("playable") : `${t("notPlayable")}: ${getReasonText(result.reason)}`,
                colorHint: isWildType(card.type) && result.canPlay && !chosenColor.value ? t("chooseColor") : ""
            };
        });

        const hasPlayablePenaltyResponse = computed(() =>
            hasStackablePenaltyCard(handCards.value, pendingDrawType.value)
        );

        const getPenaltyNoticeText = () => {
            if (!isPendingDrawStack.value || !isMyTurn.value || !hasLoadedHand.value) return "";
            if (!hasPlayablePenaltyResponse.value) {
                return t("noStackable", { count: pendingDrawCount.value });
            }
            if (pendingDrawType.value === "WILD_DRAW_FOUR_CHAIN") {
                return t("pendingPlus4", { count: pendingDrawCount.value });
            }
            if (pendingDrawType.value === "DRAW_STACK") {
                return t("pendingEqualHigher", { count: pendingDrawCount.value });
            }
            return t("pendingPlus2", { count: pendingDrawCount.value });
        };

        const showPenaltyNotice = computed(() => Boolean(getPenaltyNoticeText()));
        const penaltyNoticeText = computed(() => getPenaltyNoticeText());
        const showDrawPenaltyButton = computed(() => showPenaltyNotice.value && hasPlayablePenaltyResponse.value);
        const drawPenaltyButtonText = computed(() =>
            drawingPenalty.value ? t("drawing") : `${t("acceptPenalty")}: ${pendingDrawCount.value}`
        );

        const canPlaySelected = computed(() => {
            if (selectedCard.value === null) return false;
            const card = handCards.value[selectedCard.value];
            if (!card) return false;
            const result = evaluatePlayability(card, topCard.value, currentColor.value);
            return result.canPlay && (!needsColorPick.value || Boolean(chosenColor.value));
        });

        const turnLabel = computed(() => {
            if (gameStatus.value === "FINISHED") return t("gameFinished");
            if (gameStatus.value !== "PLAYING") {
                return `${t("waitingPlayers")} (${playerCount.value}/${maxPlayers.value})`;
            }
            if (showPenaltyNotice.value) {
                return hasPlayablePenaltyResponse.value
                    ? `${t("drawStack")} (${pendingDrawCount.value})`
                    : `${t("drawCards")} ${pendingDrawCount.value}`;
            }
            return isMyTurn.value ? t("yourTurn") : `${t("turn")}: ${currentPlayerName.value}`;
        });

        const showToast = (message) => {
            if (!message) return;
            toastMsg.value = message;
            window.setTimeout(() => {
                if (toastMsg.value === message) toastMsg.value = "";
            }, 3000);
        };

        const addLog = (message) => {
            if (!message) return;
            gameLog.value.unshift(message);
            if (gameLog.value.length > 50) gameLog.value.pop();
        };

        const clearCardSelection = () => {
            selectedCard.value = null;
            needsColorPick.value = false;
            chosenColor.value = "";
        };

        const clearAutoPenaltyTimer = () => {
            if (autoPenaltyTimer) {
                clearTimeout(autoPenaltyTimer);
                autoPenaltyTimer = null;
            }
        };

        const refreshHandPlayability = () => {
            handCards.value = sortHandCards(handCards.value).map(decorateCard);
            if (selectedCard.value === null) return;
            const selected = handCards.value[selectedCard.value];
            if (!selected || selected.stateClass !== "playable") {
                clearCardSelection();
                return;
            }
            needsColorPick.value = isWildType(selected.type);
            if (!needsColorPick.value) chosenColor.value = "";
        };

        const syncPendingDrawUiState = () => {
            clearAutoPenaltyTimer();
            if (!isPendingDrawStack.value || !hasLoadedHand.value || !isMyTurn.value || gameStatus.value !== "PLAYING") {
                autoPenaltyInProgress.value = false;
                lastAutoPenaltyKey.value = "";
                return;
            }
            if (hasPlayablePenaltyResponse.value) {
                lastAutoPenaltyKey.value = "";
                return;
            }

            const pendingKey = getPendingDrawStateKey();
            if (!pendingKey || autoPenaltyInProgress.value || lastAutoPenaltyKey.value === pendingKey) return;
            autoPenaltyTimer = setTimeout(() => {
                autoPenaltyTimer = null;
                drawPenaltyAction({ autoTriggered: true, pendingKey });
            }, 150);
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

            let statusText = t("rematchNeedBoth");
            if (isReady && otherReady) statusText = t("rematchReadyBoth");
            else if (isReady) statusText = t("rematchReadyYou");
            else if (otherReady) statusText = t("rematchReadyOther");

            return {
                win: String(gameState.winnerId) === String(userId.value),
                title: winner ? `${winner.username} ${t("wins")}!` : t("gameOver"),
                statusText,
                rematchButtonDisabled: restartingGame.value || isReady,
                rematchButtonText: isReady ? t("ready") : (restartingGame.value ? t("submitting") : t("rematch"))
            };
        };

        const handleGameFinished = (gameState) => {
            clearCardSelection();
            gameStatus.value = "FINISHED";
            currentTurn.value = null;
            pendingDrawCount.value = 0;
            pendingDrawType.value = "NONE";
            autoPenaltyInProgress.value = false;
            lastAutoPenaltyKey.value = "";
            clearAutoPenaltyTimer();
            gameResult.value = buildGameResult(gameState);
        };

        const resetLocalState = () => {
            roomId.value = null;
            roomCode.value = "";
            roomStatus.value = "WAITING";
            maxPlayers.value = 2;
            totalRounds.value = 8;
            roundTimeLimitMinutes.value = 10;
            gameMode.value = "CLASSIC";
            gameId.value = null;
            gameStatus.value = "WAITING";
            currentTurn.value = null;
            clockwise.value = true;
            direction.value = 1;
            currentColor.value = "RED";
            pendingDrawCount.value = 0;
            pendingDrawType.value = "NONE";
            lastPenaltyPlayerId.value = null;
            drawPileSize.value = 0;
            topCard.value = null;
            handCards.value = [];
            tablePlayers.value = [];
            opponents.value = [];
            currentPlayerName.value = t("waitingPlayers");
            clearCardSelection();
            gameResult.value = null;
            toastMsg.value = "";
            leavingRoom.value = false;
            restartingGame.value = false;
            drawingPenalty.value = false;
            hasLoadedHand.value = false;
            autoPenaltyInProgress.value = false;
            lastAutoPenaltyKey.value = "";
            clearAutoPenaltyTimer();
        };

        const storeLobbyNotice = (message) => {
            if (message) sessionStorage.setItem("lobbyNotice", message);
        };

        const cleanupRealtime = () => {
            clearAutoPenaltyTimer();
            if (pollTimer) {
                clearInterval(pollTimer);
                pollTimer = null;
            }
            if (reconnectTimer) {
                clearTimeout(reconnectTimer);
                reconnectTimer = null;
            }
            for (const subscription of [roomSubscription, gameSubscription, handSubscription]) {
                if (subscription.value) {
                    subscription.value.unsubscribe();
                    subscription.value = null;
                }
            }
            subscribedGameId.value = null;
            wsConnected.value = false;
            if (stompClient.value) {
                try {
                    if (typeof stompClient.value.deactivate === "function") stompClient.value.deactivate();
                    else if (typeof stompClient.value.disconnect === "function") stompClient.value.disconnect(() => {});
                } catch (error) {
                    console.error(error);
                }
                stompClient.value = null;
            }
        };

        const applyRoomConfig = (state) => {
            maxPlayers.value = Number(state.maxPlayers || maxPlayers.value || 2);
            totalRounds.value = Number(state.totalRounds || totalRounds.value || 8);
            roundTimeLimitMinutes.value = Number(state.roundTimeLimitMinutes || roundTimeLimitMinutes.value || 10);
            gameMode.value = state.gameMode || gameMode.value || "CLASSIC";
        };

        const mapTablePlayer = (player) => ({
            userId: player.userId,
            username: player.username,
            seatIndex: player.seatIndex,
            handCount: player.handCount ?? 0,
            saidUno: Boolean(player.saidUno),
            isMe: String(player.userId) === String(userId.value),
            isCurrentTurn: String(player.userId) === String(currentTurn.value)
        });

        const renderRoom = (roomState) => {
            if (!roomState) return;
            roomId.value = roomState.roomId || roomState.id || roomId.value;
            roomCode.value = roomState.roomCode || roomCode.value;
            roomStatus.value = roomState.status || roomStatus.value;
            applyRoomConfig(roomState);
            if (roomState.gameId) {
                gameId.value = roomState.gameId;
                subscribeGameChannels(roomState.gameId);
            }
            const players = roomState.players || [];
            tablePlayers.value = players.map(mapTablePlayer);
            opponents.value = tablePlayers.value.filter((player) => !player.isMe);
        };

        const renderGame = (gameState) => {
            if (!gameState) return;
            roomId.value = gameState.roomId || roomId.value;
            roomCode.value = gameState.roomCode || roomCode.value;
            roomStatus.value = gameState.roomStatus || roomStatus.value;
            applyRoomConfig(gameState);
            gameId.value = gameState.gameId;
            gameStatus.value = String(gameState.status || gameState.phase || "WAITING").toUpperCase();
            currentTurn.value = gameState.currentTurn;
            clockwise.value = gameState.clockwise !== false;
            direction.value = Number(gameState.direction || (clockwise.value ? 1 : -1));
            currentColor.value = gameState.currentColor || "RED";
            pendingDrawCount.value = Number(gameState.pendingDrawCount || 0);
            pendingDrawType.value = gameState.pendingDrawType || "NONE";
            lastPenaltyPlayerId.value = gameState.lastPenaltyPlayerId ?? null;
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
            tablePlayers.value = players.map(mapTablePlayer);
            opponents.value = tablePlayers.value.filter((player) => !player.isMe);
            const turnPlayer = players.find((player) => String(player.userId) === String(gameState.currentTurn));
            currentPlayerName.value = turnPlayer?.username || t("waitingPlayers");

            if (gameStatus.value === "FINISHED") handleGameFinished(gameState);
            else {
                gameResult.value = null;
                restartingGame.value = false;
            }
            subscribeGameChannels(gameState.gameId);
            refreshHandPlayability();
            syncPendingDrawUiState();
        };

        const renderSnapshot = (snapshot) => {
            if (!snapshot) return;
            if (snapshot.roomState) renderRoom(snapshot.roomState);
            if (snapshot.gameState) renderGame(snapshot.gameState);
            if (Array.isArray(snapshot.handCards)) applyHandCards(snapshot.handCards);
        };

        const returnToLobby = async ({ force = false, notifyServer = true, notice = "", skipConfirm = false } = {}) => {
            if (leavingRoom.value) return;
            if (!skipConfirm && !force && gameStatus.value === "PLAYING") {
                const confirmed = window.confirm(t("backToLobby") + "?");
                if (!confirmed) return;
            }
            leavingRoom.value = true;
            try {
                if (notifyServer && roomId.value) {
                    const response = await axios.post(`${apiBase}/room/${roomId.value}/leave`);
                    const message = response.data?.data?.message || response.data?.message;
                    if (message) storeLobbyNotice(message);
                } else if (notice) {
                    storeLobbyNotice(notice);
                }
            } catch (error) {
                const message = error.response?.data?.message || notice;
                if (message) storeLobbyNotice(message);
            } finally {
                cleanupRealtime();
                resetLocalState();
                window.location.replace("lobby.html");
            }
        };

        const handleRoomDeleted = (payload) => {
            returnToLobby({
                force: true,
                notifyServer: false,
                notice: payload?.message || (language.value === "zh" ? "房间已关闭。" : "Room closed."),
                skipConfirm: true
            });
        };

        const handleMissingRoomOrGame = (error, fallbackMessage) => {
            const message = error?.response?.data?.message || fallbackMessage;
            if ([400, 404].includes(error?.response?.status)) {
                storeLobbyNotice(message || (language.value === "zh" ? "房间不可用" : "Room unavailable"));
                cleanupRealtime();
                resetLocalState();
                window.location.replace("lobby.html");
                return true;
            }
            return false;
        };

        const syncHand = async () => {
            if (!roomId.value) return;
            try {
                const response = await axios.get(`${apiBase}/game/room/${roomId.value}/hand`);
                if (response.data.code === 200) applyHandCards(response.data.data);
            } catch (error) {
                if (!handleMissingRoomOrGame(error, language.value === "zh" ? "房间不可用" : "Room unavailable")) console.error(error);
            }
        };

        const refreshFromServer = async () => {
            if (!roomId.value) return;
            try {
                const response = await axios.get(`${apiBase}/game/room/${roomId.value}/state`);
                if (response.data.code === 200) {
                    renderGame(response.data.data);
                    await syncHand();
                }
            } catch (error) {
                if (!handleMissingRoomOrGame(error, language.value === "zh" ? "房间不可用" : "Room unavailable")) console.error(error);
            }
        };

        const subscribeRoomTopic = () => {
            if (!stompClient.value || !wsConnected.value || roomSubscription.value || !roomId.value) return;
            roomSubscription.value = stompClient.value.subscribe(`/topic/rooms/${roomId.value}`, (message) => {
                const payload = JSON.parse(message.body);
                if (payload.type === "ROOM_DELETED") {
                    handleRoomDeleted(payload);
                    return;
                }
                if (payload.roomState) renderRoom(payload.roomState);
                if (payload.message) addLog(payload.message);
            });
        };

        function subscribeGameChannels(nextGameId) {
            if (!nextGameId || !stompClient.value || !wsConnected.value) return;
            if (String(subscribedGameId.value) === String(nextGameId) && gameSubscription.value && handSubscription.value) {
                return;
            }
            if (gameSubscription.value) gameSubscription.value.unsubscribe();
            if (handSubscription.value) handSubscription.value.unsubscribe();
            gameSubscription.value = null;
            handSubscription.value = null;
            subscribedGameId.value = String(nextGameId);

            gameSubscription.value = stompClient.value.subscribe(`/topic/games/${nextGameId}`, (message) => {
                const payload = JSON.parse(message.body);
                if (payload.type === "ROOM_DELETED") {
                    handleRoomDeleted(payload);
                    return;
                }
                if (payload.gameState) renderGame(payload.gameState);
                if (payload.message) addLog(payload.message);
            });

            handSubscription.value = stompClient.value.subscribe(`/topic/games/${nextGameId}/hands/${userId.value}`, (message) => {
                const payload = JSON.parse(message.body);
                if (Array.isArray(payload.handCards)) applyHandCards(payload.handCards);
            });
        }

        const connectWebSocket = () => {
            const stompApi = window.Stomp || window.StompJs?.Stomp;
            if (!stompApi) return;
            const socket = new SockJS("/api/ws");
            stompClient.value = stompApi.over(socket);
            stompClient.value.debug = () => {};
            stompClient.value.connect({}, () => {
                wsConnected.value = true;
                subscribeRoomTopic();
                if (gameId.value) subscribeGameChannels(gameId.value);
                addLog(t("connected"));
            }, () => {
                wsConnected.value = false;
                roomSubscription.value = null;
                gameSubscription.value = null;
                handSubscription.value = null;
                subscribedGameId.value = null;
                if (reconnectTimer) clearTimeout(reconnectTimer);
                reconnectTimer = setTimeout(connectWebSocket, 3000);
            });
        };

        const joinAndLoad = async () => {
            try {
                const response = await axios.post(`${apiBase}/game/${roomId.value}/join`);
                if (response.data.code === 200) renderSnapshot(response.data.data);
                else showToast(response.data.message || (language.value === "zh" ? "加入游戏失败" : "Failed to join game"));
            } catch (error) {
                if (!handleMissingRoomOrGame(error, language.value === "zh" ? "房间不可用" : "Room unavailable")) {
                    showToast(error.response?.data?.message || (language.value === "zh" ? "加入游戏失败" : "Failed to join game"));
                }
            }
        };

        const selectCard = (index) => {
            const card = handCards.value[index];
            if (!card) return;
            const result = evaluatePlayability(card, topCard.value, currentColor.value);
            if (!result.canPlay) {
                showToast(getReasonText(result.reason));
                return;
            }
            if (selectedCard.value === index) {
                clearCardSelection();
                return;
            }
            selectedCard.value = index;
            needsColorPick.value = isWildType(card.type);
            if (!needsColorPick.value) chosenColor.value = "";
        };

        const pickColor = (color) => {
            chosenColor.value = color;
        };

        const playSelectedCard = async () => {
            if (selectedCard.value === null) return;
            const card = handCards.value[selectedCard.value];
            if (!card) return;
            if (needsColorPick.value && !chosenColor.value) {
                showToast(t("chooseColor"));
                return;
            }
            if (!canPlaySelected.value) {
                showToast(getReasonText(evaluatePlayability(card, topCard.value, currentColor.value).reason));
                return;
            }
            try {
                const params = new URLSearchParams();
                params.append("cardIndex", String(selectedCard.value));
                if (chosenColor.value) params.append("chosenColor", chosenColor.value);
                const response = await axios.post(`${apiBase}/game/${gameId.value}/play?${params.toString()}`);
                if (response.data.code === 200) {
                    renderSnapshot(response.data.data);
                    clearCardSelection();
                } else {
                    showToast(response.data.message || (language.value === "zh" ? "出牌失败" : "Failed to play card"));
                    await refreshFromServer();
                }
            } catch (error) {
                showToast(error.response?.data?.message || (language.value === "zh" ? "出牌失败" : "Failed to play card"));
                await refreshFromServer();
            }
        };

        const drawCardAction = async () => {
            if (!canDraw.value) {
                showToast(isPendingDrawStack.value ? t("drawStack") : t("notYourTurn"));
                return;
            }
            try {
                const response = await axios.post(`${apiBase}/game/${gameId.value}/draw`);
                if (response.data.code === 200) {
                    renderSnapshot(response.data.data);
                    clearCardSelection();
                } else {
                    showToast(response.data.message || (language.value === "zh" ? "抽牌失败" : "Failed to draw card"));
                }
            } catch (error) {
                showToast(error.response?.data?.message || (language.value === "zh" ? "抽牌失败" : "Failed to draw card"));
            }
        };

        const drawPenaltyAction = async ({ autoTriggered = false, pendingKey = "" } = {}) => {
            if (!autoTriggered && (!showDrawPenaltyButton.value || drawingPenalty.value)) return;
            const resolvedPendingKey = pendingKey || getPendingDrawStateKey();
            if (autoTriggered) {
                if (!resolvedPendingKey || autoPenaltyInProgress.value || lastAutoPenaltyKey.value === resolvedPendingKey) return;
                autoPenaltyInProgress.value = true;
                lastAutoPenaltyKey.value = resolvedPendingKey;
            }
            drawingPenalty.value = true;
            try {
                const response = await axios.post(`${apiBase}/game/${gameId.value}/draw-penalty`);
                if (response.data.code === 200) {
                    renderSnapshot(response.data.data);
                    clearCardSelection();
                } else {
                    showToast(response.data.message || (language.value === "zh" ? "抽取罚牌失败" : "Failed to draw penalty"));
                    if (autoTriggered) lastAutoPenaltyKey.value = "";
                    await refreshFromServer();
                }
            } catch (error) {
                showToast(error.response?.data?.message || (language.value === "zh" ? "抽取罚牌失败" : "Failed to draw penalty"));
                if (autoTriggered) lastAutoPenaltyKey.value = "";
                await refreshFromServer();
            } finally {
                drawingPenalty.value = false;
                autoPenaltyInProgress.value = false;
            }
        };

        const restartGameAction = async () => {
            if (!gameId.value || restartingGame.value) return;
            restartingGame.value = true;
            try {
                const response = await axios.post(`${apiBase}/game/${gameId.value}/rematch-ready`);
                if (response.data.code === 200) {
                    renderSnapshot(response.data.data);
                    addLog(t("rematch"));
                } else {
                    showToast(response.data.message || (language.value === "zh" ? "再来一局请求失败" : "Failed to request rematch"));
                }
            } catch (error) {
                showToast(error.response?.data?.message || (language.value === "zh" ? "再来一局请求失败" : "Failed to request rematch"));
            } finally {
                if (gameStatus.value === "FINISHED") restartingGame.value = false;
            }
        };

        const handleDelegatedButtonClick = (event) => {
            const button = event.target.closest("#backToLobbyButton, #endReturnLobbyBtn, #restartGameBtn, #drawPenaltyButton");
            if (!button || button.disabled) return;
            event.preventDefault();
            if (button.id === "backToLobbyButton") returnToLobby({ force: false, notifyServer: true });
            if (button.id === "endReturnLobbyBtn") returnToLobby({ force: true, notifyServer: true });
            if (button.id === "restartGameBtn") restartGameAction();
            if (button.id === "drawPenaltyButton") drawPenaltyAction();
        };

        onMounted(async () => {
            applyLanguage();
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
                if (!wsConnected.value) refreshFromServer();
            }, 4000);
        });

        onUnmounted(() => {
            cleanupRealtime();
            clearAutoPenaltyTimer();
            if (beforeUnloadHandler) window.removeEventListener("beforeunload", beforeUnloadHandler);
            if (delegatedButtonHandler) document.removeEventListener("click", delegatedButtonHandler);
        });

        return {
            roomId,
            roomCode,
            roomStatus,
            maxPlayers,
            totalRounds,
            roundTimeLimitMinutes,
            gameMode,
            language,
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
            tablePlayers,
            opponents,
            playerCount,
            isMyTurn,
            turnLabel,
            selectedCard,
            needsColorPick,
            chosenColor,
            gameLog,
            logExpanded,
            rulesExpanded,
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
            gameModeLabel,
            connectionLabel,
            languageLabel,
            selectedCardInfo,
            modeRuleLines,
            t,
            toggleLanguage,
            colorName,
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
