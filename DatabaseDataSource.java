package org.framework.datasource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A {@code <vs:Database>} data source: one compiled statement run against a
 * pooled connection.
 *
 * <p>
 * For a SELECT, the first row of the result is the row the request uses and
 * every row is counted so {@code Name.rowCount} can report the total. For an
 * INSERT, UPDATE or DELETE there is no row; {@code rowCount} is the number of
 * rows the statement changed. Connections run in auto-commit mode, so a change
 * is committed as soon as it runs.
 */
public class DatabaseDataSource {

    private static final int QUERY_TIMEOUT_SECONDS = 30;
    private static final int FETCH_SIZE = 100;

    private final String name;
    private final String connectionName;
    private final SqlTemplate template;
    private final ConnectionPoolRegistry connectionPools;

    public DatabaseDataSource(String name, String connectionName, SqlTemplate template,
            ConnectionPoolRegistry connectionPools) {
        this.name = name;
        this.connectionName = connectionName;
        this.template = template;
        this.connectionPools = connectionPools;
    }

    public String getName() {
        return name;
    }

    public String getConnectionName() {
        return connectionName;
    }

    public SqlTemplate getTemplate() {
        return template;
    }

    /**
     * Runs the query with {@code values} bound in the order of
     * {@link SqlTemplate#getVariables()}.
     */
    public QueryResult execute(List<String> values) throws SQLException {
        if (values.size() != template.getVariables().size()) {
            throw new DataSourceException("Expected " + template.getVariables().size()
                    + " values for data source " + name + " but got " + values.size());
        }

        Connection connection = connectionPools.borrow(connectionName);
        try (PreparedStatement statement = connection.prepareStatement(template.getSql())) {
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            statement.setFetchSize(FETCH_SIZE);
            for (int i = 0; i < values.size(); i++) {
                statement.setString(i + 1, values.get(i));
            }

            if (!statement.execute()) {
                // INSERT / UPDATE / DELETE: report how many rows changed.
                return new QueryResult(null, Math.max(0, statement.getUpdateCount()), true);
            }
            try (ResultSet resultSet = statement.getResultSet()) {
                ResultSetMetaData metaData = resultSet.getMetaData();
                Map<String, String> firstRow = null;
                int rowCount = 0;
                while (resultSet.next()) {
                    if (rowCount == 0) {
                        firstRow = readRow(resultSet, metaData);
                    }
                    rowCount++;
                }
                return new QueryResult(firstRow, rowCount, false);
            }
        } finally {
            connectionPools.release(connectionName, connection);
        }
    }

    private static Map<String, String> readRow(ResultSet resultSet, ResultSetMetaData metaData) throws SQLException {
        Map<String, String> row = new LinkedHashMap<>();
        for (int column = 1; column <= metaData.getColumnCount(); column++) {
            String label = metaData.getColumnLabel(column);
            String value = resultSet.getString(column);
            // A join can return the same label twice - the first one wins.
            row.putIfAbsent(label, value == null ? "" : value);
        }
        return row;
    }

    /**
     * The first row of a query (null when there were none) and how many rows it
     * returned - or, for an INSERT/UPDATE/DELETE, how many rows it changed.
     */
    public static final class QueryResult {
        private final Map<String, String> firstRow;
        private final int rowCount;
        private final boolean update;

        QueryResult(Map<String, String> firstRow, int rowCount, boolean update) {
            this.firstRow = firstRow == null ? null : Collections.unmodifiableMap(firstRow);
            this.rowCount = rowCount;
            this.update = update;
        }

        /** True for INSERT/UPDATE/DELETE, where rowCount is the number of rows changed. */
        public boolean isUpdate() {
            return update;
        }

        public Map<String, String> getFirstRow() {
            return firstRow;
        }

        public int getRowCount() {
            return rowCount;
        }
    }
}
