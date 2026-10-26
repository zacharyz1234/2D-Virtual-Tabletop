package virtualTabletop;

public class Main {
    public static void main(String[] args) {
        Game game = new Game("Alice", "Bob");

        game.deal();

        System.out.println(game.getPlayer1().getName() + "'s hand:");
        for (Card card : game.getPlayer1().getHand()) {
            System.out.println(card);
        }

        System.out.println("\n" + game.getPlayer2().getName() + "'s hand:");
        for (Card card : game.getPlayer2().getHand()) {
            System.out.println(card);
        }

        System.out.println("\nCards remaining in deck: "
                + game.getDeck().size());
    }
}