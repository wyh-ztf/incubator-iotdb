/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.iotdb.db.query.executor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.iotdb.db.conf.IoTDBDescriptor;
import org.apache.iotdb.db.engine.querycontext.QueryDataSource;
import org.apache.iotdb.db.exception.StorageEngineException;
import org.apache.iotdb.db.exception.query.QueryProcessException;
import org.apache.iotdb.db.metadata.PartialPath;
import org.apache.iotdb.db.qp.physical.crud.AggregationPlan;
import org.apache.iotdb.db.qp.physical.crud.RawDataQueryPlan;
import org.apache.iotdb.db.query.aggregation.AggregateResult;
import org.apache.iotdb.db.query.aggregation.impl.MultiAggrResult;
import org.apache.iotdb.db.query.context.QueryContext;
import org.apache.iotdb.db.query.control.QueryResourceManager;
import org.apache.iotdb.db.query.dataset.SingleDataSet;
import org.apache.iotdb.db.query.factory.AggregateResultFactory;
import org.apache.iotdb.db.query.filter.TsFileFilter;
import org.apache.iotdb.db.query.reader.series.IAggregateReader;
import org.apache.iotdb.db.query.reader.series.IReaderByTimestamp;
import org.apache.iotdb.db.query.reader.series.SeriesAggregateReader;
import org.apache.iotdb.db.query.reader.series.SeriesReaderByTimestamp;
import org.apache.iotdb.db.query.timegenerator.ServerTimeGenerator;
import org.apache.iotdb.db.utils.FilePathUtils;
import org.apache.iotdb.db.utils.QueryUtils;
import org.apache.iotdb.tsfile.file.metadata.enums.TSDataType;
import org.apache.iotdb.tsfile.file.metadata.statistics.Statistics;
import org.apache.iotdb.tsfile.read.common.BatchData;
import org.apache.iotdb.tsfile.read.common.RowRecord;
import org.apache.iotdb.tsfile.read.expression.IExpression;
import org.apache.iotdb.tsfile.read.expression.impl.GlobalTimeExpression;
import org.apache.iotdb.tsfile.read.filter.basic.Filter;
import org.apache.iotdb.tsfile.read.query.dataset.QueryDataSet;
import org.apache.iotdb.tsfile.read.query.timegenerator.TimeGenerator;

public class AggregationExecutor {

  private List<PartialPath> selectedSeries;
  protected List<TSDataType> dataTypes;
  protected List<String> aggregations;
  protected IExpression expression;

  /**
   * aggregation batch calculation size.
   **/
  private int aggregateFetchSize;

  protected AggregationExecutor(AggregationPlan aggregationPlan) {
    this.selectedSeries = aggregationPlan.getDeduplicatedPaths();
    this.dataTypes = aggregationPlan.getDeduplicatedDataTypes();
    this.aggregations = aggregationPlan.getDeduplicatedAggregations();
    this.expression = aggregationPlan.getExpression();
    this.aggregateFetchSize = IoTDBDescriptor.getInstance().getConfig().getBatchSize();
  }

  /**
   * execute aggregate function with only time filter or no filter.
   *
   * @param context query context
   */
  public QueryDataSet executeWithoutValueFilter(QueryContext context, AggregationPlan aggregationPlan)
      throws StorageEngineException, IOException, QueryProcessException {

    Filter timeFilter = null;
    if (expression != null) {
      timeFilter = ((GlobalTimeExpression) expression).getFilter();
    }

    // TODO use multi-thread
    Map<PartialPath, List<Integer>> pathToAggrIndexesMap = groupAggregationsBySeries(selectedSeries);
    AggregateResult[] aggregateResultList = new AggregateResult[selectedSeries.size()];
    for (Map.Entry<PartialPath, List<Integer>> entry : pathToAggrIndexesMap.entrySet()) {
      List<AggregateResult> aggregateResults = aggregateOneSeries(entry, aggregationPlan.getAllMeasurementsInDevice(entry.getKey().getDevice()), timeFilter, context);
      int index = 0;
      for (int i : entry.getValue()) {
        aggregateResultList[i] = aggregateResults.get(index);
        index++;
      }
    }

    return constructDataSet(Arrays.asList(aggregateResultList), aggregationPlan);
  }

