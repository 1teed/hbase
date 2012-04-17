/*
 * Copyright The Apache Software Foundation
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. The ASF
 * licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package org.apache.hadoop.hbase.regionserver;

import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.util.Arrays;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.hbase.HBaseTestingUtility;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.MediumTests;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.HBaseAdmin;
import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.regionserver.metrics.RegionMetricsStorage;
import org.apache.hadoop.hbase.regionserver.metrics.SchemaMetrics;
import org.apache.hadoop.hbase.regionserver.metrics.SchemaMetrics.
    StoreMetricType;
import org.apache.hadoop.hbase.util.Bytes;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * Test metrics incremented on region server operations.
 */
@Category(MediumTests.class)
public class TestRegionServerMetrics {

  private static final Log LOG =
      LogFactory.getLog(TestRegionServerMetrics.class.getName());

  private final static String TABLE_NAME =
      TestRegionServerMetrics.class.getSimpleName() + "Table";
  private String[] FAMILIES = new String[] { "cf1", "cf2", "anotherCF" };
  private static final int MAX_VERSIONS = 1;
  private static final int NUM_COLS_PER_ROW = 15;
  private static final int NUM_FLUSHES = 3;
  private static final int NUM_REGIONS = 4;

  private static final SchemaMetrics ALL_METRICS =
      SchemaMetrics.ALL_SCHEMA_METRICS;

  private final HBaseTestingUtility TEST_UTIL =
      new HBaseTestingUtility();

  private Map<String, Long> startingMetrics;

  private final int META_AND_ROOT = 2;

  @Before
  public void setUp() throws Exception {
    SchemaMetrics.setUseTableNameInTest(true);
    startingMetrics = SchemaMetrics.getMetricsSnapshot();
    TEST_UTIL.startMiniCluster();
  }

  @After
  public void tearDown() throws Exception {
    TEST_UTIL.shutdownMiniCluster();
    SchemaMetrics.validateMetricChanges(startingMetrics);
  }

  private void assertStoreMetricEquals(long expected,
      SchemaMetrics schemaMetrics, StoreMetricType storeMetricType) {
    final String storeMetricName =
        schemaMetrics.getStoreMetricName(storeMetricType);
    Long startValue = startingMetrics.get(storeMetricName);
    assertEquals("Invalid value for store metric " + storeMetricName
        + " (type " + storeMetricType + ")", expected,
        RegionMetricsStorage.getNumericMetric(storeMetricName)
            - (startValue != null ? startValue : 0));
  }

  @Test
  public void testMultipleRegions() throws IOException, InterruptedException {

    TEST_UTIL.createRandomTable(
        TABLE_NAME,
        Arrays.asList(FAMILIES),
        MAX_VERSIONS, NUM_COLS_PER_ROW, NUM_FLUSHES, NUM_REGIONS, 1000);

    final HRegionServer rs =
        TEST_UTIL.getMiniHBaseCluster().getRegionServer(0);

    assertEquals(NUM_REGIONS + META_AND_ROOT, rs.getOnlineRegions().size());

    rs.doMetrics();
    for (HRegion r : TEST_UTIL.getMiniHBaseCluster().getRegions(
        Bytes.toBytes(TABLE_NAME))) {
      for (Map.Entry<byte[], Store> storeEntry : r.getStores().entrySet()) {
        LOG.info("For region " + r.getRegionNameAsString() + ", CF " +
            Bytes.toStringBinary(storeEntry.getKey()) + " found store files " +
            ": " + storeEntry.getValue().getStorefiles());
      }
    }

    assertStoreMetricEquals(NUM_FLUSHES * NUM_REGIONS * FAMILIES.length
        + META_AND_ROOT, ALL_METRICS, StoreMetricType.STORE_FILE_COUNT);

    for (String cf : FAMILIES) {
      SchemaMetrics schemaMetrics = SchemaMetrics.getInstance(TABLE_NAME, cf);
      assertStoreMetricEquals(NUM_FLUSHES * NUM_REGIONS, schemaMetrics,
          StoreMetricType.STORE_FILE_COUNT);
    }

    // ensure that the max value is also maintained
    final String storeMetricName = ALL_METRICS
        .getStoreMetricNameMax(StoreMetricType.STORE_FILE_COUNT);
    assertEquals("Invalid value for store metric " + storeMetricName,
        NUM_FLUSHES, RegionMetricsStorage.getNumericMetric(storeMetricName));
  }


