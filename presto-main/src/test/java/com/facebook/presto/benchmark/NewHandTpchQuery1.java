package com.facebook.presto.benchmark;

import com.facebook.presto.benchmark.NewHandTpchQuery1.TpchQuery1Operator.TpchQuery1OperatorFactory;
import com.facebook.presto.block.Block;
import com.facebook.presto.block.BlockCursor;
import com.facebook.presto.block.BlockIterable;
import com.facebook.presto.noperator.DriverContext;
import com.facebook.presto.noperator.NewAlignmentOperator.NewAlignmentOperatorFactory;
import com.facebook.presto.noperator.NewHashAggregationOperator.NewHashAggregationOperatorFactory;
import com.facebook.presto.noperator.NewOperator;
import com.facebook.presto.noperator.NewOperatorFactory;
import com.facebook.presto.noperator.OperatorContext;
import com.facebook.presto.operator.Page;
import com.facebook.presto.operator.PageBuilder;
import com.facebook.presto.serde.BlocksFileEncoding;
import com.facebook.presto.sql.planner.plan.AggregationNode.Step;
import com.facebook.presto.sql.tree.Input;
import com.facebook.presto.tpch.TpchBlocksProvider;
import com.facebook.presto.tuple.TupleInfo;
import com.facebook.presto.tuple.TupleInfo.Type;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ListenableFuture;
import io.airlift.slice.Slice;
import io.airlift.slice.Slices;

import java.util.List;
import java.util.concurrent.ExecutorService;

import static com.facebook.presto.operator.AggregationFunctionDefinition.aggregation;
import static com.facebook.presto.operator.aggregation.CountAggregation.COUNT;
import static com.facebook.presto.operator.aggregation.DoubleAverageAggregation.DOUBLE_AVERAGE;
import static com.facebook.presto.operator.aggregation.DoubleSumAggregation.DOUBLE_SUM;
import static com.facebook.presto.util.Threads.daemonThreadsNamed;
import static com.google.common.base.Charsets.UTF_8;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static java.util.concurrent.Executors.newCachedThreadPool;

