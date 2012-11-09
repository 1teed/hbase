/**
 * Copyright 2010 The Apache Software Foundation
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
package org.apache.hadoop.hbase.master.handler;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.hbase.HServerInfo;
import org.apache.hadoop.hbase.executor.HBaseEventHandler;
import org.apache.hadoop.hbase.master.ServerManager;

/**
 * This is the event handler for all events relating to closing regions on the
 * HMaster. The following event types map to this handler:
 *   - RS_REGION_CLOSING
 *   - RS_REGION_CLOSED
 */
public class MasterCloseRegionHandler extends HBaseEventHandler
{
  private static final Log LOG = LogFactory.getLog(MasterCloseRegionHandler.class);
  
  public MasterCloseRegionHandler(HBaseEventType eventType, 
                                  ServerManager serverManager, 
                                  String serverName, 
                                  String regionName, 
                                  byte[] serializedData) {
    super(false, serverName, eventType, regionName, serializedData, serverManager);
  }

  /**
   * Handle the various events relating to closing regions. We can get the 
   * following events here:
   *   - RS_REGION_CLOSING : No-op
   *   - RS_REGION_CLOSED  : The region is closed. If we are not in a shutdown 
   *                         state, find the RS to open this region. This could 
   *                         be a part of a region move, or just that the RS has 
   *                         died. Should result in a M_REQUEST_OPENREGION event 
   *                         getting created.
   */
  @Override
  public void process()
  {
    LOG.debug("Event = " + getHBEvent() + ", region = " + regionName);
    // handle RS_REGION_CLOSED events
    handleRegionClosedEvent();
  }
  
  private void handleRegionClosedEvent() {
    ensureEventDataAvailable();
    String serverName = hbEventData.getRsName();
    HServerInfo serverInfo = serverManager.getServerInfo(serverName);
    if (serverInfo == null) {
      LOG.warn("Could not find serverInfo for " + serverName + " . Creating from serverName");
      serverInfo = HServerInfo.fromServerName(serverName);
    }
    
    // process the region close - this will cause the reopening of the 
    // region as a part of the heartbeat of some RS
    serverManager.processRegionClose(serverInfo, 
        hbEventData.getHmsg().getRegionInfo());
    
    LOG.info("Processed close of region " + 
        hbEventData.getHmsg().getRegionInfo().getRegionNameAsString() +
        " by region server: " + serverName);
  }
}
