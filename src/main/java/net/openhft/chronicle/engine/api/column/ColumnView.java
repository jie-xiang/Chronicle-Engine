package net.openhft.chronicle.engine.api.column;

import net.openhft.chronicle.wire.AbstractMarshallable;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * @author Rob Austin.
 */
public interface ColumnView<K> {


    ArrayList<String> columnNames();

    class MarshableFilter extends AbstractMarshallable {
        public final String columnName;
        public final String filter;

        public MarshableFilter(String columnName, String filter) {
            this.columnName = columnName;
            this.filter = filter;
        }
    }

    class MarshableOrderBy extends AbstractMarshallable {
        public final String column;
        public final boolean isAscending;

        public MarshableOrderBy(String column, boolean isAscending) {
            this.column = column;
            this.isAscending = isAscending;
        }
    }

    class Query extends AbstractMarshallable {
        public long fromIndex;
        public List<MarshableOrderBy> marshableOrderBy = new ArrayList<>();
        public List<MarshableFilter> marshableFilters = new ArrayList<>();
    }

    List<Column> columns();

    /**
     * @return the total number of rows
     */
    long longSize();

    int rowCount(Query query);

    boolean containsKey(K k);

    boolean removeRow(@NotNull Map<String, Object> cells);

    void addRow(@NotNull Map<String, Object> cells);


    /**
     * called when ever the user modify the cells and the data changes
     *
     * @param columnName the column name of the cell
     * @param key        the rowID of the cell
     * @param oldKey     the old rowID of the cell
     * @param value      the new value of the cell
     * @param oldValue   the old value of the cell
     */
    void onRowChanged(String columnName, K key, K oldKey, Object value, Object oldValue);

    /**
     * called whenever some data in the underlying structure has changed and hence the visual
     * layer has to be refreshed
     *
     * @param r to refresh the visual layer
     */
    void registerChangeListener(@NotNull Runnable r);

    Iterator<Row> iterator(ColumnView.Query query);

    boolean canDeleteRows();


}
