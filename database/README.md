# Host-owned tabletop database plan

This plan uses Java, JDBC, and SQLite for the approved scope: built-in and
custom card definitions, individual card copies, current decks, player hands,
their order, and a table background image. The initial SQL is in `schema.sql`.
The Java/JDBC component is implemented in
`virtualTabletop/database/TabletopDatabase.java`. The existing console game
and the browser/network layer have not yet been connected to it.

## Ownership and scope

The hosting player runs the backend and owns the authoritative SQLite file.
Joining browsers receive permitted card data and images through the host's
networking/web code. They do not open or synchronize the SQLite file.
IDs identify records within this host's database; they are not global IDs
across separate hosts' databases. The host assigns a local ID when importing
a custom card from another player.

The initial schema represents one current session per database file. It does
not define a multi-session save library, reusable deck templates, accounts,
card rules, coordinates, rotation, turn history, or discard-pile behavior.
Those would need separate requirements and approval. The session's stored
deck and hand contents can be reloaded; this is not a complete game save.

## Tables and fields

| Table | Fields | Meaning |
| --- | --- | --- |
| `card_definitions` | `card_id`, `name`, `image_path` | A card design and its artwork. The same structure supports built-in and custom cards. |
| `decks` | `deck_id` | A current deck used during play. Its contents live in `card_instances`. |
| `hands` | `hand_id`, `owner_reference` | A hand belonging to a player identified by the game/network layer. No account table is needed. |
| `card_instances` | `instance_id`, `card_id`, `deck_id`, `hand_id`, `position` | An individual copy of a definition, its current container, and its order. |
| `table_settings` | `settings_id`, `background_image_path` | One settings row with ID 1. A NULL path means no background image is selected. |

IDs are integer primary keys. Image paths are relative to a configured asset
root; they are not absolute Windows paths. Names and paths need not be unique:
different definitions can have the same name or share artwork.

`owner_reference` is an opaque player key supplied by the game/network layer,
not a display name. That layer must preserve or re-associate the key when a
player reconnects so the correct hand can be retrieved.

Each copy is in at most one deck or hand. A copy with neither reference is
unassigned and has a NULL position. This permits removing a card from a hand
without losing its identity, but does not store its location on the table.
Deleting a referenced definition, deck, or hand is rejected until its card
copies have been handled explicitly. Nothing is silently deleted by cascade.

## Ordering and card operations

For decks and hands, positions are zero-based. The lowest position in a deck
is the next card to draw. Gaps are allowed after moves; positions do not need
to be renumbered after every draw. A position is unique within its container.
Always use `ORDER BY position`; SQLite's incidental row order is not deck order.

- Create a custom definition: insert its name and host-local image reference;
  return the generated `card_id`. This alone does not add a copy to a deck.
- Build a current deck: insert a `decks` row, then insert card copies pointing
  to their definitions and that deck with positions 0, 1, 2, and so on. Multiple
  copies can refer to the same definition.
- Create a hand: insert the owner reference supplied by the game code and
  return the generated `hand_id`.
- Deal: in one short write transaction, select the lowest-position copy in
  the source deck, calculate the next position in the target hand, and update
  that same copy's `deck_id` to NULL, `hand_id` to the target, and `position` to
  the calculated value. An empty hand starts at 0; otherwise use MAX + 1.
- Return a card: move the existing copy into a deck in one transaction.
  The caller supplies the requested final ordering; do not silently decide
  whether a returned card goes on top or bottom.
- Reorder or shuffle: update positions in a transaction. Validate that the
  requested IDs are exactly the container's current copies, with no duplicates.
  To avoid unique-position collisions during swaps, first move its positions
  to distinct temporary integers above both the existing and final ranges,
  then assign final positions 0 through N - 1. Roll back the whole operation
  if validation or any update fails.
- Set the background: update the single `table_settings` row with the stored
  image reference, or NULL to clear it.

For example, two copies of one custom design can have different locations:

| instance_id | card_id | deck_id | hand_id | position |
| --- | --- | --- | --- | --- |
| 101 | 7 | 1 | NULL | 0 |
| 102 | 7 | NULL | 3 | 0 |

Both refer to definition 7. Dealing copy 101 does not change copy 102 or the
shared definition. Deck and hand sizes are calculated from their copy rows.

