/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package io.druid.segment.filter;

import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import io.druid.collections.spatial.search.RadiusBound;
import io.druid.collections.spatial.search.RectangularBound;
import io.druid.data.input.MapBasedInputRow;
import io.druid.data.input.impl.DimensionsSpec;
import io.druid.data.input.impl.SpatialDimensionSchema;
import io.druid.java.util.common.DateTimes;
import io.druid.java.util.common.Intervals;
import io.druid.java.util.common.StringUtils;
import io.druid.java.util.common.granularity.Granularities;
import io.druid.segment.writeout.SegmentWriteOutMediumFactory;
import io.druid.query.Druids;
import io.druid.query.FinalizeResultsQueryRunner;
import io.druid.query.QueryPlus;
import io.druid.query.QueryRunner;
import io.druid.query.QueryRunnerTestHelper;
import io.druid.query.Result;
import io.druid.query.aggregation.AggregatorFactory;
import io.druid.query.aggregation.CountAggregatorFactory;
import io.druid.query.aggregation.FilteredAggregatorFactory;
import io.druid.query.aggregation.LongSumAggregatorFactory;
import io.druid.query.filter.SpatialDimFilter;
import io.druid.query.timeseries.TimeseriesQuery;
import io.druid.query.timeseries.TimeseriesQueryEngine;
import io.druid.query.timeseries.TimeseriesQueryQueryToolChest;
import io.druid.query.timeseries.TimeseriesQueryRunnerFactory;
import io.druid.query.timeseries.TimeseriesResultValue;
import io.druid.segment.IncrementalIndexSegment;
import io.druid.segment.IndexIO;
import io.druid.segment.IndexMerger;
import io.druid.segment.IndexSpec;
import io.druid.segment.QueryableIndex;
import io.druid.segment.QueryableIndexSegment;
import io.druid.segment.Segment;
import io.druid.segment.TestHelper;
import io.druid.segment.incremental.IncrementalIndex;
import io.druid.segment.incremental.IncrementalIndexSchema;
import org.joda.time.Interval;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 */
@RunWith(Parameterized.class)
public class SpatialFilterBonusTest
{
  public static final int NUM_POINTS = 5000;
  private static Interval DATA_INTERVAL = Intervals.of("2013-01-01/2013-01-07");
  private static AggregatorFactory[] METRIC_AGGS = new AggregatorFactory[]{
      new CountAggregatorFactory("rows"),
      new LongSumAggregatorFactory("val", "val")
  };
  private static List<String> DIMS = Lists.newArrayList("dim", "dim.geo");

  private final Segment segment;

  public SpatialFilterBonusTest(Segment segment)
  {
    this.segment = segment;
  }

  @Parameterized.Parameters
  public static Collection<?> constructorFeeder() throws IOException
  {
    List<Object[]> argumentArrays = new ArrayList<>();
    for (SegmentWriteOutMediumFactory segmentWriteOutMediumFactory : SegmentWriteOutMediumFactory.builtInFactories()) {
      IndexMerger indexMerger = TestHelper.getTestIndexMergerV9(segmentWriteOutMediumFactory);
      IndexIO indexIO = TestHelper.getTestIndexIO(segmentWriteOutMediumFactory);
      final IndexSpec indexSpec = new IndexSpec();
      final IncrementalIndex rtIndex = makeIncrementalIndex();
      final QueryableIndex mMappedTestIndex = makeQueryableIndex(indexSpec, indexMerger, indexIO);
      final QueryableIndex mergedRealtimeIndex = makeMergedQueryableIndex(indexSpec, indexMerger, indexIO);
      argumentArrays.add(new Object[] {new IncrementalIndexSegment(rtIndex, null)});
      argumentArrays.add(new Object[] {new QueryableIndexSegment(null, mMappedTestIndex)});
      argumentArrays.add(new Object[] {new QueryableIndexSegment(null, mergedRealtimeIndex)});
    }
    return argumentArrays;
  }

