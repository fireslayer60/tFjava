package io.testkit.seed;

import io.testkit.core.TestKitException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

/**
 * Inserts rows into a relational database via plain JDBC.
 * Tracks inserted PKs so callers can run rollback-style cleanup.
 */
public final class JdbcSeeder {

    private static final Logger log = LoggerFactory.getLogger(JdbcSeeder.class);

    private final DataSource dataSource;

    public JdbcSeeder(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Insert a single row into {@code table}.
     *
     * @param table  target table name
     * @param values column → value map
     * @return generated key(s) or empty map if not supported
     */
    public Map<String, Object> insert(String table, Map<String, Object> values) {
        List<String> cols = new ArrayList<>(values.keySet());
        String placeholders = String.join(", ", Collections.nCopies(cols.size(), "?"));
        String colNames = String.join(", ", cols);
        String sql = "INSERT INTO " + table + " (" + colNames + ") VALUES (" + placeholders + ")";

        log.debug("Seeding: {}", sql);

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {

            for (int i = 0; i < cols.size(); i++) {
                ps.setObject(i + 1, values.get(cols.get(i)));
            }
            ps.executeUpdate();

            Map<String, Object> keys = new LinkedHashMap<>();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                ResultSetMetaData meta = rs.getMetaData();
                if (rs.next()) {
                    for (int i = 1; i <= meta.getColumnCount(); i++) {
                        keys.put(meta.getColumnName(i), rs.getObject(i));
                    }
                }
            }
            return keys;

        } catch (SQLException e) {
            throw new TestKitException("Failed to insert into table '" + table + "': " + e.getMessage(), e);
        }
    }

    /** Insert multiple rows, returning a list of generated-key maps. */
    public List<Map<String, Object>> insertAll(String table, List<Map<String, Object>> rows) {
        List<Map<String, Object>> generatedKeys = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            generatedKeys.add(insert(table, row));
        }
        return generatedKeys;
    }

    /** Execute arbitrary SQL (e.g. for setup sequences). */
    public void execute(String sql, Object... params) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new TestKitException("SQL execution failed: " + e.getMessage(), e);
        }
    }

    /** Delete rows matching a WHERE clause. */
    public int deleteWhere(String table, String where, Object... params) {
        String sql = "DELETE FROM " + table + " WHERE " + where;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new TestKitException("Delete failed: " + e.getMessage(), e);
        }
    }

    /** Truncate a table (use with care — no FK checks). */
    public void truncate(String table) {
        execute("TRUNCATE TABLE " + table);
    }
}
