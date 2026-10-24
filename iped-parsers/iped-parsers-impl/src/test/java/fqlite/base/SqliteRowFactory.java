package fqlite.base;

import java.util.HashMap;
import java.util.Map;

/**
 * Creates {@link SqliteRow} instances for tests, as setting column names is
 * package private.
 */
public class SqliteRowFactory {

    /**
     * @param namesAndValues
     *            column names followed by their values
     */
    public static SqliteRow create(boolean deleted, Object... namesAndValues) {
        Map<String, Integer> names = new HashMap<>();
        Object[] values = new Object[namesAndValues.length / 2];
        for (int i = 0; i < values.length; i++) {
            names.put((String) namesAndValues[2 * i], i);
            values[i] = namesAndValues[2 * i + 1];
        }
        SqliteRow row = new SqliteRow();
        row.setColumnNamesMap(names);
        row.setRowData(values);
        row.setDeletedRow(deleted);
        return row;
    }
}
