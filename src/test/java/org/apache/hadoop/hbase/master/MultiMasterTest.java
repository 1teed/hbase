/*
 * Copyright 2011 The Apache Software Foundation
 *
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
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hbase.master;

import static org.junit.Assert.*;

import java.io.IOException;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.hbase.HBaseTestingUtility;
import org.apache.hadoop.hbase.HRegionInfo;
import org.apache.hadoop.hbase.LocalHBaseCluster;
import org.apache.hadoop.hbase.MiniHBaseCluster;
import org.apache.hadoop.hbase.regionserver.HRegion;
import org.apache.hadoop.hbase.regionserver.HRegionServer;
import org.apache.hadoop.hbase.util.JVMClusterUtil.RegionServerThread;
import org.apache.hadoop.hbase.zookeeper.ZooKeeperWrapper;
import org.junit.After;

public class MultiMasterTest {
  private static final Log LOG = LogFactory.getLog(MultiMasterTest.class);
  private MiniHBaseCluster cluster;

  protected final HBaseTestingUtility TEST_UTIL = new HBaseTestingUtility();

  public void startMiniCluster(int numMasters, int numRS) throws IOException,
      InterruptedException {
    cluster = TEST_UTIL.startMiniCluster(numMasters, numRS);
  }

  @After
  public void tearDown() throws IOException {
    header("Starting cluster shutdown");
    TEST_UTIL.shutdownMiniCluster();
    assertTrue(
        "Some ZK wrapper instances in the namespace have not been closed."
            + " See error logs above.",
        ZooKeeperWrapper.allInstancesInNamespaceClosed());
    assertFalse("An unknown ZK wrapper was closed. Most likely a ZK wrapper "
        + "was closed twice. See error logs above.",
        ZooKeeperWrapper.closedUnknownZKWrapperInTest());
  }

  /** Logs a prominent debug message with empty lines before and after. */
  protected static final void header(String msg) {
    LOG.debug("\n\n" + msg + "\n");
  }

  protected int getActiveMasterIndex() {
    final List<HMaster> masters = cluster.getMasters();
    int numActive = 0;
    int activeIndex = -1;
    for (int i = 0; i < masters.size(); i++) {
      if (masters.get(i).isActiveMaster()) {
        numActive++;
        activeIndex = i;
      }
    }
    assertEquals("Expected to find exactly one active master " + numActive,
       1, numActive);
    assertTrue(activeIndex != -1);
    return activeIndex;
  }

  /**
   * Verify we have the right number of masters and they are online.
   */
  protected void ensureMastersAreUp(final int expectedNumMasters) {
    final List<HMaster> masters = cluster.getMasters();

    // make sure all masters come online
    for (int i = 0; i < masters.size(); ++i) {
      assertTrue("Master #" + i + " (0-based, out of " + masters.size()
          + ") is dead", masters.get(i).isAlive());
    }
    assertEquals(expectedNumMasters, masters.size());
  }

  protected void killRegionServerWithMeta() {
    header("Killing the regionserver containing the META region");
    List<RegionServerThread> regionServerThreads =
        cluster.getRegionServerThreads();
    int count = 0;
    HRegion metaRegion = null;
    for (RegionServerThread regionServerThread : regionServerThreads) {
      HRegionServer regionServer = regionServerThread.getRegionServer();
      metaRegion = regionServer
          .getOnlineRegion(HRegionInfo.FIRST_META_REGIONINFO.getRegionName());
      if (metaRegion != null) {
        ++count;
        try {
          regionServer.abort("Stopping regionserver with META");
        } catch (Exception ex) {
          LOG.error(ex);
          fail("Failed to stop regionserver with META: " + ex.getMessage());
        }
        break;
      }
    }
    assertEquals("Expecting to kill exactly one regionserver with meta",
        1, count);
  }

  protected static void shortSleep() {
    try {
      Thread.sleep(5000);
    } catch (InterruptedException ex) {
      fail("Interrupted during a short sleep");
    }
  }

  protected LocalHBaseCluster localCluster() {
    return cluster.getHBaseCluster();
  }

  public MiniHBaseCluster miniCluster() {
    return cluster;
  }

}
