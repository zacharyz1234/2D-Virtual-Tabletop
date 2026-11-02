package virtualTabletop;

import java.util.*;

public class Main {
    public static void main(String[] args) {
    	
    	Scanner input = new Scanner(System.in);
        Game game = new Game("Player 1", "Player 2", "Player 3", "Player 4");

        game.deal();
        game.printAllHands();
        
        System.out.println("\nCards remaining in deck: "
                + game.getDeck().size());
        
        //Turn order is determined by turnOrderCalc function in the Game class
        //Math.random(); //for dice
        for(int i = 0; i < 5; i++)
        {
        	System.out.println("\n" + game.turnOrderCalc().getName() + "'s Turn!");
        	
	        System.out.print("\nWhat would you like to do? [1: Draw card | 2: Play card | 3: Test] "); 
	        int n = input.nextInt();
	        game.getMenu(n);
	        
	        //Action then turn ends (increment turn pointer)
	        //Call turn function here
        }
        
        System.out.println("\nGAME END");
        input.close();
        
    }
}