package virtualTabletop;

public class Game {
    private final Deck deck;
    private final Player player1;
    private final Player player2;
    private final Player player3;
    private final Player player4;
    private int turnOrder;

    public Game(String player1Name, String player2Name, String player3Name, String player4Name) {
        deck = new Deck();
        player1 = new Player(player1Name);
        player2 = new Player(player2Name);
        player3 = new Player(player3Name);
        player4 = new Player(player4Name);
        turnOrder = 1;
    }

    //ask host for number to deal
    public void deal() {
        deck.shuffle();

        for (int i = 0; i < 5; i++) {
            player1.addCard(deck.dealCard());
            player2.addCard(deck.dealCard());
            player3.addCard(deck.dealCard());
            player4.addCard(deck.dealCard());
        }
    }
    
    public void printAllHands()
    {
    	System.out.println(player1.getName() + "'s hand:");
        for (Card card : player1.getHand()) {
            System.out.println(card);
        }
        
        System.out.println();
        
        System.out.println(player2.getName() + "'s hand:");
        for (Card card : player2.getHand()) {
            System.out.println(card);
        }
        
        System.out.println();
        
        System.out.println(player3.getName() + "'s hand:");
        for (Card card : player3.getHand()) {
            System.out.println(card);
        }
        
        System.out.println();

        System.out.println(player4.getName() + "'s hand:");
        for (Card card : player4.getHand()) {
            System.out.println(card);
        }
    }
    
    public void printHand()
    {
    	Player player = turnOrderCalc();
    	
    	System.out.println(player.getName() + "'s hand:");
        for (Card card : player.getHand()) {
            System.out.println(card);
        }
    }
    
    public void dealCustom(Player player, int num) {
    	for (int i = 0; i < num; i++) {
    		player.addCard(deck.dealCard());
    	}
    }
    
    //how many cards to play?
    //Placed anywhere on the grid
    //Storing data to display? 2D array 
    public void playCard(Card removeCard)
    {
    	Player player = turnOrderCalc();
        for (int i = 0; i < player.getHand().size(); i++) {
        	System.out.print(player.getHand().size());
        }
    	
    	//needs to get the card requested
    	//player.removeCard(null);
    }
    
    public Player turnOrderCalc()
    {
    	if (turnOrder == 1)
    	{
    		return player1;
    	}
    	else if (turnOrder == 2)
    	{
    		return player2;
    	}
    	else if (turnOrder == 3)
    	{
    		return player3;
    	}
    	else if (turnOrder == 4)
    	{
    		return player4;
    	}
    	
    	return null;
    }
    
    public Deck getDeck() {
        return deck;
    }
    
    public void getMenu(int n) {
    	Player player = turnOrderCalc();
    	
    	if (n == 1)
        {
        	dealCustom(player, 1);
        }
        else if (n == 2)
        {
        	System.out.print("Which card do you want to play? ");
        	//User selects here, work in progress
        	
        	System.out.println("\nPlayer played a card. \n");
        }
        
        else if (n < 2)
        {
        	System.out.println("Testing");
        }
    	
    	System.out.println();
        printHand();
        
    	//Turn is over, change turn order counter
    	
    	if (turnOrder > 3)
    	{
    		turnOrder = 1;
    	}
    	else
    	{
    		turnOrder++;
    	}
    	
    }
    
}