  @org.junit.Rule
  public org.apache.hadoop.hbase.ResourceCheckerJUnitRule cu =
    new org.apache.hadoop.hbase.ResourceCheckerJUnitRule();

  private void assertSizeMetric(String table, String[] cfs, int[] metrics) {
    // we have getsize & nextsize for each column family
    assertEquals(cfs.length * 2, metrics.length);

    for (int i =0; i < cfs.length; ++i) {
      String prefix = SchemaMetrics.generateSchemaMetricsPrefix(table, cfs[i]);
      String getMetric = prefix + SchemaMetrics.METRIC_GETSIZE;
      String nextMetric = prefix + SchemaMetrics.METRIC_NEXTSIZE;

      // verify getsize and nextsize matches
      int getSize = RegionMetricsStorage.getNumericMetrics().containsKey(getMetric) ?
          RegionMetricsStorage.getNumericMetrics().get(getMetric).intValue() : 0;
      int nextSize = RegionMetricsStorage.getNumericMetrics().containsKey(nextMetric) ?
          RegionMetricsStorage.getNumericMetrics().get(nextMetric).intValue() : 0;

      assertEquals(metrics[i], getSize);
      assertEquals(metrics[cfs.length + i], nextSize);
    }
  }

  @Test
  public void testGetNextSize() throws IOException, InterruptedException {
    String rowName = "row1";
    byte[] ROW = Bytes.toBytes(rowName);
    String tableName = "SizeMetricTest";
    byte[] TABLE = Bytes.toBytes(tableName);
    String cf1Name = "cf1";
    String cf2Name = "cf2";
    String[] cfs = new String[] {cf1Name, cf2Name};
    byte[] CF1 = Bytes.toBytes(cf1Name);
    byte[] CF2 = Bytes.toBytes(cf2Name);

    long ts = 1234;
    HTable hTable = TEST_UTIL.createTable(TABLE, new byte[][]{CF1, CF2});
    HBaseAdmin admin = new HBaseAdmin(TEST_UTIL.getConfiguration());

    Put p = new Put(ROW);
    p.add(CF1, CF1, ts, CF1);
    p.add(CF2, CF2, ts, CF2);
    hTable.put(p);

    KeyValue kv1 = new KeyValue(ROW, CF1, CF1, ts, CF1);
    KeyValue kv2 = new KeyValue(ROW, CF2, CF2, ts, CF2);
    int kvLength = kv1.getLength();
    assertEquals(kvLength, kv2.getLength());

    // only cf1.getsize is set on Get
    hTable.get(new Get(ROW).addFamily(CF1));
    assertSizeMetric(tableName, cfs, new int[] {kvLength, 0, 0, 0});

    // only cf2.getsize is set on Get
    hTable.get(new Get(ROW).addFamily(CF2));
    assertSizeMetric(tableName, cfs, new int[] {kvLength, kvLength, 0, 0});

    // only cf2.nextsize is set
    for (Result res : hTable.getScanner(CF2)) {
    }
    assertSizeMetric(tableName, cfs,
        new int[] {kvLength, kvLength, 0, kvLength});

    // only cf2.nextsize is set
    for (Result res : hTable.getScanner(CF1)) {
    }
    assertSizeMetric(tableName, cfs,
        new int[] {kvLength, kvLength, kvLength, kvLength});

    // getsize/nextsize should not be set on flush or compaction
    for (HRegion hr : TEST_UTIL.getMiniHBaseCluster().getRegions(TABLE)) {
      hr.flushcache();
      hr.compactStores();
    }
    assertSizeMetric(tableName, cfs,
        new int[] {kvLength, kvLength, kvLength, kvLength});
  }
}