public class NewHandTpchQuery1
        extends AbstractSimpleOperatorBenchmark
{
    public NewHandTpchQuery1(ExecutorService executor, TpchBlocksProvider tpchBlocksProvider)
    {
        super(executor, tpchBlocksProvider, "hand_tpch_query_1", 1, 5);
    }

    @Override
    protected List<? extends NewOperatorFactory> createOperatorFactories()
    {
        // select
        //     returnflag,
        //     linestatus,
        //     sum(quantity) as sum_qty,
        //     sum(extendedprice) as sum_base_price,
        //     sum(extendedprice * (1 - discount)) as sum_disc_price,
        //     sum(extendedprice * (1 - discount) * (1 + tax)) as sum_charge,
        //     avg(quantity) as avg_qty,
        //     avg(extendedprice) as avg_price,
        //     avg(discount) as avg_disc,
        //     count(*) as count_order
        // from
        //     lineitem
        // where
        //     shipdate <= '1998-09-02'
        // group by
        //     returnflag,
        //     linestatus
        // order by
        //     returnflag,
        //     linestatus

        BlockIterable returnFlag = getBlockIterable("lineitem", "returnflag", BlocksFileEncoding.RAW);
        BlockIterable lineStatus = getBlockIterable("lineitem", "linestatus", BlocksFileEncoding.RAW);
        BlockIterable quantity = getBlockIterable("lineitem", "quantity", BlocksFileEncoding.RAW);
        BlockIterable extendedPrice = getBlockIterable("lineitem", "extendedprice", BlocksFileEncoding.RAW);
        BlockIterable discount = getBlockIterable("lineitem", "discount", BlocksFileEncoding.RAW);
        BlockIterable tax = getBlockIterable("lineitem", "tax", BlocksFileEncoding.RAW);
        BlockIterable shipDate = getBlockIterable("lineitem", "shipdate", BlocksFileEncoding.RAW);

        NewAlignmentOperatorFactory alignmentOperator = new NewAlignmentOperatorFactory(
                0,
                returnFlag,
                lineStatus,
                quantity,
                extendedPrice,
                discount,
                tax,
                shipDate);

        TpchQuery1OperatorFactory tpchQuery1Operator = new TpchQuery1OperatorFactory(1);
        NewHashAggregationOperatorFactory aggregationOperator = new NewHashAggregationOperatorFactory(
                2,
                tpchQuery1Operator.getTupleInfos().get(0),
                0,
                Step.SINGLE,
                ImmutableList.of(
                        aggregation(DOUBLE_SUM, new Input(1, 0)),
                        aggregation(DOUBLE_SUM, new Input(2, 0)),
                        aggregation(DOUBLE_SUM, new Input(3, 0)),
                        aggregation(DOUBLE_AVERAGE, new Input(1, 0)),
                        aggregation(DOUBLE_AVERAGE, new Input(4, 0)),
                        aggregation(DOUBLE_AVERAGE, new Input(5, 0)),
                        aggregation(COUNT, new Input(1, 0))
                ),
                10_000);

        return ImmutableList.of(alignmentOperator, tpchQuery1Operator, aggregationOperator);
    }

    public static class TpchQuery1Operator
            implements com.facebook.presto.noperator.NewOperator
    {
        private static final ImmutableList<TupleInfo> TUPLE_INFOS = ImmutableList.of(new TupleInfo(Type.VARIABLE_BINARY, Type.VARIABLE_BINARY),
                TupleInfo.SINGLE_DOUBLE,
                TupleInfo.SINGLE_DOUBLE,
                TupleInfo.SINGLE_DOUBLE,
                TupleInfo.SINGLE_DOUBLE,
                TupleInfo.SINGLE_DOUBLE);

        public static class TpchQuery1OperatorFactory
                implements com.facebook.presto.noperator.NewOperatorFactory
        {
            private final int operatorId;

            public TpchQuery1OperatorFactory(int operatorId)
            {
                this.operatorId = operatorId;
            }

            @Override
            public List<TupleInfo> getTupleInfos()
            {
                return TUPLE_INFOS;
            }

            @Override
            public NewOperator createOperator(DriverContext driverContext)
            {
                OperatorContext operatorContext = driverContext.addOperatorContext(operatorId, TpchQuery1Operator.class.getSimpleName());
                return new TpchQuery1Operator(operatorContext);
            }

            @Override
            public void close()
            {
            }
        }

        private final OperatorContext operatorContext;
        private final PageBuilder pageBuilder;
        private boolean finishing;

        public TpchQuery1Operator(OperatorContext operatorContext)
        {
            this.operatorContext = checkNotNull(operatorContext, "operatorContext is null");
            this.pageBuilder = new PageBuilder(TUPLE_INFOS);
        }

        @Override
        public OperatorContext getOperatorContext()
        {
            return operatorContext;
        }

        @Override
        public List<TupleInfo> getTupleInfos()
        {
            return TUPLE_INFOS;
        }

        @Override
        public void finish()
        {
            finishing = true;
        }

        @Override
        public boolean isFinished()
        {
            return finishing && pageBuilder.isEmpty();
        }

        @Override
        public ListenableFuture<?> isBlocked()
        {
            return NOT_BLOCKED;
        }

        @Override
        public boolean needsInput()
        {
            return !pageBuilder.isFull();
        }

        @Override
        public void addInput(Page page)
        {
            checkNotNull(page, "page is null");
            checkState(!pageBuilder.isFull(), "Output buffer is full");
            checkState(!finishing, "Operator is finished");

            filterAndProjectRowOriented(pageBuilder,
                    page.getBlock(0),
                    page.getBlock(1),
                    page.getBlock(2),
                    page.getBlock(3),
                    page.getBlock(4),
                    page.getBlock(5),
                    page.getBlock(6));
        }

        @Override
        public Page getOutput()
        {
            // only return a page if the page buffer isFull or we are finishing and the page buffer has data
            if (pageBuilder.isFull() || (finishing && !pageBuilder.isEmpty())) {
                Page page = pageBuilder.build();
                pageBuilder.reset();
                return page;
            }
            return null;
        }

        private static final Slice MAX_SHIP_DATE = Slices.copiedBuffer("1998-09-02", UTF_8);

        private void filterAndProjectRowOriented(PageBuilder pageBuilder,
                Block returnFlagBlock,
                Block lineStatusBlock,
                Block quantityBlock,
                Block extendedPriceBlock,
                Block discountBlock,
                Block taxBlock,
                Block shipDateBlock)
        {
            int rows = returnFlagBlock.getPositionCount();

            BlockCursor returnFlagCursor = returnFlagBlock.cursor();
            BlockCursor lineStatusCursor = lineStatusBlock.cursor();
            BlockCursor quantityCursor = quantityBlock.cursor();
            BlockCursor extendedPriceCursor = extendedPriceBlock.cursor();
            BlockCursor discountCursor = discountBlock.cursor();
            BlockCursor taxCursor = taxBlock.cursor();
            BlockCursor shipDateCursor = shipDateBlock.cursor();

            for (int position = 0; position < rows; position++) {
                checkState(returnFlagCursor.advanceNextPosition());
                checkState(lineStatusCursor.advanceNextPosition());
                checkState(quantityCursor.advanceNextPosition());
                checkState(extendedPriceCursor.advanceNextPosition());
                checkState(discountCursor.advanceNextPosition());
                checkState(taxCursor.advanceNextPosition());
                checkState(shipDateCursor.advanceNextPosition());

                if (shipDateCursor.isNull(0)) {
                    continue;
                }

                Slice shipDate = shipDateCursor.getSlice(0);

                // where
                //     shipdate <= '1998-09-02'
                if (shipDate.compareTo(MAX_SHIP_DATE) <= 0) {
                    //     returnflag, linestatus
                    //     quantity
                    //     extendedprice
                    //     extendedprice * (1 - discount)
                    //     extendedprice * (1 - discount) * (1 + tax)
                    //     discount

                    if (returnFlagCursor.isNull(0)) {
                        pageBuilder.getBlockBuilder(0).appendNull();
                    }
                    else {
                        pageBuilder.getBlockBuilder(0).append(returnFlagCursor.getSlice(0));
                    }
                    if (lineStatusCursor.isNull(0)) {
                        pageBuilder.getBlockBuilder(0).appendNull();
                    }
                    else {
                        pageBuilder.getBlockBuilder(0).append(lineStatusCursor.getSlice(0));
                    }

                    double quantity = quantityCursor.getDouble(0);
                    double extendedPrice = extendedPriceCursor.getDouble(0);
                    double discount = discountCursor.getDouble(0);
                    double tax = taxCursor.getDouble(0);

                    boolean quantityIsNull = quantityCursor.isNull(0);
                    boolean extendedPriceIsNull = extendedPriceCursor.isNull(0);
                    boolean discountIsNull = discountCursor.isNull(0);
                    boolean taxIsNull = taxCursor.isNull(0);

                    if (quantityIsNull) {
                        pageBuilder.getBlockBuilder(1).appendNull();
                    }
                    else {
                        pageBuilder.getBlockBuilder(1).append(quantity);
                    }

                    if (extendedPriceIsNull) {
                        pageBuilder.getBlockBuilder(2).appendNull();
                    }
                    else {
                        pageBuilder.getBlockBuilder(2).append(extendedPrice);
                    }

                    if (extendedPriceIsNull || discountIsNull) {
                        pageBuilder.getBlockBuilder(3).appendNull();
                    }
                    else {
                        pageBuilder.getBlockBuilder(3).append(extendedPrice * (1 - discount));
                    }

                    if (extendedPriceIsNull || discountIsNull || taxIsNull) {
                        pageBuilder.getBlockBuilder(4).appendNull();
                    }
                    else {
                        pageBuilder.getBlockBuilder(4).append(extendedPrice * (1 - discount) * (1 + tax));
                    }

                    if (discountIsNull) {
                        pageBuilder.getBlockBuilder(5).appendNull();
                    }
                    else {
                        pageBuilder.getBlockBuilder(5).append(discount);
                    }
                }
            }

            checkState(!returnFlagCursor.advanceNextPosition());
            checkState(!lineStatusCursor.advanceNextPosition());
            checkState(!quantityCursor.advanceNextPosition());
            checkState(!extendedPriceCursor.advanceNextPosition());
            checkState(!discountCursor.advanceNextPosition());
            checkState(!taxCursor.advanceNextPosition());
            checkState(!shipDateCursor.advanceNextPosition());
        }
    }


    public static void main(String[] args)
    {
        ExecutorService executor = newCachedThreadPool(daemonThreadsNamed("test"));
        new NewHandTpchQuery1(executor, DEFAULT_TPCH_BLOCKS_PROVIDER).runBenchmark(
                new SimpleLineBenchmarkResultWriter(System.out)
        );
    }
}