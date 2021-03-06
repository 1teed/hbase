/**
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
package org.apache.hadoop.hbase.client;

import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.Abortable;
import org.apache.hadoop.hbase.HRegionLocation;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.MasterAdminProtocol;
import org.apache.hadoop.hbase.MasterMonitorProtocol;
import org.apache.hadoop.hbase.ServerName;
import org.apache.hadoop.hbase.catalog.CatalogTracker;
import org.apache.hadoop.hbase.client.coprocessor.Batch;
import org.apache.hadoop.hbase.exceptions.MasterNotRunningException;
import org.apache.hadoop.hbase.exceptions.ZooKeeperConnectionException;
import org.apache.hadoop.hbase.zookeeper.ZooKeeperWatcher;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * Cluster connection.  Hosts a connection to the ZooKeeper ensemble and
 * thereafter into the HBase cluster.  Knows how to locate regions out on the cluster,
 * keeps a cache of locations and then knows how to recalibrate after they move.
 * {@link HConnectionManager} manages instances of this class.
 *
 * <p>HConnections are used by {@link HTable} mostly but also by
 * {@link HBaseAdmin}, {@link CatalogTracker},
 * and {@link ZooKeeperWatcher}.  HConnection instances can be shared.  Sharing
 * is usually what you want because rather than each HConnection instance
 * having to do its own discovery of regions out on the cluster, instead, all
 * clients get to share the one cache of locations.  Sharing makes cleanup of
 * HConnections awkward.  See {@link HConnectionManager} for cleanup
 * discussion.
 *
 * @see HConnectionManager
 */
@InterfaceAudience.Public
@InterfaceStability.Stable
public interface HConnection extends Abortable, Closeable {
  /**
   * @return Configuration instance being used by this HConnection instance.
   */
  public Configuration getConfiguration();

  /**
   * Retrieve ZooKeeperWatcher used by this connection.
   * @return ZooKeeperWatcher handle being used by the connection.
   * @throws IOException if a remote or network exception occurs
   * @deprecated Removed because it was a mistake exposing zookeeper in this
   * interface (ZooKeeper is an implementation detail).
   * Deprecated in HBase 0.94
   */
  @Deprecated
  public ZooKeeperWatcher getZooKeeperWatcher() throws IOException;

  /** @return - true if the master server is running */
  public boolean isMasterRunning()
  throws MasterNotRunningException, ZooKeeperConnectionException;

  /**
   * A table that isTableEnabled == false and isTableDisabled == false
   * is possible. This happens when a table has a lot of regions
   * that must be processed.
   * @param tableName table name
   * @return true if the table is enabled, false otherwise
   * @throws IOException if a remote or network exception occurs
   */
  public boolean isTableEnabled(byte[] tableName) throws IOException;

  /**
   * @param tableName table name
   * @return true if the table is disabled, false otherwise
   * @throws IOException if a remote or network exception occurs
   */
  public boolean isTableDisabled(byte[] tableName) throws IOException;

  /**
   * @param tableName table name
   * @return true if all regions of the table are available, false otherwise
   * @throws IOException if a remote or network exception occurs
   */
  public boolean isTableAvailable(byte[] tableName) throws IOException;
  
  /**
   * Use this api to check if the table has been created with the specified number of 
   * splitkeys which was used while creating the given table.
   * Note : If this api is used after a table's region gets splitted, the api may return
   * false.
   * @param tableName
   *          tableName
   * @param splitKeys
   *          splitKeys used while creating table
   * @throws IOException
   *           if a remote or network exception occurs
   */
  public boolean isTableAvailable(byte[] tableName, byte[][] splitKeys) throws IOException;

  /**
   * List all the userspace tables.  In other words, scan the META table.
   *
   * If we wanted this to be really fast, we could implement a special
   * catalog table that just contains table names and their descriptors.
   * Right now, it only exists as part of the META table's region info.
   *
   * @return - returns an array of HTableDescriptors
   * @throws IOException if a remote or network exception occurs
   */
  public HTableDescriptor[] listTables() throws IOException;

  /**
   * @param tableName table name
   * @return table metadata
   * @throws IOException if a remote or network exception occurs
   */
  public HTableDescriptor getHTableDescriptor(byte[] tableName)
  throws IOException;

  /**
   * Find the location of the region of <i>tableName</i> that <i>row</i>
   * lives in.
   * @param tableName name of the table <i>row</i> is in
   * @param row row key you're trying to find the region of
   * @return HRegionLocation that describes where to find the region in
   * question
   * @throws IOException if a remote or network exception occurs
   */
  public HRegionLocation locateRegion(final byte [] tableName,
      final byte [] row)
  throws IOException;