## Java/JDBC boundary

`TabletopDatabase` provides connection/initialization code and repository
methods for definitions, decks, hands, card moves/order, and the background
setting. Methods return immutable Java records/lists; networking and HTML
rendering belong to the other project components.

Use the Xerial SQLite JDBC driver and a configurable database file location.
Enable `PRAGMA foreign_keys = ON` on every connection before transactions.
Use prepared statements for values, close JDBC resources, and commit related
changes together. A host-side write queue or equivalent serialization should
keep read-modify-write operations from racing. Commit before broadcasting a
successful change. On failure, roll back and report it to the calling code.

The implementation serializes public methods on each database instance and
starts multi-statement writes with `BEGIN IMMEDIATE`. SQLite coordinates
writes across separate connections to the same file; the busy timeout is five
seconds. Calls that still encounter contention throw `SQLException` so the
caller can report or retry the action. A failed transactional write rolls back.

Reads needed by the game include definitions, a deck's ordered copies, a hand's
ordered copies, and the background reference. A reconnect snapshot should use
a consistent read transaction. The networking layer decides which information
each joining player may receive; do not send the entire database as a snapshot.
Preserving deck order does not imply revealing hidden draw order or other
players' hands. Share those details only when the game's rules allow it.

The existing `Card(Suit, CardFace)` record and generated 52-card `Deck` do not
yet represent arbitrary custom designs or database copy identities. Integration
must retain `card_id` and `instance_id` when building runtime objects. Suits,
rank, and values remain in the existing Java logic; the approved database does
not add those fields or custom game rules.

## Images and the HTML frontend

Store files on the host separately from the database. For the existing artwork,
if the project folder is the configured asset root, a stored reference can be:

```text
images/Card Face Textures/ace_of_spades.png
```

The host's web code maps the approved image directory to an HTTP route and
constructs a URL such as `/images/Card%20Face%20Textures/ace_of_spades.png`.
The browser uses that URL in an `img` element. The same approach serves the
background. A database path by itself does not make a file web-accessible.

The custom-card flow crosses team boundaries:

1. File-transfer code accepts the player's image and stores it under the host's
   managed asset root, assigning a path that does not overwrite existing artwork.
2. The database component inserts the card name and the resulting relative path.
3. The host assigns its `card_id` and shares permitted metadata and an image URL.
4. Other browsers download the image from the host. They do not need matching
   local Windows paths or a local SQLite database.

Paths must be resolved inside the managed image directory. The file-serving
layer must enforce that boundary; SQL only checks that a path is nonempty.
An image referenced in SQLite must actually be present before registering the
definition. A SQL rollback cannot undo a filesystem operation; if registration
fails, file-storage code handles any newly created orphan file. Keep the image
folder with the database when transferring the host's saved data.

## Implementation and acceptance checks

The Java component initializes an empty file automatically from the bundled
schema and reuses an existing database on reopening. It rejects unrelated or
incomplete table layouts instead of dropping data. It is not a schema migration
tool. Initialization and failure checks are transactional.

The added JUnit integration-test file and its dependency have been removed at
the project's request. Build the Java sources with `mvnw.cmd compile` from the
root. The original `virtualTabletop/CardTest.java` remains a standalone deck
visualization program, not a JUnit test.

Remaining integration work is image seeding, runtime game-model integration,
and networking/web implementation. In particular, actual image URL loading
must be verified when the team's web-serving code is available.

Schema verification passed using a temporary file-backed SQLite database:
initialization; shared definitions with multiple copies; independent deck
ordering; 16 rejected invalid writes; ordered dealing; transactional reordering;
rollback after a failed multi-card move; and persistence of definitions, deck
order, hands, and background after reopening. Foreign-key and integrity checks
were clean. The former JDBC suite also passed all 11 tests before its removal.
These are historical verification results, not tests included in the current
source tree; they do not verify the future web integration.

## Calling the Java API

Application Java files retain the original `virtualTabletop/` folder, with
database classes in `virtualTabletop/database/`. Maven uses the project root
as its source root and compiles only `virtualTabletop/**/*.java`. The added
`src/main/java` and `src/test/java` layout is no longer used.
Open the project root (the folder containing `pom.xml`) in VS Code and let it
import the Maven project. If stale package or dependency errors remain, run
`Java: Clean Java Language Server Workspace` from the Command Palette and
allow the project to reload and resolve dependencies.