  private static IncrementalIndex makeIncrementalIndex() throws IOException
  {
    IncrementalIndex theIndex = new IncrementalIndex.Builder()
        .setIndexSchema(
            new IncrementalIndexSchema.Builder()
                .withMinTimestamp(DATA_INTERVAL.getStartMillis())
                .withQueryGranularity(Granularities.DAY)
                .withMetrics(METRIC_AGGS)
                .withDimensionsSpec(
                    new DimensionsSpec(
                        null,
                        null,
                        Arrays.asList(
                            new SpatialDimensionSchema(
                                "dim.geo",
                                Lists.<String>newArrayList()
                            )
                        )
                    )
                ).build()
        )
        .setReportParseExceptions(false)
        .setMaxRowCount(NUM_POINTS)
        .buildOnheap();

    theIndex.add(
        new MapBasedInputRow(
            DateTimes.of("2013-01-01").getMillis(),
            DIMS,
            ImmutableMap.<String, Object>of(
                "timestamp", DateTimes.of("2013-01-01").toString(),
                "dim", "foo",
                "dim.geo", "0.0,0.0",
                "val", 17L
            )
        )
    );
    theIndex.add(
        new MapBasedInputRow(
            DateTimes.of("2013-01-02").getMillis(),
            DIMS,
            ImmutableMap.<String, Object>of(
                "timestamp", DateTimes.of("2013-01-02").toString(),
                "dim", "foo",
                "dim.geo", "1.0,3.0",
                "val", 29L
            )
        )
    );
    theIndex.add(
        new MapBasedInputRow(
            DateTimes.of("2013-01-03").getMillis(),
            DIMS,
            ImmutableMap.<String, Object>of(
                "timestamp", DateTimes.of("2013-01-03").toString(),
                "dim", "foo",
                "dim.geo", "4.0,2.0",
                "val", 13L
            )
        )
    );
    theIndex.add(
        new MapBasedInputRow(
            DateTimes.of("2013-01-04").getMillis(),
            DIMS,
            ImmutableMap.<String, Object>of(
                "timestamp", DateTimes.of("2013-01-04").toString(),
                "dim", "foo",
                "dim.geo", "7.0,3.0",
                "val", 91L
            )
        )
    );
    theIndex.add(
        new MapBasedInputRow(
            DateTimes.of("2013-01-05").getMillis(),
            DIMS,
            ImmutableMap.<String, Object>of(
                "timestamp", DateTimes.of("2013-01-05").toString(),
                "dim", "foo",
                "dim.geo", "8.0,6.0",
                "val", 47L
            )
        )
    );
    theIndex.add(
        new MapBasedInputRow(
            DateTimes.of("2013-01-05").getMillis(),
            DIMS,
            ImmutableMap.<String, Object>of(
                "timestamp", DateTimes.of("2013-01-05").toString(),
                "dim", "foo",
                "dim.geo", "_mmx.unknown",
                "val", 501L
            )
        )
    );

    // Add a bunch of random points, without replacement
    Set<String> alreadyChosen = Sets.newHashSet();
    Random rand = new Random();
    for (int i = 6; i < NUM_POINTS; i++) {
      String coord = null;
      while (coord == null) {
        coord = StringUtils.format(
            "%s,%s",
            (float) (rand.nextFloat() * 10 + 10.0),
            (float) (rand.nextFloat() * 10 + 10.0)
        );
        if (!alreadyChosen.add(coord)) {
          coord = null;
        }
      }
      theIndex.add(
          new MapBasedInputRow(
              DateTimes.of("2013-01-01").getMillis(),
              DIMS,
              ImmutableMap.<String, Object>of(
                  "timestamp", DateTimes.of("2013-01-01").toString(),
                  "dim", "boo",
                  "dim.geo", coord,
                  "val", i
              )
          )
      );
    }

    return theIndex;
  }

  private static QueryableIndex makeQueryableIndex(IndexSpec indexSpec, IndexMerger indexMerger, IndexIO indexIO)
      throws IOException
  {
    IncrementalIndex theIndex = makeIncrementalIndex();
    File tmpFile = File.createTempFile("billy", "yay");
    tmpFile.delete();
    tmpFile.mkdirs();
    tmpFile.deleteOnExit();

    indexMerger.persist(theIndex, tmpFile, indexSpec, null);
    return indexIO.loadIndex(tmpFile);
  }

