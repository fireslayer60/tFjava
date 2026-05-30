package io.testkit.db;

import io.testkit.core.TestKitException;

import javax.sql.DataSource;
import java.sql.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Lightweight JDBC wrapper that returns {@link RowSet} instances. */
public final class DbClient {

    private final DataSource dataSource;

    public DbClient(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public RowSet query(String sql, Object... params) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);

            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData meta = rs.getMetaData();
                int colCount = meta.getColumnCount();
                List<Map<String, Object>> rows = new ArrayList<>();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= colCount; i++) {
                        row.put(meta.getColumnLabel(i).toLowerCase(), rs.getObject(i));
                    }
                    rows.add(row);
                }
                return new RowSet(rows, sql);
            }
        } catch (SQLException e) {
            throw new TestKitException("DB query failed: " + e.getMessage(), e);
        }
    }

    public int execute(String sql, Object... params) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new TestKitException("DB execute failed: " + e.getMessage(), e);
        }
    }

    /** Count rows matching a WHERE clause. */
    public long count(String table, String where, Object... params) {
        String sql = where != null && !where.isBlank()
                ? "SELECT COUNT(*) FROM " + table + " WHERE " + where
                : "SELECT COUNT(*) FROM " + table;
        RowSet rs = query(sql, params);
        Object val = rs.first().values().iterator().next();
        return ((Number) val).longValue();
    }

    /** Check if a row exists. */
    public boolean exists(String table, String where, Object... params) {
        return count(table, where, params) > 0;
    }
}
