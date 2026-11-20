package virtualTabletop.database;

import java.nio.file.Path;

/** Initializes or inspects a host database without seeding cards or starting a game. */
public final class DatabaseMain {
    private DatabaseMain() { }

    public static void main(String[] args) throws Exception {
        if (args.length > 1) { throw new IllegalArgumentException("Usage: DatabaseMain [database-file]"); }
        Path file = Path.of(args.length == 0 ? "data/tabletop.db" : args[0]);
        try (TabletopDatabase database = TabletopDatabase.open(file)) {
            System.out.println("Database ready: " + file.toAbsolutePath().normalize());
            System.out.println("Card definitions: " + database.listCardDefinitions().size());
            System.out.println("Current decks: " + database.listDeckIds().size());
            System.out.println("Hands: " + database.listHands().size());
            System.out.println("Background: " + database.getBackgroundImagePath().orElse("none"));
        }
    }
}
