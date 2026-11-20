# Understanding TabletopDatabase.java

`TabletopDatabase.java` connects the Java application to the host's SQLite
database through JDBC. Game code calls Java methods such as `createDeck()` and
`dealCard()` instead of writing SQL throughout the application.

One `TabletopDatabase` object owns one JDBC connection. The class manages
storage for card definitions, individual card copies, current decks, player
hands, card order, and the table background image reference.

It does not transfer images, render HTML, enforce player permissions, or decide
game rules. Those responsibilities belong to the game and networking layers.

## 1. Returned data: Java records

The three records at the top of the class hold data returned from SQLite.
Records are compact Java data types with automatically generated accessors,
such as `card.cardId()` or `hand.ownerReference()`.

| Record | Fields | Meaning |
| --- | --- | --- |
| `CardDefinition` | `cardId`, `name`, `imagePath` | A card design and its image reference. |
| `CardCopy` | `instanceId`, `cardId`, `deckId`, `handId`, `position` | An individual copy of a definition and its current placement/order. |
| `Hand` | `handId`, `ownerReference` | A hand and the player key associated with it. |

A definition and a copy represent different things. Two Fire Dragon cards can
share one definition, name, and image, while each copy has its own `instanceId`.
One copy can be in a deck while another is in a player's hand.

Some `CardCopy` fields use `Long` rather than `long` because they can be `null`.
For example, a copy in a hand has no `deckId`. An unassigned copy has no deck,
hand, or position. `ownerReference` is a player key supplied by the game or
networking code, not a database-managed user account.

## 2. Opening the database

Call the static `open(Path file)` method to create a database object:

```java
TabletopDatabase.open(Path.of("data/tabletop.db"));
```

`open()` resolves the path, creates parent directories if necessary, opens the
SQLite connection, and calls `initialize()`. If setup fails, it closes the
connection before passing the error back to the caller.

The private constructor configures two SQLite settings:

- `PRAGMA foreign_keys = ON` enables enforcement of relationships between
  tables. For example, a copy cannot reference a nonexistent card definition.
- `PRAGMA busy_timeout = 5000` allows SQLite to wait up to five seconds for a
  database lock rather than immediately failing because another connection
  is using it.

### initialize()

For an empty database, `initialize()` reads the bundled
`database/schema.sql` resource and creates its tables. Maven copies this
resource from the project's [schema file](../../database/schema.sql).

For an existing database, it checks that the expected table names and required
columns are present, and that there is exactly one settings row with ID 1.
It preserves existing records. These checks are not a full schema comparison
or an automatic migration system.

Initialization runs inside a transaction. Its simple schema reader removes
full-line SQL comments and splits statements at semicolons. It is intended for
the current schema, not arbitrary SQL scripts containing triggers or semicolons
inside strings.

## 3. Card definitions

| Method | Purpose |
| --- | --- |
| `createCardDefinition(name, imagePath)` | Inserts a definition and returns its generated ID, name, and image path. |
| `findCardDefinition(cardId)` | Returns an `Optional` containing the definition, or an empty `Optional` when it does not exist. |
| `listCardDefinitions()` | Returns all definitions in card-ID order. |
| `updateCardDefinition(cardId, name, imagePath)` | Changes the definition's name and artwork reference. All its copies share those changes. |
| `deleteCardDefinition(cardId)` | Deletes a definition; foreign-key enforcement rejects deletion while copies still reference it. |

Creating a definition alone does not place a card in a deck. The image path is
stored as text. This class does not verify, load, move, or delete the image file.

## 4. Decks, hands, and card copies

`createDeck(List<Long> cardIds)` creates a live deck from definition IDs in the
specified draw order. Repeating a definition ID creates separate copies of the
same design. The returned value is the new deck ID.

`addCardToDeck(deckId, cardId)` creates another copy at the end of an existing
deck. `createHand(ownerReference)` creates a hand and returns its ID and owner
reference. `listDeckIds()` and `listHands()` retrieve the existing containers.

`getDeckCards(deckId)` and `getHandCards(handId)` return copies ordered by their
stored `position`. The lowest deck position is the next card to draw. Gaps in
position numbers are allowed after moves.

The private `Container` enum supplies the trusted table and column names for
decks and hands. This lets shared helper methods work with either container
without duplicating their SQL logic.

## 5. Dealing and returning cards

`dealCard(deckId, handId)` calls `dealCards()` with a count of one. It returns an
empty `Optional` if the source deck is empty. Unknown deck or hand IDs are errors.

`dealCards(deckId, handId, count)`:

1. Checks that both containers exist.
2. Selects up to `count` available copies from the deck in position order.
3. Clears each copy's deck reference and assigns its destination hand.
4. Appends each copy to the end of that hand's current order.
5. Returns the moved copies after the transaction commits.

