package com.uno.model;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.uno.entity.enums.CardColor;
import com.uno.entity.enums.CardType;

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

    @JsonIgnore
    public boolean canPlayOn(Card topCard, CardColor currentColor) {
        if (this.type == null || this.color == null) {
            return false;
        }
        if (isWildLike(this.type)) {
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
        return this.type != CardType.NUMBER
                && topCard.type() != CardType.NUMBER
                && this.type == topCard.type();
    }

    @JsonIgnore
    public String getDisplayName() {
        return switch (type) {
            case NUMBER -> String.valueOf(value);
            case SKIP -> "SKIP";
            case REVERSE -> "REV";
            case DRAW_TWO -> "+2";
            case DRAW_FOUR -> "+4";
            case DISCARD_ALL_COLOR -> "DROP";
            case SKIP_ALL -> "SKIP ALL";
            case WILD -> "WILD";
            case WILD_DRAW_FOUR -> "+4";
            case WILD_DRAW_SIX -> "+6";
            case WILD_DRAW_TEN -> "+10";
            case WILD_REVERSE_DRAW_FOUR -> "+4 REV";
        };
    }

    @JsonIgnore
    public String getColorClass() {
        if (color == null || color == CardColor.WILD) {
            return "wild";
        }
        return color.name().toLowerCase();
    }

    private boolean isWildLike(CardType type) {
        return type == CardType.WILD
                || type == CardType.WILD_DRAW_FOUR
                || type == CardType.WILD_DRAW_SIX
                || type == CardType.WILD_DRAW_TEN
                || type == CardType.WILD_REVERSE_DRAW_FOUR;
    }
}