The project uses JDK 17+ and Maven with the Xerial SQLite JDBC dependency. The
wrapper handles Maven installation; no separate SQLite server or Rust service
is required. The schema is packaged as a classpath resource, so opening a
database does not depend on the working directory containing `schema.sql`.

```java
import java.nio.file.Path;
import java.util.List;
import virtualTabletop.database.TabletopDatabase;

try (var db = TabletopDatabase.open(Path.of("data/tabletop.db"))) {
    // Run this creation flow once when the host accepts a new card/deck.
    // The image-storage code must already have stored this file.
    var dragon = db.createCardDefinition("Fire Dragon", "images/custom/dragon.png");
    long deckId = db.createDeck(List.of(dragon.cardId(), dragon.cardId()));
    var hand = db.createHand("player-key-from-network-layer");

    var dealt = db.dealCard(deckId, hand.handId());
    var remaining = db.getDeckCards(deckId);
    var held = db.getHandCards(hand.handId());
    db.setBackgroundImagePath("images/backgrounds/wood.png");
}
```

Persist/reuse the returned IDs. Repeating creation calls intentionally creates
new records; opening a database alone does not seed or duplicate anything.

| API | Contract |
| --- | --- |
| `open(Path)` | Creates parent directories, initializes an empty database, and returns a closeable connection owner. |
| `createCardDefinition`, `findCardDefinition`, `listCardDefinitions`, `updateCardDefinition`, `deleteCardDefinition` | Card design CRUD; lookup returns `Optional`, and referenced definitions cannot be deleted. |
| `createDeck(List<Long>)` | Creates a live deck from definition IDs in draw order; duplicate IDs create distinct copies. Failure rolls back the whole deck. |
| `listDeckIds`, `createHand`, `listHands` | Lists current deck IDs and manages hand identity using caller-supplied player keys. |
| `getDeckCards`, `getHandCards` | Returns copies in ascending stored position order; an unknown container is an error. |
| `addCardToDeck` | Appends a newly created copy of an existing definition. |
| `dealCard`, `dealCards` | Moves up to the requested number of available copies to the end of a hand. A single draw from an empty deck returns `Optional.empty()`; multi-card draws are atomic. |
| `returnCardToDeck(instanceId, deckId, index)` | Moves a hand/unassigned copy into a deck at an explicit index, preserving all other cards' relative order. Index 0 is the top; deck size is the bottom. |
| `reorderDeck`, `reorderHand` | Accepts an exact permutation of current instance IDs; duplicate, missing, or foreign IDs are rejected. The caller can shuffle that list first. |
| `unassignCard` | Clears deck/hand membership and position without deleting the copy or storing a board location. |
| `deleteCardCopy`, `deleteDeck`, `deleteHand` | Explicit deletion; nonempty containers cannot be deleted. |
| `getBackgroundImagePath`, `setBackgroundImagePath` | Reads or writes the background reference; pass null to clear it. |
| `close()` | Closes the JDBC connection; use try-with-resources. |

`CardCopy` includes definition ID, copy ID, nullable deck/hand references, and
nullable position. Find its name/artwork through `findCardDefinition(cardId)`.
Definition updates affect every copy referencing that definition. Invalid
arguments throw `IllegalArgumentException`; database errors, unknown IDs in
mutations, and rejected foreign keys throw `SQLException`. File initialization
can also throw `IOException`.

Separate read calls do not form an atomic session snapshot. Before adding a
reconnect/snapshot feature, coordinate with the game layer on a consistent read
boundary and which fields each player may see. Paths are stored as supplied;
this API does not read, upload, serve, or delete image files.

References: [SQLite JDBC driver](https://github.com/xerial/sqlite-jdbc),
[SQLite foreign keys](https://www.sqlite.org/foreignkeys.html),
[SQLite application-server use](https://www.sqlite.org/whentouse.html),
[HTML images](https://developer.mozilla.org/en-US/docs/Learn_web_development/Core/Structuring_content/HTML_images).
