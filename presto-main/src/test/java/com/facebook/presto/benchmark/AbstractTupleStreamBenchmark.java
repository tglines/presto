package com.facebook.presto.benchmark;

import com.facebook.presto.Range;
import com.facebook.presto.block.Cursor;
import com.facebook.presto.block.Cursors;
import com.facebook.presto.block.QuerySession;
import com.facebook.presto.block.StatsCollectingTupleStreamSerde;
import com.facebook.presto.block.TupleStream;
import com.facebook.presto.block.TupleStreamSerdes;
import com.facebook.presto.nblock.Blocks;
import com.facebook.presto.operator.tap.StatsTupleValueSink;
import com.facebook.presto.serde.BlockSerdes;
import com.facebook.presto.serde.BlocksSerde;
import com.facebook.presto.slice.Slice;
import com.facebook.presto.slice.Slices;
import com.facebook.presto.tpch.CachingTpchDataProvider;
import com.facebook.presto.tpch.GeneratingTpchDataProvider;
import com.facebook.presto.tpch.MetricRecordingTpchDataProvider;
import com.facebook.presto.tpch.TpchDataProvider;
import com.facebook.presto.tpch.TpchSchema;
import com.facebook.presto.tpch.TpchSchema.Column;
import com.facebook.presto.tpch.TpchTupleStreamProvider;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.airlift.units.DataSize;
import io.airlift.units.Duration;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

/**
 * Abstract template for benchmarks that want to test the performance of a single TupleStream.
 *
 * How to use this:
 * - Subclasses must call loadColumnFile(...) in the setUp phase to specify the columns of interest.
 * - Subclasses must implement createBenchmarkedTupleStream(...) where the argument will be a list of
 * decoded TupleStreams that will occur in the same order as which loadColumnFile was originally called.
 * The output should be the TupleStream that will be benchmarked.
 * - The first column to be requested will be used to represent the count of the number of input rows
 */
public abstract class AbstractTupleStreamBenchmark
        extends AbstractBenchmark
{
    private static final TpchDataProvider TPCH_DATA_PROVIDER = new CachingTpchDataProvider(new GeneratingTpchDataProvider());

    private final TpchDataProvider tpchDataProvider;

    protected AbstractTupleStreamBenchmark(String benchmarkName, int warmupIterations, int measuredIterations, TpchDataProvider tpchDataProvider)
    {
        super(benchmarkName, warmupIterations, measuredIterations);
        this.tpchDataProvider = tpchDataProvider;
    }

    protected AbstractTupleStreamBenchmark(String benchmarkName, int warmupIterations, int measuredIterations)
    {
        this(benchmarkName, warmupIterations, measuredIterations, TPCH_DATA_PROVIDER);
    }

    @Override
    protected String getDefaultResult()
    {
        return "input_rows_per_second";
    }

    protected abstract TupleStream createBenchmarkedTupleStream(TpchTupleStreamProvider inputStreamProvider);

    @Override
    protected Map<String, Long> runOnce()
    {
        long start = System.nanoTime();

        MetricRecordingTpchDataProvider metricRecordingTpchDataProvider = new MetricRecordingTpchDataProvider(tpchDataProvider);
        StatsTpchTupleStreamProvider statsTpchTupleStreamProvider = new StatsTpchTupleStreamProvider(metricRecordingTpchDataProvider);

        TupleStream tupleStream = createBenchmarkedTupleStream(statsTpchTupleStreamProvider);

        Cursor cursor = tupleStream.cursor(new QuerySession());
        long outputRows = 0;
        while (Cursors.advanceNextValueNoYield(cursor)) {
            outputRows += cursor.getCurrentValueEndPosition() - cursor.getPosition() + 1;
        }

        Duration totalDuration = Duration.nanosSince(start);
        Duration dataGenerationDuration = metricRecordingTpchDataProvider.getDataFetchElapsedTime();
        checkState(totalDuration.compareTo(dataGenerationDuration) >= 0, "total time should be at least as large as data generation time");

        // Compute the benchmark execution time without factoring in the time to generate the data source
        double executionMillis = totalDuration.convertTo(TimeUnit.MILLISECONDS) - dataGenerationDuration.toMillis();
        double executionSeconds = executionMillis / TimeUnit.SECONDS.toMillis(1);

        DataSize totalDataSize = metricRecordingTpchDataProvider.getCumulativeDataSize();
        
        checkState(!statsTpchTupleStreamProvider.getStats().isEmpty(), "no columns were fetched");
        // Use the first column fetched as the indicator of the number of rows
        long inputRows = statsTpchTupleStreamProvider.getStats().get(0).getRowCount();

        return ImmutableMap.<String, Long>builder()
                .put("elapsed_millis", (long) executionMillis)
                .put("input_rows", inputRows)
                .put("input_rows_per_second", (long) (inputRows / executionSeconds))
                .put("output_rows", outputRows)
                .put("output_rows_per_second", (long) (outputRows / executionSeconds))
                .put("input_megabytes", (long) totalDataSize.getValue(DataSize.Unit.MEGABYTE))
                .put("input_megabytes_per_second", (long) (totalDataSize.getValue(DataSize.Unit.MEGABYTE) / executionSeconds))
                .build();
    }

    private static class StatsTpchTupleStreamProvider
            implements TpchTupleStreamProvider
    {
        private final TpchDataProvider tpchDataProvider;
        private final ImmutableList.Builder<StatsTupleValueSink.Stats> statsBuilder = ImmutableList.builder();

        private StatsTpchTupleStreamProvider(TpchDataProvider tpchDataProvider)
        {
            this.tpchDataProvider = checkNotNull(tpchDataProvider, "tpchDataProvider is null");
        }

        @Override
        public TupleStream getTupleStream(TpchSchema.Column column, TupleStreamSerdes.Encoding encoding)
        {
            checkNotNull(column, "column is null");
            checkNotNull(encoding, "encoding is null");
            // Wrap the encoding with stats collection
            StatsCollectingTupleStreamSerde serde = new StatsCollectingTupleStreamSerde(encoding.createSerde());
            try {
                Slice slice = Slices.mapFileReadOnly(tpchDataProvider.getColumnFile(column, serde.createSerializer(), encoding.getName()));
                statsBuilder.add(serde.createDeserializer().deserializeStats(slice));
                return serde.createDeserializer().deserialize(Range.ALL, slice);
            } catch (IOException e) {
                throw Throwables.propagate(e);
            }
        }

        @Override
        public Blocks getBlocks(Column column, BlockSerdes.Encoding encoding)
        {
            checkNotNull(column, "column is null");
            checkNotNull(encoding, "encoding is null");
            try {
                File columnFile = tpchDataProvider.getColumnFile(column, encoding.createSerde(), encoding.getName());
                Slice slice = Slices.mapFileReadOnly(columnFile);
//                statsBuilder.add(serde.createDeserializer().deserializeStats(slice));
//                return serde.createDeserializer().deserializeBlocks(Range.ALL, slice);
                return BlocksSerde.readBlocks(slice);
            } catch (IOException e) {
                throw Throwables.propagate(e);
            }
        }

        public List<StatsTupleValueSink.Stats> getStats()
        {
            return statsBuilder.build();
        }
    }
}