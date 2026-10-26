package virtualTabletop;

import java.util.ArrayList;

public class Player {
    private final String name;
    private final ArrayList<Card> hand;

    public Player(String name) {
        this.name = name;
        this.hand = new ArrayList<>();
    }

    public String getName() {
        return name;
    }

    public ArrayList<Card> getHand() {
        return new ArrayList<>(hand);
    }

    public void addCard(Card card) {
        hand.add(card);
    }
}