The same individual copies move between containers; dealing does not delete
their definitions or generate replacement copies. If fewer cards remain than
requested, the method deals the available cards. Negative counts are rejected.

`returnCardToDeck(instanceId, deckId, index)` moves a hand-held or unassigned copy
back into a deck. The caller explicitly chooses its index: 0 is the top, and
the current deck size is the bottom. Other cards retain their relative order.
A copy already in a deck is rejected by this method.

`unassignCard(instanceId)` clears a copy's deck, hand, and position without
deleting it. This does not record a location on the table.

`deleteCardCopy()`, `deleteDeck()`, and `deleteHand()` perform explicit deletion.
Nonempty decks and hands cannot be deleted until their copies are handled.

## 6. Preserving or changing order

`reorderDeck()` and `reorderHand()` accept an exact list of the container's
current copy IDs in their desired order. Missing, repeated, or foreign IDs are
rejected. The caller can shuffle that list before supplying it.

The private `reorder()` helper first moves current position numbers above the
existing range, then assigns final positions starting at 0. This temporary
step avoids unique-position conflicts while swapping cards. Arithmetic checks
prevent overflowing the position numbers.

## 7. Table background

`getBackgroundImagePath()` returns an `Optional<String>` containing the current
background reference. `setBackgroundImagePath(path)` changes it; passing `null`
clears it. A blank string is rejected.

As with card artwork, the image must be managed and served by other code.

## 8. Transactions and concurrent access

The private `write()` helper wraps multi-step changes in a transaction:

```text
BEGIN IMMEDIATE
    Perform the requested changes
    COMMIT if successful
    ROLLBACK if an operation fails
```

For example, if dealing several cards fails after one copy has moved, rollback
undoes that earlier move too. Deck creation, dealing, returning, and reordering
use this helper.

Public instance methods are `synchronized`, so threads sharing the same
`TabletopDatabase` object use its connection one at a time. SQLite's write lock
also coordinates separate connections to the same database file. This does not
implement peer-to-peer synchronization; joining players communicate with the
host through the networking layer.

Separate read-method calls do not form one atomic snapshot. The game layer must
also decide which information each player is allowed to receive.

## 9. SQL and validation helpers

| Helper | Responsibility |
| --- | --- |
| `prepare()` | Creates a prepared statement and binds values to its `?` placeholders. Values are treated as data rather than SQL commands. |
| `update()` | Executes an INSERT, UPDATE, or DELETE and returns the affected-row count. |
| `insert()` | Executes an insert and reads its generated ID using `last_insert_rowid()` on the same connection. |
| `execute()` | Runs fixed SQL such as transaction commands or initialization statements. |
| `contained()` | Checks a container and retrieves its copies in position order. |
| `requireContainer()` | Rejects an unknown deck or hand. |
| `nextPosition()` | Returns 0 for an empty container or one greater than its highest position. |
| `copies()`, `copy()`, `definition()`, `nullableLong()` | Convert JDBC query results into Java records while preserving SQL NULL values. |
| `requireText()` | Rejects missing or blank text arguments. |
| `requireOne()` | Checks that a mutation affected exactly one expected record. |
| `rollback()` | Attempts to undo the current transaction, retaining rollback errors alongside the original failure. |

Database failures are reported through `SQLException`. Invalid arguments often
produce `IllegalArgumentException`; opening the database can also produce
`IOException`. Returned lists are unmodifiable.

## 10. Example usage

The following example belongs inside a Java method that handles or declares
`SQLException` and `IOException`. Required imports are shown:

```java
import java.nio.file.Path;
import java.util.List;
import virtualTabletop.database.TabletopDatabase;

try (var db = TabletopDatabase.open(Path.of("data/tabletop.db"))) {
    // The asset layer must already have stored this image on the host.
    var dragon = db.createCardDefinition(
        "Fire Dragon", "images/custom/dragon.png"
    );

    // One definition, but two independent copies in this deck.
    long deckId = db.createDeck(List.of(dragon.cardId(), dragon.cardId()));
    var hand = db.createHand("player-1");

    var dealt = db.dealCard(deckId, hand.handId());
    var remaining = db.getDeckCards(deckId);
    var held = db.getHandCards(hand.handId());
}
```

The class implements `AutoCloseable`, so leaving the try-with-resources block
calls `close()` and releases the JDBC connection. Each repeated creation call
creates new records; opening the file by itself does not duplicate or seed data.

The existing console game is not yet connected to this API. For an entry point
that initializes a database and prints its summary, see `DatabaseMain.java` in
this directory. The broader schema and integration plan is in the
[database guide](../../database/README.md).