  /**
   * get aggregation result for one series
   *
   * @param pathToAggrIndexes entry of path to aggregation indexes map
   * @param timeFilter time filter
   * @param context query context
   * @return AggregateResult list
   */
  protected List<AggregateResult> aggregateOneSeries(
      Map.Entry<PartialPath, List<Integer>> pathToAggrIndexes,
      Set<String> measurements,
      Filter timeFilter, QueryContext context)
      throws IOException, QueryProcessException, StorageEngineException {
   /* List<AggregateResult> aggregateResultList = new ArrayList<>();*/
    List<String> aggregateTypeList = new ArrayList<>();

    PartialPath seriesPath = pathToAggrIndexes.getKey();
    TSDataType tsDataType = dataTypes.get(pathToAggrIndexes.getValue().get(0));
    MultiAggrResult multiAggrResult = new MultiAggrResult(tsDataType);

    for (int i : pathToAggrIndexes.getValue()) {
      // construct AggregateResult
     /* AggregateResult aggregateResult = AggregateResultFactory
          .getAggrResultByName(aggregations.get(i), tsDataType);
      aggregateResultList.add(aggregateResult);*/
      aggregateTypeList.add(aggregations.get(i));
    }
    aggregateOneSeries(seriesPath, measurements, context, timeFilter, tsDataType, multiAggrResult, aggregateTypeList, null);
   /* return aggregateResultList;*/
    return multiAggrResult.getAggregateResultList();
  }

  public static void aggregateOneSeries(PartialPath seriesPath, Set<String> measurements, QueryContext context, Filter timeFilter,
      TSDataType tsDataType, MultiAggrResult multiAggrResult, List<String> aggregateTypeList,/*List<AggregateResult> aggregateResultList,*/ TsFileFilter fileFilter)
      throws StorageEngineException, IOException, QueryProcessException {

    // construct series reader without value filter
    QueryDataSource queryDataSource = QueryResourceManager.getInstance()
        .getQueryDataSource(seriesPath, context, timeFilter);
    if (fileFilter != null) {
      QueryUtils.filterQueryDataSource(queryDataSource, fileFilter);
    }
    // update filter by TTL
    timeFilter = queryDataSource.updateFilterUsingTTL(timeFilter);

    IAggregateReader seriesReader = new SeriesAggregateReader(seriesPath, measurements,
        tsDataType, context, queryDataSource, timeFilter, null, null);
    aggregateFromReader(seriesReader, multiAggrResult, /*aggregateResultList*/aggregateTypeList);
  }

  @SuppressWarnings("squid:S3776") // Suppress high Cognitive Complexity warning
  private static void aggregateFromReader(IAggregateReader seriesReader, MultiAggrResult multiAggrResult,
      /*List<AggregateResult> aggregateResultList*/List<String> aggregateTypeList) throws QueryProcessException, IOException {
    /*int remainingToCalculate = aggregateResultList.size();*/
    int remainingToCalculate = aggregateTypeList.size();
    boolean[] isCalculatedArray = new boolean[aggregateTypeList.size()];

    while (seriesReader.hasNextFile()) {
      // cal by file statistics
      if (seriesReader.canUseCurrentFileStatistics()) {
        Statistics fileStatistics = seriesReader.currentFileStatistics();
        remainingToCalculate = aggregateStatistics(multiAggrResult, aggregateTypeList, isCalculatedArray,
                remainingToCalculate, fileStatistics);
        if (remainingToCalculate == 0) {
          return;
        }
        seriesReader.skipCurrentFile();
        continue;
      }

      while (seriesReader.hasNextChunk()) {
        // cal by chunk statistics
        if (seriesReader.canUseCurrentChunkStatistics()) {
          Statistics chunkStatistics = seriesReader.currentChunkStatistics();
          remainingToCalculate = aggregateStatistics(multiAggrResult, aggregateTypeList, isCalculatedArray,
                  remainingToCalculate, chunkStatistics);
          if (remainingToCalculate == 0) {
            return;
          }
          seriesReader.skipCurrentChunk();
          continue;
        }

        remainingToCalculate = aggregatePages(seriesReader, aggregateTypeList, multiAggrResult,
                isCalculatedArray, remainingToCalculate);
        if (remainingToCalculate == 0) {
          return;
        }
      }
    }

  }