  private static QueryableIndex makeMergedQueryableIndex(
      final IndexSpec indexSpec,
      final IndexMerger indexMerger,
      final IndexIO indexIO
  )
  {
    try {
      IncrementalIndex first = new IncrementalIndex.Builder()
          .setIndexSchema(
              new IncrementalIndexSchema.Builder()
                  .withMinTimestamp(DATA_INTERVAL.getStartMillis())
                  .withQueryGranularity(Granularities.DAY)
                  .withMetrics(METRIC_AGGS)
                  .withDimensionsSpec(
                      new DimensionsSpec(
                          null,
                          null,
                          Arrays.asList(
                              new SpatialDimensionSchema(
                                  "dim.geo",
                                  Lists.<String>newArrayList()
                              )
                          )
                      )

                  ).build()
          )
          .setReportParseExceptions(false)
          .setMaxRowCount(NUM_POINTS)
          .buildOnheap();

      IncrementalIndex second = new IncrementalIndex.Builder()
          .setIndexSchema(
              new IncrementalIndexSchema.Builder()
                  .withMinTimestamp(DATA_INTERVAL.getStartMillis())
                  .withQueryGranularity(Granularities.DAY)
                  .withMetrics(METRIC_AGGS)
                  .withDimensionsSpec(
                      new DimensionsSpec(
                          null,
                          null,
                          Arrays.asList(
                              new SpatialDimensionSchema(
                                  "dim.geo",
                                  Lists.<String>newArrayList()
                              )
                          )
                      )
                  ).build()
          )
          .setReportParseExceptions(false)
          .setMaxRowCount(NUM_POINTS)
          .buildOnheap();

      IncrementalIndex third = new IncrementalIndex.Builder()
          .setIndexSchema(
              new IncrementalIndexSchema.Builder()
                  .withMinTimestamp(DATA_INTERVAL.getStartMillis())
                  .withQueryGranularity(Granularities.DAY)
                  .withMetrics(METRIC_AGGS)
                  .withDimensionsSpec(
                      new DimensionsSpec(
                          null,
                          null,
                          Arrays.asList(
                              new SpatialDimensionSchema(
                                  "dim.geo",
                                  Lists.<String>newArrayList()
                              )
                          )
                      )

                  ).build()
          )
          .setReportParseExceptions(false)
          .setMaxRowCount(NUM_POINTS)
          .buildOnheap();

      first.add(
          new MapBasedInputRow(
              DateTimes.of("2013-01-01").getMillis(),
              DIMS,
              ImmutableMap.<String, Object>of(
                  "timestamp", DateTimes.of("2013-01-01").toString(),
                  "dim", "foo",
                  "dim.geo", "0.0,0.0",
                  "val", 17L
              )
          )
      );
      first.add(
          new MapBasedInputRow(
              DateTimes.of("2013-01-02").getMillis(),
              DIMS,
              ImmutableMap.<String, Object>of(
                  "timestamp", DateTimes.of("2013-01-02").toString(),
                  "dim", "foo",
                  "dim.geo", "1.0,3.0",
                  "val", 29L
              )
          )
      );
      first.add(
          new MapBasedInputRow(
              DateTimes.of("2013-01-03").getMillis(),
              DIMS,
              ImmutableMap.<String, Object>of(
                  "timestamp", DateTimes.of("2013-01-03").toString(),
                  "dim", "foo",
                  "dim.geo", "4.0,2.0",
                  "val", 13L
              )
          )
      );
      first.add(
          new MapBasedInputRow(
              DateTimes.of("2013-01-05").getMillis(),
              DIMS,
              ImmutableMap.<String, Object>of(
                  "timestamp", DateTimes.of("2013-01-05").toString(),
                  "dim", "foo",
                  "dim.geo", "_mmx.unknown",
                  "val", 501L
              )
          )
      );
      second.add(
          new MapBasedInputRow(
              DateTimes.of("2013-01-04").getMillis(),
              DIMS,
              ImmutableMap.<String, Object>of(
                  "timestamp", DateTimes.of("2013-01-04").toString(),
                  "dim", "foo",
                  "dim.geo", "7.0,3.0",
                  "val", 91L
              )
          )
      );
      second.add(
          new MapBasedInputRow(
              DateTimes.of("2013-01-05").getMillis(),
              DIMS,
              ImmutableMap.<String, Object>of(
                  "timestamp", DateTimes.of("2013-01-05").toString(),
                  "dim", "foo",
                  "dim.geo", "8.0,6.0",
                  "val", 47L
              )
          )
      );

      // Add a bunch of random points
      Random rand = new Random();
      for (int i = 6; i < NUM_POINTS; i++) {
        third.add(
            new MapBasedInputRow(
                DateTimes.of("2013-01-01").getMillis(),
                DIMS,
                ImmutableMap.<String, Object>of(
                    "timestamp", DateTimes.of("2013-01-01").toString(),
                    "dim", "boo",
                    "dim.geo", StringUtils.format(
                        "%s,%s",
                        (float) (rand.nextFloat() * 10 + 10.0),
                        (float) (rand.nextFloat() * 10 + 10.0)
                    ),
                    "val", i
                )
            )
        );
      }


      File tmpFile = File.createTempFile("yay", "who");
      tmpFile.delete();

      File firstFile = new File(tmpFile, "first");
      File secondFile = new File(tmpFile, "second");
      File thirdFile = new File(tmpFile, "third");
      File mergedFile = new File(tmpFile, "merged");

      firstFile.mkdirs();
      firstFile.deleteOnExit();
      secondFile.mkdirs();
      secondFile.deleteOnExit();
      thirdFile.mkdirs();
      thirdFile.deleteOnExit();
      mergedFile.mkdirs();
      mergedFile.deleteOnExit();

      indexMerger.persist(first, DATA_INTERVAL, firstFile, indexSpec, null);
      indexMerger.persist(second, DATA_INTERVAL, secondFile, indexSpec, null);
      indexMerger.persist(third, DATA_INTERVAL, thirdFile, indexSpec, null);

      QueryableIndex mergedRealtime = indexIO.loadIndex(
          indexMerger.mergeQueryableIndex(
              Arrays.asList(
                  indexIO.loadIndex(firstFile),
                  indexIO.loadIndex(secondFile),
                  indexIO.loadIndex(thirdFile)
              ),
              true,
              METRIC_AGGS,
              mergedFile,
              indexSpec,
              null
          )
      );

      return mergedRealtime;
    }
    catch (IOException e) {
      throw Throwables.propagate(e);
    }
  }

