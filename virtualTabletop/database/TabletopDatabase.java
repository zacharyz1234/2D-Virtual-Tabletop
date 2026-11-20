package virtualTabletop.database;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Host-side persistence. One instance owns one connection; public operations serialize access. */
public final class TabletopDatabase implements AutoCloseable {
    public record CardDefinition(long cardId, String name, String imagePath) { }
    public record CardCopy(long instanceId, long cardId, Long deckId, Long handId, Long position) { }
    public record Hand(long handId, String ownerReference) { }

    private enum Container {
        DECK("decks", "deck_id"), HAND("hands", "hand_id");
        final String table;
        final String column;
        Container(String table, String column) { this.table = table; this.column = column; }
    }

    private final Connection connection;

    /** Opens an existing database or initializes an empty one from the bundled schema. */
    public static TabletopDatabase open(Path file) throws SQLException, IOException {
        Path absolute = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
        Files.createDirectories(absolute.getParent());
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + absolute);
        try {
            TabletopDatabase database = new TabletopDatabase(connection);
            database.initialize();
            return database;
        } catch (SQLException | IOException | RuntimeException failure) {
            try { connection.close(); } catch (SQLException closing) { failure.addSuppressed(closing); }
            throw failure;
        }
    }

    private TabletopDatabase(Connection connection) throws SQLException {
        this.connection = connection;
        execute("PRAGMA foreign_keys = ON");
        execute("PRAGMA busy_timeout = 5000");
    }

    private void initialize() throws SQLException, IOException {
        // Hold the writer lock so two host-side connections cannot both initialize an empty file.
        execute("BEGIN IMMEDIATE");
        try {
            Set<String> tables = new HashSet<>();
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery(
                         "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'")) {
                while (rows.next()) { tables.add(rows.getString(1)); }
            }
            if (tables.isEmpty()) {
                String schema;
                try (InputStream resource = TabletopDatabase.class.getResourceAsStream("/database/schema.sql")) {
                    if (resource == null) { throw new IOException("Bundled database/schema.sql is missing"); }
                    schema = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
                }
                // This project's schema has simple statements, no triggers or semicolons in literals.
                String sql = schema.lines().filter(line -> !line.stripLeading().startsWith("--"))
                        .reduce("", (left, right) -> left + right + "\n");
                for (String part : sql.split(";")) {
                    String command = part.trim();
                    if (!command.isEmpty() && !command.equalsIgnoreCase("BEGIN TRANSACTION")
                            && !command.equalsIgnoreCase("COMMIT")
                            && !command.equalsIgnoreCase("PRAGMA foreign_keys = ON")) {
                        execute(command);
                    }
                }
            } else if (!tables.equals(Set.of("card_definitions", "decks", "hands",
                    "card_instances", "table_settings"))) {
                throw new SQLException("Database contains an unsupported schema; no tables were changed");
            }
            // Fail early for missing columns or a missing singleton settings row.
            try (Statement statement = connection.createStatement()) {
                statement.executeQuery("SELECT card_id, name, image_path FROM card_definitions LIMIT 0").close();
                statement.executeQuery("SELECT deck_id FROM decks LIMIT 0").close();
                statement.executeQuery("SELECT hand_id, owner_reference FROM hands LIMIT 0").close();
                statement.executeQuery("SELECT instance_id, card_id, deck_id, hand_id, position FROM card_instances LIMIT 0").close();
                try (ResultSet rows = statement.executeQuery(
                        "SELECT settings_id, background_image_path FROM table_settings")) {
                    if (!rows.next() || rows.getLong(1) != 1 || rows.next()) {
                        throw new SQLException("Database must contain exactly one table_settings row with ID 1");
                    }
                }
            }
            execute("COMMIT");
        } catch (SQLException | IOException | RuntimeException failure) {
            rollback(failure);
            throw failure;
        }
    }

    public synchronized CardDefinition createCardDefinition(String name, String imagePath) throws SQLException {
        requireText(name, "name");
        requireText(imagePath, "imagePath");
        long id = insert("INSERT INTO card_definitions(name, image_path) VALUES (?, ?)", name, imagePath);
        return new CardDefinition(id, name, imagePath);
    }

    public synchronized Optional<CardDefinition> findCardDefinition(long cardId) throws SQLException {
        try (PreparedStatement statement = prepare("SELECT * FROM card_definitions WHERE card_id = ?", cardId);
             ResultSet rows = statement.executeQuery()) {
            return rows.next() ? Optional.of(definition(rows)) : Optional.empty();
        }
    }

    public synchronized List<CardDefinition> listCardDefinitions() throws SQLException {
        List<CardDefinition> result = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT * FROM card_definitions ORDER BY card_id")) {
            while (rows.next()) { result.add(definition(rows)); }
        }
        return List.copyOf(result);
    }

    public synchronized void updateCardDefinition(long cardId, String name, String imagePath) throws SQLException {
        requireText(name, "name");
        requireText(imagePath, "imagePath");
        requireOne(update("UPDATE card_definitions SET name = ?, image_path = ? WHERE card_id = ?",
                name, imagePath, cardId), "Card definition", cardId);
    }

    /** Referenced definitions cannot be deleted until their copies have been explicitly removed. */
    public synchronized void deleteCardDefinition(long cardId) throws SQLException {
        requireOne(update("DELETE FROM card_definitions WHERE card_id = ?", cardId), "Card definition", cardId);
    }

    /** Creates a live deck. Input IDs are in draw order; repeated definitions create distinct copies. */
    public synchronized long createDeck(List<Long> cardIds) throws SQLException {
        List<Long> ids = List.copyOf(cardIds);
        return write(() -> {
            long deckId = insert("INSERT INTO decks DEFAULT VALUES");
            for (int position = 0; position < ids.size(); position++) {
                insert("INSERT INTO card_instances(card_id, deck_id, position) VALUES (?, ?, ?)",
                        ids.get(position), deckId, position);
            }
            return deckId;
        });
    }

    public synchronized List<Long> listDeckIds() throws SQLException {
        List<Long> ids = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT deck_id FROM decks ORDER BY deck_id")) {
            while (rows.next()) { ids.add(rows.getLong(1)); }
        }
        return List.copyOf(ids);
    }

    public synchronized Hand createHand(String ownerReference) throws SQLException {
        requireText(ownerReference, "ownerReference");
        return new Hand(insert("INSERT INTO hands(owner_reference) VALUES (?)", ownerReference), ownerReference);
    }

    public synchronized List<Hand> listHands() throws SQLException {
        List<Hand> result = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT * FROM hands ORDER BY hand_id")) {
            while (rows.next()) { result.add(new Hand(rows.getLong("hand_id"), rows.getString("owner_reference"))); }
        }
        return List.copyOf(result);
    }

    public synchronized List<CardCopy> getDeckCards(long deckId) throws SQLException {
        return contained(Container.DECK, deckId);
    }

    public synchronized List<CardCopy> getHandCards(long handId) throws SQLException {
        return contained(Container.HAND, handId);
    }

    /** Adds a new copy at the end of a live deck. */
    public synchronized CardCopy addCardToDeck(long deckId, long cardId) throws SQLException {
        return write(() -> {
            requireContainer(Container.DECK, deckId);
            long position = nextPosition(Container.DECK, deckId);
            long id = insert("INSERT INTO card_instances(card_id, deck_id, position) VALUES (?, ?, ?)",
                    cardId, deckId, position);
            return copy(id);
        });
    }

    /** Empty source returns Optional.empty(); unknown deck/hand is an error. Multi-card deals are atomic. */
    public synchronized Optional<CardCopy> dealCard(long deckId, long handId) throws SQLException {
        List<CardCopy> dealt = dealCards(deckId, handId, 1);
        return dealt.isEmpty() ? Optional.empty() : Optional.of(dealt.get(0));
    }

    /** Deals up to count available copies, appending them to the hand in draw order. */
    public synchronized List<CardCopy> dealCards(long deckId, long handId, int count) throws SQLException {
        if (count < 0) { throw new IllegalArgumentException("count must be nonnegative"); }
        return write(() -> {
            requireContainer(Container.DECK, deckId);
            requireContainer(Container.HAND, handId);
            List<CardCopy> source = copies("SELECT * FROM card_instances WHERE deck_id = ? ORDER BY position LIMIT ?",
                    deckId, count);
            List<CardCopy> result = new ArrayList<>();
            for (CardCopy card : source) {
                long position = nextPosition(Container.HAND, handId);
                update("UPDATE card_instances SET deck_id = NULL, hand_id = ?, position = ? WHERE instance_id = ?",
                        handId, position, card.instanceId());
                result.add(copy(card.instanceId()));
            }
            return List.copyOf(result);
        });
    }

    /** Returns a card from a hand or unassigned state to an explicitly chosen index (0 = top). */
    public synchronized void returnCardToDeck(long instanceId, long deckId, int index) throws SQLException {
        write(() -> {
            requireContainer(Container.DECK, deckId);
            CardCopy card = copy(instanceId);
            if (card.deckId() != null) { throw new IllegalArgumentException("Card copy is already in a deck"); }
            List<Long> order = new ArrayList<>(contained(Container.DECK, deckId).stream()
                    .map(CardCopy::instanceId).toList());
            if (index < 0 || index > order.size()) { throw new IllegalArgumentException("Invalid insertion index"); }
            update("UPDATE card_instances SET deck_id = ?, hand_id = NULL, position = ? WHERE instance_id = ?",
                    deckId, nextPosition(Container.DECK, deckId), instanceId);
            order.add(index, instanceId);
            reorder(Container.DECK, deckId, order);
            return null;
        });
    }

    /** Removes container membership only; does not store a table coordinate or delete the copy. */
    public synchronized void unassignCard(long instanceId) throws SQLException {
        requireOne(update("UPDATE card_instances SET deck_id = NULL, hand_id = NULL, position = NULL WHERE instance_id = ?",
                instanceId), "Card copy", instanceId);
    }

    public synchronized void deleteCardCopy(long instanceId) throws SQLException {
        requireOne(update("DELETE FROM card_instances WHERE instance_id = ?", instanceId), "Card copy", instanceId);
    }

    /** Deleting nonempty containers is rejected by the schema rather than silently discarding copies. */
    public synchronized void deleteDeck(long deckId) throws SQLException {
        requireOne(update("DELETE FROM decks WHERE deck_id = ?", deckId), "Deck", deckId);
    }

    public synchronized void deleteHand(long handId) throws SQLException {
        requireOne(update("DELETE FROM hands WHERE hand_id = ?", handId), "Hand", handId);
    }

    public synchronized void reorderDeck(long deckId, List<Long> instanceIds) throws SQLException {
        List<Long> ids = List.copyOf(instanceIds);
        write(() -> { reorder(Container.DECK, deckId, ids); return null; });
    }

    public synchronized void reorderHand(long handId, List<Long> instanceIds) throws SQLException {
        List<Long> ids = List.copyOf(instanceIds);
        write(() -> { reorder(Container.HAND, handId, ids); return null; });
    }

    public synchronized Optional<String> getBackgroundImagePath() throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT background_image_path FROM table_settings WHERE settings_id = 1")) {
            if (!rows.next()) { throw new SQLException("Missing table settings"); }
            return Optional.ofNullable(rows.getString(1));
        }
    }

    /** Pass null to clear the image. File existence and HTTP serving belong to the asset layer. */
    public synchronized void setBackgroundImagePath(String path) throws SQLException {
        if (path != null) { requireText(path, "path"); }
        requireOne(update("UPDATE table_settings SET background_image_path = ? WHERE settings_id = 1", path),
                "Table settings", 1);
    }

    private void reorder(Container container, long id, List<Long> order) throws SQLException {
        List<CardCopy> current = contained(container, id);
        Set<Long> expected = new HashSet<>(current.stream().map(CardCopy::instanceId).toList());
        if (order.size() != current.size() || !new HashSet<>(order).equals(expected)) {
            throw new IllegalArgumentException("Order must contain every current copy exactly once");
        }
        if (current.isEmpty()) { return; }
        long max = current.stream().mapToLong(CardCopy::position).max().orElseThrow();
        long offset = Math.addExact(max, 1);
        // Check overflow before asking SQLite to perform arithmetic (which could promote to REAL).
        Math.addExact(max, offset);
        update("UPDATE card_instances SET position = position + ? WHERE " + container.column + " = ?", offset, id);
        for (int position = 0; position < order.size(); position++) {
            update("UPDATE card_instances SET position = ? WHERE instance_id = ?", position, order.get(position));
        }
    }

    private List<CardCopy> contained(Container container, long id) throws SQLException {
        requireContainer(container, id);
        return copies("SELECT * FROM card_instances WHERE " + container.column + " = ? ORDER BY position", id);
    }

    private void requireContainer(Container container, long id) throws SQLException {
        try (PreparedStatement statement = prepare("SELECT 1 FROM " + container.table + " WHERE " + container.column + " = ?", id);
             ResultSet rows = statement.executeQuery()) {
            if (!rows.next()) { throw new SQLException("Unknown " + container.table + " ID: " + id); }
        }
    }

    private long nextPosition(Container container, long id) throws SQLException {
        try (PreparedStatement statement = prepare("SELECT MAX(position) FROM card_instances WHERE " + container.column + " = ?", id);
             ResultSet rows = statement.executeQuery()) {
            rows.next();
            long max = rows.getLong(1);
            return rows.wasNull() ? 0 : Math.addExact(max, 1);
        }
    }

    private List<CardCopy> copies(String sql, Object... args) throws SQLException {
        List<CardCopy> result = new ArrayList<>();
        try (PreparedStatement statement = prepare(sql, args); ResultSet rows = statement.executeQuery()) {
            while (rows.next()) { result.add(copy(rows)); }
        }
        return List.copyOf(result);
    }

    private CardCopy copy(long id) throws SQLException {
        List<CardCopy> result = copies("SELECT * FROM card_instances WHERE instance_id = ?", id);
        if (result.isEmpty()) { throw new SQLException("Unknown card copy ID: " + id); }
        return result.get(0);
    }

    private static CardDefinition definition(ResultSet rows) throws SQLException {
        return new CardDefinition(rows.getLong("card_id"), rows.getString("name"), rows.getString("image_path"));
    }

    private static CardCopy copy(ResultSet rows) throws SQLException {
        return new CardCopy(rows.getLong("instance_id"), rows.getLong("card_id"),
                nullableLong(rows, "deck_id"), nullableLong(rows, "hand_id"), nullableLong(rows, "position"));
    }

    private static Long nullableLong(ResultSet rows, String column) throws SQLException {
        long value = rows.getLong(column);
        return rows.wasNull() ? null : value;
    }

    private PreparedStatement prepare(String sql, Object... args) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        try {
            for (int index = 0; index < args.length; index++) { statement.setObject(index + 1, args[index]); }
            return statement;
        } catch (SQLException failure) {
            try { statement.close(); } catch (SQLException closing) { failure.addSuppressed(closing); }
            throw failure;
        }
    }

    private int update(String sql, Object... args) throws SQLException {
        try (PreparedStatement statement = prepare(sql, args)) { return statement.executeUpdate(); }
    }

    private long insert(String sql, Object... args) throws SQLException {
        update(sql, args);
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT last_insert_rowid()")) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private void execute(String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) { statement.execute(sql); }
    }

    @FunctionalInterface
    private interface Work<T> { T run() throws SQLException; }

    private <T> T write(Work<T> work) throws SQLException {
        execute("BEGIN IMMEDIATE");
        try {
            T result = work.run();
            execute("COMMIT");
            return result;
        } catch (SQLException | RuntimeException failure) {
            rollback(failure);
            throw failure;
        }
    }

    private void rollback(Exception original) {
        try { execute("ROLLBACK"); } catch (SQLException rollback) { original.addSuppressed(rollback); }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) { throw new IllegalArgumentException(field + " must not be blank"); }
    }

    private static void requireOne(int affected, String entity, long id) throws SQLException {
        if (affected != 1) { throw new SQLException(entity + " not found: " + id); }
    }

    @Override
    public synchronized void close() throws SQLException { connection.close(); }
}
