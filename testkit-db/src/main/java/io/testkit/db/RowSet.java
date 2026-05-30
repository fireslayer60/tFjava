package io.testkit.db;

import io.testkit.core.TestKitException;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Immutable result of a SQL query — a list of column→value maps. */
public final class RowSet {

    private final List<Map<String, Object>> rows;
    private final String sql;

    RowSet(List<Map<String, Object>> rows, String sql) {
        this.rows = List.copyOf(rows);
        this.sql  = sql;
    }

    public int size()           { return rows.size(); }
    public boolean isEmpty()    { return rows.isEmpty(); }
    public List<Map<String, Object>> rows() { return rows; }

    public Map<String, Object> first() {
        if (rows.isEmpty()) throw new TestKitException("RowSet is empty — no first row. SQL: " + sql);
        return rows.get(0);
    }

    public Optional<Map<String, Object>> firstOrEmpty() {
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @SuppressWarnings("unchecked")
    public <T> T column(int row, String col) {
        if (row >= rows.size()) throw new TestKitException("Row " + row + " out of bounds (size=" + rows.size() + ")");
        Object val = rows.get(row).get(col);
        if (val == null && !rows.get(row).containsKey(col)) {
            throw new TestKitException("Column '" + col + "' not found. Available: " + rows.get(row).keySet());
        }
        return (T) val;
    }

    /** Get a column value from the first row. */
    public <T> T value(String col) { return column(0, col); }

    // ── Fluent assertions ─────────────────────────────────────────────────────

    public RowSet assertRowCount(int expected) {
        if (rows.size() != expected) {
            throw new TestKitException(
                    "Expected %d row(s) but got %d. SQL: %s".formatted(expected, rows.size(), sql));
        }
        return this;
    }

    public RowSet assertNotEmpty() {
        if (rows.isEmpty()) throw new TestKitException("Expected rows but query returned nothing. SQL: " + sql);
        return this;
    }

    public RowSet assertEmpty() {
        if (!rows.isEmpty()) throw new TestKitException("Expected no rows but got " + rows.size() + ". SQL: " + sql);
        return this;
    }

    public RowSet assertColumn(String col, Object expected) {
        Object actual = value(col);
        if (!String.valueOf(expected).equals(String.valueOf(actual))) {
            throw new TestKitException(
                    "Column '%s': expected '%s' but got '%s'".formatted(col, expected, actual));
        }
        return this;
    }

    public RowSet assertColumnNull(String col) {
        Object actual = value(col);
        if (actual != null) throw new TestKitException("Column '" + col + "' expected NULL but got: " + actual);
        return this;
    }

    public RowSet assertColumnNotNull(String col) {
        Object actual = value(col);
        if (actual == null) throw new TestKitException("Column '" + col + "' was unexpectedly NULL");
        return this;
    }
}