  /**
   * Aggregate each result in the list with the statistics
   * @param aggregateTypeList
   * @param isCalculatedArray
   * @param remainingToCalculate
   * @param statistics
   * @return new remainingToCalculate
   * @throws QueryProcessException
   */
  private static int aggregateStatistics(/*List<AggregateResult> aggregateResultList,*/MultiAggrResult multiAggrResult,
      List<String> aggregateTypeList,
      boolean[] isCalculatedArray, int remainingToCalculate, Statistics statistics)
      throws QueryProcessException {
   /* int newRemainingToCalculate = remainingToCalculate;
    for (int i = 0; i < aggregateTypeList.size(); i++) {
      if (!isCalculatedArray[i]) {
        AggregateResult aggregateResult = aggregateResultList.get(i);
        aggregateResult.updateResultFromStatistics(statistics);
        if (aggregateResult.isCalculatedAggregationResult()) {
          isCalculatedArray[i] = true;
          newRemainingToCalculate--;
          if (newRemainingToCalculate == 0) {
            return newRemainingToCalculate;
          }
        }
      }
    }*/
    /*if(aggregateTypeList.size()>1)
      return multiAggrResult.updateResultFromStatistics(statistics, isCalculatedArray, remainingToCalculate, aggregateTypeList);*/
    /*return newRemainingToCalculate;*/
   return multiAggrResult.updateResultFromStatistics(statistics, isCalculatedArray, remainingToCalculate, aggregateTypeList);
  }

  @SuppressWarnings("squid:S3776") // Suppress high Cognitive Complexity warning
  private static int aggregatePages(IAggregateReader seriesReader,List<String> aggregateTypeList
      /*List<AggregateResult> aggregateResultList*/, MultiAggrResult multiAggrResult, boolean[] isCalculatedArray, int remainingToCalculate)
      throws IOException, QueryProcessException {
    while (seriesReader.hasNextPage()) {
      //cal by page statistics
      if (seriesReader.canUseCurrentPageStatistics()) {
        Statistics pageStatistic = seriesReader.currentPageStatistics();
        remainingToCalculate = aggregateStatistics(multiAggrResult, aggregateTypeList, isCalculatedArray,
            remainingToCalculate, pageStatistic);
       /* remainingToCalculate = aggregateStatistics(pageStatistic, isCalculatedArray, remainingToCalculate, aggregateTypeList);*/
        if (remainingToCalculate == 0) {
          return 0;
        }
        seriesReader.skipCurrentPage();
        continue;
      }
      BatchData nextOverlappedPageData = seriesReader.nextPage();
      remainingToCalculate = multiAggrResult.updateResultFromPageData(nextOverlappedPageData, aggregateTypeList, isCalculatedArray, remainingToCalculate);
     /* for (int i = 0; i < aggregateResultList.size(); i++) {
        if (!isCalculatedArray[i]) {
          AggregateResult aggregateResult = aggregateResultList.get(i);
          aggregateResult.updateResultFromPageData(nextOverlappedPageData);
          nextOverlappedPageData.resetBatchData();
          if (aggregateResult.isCalculatedAggregationResult()) {
            isCalculatedArray[i] = true;
            remainingToCalculate--;
            if (remainingToCalculate == 0) {
              return 0;
            }
          }
        }
      }*/
    }
    return remainingToCalculate;
  }

  /**
   * execute aggregate function with value filter.
   *
   * @param context query context.
   */
  public QueryDataSet executeWithValueFilter(QueryContext context, RawDataQueryPlan queryPlan)
      throws StorageEngineException, IOException, QueryProcessException {

    TimeGenerator timestampGenerator = getTimeGenerator(context, queryPlan);
    List<IReaderByTimestamp> readersOfSelectedSeries = new ArrayList<>();
    for (int i = 0; i < selectedSeries.size(); i++) {
      PartialPath path = selectedSeries.get(i);
      IReaderByTimestamp seriesReaderByTimestamp = getReaderByTime(path, queryPlan,
          dataTypes.get(i), context);
      readersOfSelectedSeries.add(seriesReaderByTimestamp);
    }

    List<AggregateResult> aggregateResults = new ArrayList<>();
    for (int i = 0; i < selectedSeries.size(); i++) {
      TSDataType type = dataTypes.get(i);
      AggregateResult result = AggregateResultFactory.getAggrResultByName(aggregations.get(i), type);
      aggregateResults.add(result);
    }
    aggregateWithValueFilter(aggregateResults, timestampGenerator, readersOfSelectedSeries);
    return constructDataSet(aggregateResults, queryPlan);
  }

