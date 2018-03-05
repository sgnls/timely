package timely.store.cache;

import org.apache.accumulo.core.data.ByteSequence;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.KeyValue;
import org.apache.accumulo.core.data.Range;
import org.apache.accumulo.core.data.Value;
import org.apache.accumulo.core.iterators.IteratorEnvironment;
import org.apache.accumulo.core.iterators.SortedKeyValueIterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import timely.adapter.accumulo.MetricAdapter;
import timely.api.request.timeseries.QueryRequest;

import java.io.IOException;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;

public class DataStoreCacheIterator implements SortedKeyValueIterator<Key, Value> {

    private static final Logger LOG = LoggerFactory.getLogger(DataStoreCacheIterator.class);
    private DataStoreCache store;
    private VisibilityFilter visibilityFilter;
    private QueryRequest.SubQuery query;
    private long startTs;
    private long endTs;

    private Iterator<Map.Entry<TaggedMetric, GorillaStore>> storeItr = null;
    private WrappedGorillaDecompressorIterator decompressors = null;
    private KeyValue currentKeyValue = null;
    private Queue<KeyValue> kvQueue = new LinkedList<>();

    public DataStoreCacheIterator(DataStoreCache store, VisibilityFilter visibilityFilter, QueryRequest.SubQuery query,
            long startTs, long endTs) {

        this.store = store;
        this.visibilityFilter = visibilityFilter;
        this.query = query;
        this.startTs = startTs;
        this.endTs = endTs;

        this.storeItr = this.store.getGorillaStores(query.getMetric()).entrySet().iterator();
        this.decompressors = getNextDecompressorIterable();
        prepareEntries(100);
    }

    private WrappedGorillaDecompressorIterator getNextDecompressorIterable() {
        Map<String, String> requestedTags = query.getTags();
        WrappedGorillaDecompressorIterator nextPair = null;
        while (nextPair == null && storeItr.hasNext()) {
            Map.Entry<TaggedMetric, GorillaStore> entry = storeItr.next();
            if (entry.getKey().matches(requestedTags) && entry.getKey().isVisible(visibilityFilter)) {
                List<WrappedGorillaDecompressor> listDecompressors = entry.getValue().getDecompressors(startTs, endTs);
                nextPair = new WrappedGorillaDecompressorIterator(entry.getKey(), listDecompressors);
            }
        }
        return nextPair;
    }

    private void prepareEntries(int bufferSize) {

        if (decompressors != null && kvQueue.isEmpty()) {
            TaggedMetric tm = decompressors.getTaggedMetric();
            WrappedGorillaDecompressor decompressor = decompressors.getDecompressorWrapper();

            fi.iki.yak.ts.compression.gorilla.Pair gPair = null;
            while (kvQueue.size() < bufferSize && decompressor != null && decompressors != null) {
                gPair = decompressor.readPair();
                if (gPair == null) {
                    if (decompressors.hasNext()) {
                        decompressor = decompressors.next();
                    } else {
                        if ((decompressors = getNextDecompressorIterable()) != null) {
                            tm = decompressors.getTaggedMetric();
                            decompressor = decompressors.getDecompressorWrapper();
                        }
                    }
                } else {
                    if (gPair.getTimestamp() >= startTs && gPair.getTimestamp() <= endTs) {
                        kvQueue.add(new KeyValue(
                                MetricAdapter.toKey(tm.getMetric(), tm.getTags(), gPair.getTimestamp()), new Value(
                                        MetricAdapter.encodeValue(gPair.getDoubleValue()))));
                    }
                }
            }
        }
    }

    @Override
    public void init(SortedKeyValueIterator<Key, Value> source, Map<String, String> options, IteratorEnvironment env)
            throws IOException {

    }

    @Override
    public boolean hasTop() {
        prepareEntries(100);
        return currentKeyValue != null;
    }

    @Override
    public void next() throws IOException {
        currentKeyValue = kvQueue.poll();
    }

    @Override
    public void seek(Range range, Collection<ByteSequence> columnFamilies, boolean inclusive) throws IOException {
        prepareEntries(100);
        currentKeyValue = kvQueue.poll();
    }

    @Override
    public Key getTopKey() {
        if (currentKeyValue != null) {
            return currentKeyValue.getKey();
        } else {
            return null;
        }
    }

    @Override
    public Value getTopValue() {
        if (currentKeyValue != null) {
            return currentKeyValue.getValue();
        } else {
            return null;
        }
    }

    @Override
    public SortedKeyValueIterator<Key, Value> deepCopy(IteratorEnvironment env) {
        return null;
    }

}