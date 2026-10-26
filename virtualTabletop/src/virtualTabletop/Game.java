package virtualTabletop;

public class Game {
    private final Deck deck;
    private final Player player1;
    private final Player player2;

    public Game(String player1Name, String player2Name) {
        deck = new Deck();
        player1 = new Player(player1Name);
        player2 = new Player(player2Name);
    }

    public void deal() {
        deck.shuffle();

        for (int i = 0; i < 5; i++) {
            player1.addCard(deck.dealCard());
            player2.addCard(deck.dealCard());
        }
    }

    public Deck getDeck() {
        return deck;
    }

    public Player getPlayer1() {
        return player1;
    }

    public Player getPlayer2() {
        return player2;
    }
}