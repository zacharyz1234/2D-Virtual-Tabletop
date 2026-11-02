package virtualTabletop;

import java.util.ArrayList;
import java.util.Collections;

public class Deck {
    private final ArrayList<Card> cards;

    //Creates the deck of cards. Defines the deck as having 52 cards, and initializes the deck with
    //one of every card (previously defined in the cardFace enumeration).
    public Deck() {
        cards = new ArrayList<>(52);
        initializeDeck();
    }

    //Initializes the previously created deck with suits and faces. 
    private void initializeDeck() {
        for (Suit suit : Suit.values()) 
        {
            for (CardFace face : CardFace.values()) 
            {
                cards.add(new Card(suit, face));
            }
        }
    }
    
    public void shuffle() {
    	Collections.shuffle(cards);
    }

    public Card dealCard() {
        if (cards.isEmpty()) 
        {
            throw new IllegalStateException("The deck is empty.");
        }

        return cards.remove(0);
    }
    
    public ArrayList<Card> getCards() {
        return new ArrayList<>(cards);
    }

    public int size() {
        return cards.size();
    }
}