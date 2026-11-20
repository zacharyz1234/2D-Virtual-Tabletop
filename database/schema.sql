-- Initial schema for one host's current tabletop session.
-- Run against a new SQLite database, not an existing populated schema.
-- Java must also enable foreign_keys on EVERY connection before transactions.
PRAGMA foreign_keys = ON;

BEGIN TRANSACTION;

CREATE TABLE card_definitions (
    card_id INTEGER PRIMARY KEY,
    name TEXT NOT NULL CHECK (length(trim(name)) > 0),
    image_path TEXT NOT NULL CHECK (length(trim(image_path)) > 0)
);

CREATE TABLE decks (
    deck_id INTEGER PRIMARY KEY
);

CREATE TABLE hands (
    hand_id INTEGER PRIMARY KEY,
    owner_reference TEXT NOT NULL CHECK (length(trim(owner_reference)) > 0)
);

CREATE TABLE card_instances (
    instance_id INTEGER PRIMARY KEY,
    card_id INTEGER NOT NULL REFERENCES card_definitions(card_id),
    deck_id INTEGER REFERENCES decks(deck_id),
    hand_id INTEGER REFERENCES hands(hand_id),
    position INTEGER,
    -- A copy cannot be in a deck and a hand simultaneously.
    CHECK (deck_id IS NULL OR hand_id IS NULL),
    -- Contained cards have an order; unassigned cards have no order.
    CHECK (
        (deck_id IS NULL AND hand_id IS NULL AND position IS NULL)
        OR
        ((deck_id IS NOT NULL OR hand_id IS NOT NULL)
         AND position IS NOT NULL
         AND typeof(position) = 'integer'
         AND position >= 0)
    )
);

CREATE UNIQUE INDEX card_instances_deck_position
    ON card_instances(deck_id, position) WHERE deck_id IS NOT NULL;

CREATE UNIQUE INDEX card_instances_hand_position
    ON card_instances(hand_id, position) WHERE hand_id IS NOT NULL;

CREATE INDEX card_instances_definition ON card_instances(card_id);

CREATE TABLE table_settings (
    settings_id INTEGER PRIMARY KEY CHECK (settings_id = 1),
    background_image_path TEXT
        CHECK (background_image_path IS NULL
               OR length(trim(background_image_path)) > 0)
);

-- NULL means no background image has been selected.
INSERT INTO table_settings(settings_id, background_image_path) VALUES (1, NULL);

COMMIT;
