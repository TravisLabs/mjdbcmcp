package com.travislabs.mjdbcmcp.sql;

import com.travislabs.mjdbcmcp.datasource.Datasource;
import com.travislabs.mjdbcmcp.datasource.ObjectAllowlist;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Schema introspection through JDBC {@link DatabaseMetaData} rather than {@code information_schema}
 * or vendor catalogs, so any driver works on day one (ADR-0008).
 *
 * <p>Everything here is filtered by the Datasource's Object Allowlist. Hiding is half the point:
 * refusing without hiding produces an agent that can see a table it cannot read and spends its turns
 * rephrasing the query that was never the problem (ADR-0009).
 */
@Component
public class MetadataReader {

    /**
     * Reads database product, version, driver, and current catalog/schema information.
     *
     * @param conn active JDBC connection
     * @return map of database metadata attributes
     * @throws SQLException if a database error occurs
     */
    public Map<String, Object> databaseInfo(Connection conn) throws SQLException {
        DatabaseMetaData md = conn.getMetaData();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("product", md.getDatabaseProductName());
        out.put("productVersion", md.getDatabaseProductVersion());
        out.put("driver", md.getDriverName());
        out.put("driverVersion", md.getDriverVersion());
        out.put("catalog", conn.getCatalog());
        out.put("schema", conn.getSchema());
        out.put("readOnly", conn.isReadOnly());
        out.put("identifierQuote", md.getIdentifierQuoteString());
        return out;
    }

    /**
     * Lists visible schemas filtered by the Datasource's Object Allowlist.
     *
     * @param conn active JDBC connection
     * @param d    target Datasource
     * @return list of schema metadata maps
     * @throws SQLException if a database error occurs
     */
    public List<Map<String, Object>> schemas(Connection conn, Datasource d) throws SQLException {
        try (ResultSet rs = conn.getMetaData().getSchemas()) {
            List<Map<String, Object>> out = new ArrayList<>();
            while (rs.next()) {
                String schema = rs.getString("TABLE_SCHEM");
                if (!d.allowlist().permitsSchema(schema)) {
                    continue;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("schema", schema);
                row.put("catalog", rs.getString("TABLE_CATALOG"));
                out.add(row);
            }
            return out;
        }
    }

    /**
     * Lists visible tables and views filtered by the Datasource's Object Allowlist and optional patterns.
     *
     * @param conn        active JDBC connection
     * @param d           target Datasource
     * @param schema      schema name, or null for default
     * @param namePattern SQL LIKE table name pattern
     * @param types       list of JDBC table types (e.g. TABLE, VIEW)
     * @return list of table metadata maps
     * @throws SQLException if a database error occurs
     */
    public List<Map<String, Object>> tables(Connection conn, Datasource d, String schema,
                                            String namePattern, List<String> types) throws SQLException {
        String[] typeFilter = types == null || types.isEmpty()
                ? new String[] {"TABLE", "VIEW"}
                : types.toArray(String[]::new);
        try (ResultSet rs = conn.getMetaData()
                .getTables(null, blankToNull(schema), blankToNull(namePattern), typeFilter)) {
            List<Map<String, Object>> out = new ArrayList<>();
            while (rs.next()) {
                String tableSchema = rs.getString("TABLE_SCHEM");
                String tableName = rs.getString("TABLE_NAME");
                if (!d.allowlist().permitsTable(
                        ObjectAllowlist.resolveSchema(tableSchema, d.defaultSchema()), tableName)) {
                    continue;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("schema", tableSchema);
                row.put("name", tableName);
                row.put("type", rs.getString("TABLE_TYPE"));
                row.put("remarks", rs.getString("REMARKS"));
                out.add(row);
            }
            return out;
        }
    }

    /**
     * Inspects column definitions, primary keys, foreign keys, and indexes for a single table.
     *
     * @param conn   active JDBC connection
     * @param schema schema name
     * @param table  table name
     * @return structured map of table details
     * @throws SQLException if a database error occurs
     */
    public Map<String, Object> describeTable(Connection conn, String schema, String table) throws SQLException {
        DatabaseMetaData md = conn.getMetaData();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schema", schema);
        out.put("table", table);
        out.put("columns", columns(md, schema, table));
        out.put("primaryKey", primaryKey(md, schema, table));
        out.put("foreignKeys", foreignKeys(md, schema, table));
        out.put("indexes", indexes(md, schema, table));
        return out;
    }

    private List<Map<String, Object>> columns(DatabaseMetaData md, String schema, String table) throws SQLException {
        try (ResultSet rs = md.getColumns(null, blankToNull(schema), table, null)) {
            List<Map<String, Object>> out = new ArrayList<>();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", rs.getString("COLUMN_NAME"));
                row.put("type", rs.getString("TYPE_NAME"));
                row.put("size", rs.getInt("COLUMN_SIZE"));
                row.put("nullable", "YES".equals(rs.getString("IS_NULLABLE")));
                row.put("default", rs.getString("COLUMN_DEF"));
                row.put("autoIncrement", "YES".equals(rs.getString("IS_AUTOINCREMENT")));
                row.put("remarks", rs.getString("REMARKS"));
                out.add(row);
            }
            return out;
        }
    }

    private List<String> primaryKey(DatabaseMetaData md, String schema, String table) throws SQLException {
        try (ResultSet rs = md.getPrimaryKeys(null, blankToNull(schema), table)) {
            List<String> out = new ArrayList<>();
            while (rs.next()) {
                out.add(rs.getString("COLUMN_NAME"));
            }
            return out;
        }
    }

    private List<Map<String, Object>> foreignKeys(DatabaseMetaData md, String schema, String table) throws SQLException {
        try (ResultSet rs = md.getImportedKeys(null, blankToNull(schema), table)) {
            List<Map<String, Object>> out = new ArrayList<>();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("column", rs.getString("FKCOLUMN_NAME"));
                row.put("referencesSchema", rs.getString("PKTABLE_SCHEM"));
                row.put("referencesTable", rs.getString("PKTABLE_NAME"));
                row.put("referencesColumn", rs.getString("PKCOLUMN_NAME"));
                out.add(row);
            }
            return out;
        }
    }

    private List<Map<String, Object>> indexes(DatabaseMetaData md, String schema, String table) throws SQLException {
        try (ResultSet rs = md.getIndexInfo(null, blankToNull(schema), table, false, true)) {
            List<Map<String, Object>> out = new ArrayList<>();
            while (rs.next()) {
                String name = rs.getString("INDEX_NAME");
                if (name == null) {
                    continue;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", name);
                row.put("column", rs.getString("COLUMN_NAME"));
                row.put("unique", !rs.getBoolean("NON_UNIQUE"));
                out.add(row);
            }
            return out;
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