  protected TimeGenerator getTimeGenerator(QueryContext context, RawDataQueryPlan queryPlan) throws StorageEngineException {
    return new ServerTimeGenerator(expression, context, queryPlan);
  }

  protected IReaderByTimestamp getReaderByTime(PartialPath path, RawDataQueryPlan queryPlan, TSDataType dataType,
      QueryContext context) throws StorageEngineException, QueryProcessException {
    return new SeriesReaderByTimestamp(path, queryPlan.getAllMeasurementsInDevice(path.getDevice()), dataType, context,
        QueryResourceManager.getInstance().getQueryDataSource(path, context, null), null);
  }

  /**
   * calculate aggregation result with value filter.
   */
  private void aggregateWithValueFilter(List<AggregateResult> aggregateResults,
      TimeGenerator timestampGenerator, List<IReaderByTimestamp> readersOfSelectedSeries)
      throws IOException {

    while (timestampGenerator.hasNext()) {

      // generate timestamps for aggregate
      long[] timeArray = new long[aggregateFetchSize];
      int timeArrayLength = 0;
      for (int cnt = 0; cnt < aggregateFetchSize; cnt++) {
        if (!timestampGenerator.hasNext()) {
          break;
        }
        timeArray[timeArrayLength++] = timestampGenerator.next();
      }

      // cal part of aggregate result
      for (int i = 0; i < readersOfSelectedSeries.size(); i++) {
        aggregateResults.get(i).updateResultUsingTimestamps(timeArray, timeArrayLength,
            readersOfSelectedSeries.get(i));
      }
    }
  }

  /**
   * using aggregate result data list construct QueryDataSet.
   *
   * @param aggregateResultList aggregate result list
   */
  private QueryDataSet constructDataSet(List<AggregateResult> aggregateResultList, RawDataQueryPlan plan)
      throws QueryProcessException {
    RowRecord record = new RowRecord(0);
    for (AggregateResult resultData : aggregateResultList) {
      TSDataType dataType = resultData.getResultDataType();
      record.addField(resultData.getResult(), dataType);
    }

    SingleDataSet dataSet = null;
    if (((AggregationPlan)plan).getLevel() >= 0) {
      // current only support count operation
      Map<Integer, String> pathIndex = new HashMap<>();
      Map<String, Long> finalPaths = FilePathUtils.getPathByLevel(plan.getDeduplicatedPaths(), ((AggregationPlan)plan).getLevel(), pathIndex);

      RowRecord curRecord = FilePathUtils.mergeRecordByPath(record, finalPaths, pathIndex);

      List<PartialPath> paths = new ArrayList<>();
      List<TSDataType> dataTypes = new ArrayList<>();
      for (int i = 0; i < finalPaths.size(); i++) {
        dataTypes.add(TSDataType.INT64);
      }

      dataSet = new SingleDataSet(paths, dataTypes);
      dataSet.setRecord(curRecord);
    } else {
      dataSet = new SingleDataSet(selectedSeries, dataTypes);
      dataSet.setRecord(record);
    }

    return dataSet;
  }

  /**
   * Merge same series and convert to series map. For example: Given: paths: s1, s2, s3, s1 and
   * aggregations: count, sum, count, sum. Then: pathToAggrIndexesMap: s1 -> 0, 3; s2 -> 1; s3 -> 2
   *
   * @param selectedSeries selected series
   * @return path to aggregation indexes map
   */
  private Map<PartialPath, List<Integer>> groupAggregationsBySeries(List<PartialPath> selectedSeries) {
    Map<PartialPath, List<Integer>> pathToAggrIndexesMap = new HashMap<>();
    for (int i = 0; i < selectedSeries.size(); i++) {
      PartialPath series = selectedSeries.get(i);
      List<Integer> indexList = pathToAggrIndexesMap
          .computeIfAbsent(series, key -> new ArrayList<>());
      indexList.add(i);
    }
    return pathToAggrIndexesMap;
  }
}
