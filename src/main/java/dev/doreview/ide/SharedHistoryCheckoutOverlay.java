package dev.doreview.ide;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Local checkout provenance. Callers own the connection lock and transaction boundaries. */
final class SharedHistoryCheckoutOverlay {
    private final Connection connection;

    SharedHistoryCheckoutOverlay(Connection connection) { this.connection = connection; }

    void initialize() throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS known_checkouts(checkout_token TEXT PRIMARY KEY)");
            statement.execute("CREATE TABLE IF NOT EXISTS checkout_basis(checkout_token TEXT NOT NULL,event_id TEXT NOT NULL,PRIMARY KEY(checkout_token,event_id))");
            statement.execute("CREATE TABLE IF NOT EXISTS checkout_events(checkout_token TEXT NOT NULL,event_id TEXT NOT NULL,PRIMARY KEY(checkout_token,event_id))");
            statement.execute("CREATE TABLE IF NOT EXISTS checkout_cursor(id INTEGER PRIMARY KEY CHECK(id=1),checkout_token TEXT NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS local_event_owners(event_id TEXT PRIMARY KEY,checkout_token TEXT NOT NULL)");
        }
    }

    /** Select a first-visit seed without persisting a checkout that may still fail validation. */
    Set<String> basis(String token) throws SQLException {
        boolean known;
        try (var query = connection.prepareStatement("SELECT 1 FROM known_checkouts WHERE checkout_token=?")) {
            query.setString(1, token);
            try (var rows = query.executeQuery()) { known = rows.next(); }
        }
        var basis = new TreeSet<String>();
        String queryText = known ? "SELECT event_id FROM checkout_basis WHERE checkout_token=?"
                : "SELECT event_id FROM checkout_events WHERE checkout_token=(SELECT checkout_token FROM checkout_cursor WHERE id=1)";
        try (var query = connection.prepareStatement(queryText)) {
            if (known) query.setString(1, token);
            try (var rows = query.executeQuery()) { while (rows.next()) basis.add(rows.getString(1)); }
        }
        return Set.copyOf(basis);
    }

    /** Persist only alongside the successful scan; another process cannot silently reseed it. */
    void remember(String token, Set<String> basis) throws SQLException {
        int inserted;
        try (var insert = connection.prepareStatement("INSERT OR IGNORE INTO known_checkouts VALUES(?)")) {
            insert.setString(1, token); inserted = insert.executeUpdate();
        }
        if (inserted == 0) {
            if (!basis(token).equals(basis)) throw new IllegalStateException("Checkout review basis changed during reconciliation");
            return;
        }
        for (String id : basis) try (var insert = connection.prepareStatement("INSERT INTO checkout_basis VALUES(?,?)")) {
            insert.setString(1, token); insert.setString(2, id); insert.executeUpdate();
        }
    }

    Map<String, String> owners() throws SQLException {
        var owners = new HashMap<String, String>();
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT event_id,checkout_token FROM local_event_owners")) {
            while (rows.next()) owners.put(rows.getString(1), rows.getString(2));
        }
        return Map.copyOf(owners);
    }

    void own(String eventId, String token) throws SQLException {
        try (var insert = connection.prepareStatement("INSERT INTO local_event_owners VALUES(?,?)")) {
            insert.setString(1, eventId); insert.setString(2, token); insert.executeUpdate();
        }
    }

    /** The manifest is a seed for unseen branches; it never restores native events on this branch. */
    void observe(String token) throws SQLException {
        try (var delete = connection.prepareStatement("DELETE FROM checkout_events WHERE checkout_token=? AND event_id NOT IN (SELECT id FROM events WHERE active=1)")) {
            delete.setString(1, token); delete.executeUpdate();
        }
        try (var insert = connection.prepareStatement("INSERT OR IGNORE INTO checkout_events SELECT ?,id FROM events WHERE active=1")) {
            insert.setString(1, token); insert.executeUpdate();
        }
        try (var update = connection.prepareStatement("INSERT INTO checkout_cursor VALUES(1,?) ON CONFLICT(id) DO UPDATE SET checkout_token=excluded.checkout_token WHERE checkout_token<>excluded.checkout_token")) {
            update.setString(1, token); update.executeUpdate();
        }
    }

    Set<String> allBasisEvents() throws SQLException {
        var result = new TreeSet<String>();
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT DISTINCT event_id FROM checkout_basis")) {
            while (rows.next()) result.add(rows.getString(1));
        }
        return result;
    }
}