  /**
   * Allows flushing the region cache.
   */
  public void clearRegionCache();

  /**
   * Allows flushing the region cache of all locations that pertain to
   * <code>tableName</code>
   * @param tableName Name of the table whose regions we are to remove from
   * cache.
   */
  public void clearRegionCache(final byte [] tableName);

  /**
   * Deletes cached locations for the specific region.
   * @param location The location object for the region, to be purged from cache.
   */
  public void deleteCachedRegionLocation(final HRegionLocation location);

  /**
   * Find the location of the region of <i>tableName</i> that <i>row</i>
   * lives in, ignoring any value that might be in the cache.
   * @param tableName name of the table <i>row</i> is in
   * @param row row key you're trying to find the region of
   * @return HRegionLocation that describes where to find the region in
   * question
   * @throws IOException if a remote or network exception occurs
   */
  public HRegionLocation relocateRegion(final byte [] tableName,
      final byte [] row)
  throws IOException;

  /**
   * Gets the location of the region of <i>regionName</i>.
   * @param regionName name of the region to locate
   * @return HRegionLocation that describes where to find the region in
   * question
   * @throws IOException if a remote or network exception occurs
   */
  public HRegionLocation locateRegion(final byte [] regionName)
  throws IOException;

  /**
   * Gets the locations of all regions in the specified table, <i>tableName</i>.
   * @param tableName table to get regions of
   * @return list of region locations for all regions of table
   * @throws IOException
   */
  public List<HRegionLocation> locateRegions(final byte[] tableName)
  throws IOException;
  
  /**
   * Gets the locations of all regions in the specified table, <i>tableName</i>.
   * @param tableName table to get regions of
   * @param useCache Should we use the cache to retrieve the region information.
   * @param offlined True if we are to include offlined regions, false and we'll leave out offlined
   *          regions from returned list.
   * @return list of region locations for all regions of table
   * @throws IOException
   */
  public List<HRegionLocation> locateRegions(final byte[] tableName, final boolean useCache,
      final boolean offlined) throws IOException;

  /**
   * Returns a {@link MasterAdminProtocol} to the active master
   */
  public MasterAdminProtocol getMasterAdmin() throws IOException;

  /**
   * Returns an {@link MasterMonitorProtocol} to the active master
   */
  public MasterMonitorProtocol getMasterMonitor() throws IOException;


  /**
   * Establishes a connection to the region server at the specified address.
   * @param hostname RegionServer hostname
   * @param port RegionServer port
   * @return proxy for HRegionServer
   * @throws IOException if a remote or network exception occurs
   * @deprecated - use @link {#getAdmin(final ServerName serverName)} which takes into account
   *  the startCode
   */
  @Deprecated
  public AdminProtocol getAdmin(final String hostname, final int port)
  throws IOException;


  /**
   * Establishes a connection to the region server at the specified address.
   * @param serverName
   * @return proxy for HRegionServer
   * @throws IOException if a remote or network exception occurs
   */
  public AdminProtocol getAdmin(final ServerName serverName)
      throws IOException;

  /**
   * Establishes a connection to the region server at the specified address, and return
   * a region client protocol.
   *
   * @param hostname RegionServer hostname
   * @param port RegionServer port
   * @return ClientProtocol proxy for RegionServer
   * @throws IOException if a remote or network exception occurs
   * @deprecated - use @link {#getClient(final ServerName serverName)} which takes into account
   *  the startCode
   */
  @Deprecated
  public ClientProtocol getClient(final String hostname, final int port)
  throws IOException;


  /**
   * Establishes a connection to the region server at the specified address, and return
   * a region client protocol.
   *
   * @param serverName
   * @return ClientProtocol proxy for RegionServer
   * @throws IOException if a remote or network exception occurs
   *
   */
  public ClientProtocol getClient(final ServerName serverName) throws IOException;

  /**
   * Establishes a connection to the region server at the specified address.
   * @param hostname RegionServer hostname
   * @param port RegionServer port
   * @param getMaster - do we check if master is alive
   * @return proxy for HRegionServer
   * @throws IOException if a remote or network exception occurs
   * @deprecated use @link {#getAdmin(final ServerName serverName, boolean getMaster)}
   * which takes into account the startCode.
   */
  @Deprecated
  public AdminProtocol getAdmin(final String hostname, final int port, boolean getMaster)
  throws IOException;

  /**
   * Establishes a connection to the region server at the specified address.
   * @param serverName
   * @param getMaster - do we check if master is alive
   * @return proxy for HRegionServer
   * @throws IOException if a remote or network exception occurs
   */
  public AdminProtocol getAdmin(final ServerName serverName, boolean getMaster)
      throws IOException;

