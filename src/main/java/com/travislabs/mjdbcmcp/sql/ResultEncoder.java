package com.travislabs.mjdbcmcp.sql;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Turns a ResultSet into the payload ADR-0007 specifies: columns and their SQL types declared once,
 * rows as positional arrays, values typed rather than stringified.
 *
 * <p>"Never stringify a value to render it" is the rule that is easiest to violate by accident,
 * because {@code toString()} usually produces something that looks right. It is wrong for exactly
 * the values that matter: a {@code TIMESTAMP WITH TIME ZONE} rendered through the server's default
 * zone is a different instant, and a {@code NUMERIC(38,10)} through a double is a different number.
 */
@Component
public class ResultEncoder {

    /**
     * @param maxRows      rows to keep; one more is read to tell "exactly at the cap" from "truncated"
     * @param maxCellChars per-cell limit, applied to text and to base64 of binary
     */
    public Map<String, Object> encode(ResultSet rs, int maxRows, int maxCellChars) throws SQLException {
        ResultSetMetaData md = rs.getMetaData();
        int columnCount = md.getColumnCount();
        List<String> columns = new ArrayList<>(columnCount);
        List<String> types = new ArrayList<>(columnCount);
        for (int i = 1; i <= columnCount; i++) {
            columns.add(md.getColumnLabel(i));
            types.add(md.getColumnTypeName(i));
        }

        List<List<Object>> rows = new ArrayList<>();
        boolean rowCapReached = false;
        int truncatedCells = 0;
        while (rs.next()) {
            if (rows.size() == maxRows) {
                rowCapReached = true;
                break;
            }
            List<Object> row = new ArrayList<>(columnCount);
            for (int i = 1; i <= columnCount; i++) {
                Cell cell = value(rs, md, i, maxCellChars);
                row.add(cell.value());
                if (cell.truncated()) {
                    truncatedCells++;
                }
            }
            rows.add(row);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("columns", columns);
        out.put("types", types);
        out.put("rows", rows);
        out.put("rowCount", rows.size());
        // Stated on every response: silent truncation is how an agent concludes a table has a
        // hundred rows and reports that to a user as fact.
        out.put("rowCapReached", rowCapReached);
        if (rowCapReached) {
            out.put("message", "Result set limit reached (capped at " + maxRows + " rows).");
        }
        out.put("truncatedCells", truncatedCells);
        return out;
    }

    private record Cell(Object value, boolean truncated) {
        static Cell of(Object value) {
            return new Cell(value, false);
        }
    }

    private Cell value(ResultSet rs, ResultSetMetaData md, int column, int maxCellChars) throws SQLException {
        int sqlType = md.getColumnType(column);

        // Temporal types go through java.time so the offset survives. Drivers that cannot do this
        // fall back below rather than failing the whole result.
        Object temporal = temporal(rs, column, sqlType);
        if (temporal != null) {
            return Cell.of(temporal.toString());
        }

        Object raw = rs.getObject(column);
        if (rs.wasNull() || raw == null) {
            // SQL NULL is JSON null, never the text "NULL".
            return Cell.of(null);
        }
        return switch (raw) {
            case String s -> text(s, maxCellChars);
            case Boolean b -> Cell.of(b);
            case Integer i -> Cell.of(i);
            case Long l -> Cell.of(l);
            case Short s -> Cell.of(s);
            case Byte b -> Cell.of(b);
            case Double d -> Cell.of(d);
            case Float f -> Cell.of(f);
            case BigInteger bi -> Cell.of(exactAsDouble(new BigDecimal(bi)) ? bi : bi.toString());
            // Beyond double precision it goes out as a string rather than being silently rounded.
            case BigDecimal bd -> Cell.of(exactAsDouble(bd) ? bd : bd.toPlainString());
            case byte[] bytes -> binary(bytes, maxCellChars);
            case java.sql.Clob clob -> text(clobText(clob), maxCellChars);
            case java.sql.Blob blob -> binary(blobBytes(blob), maxCellChars);
            case java.sql.Array array -> text(String.valueOf(array.getArray()), maxCellChars);
            default -> text(String.valueOf(raw), maxCellChars);
        };
    }

    /** @return a java.time value for temporal columns, or null when this is not one (or the driver refuses) */
    private Object temporal(ResultSet rs, int column, int sqlType) {
        try {
            return switch (sqlType) {
                case Types.TIMESTAMP_WITH_TIMEZONE -> rs.getObject(column, OffsetDateTime.class);
                case Types.TIMESTAMP -> rs.getObject(column, LocalDateTime.class);
                case Types.DATE -> rs.getObject(column, LocalDate.class);
                case Types.TIME -> rs.getObject(column, LocalTime.class);
                case Types.TIME_WITH_TIMEZONE -> rs.getObject(column, OffsetTime.class);
                default -> null;
            };
        } catch (SQLException | RuntimeException e) {
            // Older drivers reject the java.time overloads. Falling back to getObject is worse but
            // still typed; failing the whole query over a formatting preference would be worse again.
            return null;
        }
    }

    private Cell text(String value, int maxCellChars) {
        if (value == null) {
            return Cell.of(null);
        }
        if (value.length() <= maxCellChars) {
            return Cell.of(value);
        }
        return new Cell(value.substring(0, maxCellChars), true);
    }

    private Cell binary(byte[] bytes, int maxCellChars) {
        if (bytes == null) {
            return Cell.of(null);
        }
        String encoded = Base64.getEncoder().encodeToString(bytes);
        return text(encoded, maxCellChars);
    }

    /** True when the value survives a round trip through double, so emitting it as a number is honest. */
    private boolean exactAsDouble(BigDecimal value) {
        double asDouble = value.doubleValue();
        return Double.isFinite(asDouble) && new BigDecimal(Double.toString(asDouble)).compareTo(value) == 0;
    }

    private String clobText(java.sql.Clob clob) throws SQLException {
        long length = clob.length();
        return clob.getSubString(1, (int) Math.min(length, Integer.MAX_VALUE));
    }

    private byte[] blobBytes(java.sql.Blob blob) throws SQLException {
        long length = blob.length();
        return blob.getBytes(1, (int) Math.min(length, Integer.MAX_VALUE));
    }
}