  @Test
  public void testSpatialQuery()
  {
    TimeseriesQuery query = Druids.newTimeseriesQueryBuilder()
                                  .dataSource("test")
                                  .granularity(Granularities.ALL)
                                  .intervals(Arrays.asList(Intervals.of("2013-01-01/2013-01-07")))
                                  .filters(
                                      new SpatialDimFilter(
                                          "dim.geo",
                                          new RadiusBound(new float[]{0.0f, 0.0f}, 5)
                                      )
                                  )
                                  .aggregators(
                                      Arrays.<AggregatorFactory>asList(
                                          new CountAggregatorFactory("rows"),
                                          new LongSumAggregatorFactory("val", "val")
                                      )
                                  )
                                  .build();

    List<Result<TimeseriesResultValue>> expectedResults = Arrays.asList(
        new Result<TimeseriesResultValue>(
            DateTimes.of("2013-01-01T00:00:00.000Z"),
            new TimeseriesResultValue(
                ImmutableMap.<String, Object>builder()
                    .put("rows", 3L)
                    .put("val", 59L)
                    .build()
            )
        )
    );
    try {
      TimeseriesQueryRunnerFactory factory = new TimeseriesQueryRunnerFactory(
          new TimeseriesQueryQueryToolChest(
              QueryRunnerTestHelper.NoopIntervalChunkingQueryRunnerDecorator()),
          new TimeseriesQueryEngine(),
          QueryRunnerTestHelper.NOOP_QUERYWATCHER
      );

      QueryRunner runner = new FinalizeResultsQueryRunner(
          factory.createRunner(segment),
          factory.getToolchest()
      );
      HashMap<String, Object> context = new HashMap<String, Object>();
      TestHelper.assertExpectedResults(expectedResults, runner.run(QueryPlus.wrap(query), context));
    }
    catch (Exception e) {
      throw Throwables.propagate(e);
    }
  }

  @Test
  public void testSpatialQueryMorePoints()
  {
    TimeseriesQuery query = Druids.newTimeseriesQueryBuilder()
                                  .dataSource("test")
                                  .granularity(Granularities.DAY)
                                  .intervals(Arrays.asList(Intervals.of("2013-01-01/2013-01-07")))
                                  .filters(
                                      new SpatialDimFilter(
                                          "dim.geo",
                                          new RectangularBound(new float[]{0.0f, 0.0f}, new float[]{9.0f, 9.0f})
                                      )
                                  )
                                  .aggregators(
                                      Arrays.<AggregatorFactory>asList(
                                          new CountAggregatorFactory("rows"),
                                          new LongSumAggregatorFactory("val", "val")
                                      )
                                  )
                                  .build();

    List<Result<TimeseriesResultValue>> expectedResults = Arrays.asList(
        new Result<TimeseriesResultValue>(
            DateTimes.of("2013-01-01T00:00:00.000Z"),
            new TimeseriesResultValue(
                ImmutableMap.<String, Object>builder()
                    .put("rows", 1L)
                    .put("val", 17L)
                    .build()
            )
        ),
        new Result<TimeseriesResultValue>(
            DateTimes.of("2013-01-02T00:00:00.000Z"),
            new TimeseriesResultValue(
                ImmutableMap.<String, Object>builder()
                    .put("rows", 1L)
                    .put("val", 29L)
                    .build()
            )
        ),
        new Result<TimeseriesResultValue>(
            DateTimes.of("2013-01-03T00:00:00.000Z"),
            new TimeseriesResultValue(
                ImmutableMap.<String, Object>builder()
                    .put("rows", 1L)
                    .put("val", 13L)
                    .build()
            )
        ),
        new Result<TimeseriesResultValue>(
            DateTimes.of("2013-01-04T00:00:00.000Z"),
            new TimeseriesResultValue(
                ImmutableMap.<String, Object>builder()
                    .put("rows", 1L)
                    .put("val", 91L)
                    .build()
            )
        ),
        new Result<TimeseriesResultValue>(
            DateTimes.of("2013-01-05T00:00:00.000Z"),
            new TimeseriesResultValue(
                ImmutableMap.<String, Object>builder()
                    .put("rows", 1L)
                    .put("val", 47L)
                    .build()
            )
        )
    );
    try {
      TimeseriesQueryRunnerFactory factory = new TimeseriesQueryRunnerFactory(
          new TimeseriesQueryQueryToolChest(
              QueryRunnerTestHelper.NoopIntervalChunkingQueryRunnerDecorator()),
          new TimeseriesQueryEngine(),
          QueryRunnerTestHelper.NOOP_QUERYWATCHER
      );

      QueryRunner runner = new FinalizeResultsQueryRunner(
          factory.createRunner(segment),
          factory.getToolchest()
      );
      HashMap<String, Object> context = new HashMap<String, Object>();
      TestHelper.assertExpectedResults(expectedResults, runner.run(QueryPlus.wrap(query), context));
    }
    catch (Exception e) {
      throw Throwables.propagate(e);
    }
  }

