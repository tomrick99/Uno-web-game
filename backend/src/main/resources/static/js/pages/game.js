const { createApp, ref, shallowRef, computed, onMounted, onUnmounted } = Vue;
const apiBase = "/api";
const realtimeUtils = window.UnoRealtimeUtils || {};
const FALLBACK_THRESHOLD_MS = 5000;
const RECONNECT_DELAY_MS = 3000;
const FALLBACK_POLL_INTERVAL_MS = 5000;
const WS_CONNECT_TIMEOUT_MS = 5000;
const websocketEndpoint = "/api/ws";

createApp({
    setup() {
        const roomId = ref(new URLSearchParams(window.location.search).get("roomId"));
        const roomCode = ref("");
        const roomStatus = ref("WAITING");
        const maxPlayers = ref(2);
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
        const toastType = ref("info");
        const toastVisible = ref(false);
        const language = ref(localStorage.getItem("unoLanguage") || "zh");
        const wsConnected = ref(false);
        const connectionMode = ref("reconnecting");
        const lastRoomVersion = ref(null);
        const lastGameVersion = ref(null);
        const lastHandVersion = ref(null);
        const lastHandPatchId = ref(null);
        const roomPlayerCount = ref(0);
        const leavingRoom = ref(false);
        const restartingGame = ref(false);
        const drawingCard = ref(false);
        const drawingPenalty = ref(false);
        const playingCard = ref(false);
        const hasLoadedHand = ref(false);
        const autoPenaltyInProgress = ref(false);
        const lastAutoPenaltyKey = ref("");

        const stompClient = shallowRef(null);
        const roomSubscription = ref(null);
        const gameSubscription = ref(null);
        const handSubscription = ref(null);
        const subscribedGameId = ref(null);

        let reconnectTimer = null;
        let pollTimer = null;
        let fallbackActivationTimer = null;
        let autoPenaltyTimer = null;
        let delegatedButtonHandler = null;
        let beforeUnloadHandler = null;
        let disconnectedAt = null;
        let wsConnectInFlight = false;
        let shouldReconnect = true;
        let pendingRealtimeBatch = null;
        let pendingRealtimeBatchTimer = null;
        let gameStartConsistencyTimer = null;
        let lastGameStartConsistencyKey = null;

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
                cardsUnit: "张牌",
                me: "我",
                currentColor: "当前颜色",
                backToLobby: "返回大厅",
                canDraw: "可以抽牌",
                cannotDraw: "现在不能抽牌",
                clickDraw: "点击抽牌",
                drawPile: "抽牌堆",
                discardPile: "弃牌堆",
                waitingStart: "等待开始...",
                selectedCard: "已选卡牌",
                rules: "规则",
                gameLog: "游戏日志",
                logHelp: "日志只显示最近动作，便于查看同步和排查问题。",
                playCard: "出牌",
                playing: "出牌中...",
                winSubtitle: "本局获胜，准备再来一局吗？",
                loseSubtitle: "本局结束，可以返回大厅或申请再来一局。",
                connected: "实时已连接",
                reconnecting: "正在重连",
                fallback: "已切换轮询",
                clockwise: "顺时针",
                counterClockwise: "逆时针",
                classic: "经典",
                red: "红",
                yellow: "黄",
                green: "绿",
                blue: "蓝",
                playable: "可以出",
                notPlayable: "不能出",
                chooseColor: "请选择颜色后再出牌。",
                gameNotPlaying: "游戏尚未开始",
                notYourTurn: "还没轮到你",
                yourTurn: "你的回合",
                waitingPlayers: "等待玩家",
                turn: "轮到",
                gameFinished: "游戏结束",
                drawStack: "需要处理罚牌",
                drawCards: "抽牌",
                drawing: "抽牌中...",
                acceptPenalty: "接受罚牌",
                noStackable: "没有可叠加的罚牌，将自动抽 {count} 张。",
                pendingEqualHigher: "待罚 {count} 张；只能叠加不小于上一张的罚牌。",
                pendingPlus4: "待罚 {count} 张；只能叠加 +4。",
                pendingPlus2: "待罚 {count} 张；只能叠加 +2。",
                rematchReadyBoth: "双方都已准备，正在重开...",
                rematchReadyYou: "你已准备，等待对方。",
                rematchReadyOther: "对方已准备。",
                rematchNeedBoth: "双方都同意后才会开始下一局。",
                ready: "已准备",
                submitting: "提交中...",
                rematch: "再来一局",
                gameOver: "游戏结束",
                wins: "获胜",
                cardNumber: "数字牌：颜色相同或数字相同即可出。",
                cardSkip: "跳过下一位玩家。",
                cardReverse: "反转出牌方向。",
                cardDrawTwo: "+2：下一位抽 2 张，Classic 叠加规则保持不变。",
                cardDrawFour: "+4：No Mercy 彩色罚牌，下一位抽 4 张。",
                cardDiscardAll: "DROP：打出后弃掉你手中所有同色牌。",
                cardSkipAll: "SKIP ALL：跳过其他所有玩家，直接回到你。",
                cardWild: "万能牌：选择下一种颜色。",
                cardWildDrawFour: "黑色 +4：选颜色，下一位抽 4 张。",
                cardWildDrawSix: "黑色 +6：选颜色，下一位抽 6 张。",
                cardWildDrawTen: "黑色 +10：选颜色，下一位抽 10 张。",
                cardWildReverseDrawFour: "黑色 +4 REV：选颜色，反转方向并让下一位抽 4 张。",
                classicRule1: "数字牌按颜色或数字匹配。",
                classicRule2: "功能牌按颜色或类型匹配；万能牌需要选色。",
                classicRule3: "Classic 只保留现有 +2 / +4 叠加规则。",
                noMercyRule1: "No Mercy 额外包含 DROP、SKIP ALL、彩色 +4 和黑色 +6 / +10 / +4 REV。",
                noMercyRule2: "黑色罚牌打出后需要选颜色。",
                noMercyRule3: "罚牌叠加必须不小于上一张罚牌数值。"
            },
            en: {
                room: "Room",
                direction: "Direction",
                mode: "Mode",
                players: "Players",
                cardsUnit: "cards",
                me: "Me",
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
                playing: "Playing...",
                winSubtitle: "You won this game. Ready for another round?",
                loseSubtitle: "Game over. You can return to the lobby or rematch.",
                connected: "Realtime connected",
                reconnecting: "Reconnecting",
                fallback: "Fallback polling",
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
            gameStatus.value === "PLAYING" && isMyTurn.value && !isPendingDrawStack.value && !drawingCard.value
        );
        const playerCount = computed(() =>
            Number(roomPlayerCount.value || tablePlayers.value.length || opponents.value.length + 1)
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
        const connectionLabel = computed(() => t(connectionMode.value));

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

        const canStackClassicPenalty = (card, discardTopCard) =>
            ["DRAW_TWO", "WILD_DRAW_FOUR"].includes(card?.type)
            && getPenaltyValue(card.type) >= getPenaltyValue(discardTopCard?.type);

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
                return canStackClassicPenalty(card, topCard.value)
                    ? { canPlay: true, reason: "pending draw allows equal or higher draw penalty card" }
                    : { canPlay: false, reason: "pending draw only allows equal or higher draw penalty cards" };
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
                return cards.some((card) => canStackClassicPenalty(card, topCard.value));
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
            return t("pendingEqualHigher", { count: pendingDrawCount.value });
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

        const clearToast = () => {
            toastVisible.value = false;
            toastMsg.value = "";
            toastType.value = "info";
        };

        const showToastMessage = (message, type = "info") => {
            if (!message) return;
            toastMsg.value = message;
            toastType.value = type;
            toastVisible.value = true;
            window.setTimeout(() => {
                if (toastMsg.value === message) {
                    clearToast();
                }
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
            roundTimeLimitMinutes.value = 10;
            gameMode.value = "CLASSIC";
            roomPlayerCount.value = 0;
            gameId.value = null;
            gameStatus.value = "WAITING";
            lastRoomVersion.value = null;
            lastGameVersion.value = null;
            lastHandVersion.value = null;
            lastHandPatchId.value = null;
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
            clearToast();
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
            if (fallbackActivationTimer) {
                clearTimeout(fallbackActivationTimer);
                fallbackActivationTimer = null;
            }
            if (pendingRealtimeBatchTimer) {
                clearTimeout(pendingRealtimeBatchTimer);
                pendingRealtimeBatchTimer = null;
            }
            if (gameStartConsistencyTimer) {
                clearTimeout(gameStartConsistencyTimer);
                gameStartConsistencyTimer = null;
            }
            pendingRealtimeBatch = null;
            lastGameStartConsistencyKey = null;
            for (const subscription of [roomSubscription, gameSubscription, handSubscription]) {
                if (subscription.value) {
                    subscription.value.unsubscribe();
                    subscription.value = null;
                }
            }
            subscribedGameId.value = null;
            wsConnected.value = false;
            wsConnectInFlight = false;
            connectionMode.value = "reconnecting";
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

        const normalizeVersion = (value) => {
            if (value === null || value === undefined || value === "") return null;
            const parsed = Number(value);
            return Number.isFinite(parsed) ? parsed : null;
        };

        const extractStateVersion = (payload) => normalizeVersion(
            payload?.version
            ?? payload?.gameState?.version
            ?? payload?.roomState?.version
        );

        const mapIncomingPublicPlayers = (players) => (Array.isArray(players) ? players : []).map((player) => ({
            userId: player.userId,
            username: player.username,
            handCount: player.handCount ?? 0,
            seatIndex: player.seatIndex,
            saidUno: Boolean(player.saidUno),
            currentPlayer: Boolean(player.currentPlayer),
            rematchReady: Boolean(player.rematchReady)
        }));

        const getChannelLabel = (payload, fallback = "unknown") => payload?.__channel || fallback;
        const hasOwnField = (value, field) => Object.prototype.hasOwnProperty.call(value || {}, field);

        const normalizeIncomingRealtimePayload = (payload) => {
            if (!payload || typeof payload !== "object") return {};
            if (payload.type === "RESYNC_REQUIRED") {
                console.warn(`[UNO-SYNC] resync required source=${getChannelLabel(payload, "server")} reason=${payload.message || "server-requested"}`);
                return {
                    type: payload.type,
                    event: payload.event || payload.type,
                    message: payload.message,
                    version: payload.version,
                    resync: true
                };
            }
            if (payload.type === "ROOM_DELETED" || payload.type === "FULL_SNAPSHOT") {
                return payload;
            }
            if (Array.isArray(payload.handCards) && (payload.userId !== undefined || String(payload.type || "").startsWith("HAND_"))) {
                return {
                    event: payload.event || payload.type,
                    message: payload.message,
                    version: payload.version,
                    handState: payload,
                    handCards: payload.handCards,
                    __channel: payload.__channel
                };
            }
            if (payload.roomState || payload.gameState || Array.isArray(payload.handCards)) {
                return payload;
            }
            if (payload.type === "ROOM_STATE" || payload.type === "LOBBY_EVENT") {
                return {
                    event: payload.event || payload.type,
                    message: payload.message,
                    version: payload.roomState?.version ?? payload.version,
                    roomState: payload.roomState || payload,
                    __channel: payload.__channel
                };
            }
            if (payload.gameId !== undefined && (
                payload.currentPlayerId !== undefined
                || payload.currentColor !== undefined
                || payload.pendingPenalty !== undefined
                || payload.pendingDrawType !== undefined
                || payload.gameStatus !== undefined
                || Array.isArray(payload.players)
            )) {
                const gameState = {
                    gameId: payload.gameId,
                    version: payload.version
                };
                const copyField = (targetField, sourceField = targetField) => {
                    if (hasOwnField(payload, sourceField)) {
                        gameState[targetField] = payload[sourceField];
                    }
                };
                copyField("roomId");
                copyField("status", "gameStatus");
                copyField("currentTurn", "currentPlayerId");
                copyField("currentPlayerId");
                copyField("currentPlayerIndex");
                copyField("currentColor");
                copyField("direction");
                copyField("pendingDrawCount", "pendingPenalty");
                copyField("pendingDrawType");
                copyField("lastPenaltyPlayerId");
                copyField("drawPileSize");
                copyField("winnerId");
                copyField("rematchReadyPlayerIds");
                if (hasOwnField(payload, "direction")) {
                    gameState.clockwise = payload.direction !== -1;
                }
                if (hasOwnField(payload, "topCard")) {
                    gameState.topCard = payload.topCard;
                } else if (hasOwnField(payload, "discardTopCard")) {
                    gameState.topCard = payload.discardTopCard;
                }
                if (Array.isArray(payload.players)) {
                    gameState.players = mapIncomingPublicPlayers(payload.players);
                }
                return {
                    event: payload.event || payload.type,
                    message: payload.message,
                    version: payload.version,
                    __channel: payload.__channel,
                    gameState
                };
            }
            if (payload.type) {
                console.warn(`[UNO-SYNC-CHECK] unknown patch type=${payload.type} channel=${getChannelLabel(payload, "unknown")}`);
            }
            return payload;
        };

        const isGameStartEvent = (eventName) => [
            "GAME_STARTED",
            "GAME_RESTARTED",
            "REMATCH_STARTED",
            "REMATCH_READY"
        ].includes(String(eventName || "").toUpperCase());

        const requiresCompletePlayingState = (eventName) => [
            "GAME_STARTED",
            "GAME_RESTARTED",
            "REMATCH_STARTED"
        ].includes(String(eventName || "").toUpperCase());

        const shouldApplyLayerVersion = (layer, incomingVersion, currentVersion, source) => {
            if (incomingVersion === null) {
                console.warn(`[UNO-SYNC-CHECK] missing version layer=${layer} source=${source}`);
                return true;
            }
            if (currentVersion !== null && incomingVersion < currentVersion) {
                console.info(`[UNO-SYNC-CHECK] ignored stale layer=${layer} incoming=${incomingVersion} current=${currentVersion} source=${source}`);
                return false;
            }
            return true;
        };

        const setDisplayedPlayers = (players) => {
            tablePlayers.value = (players || []).map(mapTablePlayer);
            opponents.value = tablePlayers.value.filter((player) => !player.isMe);
        };

        const mergeRoomPlayersIntoDisplay = (players) => {
            const existingPlayers = new Map((tablePlayers.value || []).map((player) => [String(player.userId), player]));
            tablePlayers.value = (players || []).map((player) => {
                const basePlayer = mapTablePlayer(player);
                const existingPlayer = existingPlayers.get(String(player.userId));
                if (!existingPlayer) {
                    return basePlayer;
                }
                return {
                    ...basePlayer,
                    handCount: existingPlayer.handCount ?? basePlayer.handCount,
                    saidUno: existingPlayer.saidUno ?? basePlayer.saidUno,
                    isCurrentTurn: existingPlayer.isCurrentTurn
                };
            });
            opponents.value = tablePlayers.value.filter((player) => !player.isMe);
        };

        const applyGameId = (nextGameId, { source = "unknown", version = null, force = false, allowVersionlessReplace = false } = {}) => {
            if (!nextGameId) return;
            const currentGameId = gameId.value ? String(gameId.value) : null;
            const incomingGameId = String(nextGameId);
            if (currentGameId === incomingGameId) return;

            const currentGameVersion = normalizeVersion(lastGameVersion.value);
            const canReplace = force
                || !currentGameId
                || currentGameVersion === null
                || (allowVersionlessReplace && version === null)
                || version >= currentGameVersion;

            if (!canReplace) {
                console.info(`[UNO-SYNC-CHECK] ignored stale gameId source=${source} incomingGameId=${incomingGameId} currentGameId=${currentGameId} incomingVersion=${version ?? "none"} currentGameVersion=${currentGameVersion ?? "none"}`);
                return;
            }

            console.info(`[UNO-SYNC-CHECK] gameId changed source=${source} oldGameId=${currentGameId ?? "none"} newGameId=${incomingGameId} version=${version ?? "none"}`);
            gameId.value = nextGameId;
            subscribeGameChannels(nextGameId);
        };

        const validateClientState = (source, context = {}) => {
            const players = Array.isArray(tablePlayers.value) ? tablePlayers.value : [];
            const resolvedPlayerCount = Number(roomPlayerCount.value || players.length || 0);
            const turnPlayerIndex = players.findIndex((player) => String(player.userId) === String(currentTurn.value));
            if (currentTurn.value && turnPlayerIndex < 0 && gameStatus.value === "PLAYING") {
                console.warn(`[UNO-SYNC-CHECK] currentPlayerMissing source=${source} currentPlayerId=${currentTurn.value} players=${players.length}`);
            }
            if (resolvedPlayerCount && players.length && resolvedPlayerCount !== players.length) {
                console.warn(`[UNO-SYNC-CHECK] playerCountMismatch source=${source} playerCount=${resolvedPlayerCount} playersLength=${players.length}`);
            }
            if (!Array.isArray(context.handCards) && context.previousHandCount > 0 && Array.isArray(handCards.value) && handCards.value.length === 0) {
                console.error(`[UNO-SYNC-CHECK] handCardsClearedWithoutPayload source=${source} previousHandCount=${context.previousHandCount}`);
            }
        };

        const resolveHandPatchDecision = (handState, source) => {
            const currentVersion = normalizeVersion(lastHandVersion.value);
            const decisionHelper = realtimeUtils.resolveHandPatchDecision;
            const decision = typeof decisionHelper === "function"
                ? decisionHelper({
                    incomingVersion: handState?.version,
                    currentVersion,
                    incomingPatchId: handState?.patchId,
                    lastPatchId: lastHandPatchId.value
                })
                : { apply: true, reason: "apply" };
            const channel = getChannelLabel(handState, source);
            const incomingVersion = normalizeVersion(handState?.version);
            if (!decision.apply && decision.reason === "duplicate") {
                console.info(`[UNO-SYNC-CHECK] ignored duplicate hand patchId=${handState?.patchId || "none"}`);
            } else if (!decision.apply && decision.reason === "stale") {
                console.info(`[UNO-SYNC-CHECK] ignored stale layer=hand incoming=${incomingVersion ?? "none"} current=${currentVersion ?? "none"} source=${source} channel=${channel}`);
            }
            return decision;
        };

        const renderRoom = (roomState, { source = "unknown", version = null, forceGameBinding = false } = {}) => {
            if (!roomState) return;
            roomId.value = roomState.roomId || roomState.id || roomId.value;
            roomCode.value = roomState.roomCode || roomCode.value;
            roomStatus.value = roomState.status || roomStatus.value;
            applyRoomConfig(roomState);
            roomPlayerCount.value = Number(roomState.playerCount ?? roomState.players?.length ?? roomPlayerCount.value ?? 0);
            if (roomState.gameId) {
                applyGameId(roomState.gameId, {
                    source: `${source}:room`,
                    version,
                    allowVersionlessReplace: forceGameBinding
                });
            }
            if (Array.isArray(roomState.players)) {
                if (gameStatus.value === "PLAYING") {
                    mergeRoomPlayersIntoDisplay(roomState.players);
                } else {
                    setDisplayedPlayers(roomState.players);
                }
            }
        };

        const renderGame = (gameState, { source = "unknown", version = null, allowVersionlessGameBinding = false } = {}) => {
            if (!gameState) return;
            applyGameId(gameState.gameId, {
                source: `${source}:game`,
                version,
                allowVersionlessReplace: allowVersionlessGameBinding
            });
            if (hasOwnField(gameState, "status") || hasOwnField(gameState, "phase")) {
                gameStatus.value = String(gameState.status ?? gameState.phase).toUpperCase();
            }
            if (hasOwnField(gameState, "currentTurn")) currentTurn.value = gameState.currentTurn;
            if (hasOwnField(gameState, "clockwise")) clockwise.value = gameState.clockwise !== false;
            if (hasOwnField(gameState, "direction")) direction.value = Number(gameState.direction);
            if (hasOwnField(gameState, "currentColor") && gameState.currentColor != null) currentColor.value = gameState.currentColor;
            if (hasOwnField(gameState, "pendingDrawCount")) pendingDrawCount.value = Number(gameState.pendingDrawCount ?? 0);
            if (hasOwnField(gameState, "pendingDrawType")) pendingDrawType.value = gameState.pendingDrawType ?? "NONE";
            if (hasOwnField(gameState, "lastPenaltyPlayerId")) lastPenaltyPlayerId.value = gameState.lastPenaltyPlayerId;
            if (hasOwnField(gameState, "drawPileSize")) drawPileSize.value = Number(gameState.drawPileSize ?? 0);
            if (hasOwnField(gameState, "topCard") && gameState.topCard) {
                topCard.value = {
                    color: gameState.topCard.color,
                    type: gameState.topCard.type,
                    value: gameState.topCard.value,
                    display: getCardDisplay(gameState.topCard.type, gameState.topCard.value)
                };
            }

            if (Array.isArray(gameState.players)) setDisplayedPlayers(gameState.players);
            const turnPlayer = tablePlayers.value.find((player) => String(player.userId) === String(currentTurn.value));
            currentPlayerName.value = turnPlayer?.username || t("waitingPlayers");

            if (gameStatus.value === "FINISHED") handleGameFinished(gameState);
            else {
                gameResult.value = null;
                restartingGame.value = false;
            }
            refreshHandPlayability();
            syncPendingDrawUiState();
        };

        const applyRealtimeState = (payload, source = "unknown") => {
            if (!payload) return;
            const normalizedPayload = normalizeIncomingRealtimePayload(payload);
            const previousHandCount = Array.isArray(handCards.value) ? handCards.value.length : 0;
            const eventName = normalizedPayload?.event || normalizedPayload?.type || "UNKNOWN";
            const channel = getChannelLabel(normalizedPayload, source);
            const isFullSnapshot = normalizedPayload?.type === "FULL_SNAPSHOT";
            const fallbackVersion = normalizeVersion(normalizedPayload?.version);
            const roomVersion = normalizeVersion(normalizedPayload?.roomVersion ?? normalizedPayload?.roomState?.version ?? fallbackVersion);
            const gameVersion = normalizeVersion(normalizedPayload?.gameVersion ?? normalizedPayload?.gameState?.version ?? fallbackVersion);
            const handVersion = normalizeVersion(normalizedPayload?.handVersion ?? normalizedPayload?.handState?.version ?? (Array.isArray(normalizedPayload?.handCards) ? fallbackVersion : null));
            const incomingTurn = normalizedPayload?.gameState?.currentTurn ?? normalizedPayload?.roomState?.currentTurn ?? null;
            console.info("[UNO-SYNC] applying state source=...", source, eventName);
            console.debug(`[UNO-SYNC] applying state source=${source} roomVersion=${roomVersion ?? "none"} gameVersion=${gameVersion ?? "none"} currentTurn=${incomingTurn ?? "none"}`);

            if (normalizedPayload.type === "FULL_SNAPSHOT") {
                // Full snapshot is the only payload type allowed to refresh room, game, and hand together.
            } else if (normalizedPayload.handState && (normalizedPayload.roomState || normalizedPayload.gameState)) {
                console.warn(`[UNO-SYNC-CHECK] mixed patch payload source=${source} channel=${channel} type=${normalizedPayload.type || eventName}`);
            }

            if (normalizedPayload.roomState && shouldApplyLayerVersion("room", roomVersion, normalizeVersion(lastRoomVersion.value), source)) {
                renderRoom(normalizedPayload.roomState, {
                    source,
                    version: roomVersion,
                    forceGameBinding: isGameStartEvent(eventName)
                });
                if (roomVersion !== null) {
                    lastRoomVersion.value = roomVersion;
                }
            }

            if (normalizedPayload.gameState && shouldApplyLayerVersion("game", gameVersion, normalizeVersion(lastGameVersion.value), source)) {
                const incomingGameState = normalizedPayload.gameState;
                if (!incomingGameState.gameId || (incomingGameState.status === "PLAYING" && incomingGameState.currentTurn === undefined)) {
                    console.warn(`[UNO-SYNC-CHECK] public patch incomplete source=${source} channel=${channel} gameId=${incomingGameState.gameId ?? "none"} version=${gameVersion ?? "none"} currentTurn=${incomingGameState.currentTurn ?? "none"}`);
                    refreshFromServer({ reason: `incomplete-game-patch-${source}` });
                } else {
                    renderGame(normalizedPayload.gameState, {
                        source,
                        version: gameVersion,
                        allowVersionlessGameBinding: isGameStartEvent(eventName)
                    });
                    if (gameVersion !== null) {
                        lastGameVersion.value = gameVersion;
                    }
                }
            }

            if (normalizedPayload.handState && !Array.isArray(normalizedPayload.handCards)) {
                console.warn(`[UNO-SYNC-CHECK] private hand patch missing handCards channel=${channel} version=${handVersion ?? "none"}`);
            } else if (Array.isArray(normalizedPayload.handCards)) {
                const handDecision = resolveHandPatchDecision({
                    ...(normalizedPayload.handState || normalizedPayload),
                    version: handVersion
                }, source);
                if (handDecision.apply && shouldApplyLayerVersion("hand", handVersion, normalizeVersion(lastHandVersion.value), source)) {
                    applyHandCards(normalizedPayload.handCards);
                    if (handVersion !== null) {
                        lastHandVersion.value = handVersion;
                    }
                    if (normalizedPayload.handState?.patchId) {
                        lastHandPatchId.value = normalizedPayload.handState.patchId;
                    }
                    console.info(`[UNO-SYNC] hand patch applied channel=${channel} version=${handVersion ?? "none"} patchId=${normalizedPayload.handState?.patchId || "none"}`);
                }
            }
            if (normalizedPayload.message) addLog(normalizedPayload.message);
            if (normalizedPayload.resync) refreshFromServer({ reason: `resync-${source}` });
            if (requiresCompletePlayingState(eventName)) {
                const consistencyKey = `${String(eventName).toUpperCase()}:${fallbackVersion ?? roomVersion ?? gameVersion ?? "none"}`;
                if (lastGameStartConsistencyKey !== consistencyKey) {
                    lastGameStartConsistencyKey = consistencyKey;
                    if (gameStartConsistencyTimer) clearTimeout(gameStartConsistencyTimer);
                    gameStartConsistencyTimer = setTimeout(() => {
                        gameStartConsistencyTimer = null;
                        const incomplete = !gameId.value
                            || gameStatus.value !== "PLAYING"
                            || !currentTurn.value
                            || !Array.isArray(handCards.value)
                            || handCards.value.length === 0;
                        if (incomplete) {
                            console.warn(`[UNO-SYNC-CHECK] incomplete game start source=${source} event=${eventName} gameId=${gameId.value ?? "none"} status=${gameStatus.value} currentTurn=${currentTurn.value ?? "none"} handCount=${handCards.value?.length ?? 0}; requesting snapshot`);
                            refreshFromServer({ reason: `incomplete-game-start-${source}` });
                        }
                    }, 200);
                }
            }
            validateClientState(source, {
                previousHandCount,
                handCards: normalizedPayload.handCards
            });
        };

        const flushRealtimeBatch = () => {
            pendingRealtimeBatchTimer = null;
            const batch = pendingRealtimeBatch;
            pendingRealtimeBatch = null;
            if (!batch) return;
            applyRealtimeState(batch, "ws-batch");
        };

        const queueRealtimeBatch = (patch) => {
            const mergeRealtimeBatch = realtimeUtils.mergeRealtimeBatch || ((currentBatch, nextPatch) => ({ ...(currentBatch || {}), ...(nextPatch || {}) }));
            const normalizedPatch = normalizeIncomingRealtimePayload(patch);
            pendingRealtimeBatch = mergeRealtimeBatch(pendingRealtimeBatch, normalizedPatch);
            if (pendingRealtimeBatchTimer) return;
            pendingRealtimeBatchTimer = setTimeout(flushRealtimeBatch, 40);
        };

        const renderSnapshot = (snapshot) => {
            if (!snapshot) return;
            applyRealtimeState(snapshot, "snapshot");
        };

        const shouldRefreshAfterAck = (response) => {
            if (!wsConnected.value) return true;
            return response?.data?.data?.resync === true;
        };

        const returnToLobby = async ({ force = false, notifyServer = true, notice = "", skipConfirm = false } = {}) => {
            if (leavingRoom.value) return;
            if (!skipConfirm && !force && gameStatus.value === "PLAYING") {
                const confirmed = window.confirm(t("backToLobby") + "?");
                if (!confirmed) return;
            }
            leavingRoom.value = true;
            shouldReconnect = false;
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
                shouldReconnect = false;
                cleanupRealtime();
                resetLocalState();
                window.location.replace("lobby.html");
                return true;
            }
            return false;
        };

        const loadSnapshot = async () => {
            if (!roomId.value) return null;
            const response = await axios.get(`${apiBase}/game/room/${roomId.value}/snapshot`);
            if (response.data.code === 200) {
                const version = extractStateVersion(response.data.data);
                console.debug(`[UNO-SYNC] applying state source=snapshot version=${version ?? "none"} currentTurn=${response.data.data?.gameState?.currentTurn ?? "none"}`);
                renderSnapshot(response.data.data);
                return response.data.data;
            }
            throw new Error(response.data.message || "Failed to load snapshot");
        };

        const refreshFromServer = async ({ reason = "manual" } = {}) => {
            if (!roomId.value) return;
            try {
                await loadSnapshot();
            } catch (error) {
                if (!handleMissingRoomOrGame(error, language.value === "zh" ? "房间不可用" : "Room unavailable")) {
                    console.error(`[UNO-SYNC] refresh failed reason=${reason}`, error);
                }
            }
        };

        const updateConnectionMode = () => {
            if (typeof realtimeUtils.resolveConnectionMode === "function") {
                connectionMode.value = realtimeUtils.resolveConnectionMode({
                    connected: wsConnected.value,
                    fallbackActive: Boolean(pollTimer)
                });
                return;
            }
            connectionMode.value = wsConnected.value ? "connected" : (pollTimer ? "fallback" : "reconnecting");
        };

        const startFallbackPolling = () => {
            if (pollTimer || wsConnected.value) return;
            pollTimer = setInterval(() => {
                if (!wsConnected.value) {
                    refreshFromServer({ reason: "fallback-poll" });
                }
            }, FALLBACK_POLL_INTERVAL_MS);
            console.info("[UNO-GAME] fallback polling started");
            updateConnectionMode();
        };

        const enterFallbackNow = (reason = "ws-unavailable") => {
            if (wsConnected.value) return;
            disconnectedAt = disconnectedAt || Date.now();
            console.warn("[UNO-GAME] fallback polling requested", reason);
            if (fallbackActivationTimer) {
                clearTimeout(fallbackActivationTimer);
                fallbackActivationTimer = null;
            }
            startFallbackPolling();
            refreshFromServer({ reason });
        };

        const scheduleFallbackActivation = () => {
            if (fallbackActivationTimer || wsConnected.value) return;
            fallbackActivationTimer = setTimeout(() => {
                fallbackActivationTimer = null;
                const now = Date.now();
                const shouldEnable = typeof realtimeUtils.shouldEnableFallbackPolling === "function"
                    ? realtimeUtils.shouldEnableFallbackPolling(disconnectedAt, now, FALLBACK_THRESHOLD_MS)
                    : disconnectedAt && now - disconnectedAt >= FALLBACK_THRESHOLD_MS;
                if (shouldEnable && !wsConnected.value) {
                    startFallbackPolling();
                    refreshFromServer({ reason: "fallback-start" });
                }
            }, FALLBACK_THRESHOLD_MS);
        };

        const clearRealtimeTimers = () => {
            if (pollTimer) {
                clearInterval(pollTimer);
                pollTimer = null;
                console.info("[UNO-GAME] fallback polling stopped");
            }
            if (reconnectTimer) {
                clearTimeout(reconnectTimer);
                reconnectTimer = null;
            }
            if (fallbackActivationTimer) {
                clearTimeout(fallbackActivationTimer);
                fallbackActivationTimer = null;
            }
        };

        const resetSubscriptions = () => {
            for (const subscription of [roomSubscription, gameSubscription, handSubscription]) {
                if (subscription.value) {
                    try {
                        subscription.value.unsubscribe();
                    } catch (error) {
                        console.error(error);
                    }
                    subscription.value = null;
                }
            }
            subscribedGameId.value = null;
        };

        const subscribeRoomTopic = () => {
            if (!stompClient.value || !wsConnected.value || roomSubscription.value || !roomId.value) return;
            console.info("[UNO-GAME] subscribed room topic", roomId.value);
            console.info(`[UNO-SYNC] subscribed destination=/topic/rooms/${roomId.value}`);
            roomSubscription.value = stompClient.value.subscribe(`/topic/rooms/${roomId.value}`, (message) => {
                const payload = JSON.parse(message.body || "{}");
                console.debug(`[UNO-SYNC] ws message type=${payload.type || payload.event || "ROOM_STATE"} roomId=${payload.roomId ?? roomId.value} currentTurn=${payload?.gameState?.currentTurn ?? payload?.roomState?.currentTurn ?? "none"} version=${extractStateVersion(payload) ?? "none"}`);
                if (payload.type === "ROOM_DELETED") {
                    handleRoomDeleted(payload);
                    return;
                }
                queueRealtimeBatch(payload);
            });
        };

        function subscribeGameChannels(nextGameId) {
            if (!nextGameId || !stompClient.value || !wsConnected.value) return;
            if (String(subscribedGameId.value) === String(nextGameId)
                && gameSubscription.value
                && handSubscription.value) {
                return;
            }
            if (subscribedGameId.value && String(subscribedGameId.value) !== String(nextGameId)) {
                console.info(`[UNO-SYNC-CHECK] resubscribeGameTopic oldGameId=${subscribedGameId.value} newGameId=${nextGameId}`);
            }
            if (gameSubscription.value) gameSubscription.value.unsubscribe();
            if (handSubscription.value) handSubscription.value.unsubscribe();
            gameSubscription.value = null;
            handSubscription.value = null;
            subscribedGameId.value = String(nextGameId);
            console.info("[UNO-GAME] subscribed game topic", nextGameId);
            console.info(`[UNO-SYNC] subscribed destination=/topic/games/${nextGameId}`);

            gameSubscription.value = stompClient.value.subscribe(`/topic/games/${nextGameId}`, (message) => {
                const payload = JSON.parse(message.body || "{}");
                console.debug(`[UNO-SYNC] ws message type=${payload.type || payload.event || "GAME_STATE"} roomId=${payload?.gameState?.roomId ?? roomId.value} currentTurn=${payload?.gameState?.currentTurn ?? "none"} version=${extractStateVersion(payload) ?? "none"}`);
                if (payload.type === "ROOM_DELETED") {
                    handleRoomDeleted(payload);
                    return;
                }
                queueRealtimeBatch(payload);
            });

            console.info("[UNO-GAME] subscribed user hand queue", roomId.value);
            console.info(`[UNO-SYNC] subscribed destination=/user/queue/room/${roomId.value}/hand`);
            handSubscription.value = stompClient.value.subscribe(`/user/queue/room/${roomId.value}/hand`, (message) => {
                const payload = JSON.parse(message.body || "{}");
                payload.__channel = "userQueue";
                console.info(`[UNO-SYNC] private hand received channel=userQueue version=${extractStateVersion(payload) ?? "none"} patchId=${payload.patchId || "none"}`);
                applyRealtimeState(payload, "ws-hand-userQueue");
            });

        }

        const handleSocketDisconnected = (client = null) => {
            if (client && stompClient.value && stompClient.value !== client) return;
            if (!shouldReconnect) return;
            wsConnected.value = false;
            wsConnectInFlight = false;
            disconnectedAt = disconnectedAt || Date.now();
            if (!client || stompClient.value === client) {
                stompClient.value = null;
            }
            console.warn("[UNO-GAME] ws disconnected, reconnecting");
            resetSubscriptions();
            updateConnectionMode();
            enterFallbackNow("ws-disconnected");
            if (reconnectTimer) clearTimeout(reconnectTimer);
            reconnectTimer = setTimeout(() => {
                reconnectTimer = null;
                connectWebSocket();
            }, RECONNECT_DELAY_MS);
            console.warn("[UNO-GAME] reconnect scheduled");
        };

        const scheduleReconnectAfterFailedConnect = () => {
            if (!shouldReconnect || reconnectTimer) return;
            reconnectTimer = setTimeout(() => {
                reconnectTimer = null;
                connectWebSocket();
            }, RECONNECT_DELAY_MS);
            console.warn("[UNO-GAME] reconnect scheduled");
        };

        const connectWebSocket = async ({ resyncOnConnect = true } = {}) => {
            if (!shouldReconnect) return false;
            if (wsConnected.value) return true;
            if (wsConnectInFlight) return false;
            const sockJsLoaded = typeof SockJS !== "undefined";
            const stompJsLoaded = typeof StompJs !== "undefined";
            console.info("[UNO-GAME] websocket endpoint =", websocketEndpoint);
            console.info("[UNO-GAME] SockJS loaded =", sockJsLoaded);
            console.info("[UNO-GAME] StompJs loaded =", stompJsLoaded);
            if (!sockJsLoaded || !stompJsLoaded || !window.StompJs?.Client) {
                console.error("[UNO-GAME] ws connect failed", {
                    endpoint: websocketEndpoint,
                    sockJsLoaded,
                    stompJsLoaded
                });
                disconnectedAt = disconnectedAt || Date.now();
                enterFallbackNow("ws-script-missing");
                scheduleReconnectAfterFailedConnect();
                return false;
            }

            console.info("[UNO-GAME] connecting websocket...");
            wsConnectInFlight = true;
            connectionMode.value = "reconnecting";
            return await new Promise((resolve) => {
                let settled = false;
                let client;
                let connectTimeout = null;
                const resolveOnce = (value) => {
                    if (settled) return;
                    settled = true;
                    if (connectTimeout) {
                        clearTimeout(connectTimeout);
                        connectTimeout = null;
                    }
                    resolve(value);
                };
                const handleConnected = async () => {
                    if (stompClient.value !== client) {
                        resolveOnce(false);
                        return;
                    }
                    wsConnected.value = true;
                    wsConnectInFlight = false;
                    disconnectedAt = null;
                    clearRealtimeTimers();
                    updateConnectionMode();
                    resetSubscriptions();
                    console.info("[UNO-GAME] ws connected");
                    subscribeRoomTopic();
                    if (gameId.value) subscribeGameChannels(gameId.value);
                    addLog(t("connected"));
                    if (resyncOnConnect) {
                        await refreshFromServer({ reason: "ws-connected" });
                    }
                    resolveOnce(true);
                };
                const handleConnectError = (error) => {
                    if (stompClient.value !== client) {
                        resolveOnce(false);
                        return;
                    }
                    console.error("[UNO-GAME] ws connect failed", error || { endpoint: websocketEndpoint });
                    handleSocketDisconnected(client);
                    resolveOnce(false);
                };
                const handleConnectTimeout = () => {
                    if (settled || stompClient.value !== client || wsConnected.value) return;
                    console.error("[UNO-GAME] ws connect failed", {
                        endpoint: websocketEndpoint,
                        reason: "connect-timeout"
                    });
                    wsConnectInFlight = false;
                    if (stompClient.value === client) {
                        stompClient.value = null;
                    }
                    try {
                        if (typeof client?.deactivate === "function") client.deactivate();
                        else if (typeof client?.disconnect === "function") client.disconnect(() => {});
                    } catch (error) {
                        console.error(error);
                    }
                    enterFallbackNow("ws-connect-timeout");
                    scheduleReconnectAfterFailedConnect();
                    resolveOnce(false);
                };
                const socketFactory = () => {
                    const socket = new SockJS(websocketEndpoint);
                    socket.onclose = (event) => {
                        if (shouldReconnect && stompClient.value === client) {
                            console.warn("[UNO-GAME] websocket closed", event);
                        }
                    };
                    return socket;
                };

                client = new window.StompJs.Client({
                    webSocketFactory: socketFactory,
                    reconnectDelay: 0,
                    debug: () => {},
                    onConnect: handleConnected,
                    onStompError: (frame) => {
                        console.error("[UNO-GAME] stomp error", frame);
                        handleConnectError(frame);
                    },
                    onWebSocketError: handleConnectError,
                    onWebSocketClose: (event) => {
                        if (shouldReconnect && stompClient.value === client) {
                            console.warn("[UNO-GAME] websocket closed", event);
                            handleSocketDisconnected(client);
                            resolveOnce(false);
                        }
                    }
                });
                stompClient.value = client;
                connectTimeout = setTimeout(handleConnectTimeout, WS_CONNECT_TIMEOUT_MS);
                try {
                    client.activate();
                } catch (error) {
                    handleConnectError(error);
                }
            });
        };

        const joinAndLoad = async () => {
            if (!roomId.value) {
                showToastMessage(language.value === "zh" ? "缺少 roomId，无法进入游戏页" : "Missing roomId");
                console.error("[UNO] init failed", new Error("Missing roomId"));
                return;
            }
            try {
                console.info("[UNO-GAME] joining room", roomId.value);
                const response = await axios.post(`${apiBase}/game/${roomId.value}/join`);
                console.info("[UNO-GAME] join response code =", response.data?.code);
                if (response.data.code === 200) {
                    renderSnapshot(response.data.data);
                } else {
                    showToastMessage(response.data.message || (language.value === "zh" ? "加入游戏失败" : "Failed to join game"));
                }
            } catch (error) {
                console.error("[UNO] init failed", error);
                if (!handleMissingRoomOrGame(error, language.value === "zh" ? "房间不可用" : "Room unavailable")) {
                    showToastMessage(error.response?.data?.message || (language.value === "zh" ? "加入游戏失败" : "Failed to join game"));
                }
            }
        };

        const selectCard = (index) => {
            const card = handCards.value[index];
            if (!card) return;
            const result = evaluatePlayability(card, topCard.value, currentColor.value);
            if (!result.canPlay) {
                showToastMessage(getReasonText(result.reason));
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
            if (selectedCard.value === null || playingCard.value) return;
            const card = handCards.value[selectedCard.value];
            if (!card) return;
            if (needsColorPick.value && !chosenColor.value) {
                showToastMessage(t("chooseColor"));
                return;
            }
            if (!canPlaySelected.value) {
                showToastMessage(getReasonText(evaluatePlayability(card, topCard.value, currentColor.value).reason));
                return;
            }
            playingCard.value = true;
            try {
                const params = new URLSearchParams();
                params.append("cardIndex", String(selectedCard.value));
                if (chosenColor.value) params.append("chosenColor", chosenColor.value);
                const response = await axios.post(`${apiBase}/game/${gameId.value}/play?${params.toString()}`);
                if (response.data.code === 200) {
                    clearCardSelection();
                    await refreshFromServer({ reason: "play-ack" });
                } else {
                    showToastMessage(response.data.message || (language.value === "zh" ? "出牌失败" : "Failed to play card"));
                    await refreshFromServer();
                }
            } catch (error) {
                showToastMessage(error.response?.data?.message || (language.value === "zh" ? "出牌失败" : "Failed to play card"));
                await refreshFromServer();
            } finally {
                playingCard.value = false;
            }
        };

        const drawCardAction = async () => {
            if (drawingCard.value) return;
            if (!canDraw.value) {
                showToastMessage(isPendingDrawStack.value ? t("drawStack") : t("notYourTurn"));
                return;
            }
            drawingCard.value = true;
            try {
                const response = await axios.post(`${apiBase}/game/${gameId.value}/draw`);
                if (response.data.code === 200) {
                    clearCardSelection();
                    await refreshFromServer({ reason: "draw-ack" });
                } else {
                    showToastMessage(response.data.message || (language.value === "zh" ? "抽牌失败" : "Failed to draw card"));
                    await refreshFromServer();
                }
            } catch (error) {
                showToastMessage(error.response?.data?.message || (language.value === "zh" ? "抽牌失败" : "Failed to draw card"));
                await refreshFromServer();
            } finally {
                drawingCard.value = false;
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
                    clearCardSelection();
                    await refreshFromServer({ reason: "draw-penalty-ack" });
                } else {
                    showToastMessage(response.data.message || (language.value === "zh" ? "抽取罚牌失败" : "Failed to draw penalty"));
                    if (autoTriggered) lastAutoPenaltyKey.value = "";
                    await refreshFromServer();
                }
            } catch (error) {
                showToastMessage(error.response?.data?.message || (language.value === "zh" ? "抽取罚牌失败" : "Failed to draw penalty"));
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
                    addLog(t("rematch"));
                    if (shouldRefreshAfterAck(response)) {
                        await refreshFromServer({ reason: "rematch-ack" });
                    }
                } else {
                    showToastMessage(response.data.message || (language.value === "zh" ? "再来一局请求失败" : "Failed to request rematch"));
                }
            } catch (error) {
                showToastMessage(error.response?.data?.message || (language.value === "zh" ? "再来一局请求失败" : "Failed to request rematch"));
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
            console.info("[UNO-GAME] game.js loaded");
            console.info("[UNO-GAME] roomId from URL =", roomId.value);
            console.info("[UNO-GAME] websocket endpoint =", websocketEndpoint);
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
                console.info("[UNO-GAME] init currentUser ok", response.data.data?.username || "");
            } catch (error) {
                console.error("[UNO] init failed", error);
                window.location.replace("index.html");
                return;
            }

            beforeUnloadHandler = () => cleanupRealtime();
            window.addEventListener("beforeunload", beforeUnloadHandler);
            delegatedButtonHandler = handleDelegatedButtonClick;
            document.addEventListener("click", delegatedButtonHandler);

            shouldReconnect = true;
            connectWebSocket({ resyncOnConnect: false }).then((connected) => {
                if (!connected && !wsConnected.value) {
                    updateConnectionMode();
                }
            }).catch((error) => {
                console.error("[UNO-GAME] ws connect failed", error);
                enterFallbackNow("ws-connect-exception");
            });
            await joinAndLoad();
        });

        onUnmounted(() => {
            shouldReconnect = false;
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
            toastType,
            toastVisible,
            colorMap,
            wsConnected,
            connectionMode,
            leavingRoom,
            restartingGame,
            drawingCard,
            drawingPenalty,
            playingCard,
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
            showToastMessage,
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
