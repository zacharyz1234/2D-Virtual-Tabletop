package virtualTabletop;

//Assigning values to every type of card, suits are handled in Suit enumeration
public enum CardFace {
    ACE(1, false),
    TWO(2, false),
    THREE(3, false),
    FOUR(4, false),
    FIVE(5, false),
    SIX(6, false),
    SEVEN(7, false),
    EIGHT(8, false),
    NINE(9, false),
    TEN(10, false),
    JACK(11, true),
    QUEEN(12, true),
    KING(13, true);

	//Used to assign card values, variable type may need to be reworked when adding custom cards. 
    private final int cardValue;
    private final boolean isFace;

    CardFace(int cardValue, boolean isFace) {
        this.cardValue = cardValue;
        this.isFace = isFace;
    }

    public int getCardValue() {
        return cardValue;
    }

    public boolean isFace() {
        return isFace;
    }
}