  @Test
  public void testSpatialQueryFilteredAggregator()
  {
    TimeseriesQuery query = Druids.newTimeseriesQueryBuilder()
                                  .dataSource("test")
                                  .granularity(Granularities.DAY)
                                  .intervals(Arrays.asList(Intervals.of("2013-01-01/2013-01-07")))
                                  .aggregators(
                                      Arrays.asList(
                                          new CountAggregatorFactory("rows"),
                                          new FilteredAggregatorFactory(
                                              new LongSumAggregatorFactory("valFiltered", "val"),
                                              new SpatialDimFilter(
                                                  "dim.geo",
                                                  new RectangularBound(new float[]{0.0f, 0.0f}, new float[]{9.0f, 9.0f})
                                              )
                                          ),
                                          new LongSumAggregatorFactory("val", "val")
                                      )
                                  )
                                  .build();

    List<Result<TimeseriesResultValue>> expectedResults = Arrays.asList(
        new Result<>(
            DateTimes.of("2013-01-01T00:00:00.000Z"),
            new TimeseriesResultValue(
                ImmutableMap.<String, Object>builder()
                    .put("rows", 4995L)
                    .put("val", 12497502L)
                    .put("valFiltered", 17L)
                    .build()
            )
        ),
        new Result<>(
            DateTimes.of("2013-01-02T00:00:00.000Z"),
            new TimeseriesResultValue(
                ImmutableMap.<String, Object>builder()
                    .put("rows", 1L)
                    .put("val", 29L)
                    .put("valFiltered", 29L)
                    .build()
            )
        ),
        new Result<>(
            DateTimes.of("2013-01-03T00:00:00.000Z"),
            new TimeseriesResultValue(
                ImmutableMap.<String, Object>builder()
                    .put("rows", 1L)
                    .put("val", 13L)
                    .put("valFiltered", 13L)
                    .build()
            )
        ),
        new Result<>(
            DateTimes.of("2013-01-04T00:00:00.000Z"),
            new TimeseriesResultValue(
                ImmutableMap.<String, Object>builder()
                    .put("rows", 1L)
                    .put("val", 91L)
                    .put("valFiltered", 91L)
                    .build()
            )
        ),
        new Result<>(
            DateTimes.of("2013-01-05T00:00:00.000Z"),
            new TimeseriesResultValue(
                ImmutableMap.<String, Object>builder()
                    .put("rows", 2L)
                    .put("val", 548L)
                    .put("valFiltered", 47L)
                    .build()
            )
        )
    );
    try {
      TimeseriesQueryRunnerFactory factory = new TimeseriesQueryRunnerFactory(
          new TimeseriesQueryQueryToolChest(
              QueryRunnerTestHelper.NoopIntervalChunkingQueryRunnerDecorator()),
          new TimeseriesQueryEngine(),
          QueryRunnerTestHelper.NOOP_QUERYWATCHER
      );

      QueryRunner runner = new FinalizeResultsQueryRunner(
          factory.createRunner(segment),
          factory.getToolchest()
      );
      HashMap<String, Object> context = new HashMap<String, Object>();
      TestHelper.assertExpectedResults(expectedResults, runner.run(QueryPlus.wrap(query), context));
    }
    catch (Exception e) {
      throw Throwables.propagate(e);
    }
  }
}
