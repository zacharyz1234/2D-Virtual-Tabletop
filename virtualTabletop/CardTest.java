package virtualTabletop;

//Tool for visualizing the entire deck. 
public class CardTest {
    public static void main(String[] args) {
        Deck deck = new Deck();

        //Displays the number of cards in the deck
        System.out.println("Deck size: " + deck.size());

        //Prints every card in the deck, including suit, value, and whether it's a face card.
        //Can be reworked eventually for custom cards. 
        for (Card card : deck.getCards()) 
        {
            System.out.println(
                card.suit() + " " + card.face()
                + " | Value: " + card.face().getCardValue()
                + " | Face card: " + card.face().isFace()
            );
        }
    }
}