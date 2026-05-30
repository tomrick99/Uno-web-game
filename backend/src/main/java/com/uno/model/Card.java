package com.uno.model;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.uno.entity.enums.CardColor;
import com.uno.entity.enums.CardType;

/**
 * 卡牌模型（非 JPA 实体，使用 JSON 序列化存储）
 * 使用 @JsonAutoDetect(fieldVisibility = ANY) 确保 Jackson 通过字段序列化，
 * 而不是通过 getter 方法，避免 record-style accessor 导致的序列化/反序列化不一致。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY, getterVisibility = JsonAutoDetect.Visibility.NONE)
public class Card {

    @JsonProperty("color")
    private final CardColor color;

    @JsonProperty("type")
    private final CardType type;

    @JsonProperty("value")
    private final int value;

    @JsonCreator
    public Card(
            @JsonProperty("color") CardColor color,
            @JsonProperty("type") CardType type,
            @JsonProperty("value") int value) {
        this.color = color;
        this.type = type;
        this.value = value;
    }

    @JsonProperty("color")
    public CardColor color() { return color; }

    @JsonProperty("type")
    public CardType type() { return type; }

    @JsonProperty("value")
    public int value() { return value; }

    /**
     * 判断这张牌是否可以出（匹配规则）
     */
    @JsonIgnore
    public boolean canPlayOn(Card topCard, CardColor currentColor) {
        if (this.type == null || this.color == null) {
            return false;
        }
        if (this.type == CardType.WILD || this.type == CardType.WILD_DRAW_FOUR) {
            return true;
        }
        if (this.color == currentColor) {
            return true;
        }
        if (topCard == null || topCard.type() == null) {
            return true;
        }
        if (this.type == CardType.NUMBER
                && topCard.type() == CardType.NUMBER
                && this.value == topCard.value()) {
            return true;
        }
        if (this.type != CardType.NUMBER
                && topCard.type() != CardType.NUMBER
                && this.type == topCard.type()) {
            return true;
        }
        return false;
    }

    /**
     * 获取卡牌显示文本
     */
    @JsonIgnore
    public String getDisplayName() {
        return switch (type) {
            case NUMBER -> String.valueOf(value);
            case SKIP -> "⊘";
            case REVERSE -> "⇄";
            case DRAW_TWO -> "+2";
            case WILD -> "🎨";
            case WILD_DRAW_FOUR -> "+4";
        };
    }

    /**
     * 获取卡牌颜色 CSS 类名
     */
    @JsonIgnore
    public String getColorClass() {
        if (color == null || color == CardColor.WILD) {
            return "wild";
        }
        return color.name().toLowerCase();
    }
}