  /**
   * Find region location hosting passed row
   * @param tableName table name
   * @param row Row to find.
   * @param reload If true do not use cache, otherwise bypass.
   * @return Location of row.
   * @throws IOException if a remote or network exception occurs
   */
  HRegionLocation getRegionLocation(byte [] tableName, byte [] row,
    boolean reload)
  throws IOException;

  /**
   * Pass in a ServerCallable with your particular bit of logic defined and
   * this method will manage the process of doing retries with timed waits
   * and refinds of missing regions.
   *
   * @param <T> the type of the return value
   * @param callable callable to run
   * @return an object of type T
   * @throws IOException if a remote or network exception occurs
   * @throws RuntimeException other unspecified error
   */
  @Deprecated
  public <T> T getRegionServerWithRetries(ServerCallable<T> callable)
  throws IOException, RuntimeException;

  /**
   * Pass in a ServerCallable with your particular bit of logic defined and
   * this method will pass it to the defined region server.
   * @param <T> the type of the return value
   * @param callable callable to run
   * @return an object of type T
   * @throws IOException if a remote or network exception occurs
   * @throws RuntimeException other unspecified error
   */
  @Deprecated
  public <T> T getRegionServerWithoutRetries(ServerCallable<T> callable)
  throws IOException, RuntimeException;

  /**
   * Process a mixed batch of Get, Put and Delete actions. All actions for a
   * RegionServer are forwarded in one RPC call.
   *
   *
   * @param actions The collection of actions.
   * @param tableName Name of the hbase table
   * @param pool thread pool for parallel execution
   * @param results An empty array, same size as list. If an exception is thrown,
   * you can test here for partial results, and to determine which actions
   * processed successfully.
   * @throws IOException if there are problems talking to META. Per-item
   * exceptions are stored in the results array.
   * @deprecated since 0.96 - Use {@link HTableInterface#batch} instead
   */
  @Deprecated
  public void processBatch(List<? extends Row> actions, final byte[] tableName,
      ExecutorService pool, Object[] results)
      throws IOException, InterruptedException;

  /**
   * Parameterized batch processing, allowing varying return types for different
   * {@link Row} implementations.
   * @deprecated since 0.96 - Use {@link HTableInterface#batchCallback} instead
   */
  @Deprecated
  public <R> void processBatchCallback(List<? extends Row> list,
      byte[] tableName,
      ExecutorService pool,
      Object[] results,
      Batch.Callback<R> callback) throws IOException, InterruptedException;

  /**
   * Enable or disable region cache prefetch for the table. It will be
   * applied for the given table's all HTable instances within this
   * connection. By default, the cache prefetch is enabled.
   * @param tableName name of table to configure.
   * @param enable Set to true to enable region cache prefetch.
   */
  public void setRegionCachePrefetch(final byte[] tableName,
      final boolean enable);

  /**
   * Check whether region cache prefetch is enabled or not.
   * @param tableName name of table to check
   * @return true if table's region cache prefetch is enabled. Otherwise
   * it is disabled.
   */
  public boolean getRegionCachePrefetch(final byte[] tableName);

  /**
   * Scan zookeeper to get the number of region servers
   * @return the number of region servers that are currently running
   * @throws IOException if a remote or network exception occurs
   * @deprecated This method will be changed from public to package protected.
   */
  @Deprecated
  public int getCurrentNrHRS() throws IOException;

  /**
   * @param tableNames List of table names
   * @return HTD[] table metadata
   * @throws IOException if a remote or network exception occurs
   */
  public HTableDescriptor[] getHTableDescriptors(List<String> tableNames)
  throws IOException;

  /**
   * @return true if this connection is closed
   */
  public boolean isClosed();


  /**
   * Clear any caches that pertain to server name <code>sn</code>
   * @param sn A server name
   */
  public void clearCaches(final ServerName sn);

  /**
   * This function allows HBaseAdminProtocol and potentially others to get a shared MasterMonitor
   * connection.
   * @return The shared instance. Never returns null.
   * @throws MasterNotRunningException
   */
  public MasterMonitorKeepAliveConnection getKeepAliveMasterMonitor()
      throws MasterNotRunningException;

  /**
   * This function allows HBaseAdmin and potentially others to get a shared MasterAdminProtocol
   * connection.
   * @return The shared instance. Never returns null.
   * @throws MasterNotRunningException
   */
  public MasterAdminKeepAliveConnection getKeepAliveMasterAdmin() throws MasterNotRunningException;

  /**
   * @param serverName
   * @return true if the server is known as dead, false otherwise.
   */
  public boolean isDeadServer(ServerName serverName);
}

