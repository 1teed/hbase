/**
 * Copyright 2009 The Apache Software Foundation
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
package org.apache.hadoop.hbase;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Random;
import java.util.UUID;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.commons.logging.impl.Jdk14Logger;
import org.apache.commons.logging.impl.Log4JLogger;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.client.Delete;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.HBaseAdmin;
import org.apache.hadoop.hbase.client.HConnection;
import org.apache.hadoop.hbase.client.HConnectionManager;
import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.client.NoServerForRegionException;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.ResultScanner;
import org.apache.hadoop.hbase.client.RetriesExhaustedException;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.client.ServerConnectionManager;
import org.apache.hadoop.hbase.io.encoding.DataBlockEncoding;
import org.apache.hadoop.hbase.io.hfile.Compression;
import org.apache.hadoop.hbase.io.hfile.Compression.Algorithm;
import org.apache.hadoop.hbase.ipc.HRegionInterface;
import org.apache.hadoop.hbase.master.HMaster;
import org.apache.hadoop.hbase.regionserver.HRegion;
import org.apache.hadoop.hbase.regionserver.HRegionServer;
import org.apache.hadoop.hbase.regionserver.InternalScanner;
import org.apache.hadoop.hbase.regionserver.MultiVersionConsistencyControl;
import org.apache.hadoop.hbase.regionserver.Store;
import org.apache.hadoop.hbase.regionserver.StoreFile;
import org.apache.hadoop.hbase.regionserver.StoreFile.BloomType;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.FSUtils;
import org.apache.hadoop.hbase.util.RegionSplitter;
import org.apache.hadoop.hbase.util.Threads;
import org.apache.hadoop.hbase.util.Writables;
import org.apache.hadoop.hbase.zookeeper.ZooKeeperWrapper;
import org.apache.hadoop.hdfs.DFSClient;
import org.apache.hadoop.hdfs.DistributedFileSystem;
import org.apache.hadoop.hdfs.MiniDFSCluster;
import org.apache.hadoop.hdfs.server.namenode.NameNode;
import org.apache.hadoop.mapred.MiniMRCluster;
import org.apache.hadoop.security.UnixUserGroupInformation;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.zookeeper.ZooKeeper;

import com.google.common.base.Preconditions;

/**
 * Facility for testing HBase. Added as tool to abet junit4 testing.  Replaces
 * old HBaseTestCase and HBaseCluserTestCase functionality.
 * Create an instance and keep it around doing HBase testing.  This class is
 * meant to be your one-stop shop for anything you might need testing.  Manages
 * one cluster at a time only.  Depends on log4j being on classpath and
 * hbase-site.xml for logging and test-run configuration.  It does not set
 * logging levels nor make changes to configuration parameters.
 */
public class HBaseTestingUtility {
  private final static Log LOG = LogFactory.getLog(HBaseTestingUtility.class);
  private final Configuration conf;
  private MiniZooKeeperCluster zkCluster = null;

  /**
   * The default number of regions per regionserver when creating a pre-split
   * table.
   */
  private static int DEFAULT_REGIONS_PER_SERVER = 5;

  private MiniDFSCluster dfsCluster = null;
  private MiniHBaseCluster hbaseCluster = null;
  private MiniMRCluster mrCluster = null;
  // If non-null, then already a cluster running.
  private File clusterTestBuildDir = null;
  private HBaseAdmin hbaseAdmin = null;

  /**
   * System property key to get test directory value.
   */
  public static final String TEST_DIRECTORY_KEY = "test.build.data";

  /**
   * Default parent directory for test output.
   */
  public static final String DEFAULT_TEST_DIRECTORY = "target/build/data";

  /** Compression algorithms to use in parameterized JUnit 4 tests */
  public static final List<Object[]> COMPRESSION_ALGORITHMS_PARAMETERIZED =
    Arrays.asList(new Object[][] {
      { Compression.Algorithm.NONE },
      { Compression.Algorithm.GZ }
    });

  /** This is for unit tests parameterized with a single boolean. */
  public static final List<Object[]> BOOLEAN_PARAMETERIZED =
      Arrays.asList(new Object[][] {
          { new Boolean(false) },
          { new Boolean(true) }
      });

  /** Compression algorithms to use in testing */
  public static final Compression.Algorithm[] COMPRESSION_ALGORITHMS =
      new Compression.Algorithm[] {
        Compression.Algorithm.NONE, Compression.Algorithm.GZ
      };

  /**
   * Create all combinations of Bloom filters and compression algorithms for
   * testing.
   */
  private static List<Object[]> bloomAndCompressionCombinations() {
    List<Object[]> configurations = new ArrayList<Object[]>();
    for (Compression.Algorithm comprAlgo :
         HBaseTestingUtility.COMPRESSION_ALGORITHMS) {
      for (StoreFile.BloomType bloomType : StoreFile.BloomType.values()) {
        configurations.add(new Object[] { comprAlgo, bloomType });
      }
    }
    return Collections.unmodifiableList(configurations);
  }

  public static final Collection<Object[]> BLOOM_AND_COMPRESSION_COMBINATIONS =
      bloomAndCompressionCombinations();

  public HBaseTestingUtility() {
    this(HBaseConfiguration.create());
  }

  public HBaseTestingUtility(Configuration conf) {
    this.conf = conf;
  }

  /**
   * @return Instance of Configuration.
   */
  public Configuration getConfiguration() {
    return this.conf;
  }

  /**
   * Makes sure the test directory is set up so that {@link #getTestDir()}
   * returns a valid directory. Useful in unit tests that do not run a
   * mini-cluster.
   */
  public void initTestDir() {
    if (System.getProperty(TEST_DIRECTORY_KEY) == null) {
      clusterTestBuildDir = setupClusterTestBuildDir();
      System.setProperty(TEST_DIRECTORY_KEY, clusterTestBuildDir.getPath());
    }
  }

  /**
   * @return Where to write test data on local filesystem; usually
   * {@link #DEFAULT_TEST_DIRECTORY}
   * @see #setupClusterTestBuildDir()
   */
  public static Path getTestDir() {
    return new Path(System.getProperty(TEST_DIRECTORY_KEY,
      DEFAULT_TEST_DIRECTORY));
  }

  /**
   * @param subdirName
   * @return Path to a subdirectory named <code>subdirName</code> under
   * {@link #getTestDir()}.
   * @see #setupClusterTestBuildDir()
   */
  public static Path getTestDir(final String subdirName) {
    return new Path(getTestDir(), subdirName);
  }

  /**
   * Home our cluster in a dir under target/test.  Give it a random name
   * so can have many concurrent clusters running if we need to.  Need to
   * amend the test.build.data System property.  Its what minidfscluster bases
   * it data dir on.  Moding a System property is not the way to do concurrent
   * instances -- another instance could grab the temporary
   * value unintentionally -- but not anything can do about it at moment;
   * single instance only is how the minidfscluster works.
   * @return The calculated cluster test build directory.
   */
  File setupClusterTestBuildDir() {
    String randomStr = UUID.randomUUID().toString();
    String dirStr = getTestDir(randomStr).toString();
    File dir = new File(dirStr).getAbsoluteFile();
    // Have it cleaned up on exit
    dir.deleteOnExit();
    return dir;
  }

  /**
   * @throws IOException If a cluster -- zk, dfs, or hbase -- already running.
   */
  void isRunningCluster() throws IOException {
    if (this.clusterTestBuildDir == null) return;
    throw new IOException("Cluster already running at " +
      this.clusterTestBuildDir);
  }

  /**
   * Start a minidfscluster.
   * @param servers How many DNs to start.
   * @throws Exception
   * @see {@link #shutdownMiniDFSCluster()}
   * @return The mini dfs cluster created.
   */
  public MiniDFSCluster startMiniDFSCluster(int servers) throws Exception {
    return startMiniDFSCluster(servers, null);
  }

  /**
   * Start a minidfscluster.
   * Can only create one.
   * @param dir Where to home your dfs cluster.
   * @param servers How many DNs to start.
   * @see {@link #shutdownMiniDFSCluster()}
   * @return The mini dfs cluster created.
   */
  public MiniDFSCluster startMiniDFSCluster(int servers, final File dir)
      throws IOException {
    // This does the following to home the minidfscluster
    //     base_dir = new File(System.getProperty("test.build.data", "build/test/data"), "dfs/");
    // Some tests also do this:
    //  System.getProperty("test.cache.data", "build/test/cache");
    if (dir == null) this.clusterTestBuildDir = setupClusterTestBuildDir();
    else this.clusterTestBuildDir = dir;
    System.setProperty(TEST_DIRECTORY_KEY, this.clusterTestBuildDir.toString());
    System.setProperty("test.cache.data", this.clusterTestBuildDir.toString());
    this.dfsCluster = new MiniDFSCluster(0, this.conf, servers, true, true,
      true, null, null, null, null);
    return this.dfsCluster;
  }

  /**
   * Shuts down instance created by call to {@link #startMiniDFSCluster(int, File)}
   * or does nothing.
   * @throws Exception
   */
  public void shutdownMiniDFSCluster() throws Exception {
    if (this.dfsCluster != null) {
      FileSystem.closeAll();
      // The below throws an exception per dn, AsynchronousCloseException.
      this.dfsCluster.shutdown();
    }
  }

  /**
   * Call this if you only want a zk cluster.
   * @see #startMiniZKCluster() if you want zk + dfs + hbase mini cluster.
   * @throws Exception
   * @see #shutdownMiniZKCluster()
   * @return zk cluster started.
   */
  public MiniZooKeeperCluster startMiniZKCluster() throws Exception {
    return startMiniZKCluster(setupClusterTestBuildDir());

  }

  private MiniZooKeeperCluster startMiniZKCluster(final File dir)
      throws IOException, InterruptedException {
    if (this.zkCluster != null) {
      throw new IOException("Cluster already running at " + dir);
    }
    this.zkCluster = new MiniZooKeeperCluster();
    int clientPort = this.zkCluster.startup(dir);
    this.conf.set(HConstants.ZOOKEEPER_CLIENT_PORT,
        Integer.toString(clientPort));
    return this.zkCluster;
  }

  /**
   * Shuts down zk cluster created by call to {@link #startMiniZKCluster(File)}
   * or does nothing.
   * @throws IOException
   * @see #startMiniZKCluster()
   */
  public void shutdownMiniZKCluster() throws IOException {
    if (this.zkCluster != null) this.zkCluster.shutdown();
  }

  /**
   * Start up a minicluster of hbase, dfs, and zookeeper.
   * @throws Exception
   * @return Mini hbase cluster instance created.
   * @see {@link #shutdownMiniDFSCluster()}
   */
  public MiniHBaseCluster startMiniCluster() throws Exception {
    return startMiniCluster(1, 1);
  }

  /**
   * Start up a minicluster of hbase, optionally dfs, and zookeeper.
   * Modifies Configuration.  Homes the cluster data directory under a random
   * subdirectory in a directory under System property test.build.data.
   * Directory is cleaned up on exit.
   * @param numSlaves Number of slaves to start up.  We'll start this many
   * datanodes and regionservers.  If numSlaves is > 1, then make sure
   * hbase.regionserver.info.port is -1 (i.e. no ui per regionserver) otherwise
   * bind errors.
   * @throws Exception
   * @see {@link #shutdownMiniCluster()}
   * @return Mini hbase cluster instance created.
   */
  public MiniHBaseCluster startMiniCluster(final int numSlaves)
      throws IOException, InterruptedException {
    return startMiniCluster(1, numSlaves);
  }

  /**
   * Start up a minicluster of hbase, optionally dfs, and zookeeper.
   * Modifies Configuration.  Homes the cluster data directory under a random
   * subdirectory in a directory under System property test.build.data.
   * Directory is cleaned up on exit.
   * @param numMasters Number of masters to start up.  We'll start this many
   * hbase masters.  If numMasters > 1, you can find the active/primary master
   * with {@link MiniHBaseCluster#getMaster()}.
   * @param numSlaves Number of slave servers to start up.  We'll start this
   * many datanodes and regionservers.  If servers is > 1, then make sure
   * hbase.regionserver.info.port is -1 (i.e. no ui per regionserver) otherwise
   * bind errors.
   * @throws Exception
   * @see {@link #shutdownMiniCluster()}
   * @return Mini hbase cluster instance created.
   */
  public MiniHBaseCluster startMiniCluster(final int numMasters,
      final int numSlaves) throws IOException, InterruptedException {
    LOG.info("Starting up minicluster");
    // If we already put up a cluster, fail.
    isRunningCluster();
    // Make a new random dir to home everything in.  Set it as system property.
    // minidfs reads home from system property.
    this.clusterTestBuildDir = setupClusterTestBuildDir();
    System.setProperty(TEST_DIRECTORY_KEY, this.clusterTestBuildDir.getPath());
    // Bring up mini dfs cluster. This spews a bunch of warnings about missing
    // scheme. Complaints are 'Scheme is undefined for build/test/data/dfs/name1'.
    startMiniDFSCluster(numSlaves, this.clusterTestBuildDir);

    // Mangle conf so fs parameter points to minidfs we just started up
    FileSystem fs = this.dfsCluster.getFileSystem();
    this.conf.set("fs.defaultFS", fs.getUri().toString());
    // Do old style too just to be safe.
    this.conf.set("fs.default.name", fs.getUri().toString());
    this.dfsCluster.waitClusterUp();

    // Start up a zk cluster.
    if (this.zkCluster == null) {
      startMiniZKCluster(this.clusterTestBuildDir);
    }

    // Now do the mini hbase cluster.  Set the hbase.rootdir in config.
    Path hbaseRootdir = fs.makeQualified(fs.getHomeDirectory());
    this.conf.set(HConstants.HBASE_DIR, hbaseRootdir.toString());
    fs.mkdirs(hbaseRootdir);
    FSUtils.setVersion(fs, hbaseRootdir);
    startMiniHBaseCluster(numMasters, numSlaves);

    // Don't leave here till we've done a successful scan of the .META.
    HTable t = null;
    for (int i = 0; i < 10; ++i) {
      try {
        t = new HTable(this.conf, HConstants.META_TABLE_NAME);
      } catch (NoServerForRegionException ex) {
        LOG.error("META is not online, sleeping");
        Threads.sleepWithoutInterrupt(2000);
      }
    }
    if (t == null) {
      throw new IOException("Could not open META on cluster startup");
    }

    ResultScanner s = t.getScanner(new Scan());
    while (s.next() != null) continue;
    LOG.info("Minicluster is up");
    return this.hbaseCluster;
  }

  public void startMiniHBaseCluster(final int numMasters, final int numSlaves)
      throws IOException, InterruptedException {
    this.hbaseCluster = new MiniHBaseCluster(this.conf, numMasters, numSlaves);
  }

  /**
   * @return Current mini hbase cluster. Only has something in it after a call
   * to {@link #startMiniCluster()}.
   * @see #startMiniCluster()
   */
  public MiniHBaseCluster getMiniHBaseCluster() {
    return this.hbaseCluster;
  }

  /**
   * @throws IOException
   * @see {@link #startMiniCluster(int)}
   */
  public void shutdownMiniCluster() throws IOException {
    LOG.info("Shutting down minicluster");
    if (this.hbaseCluster != null) {
      this.hbaseCluster.shutdown();
      // Wait till hbase is down before going on to shutdown zk.
      this.hbaseCluster.join();
    }
    shutdownMiniZKCluster();
    if (this.dfsCluster != null) {
      // The below throws an exception per dn, AsynchronousCloseException.
      this.dfsCluster.shutdown();
    }
    // Clean up our directory.
    if (this.clusterTestBuildDir != null && this.clusterTestBuildDir.exists()) {
      // Need to use deleteDirectory because File.delete required dir is empty.
      if (!FSUtils.deleteDirectory(FileSystem.getLocal(this.conf),
          new Path(this.clusterTestBuildDir.toString()))) {
        LOG.warn("Failed delete of " + this.clusterTestBuildDir.toString());
      }
    }
    clusterTestBuildDir = null;
    LOG.info("Minicluster is down");
    clusterTestBuildDir = null;
  }

  /**
   * Shutdown HBase mini cluster.  Does not shutdown zk or dfs if running.
   * @throws IOException
   */
  public void shutdownMiniHBaseCluster() throws IOException {
    if (this.hbaseCluster != null) {
      this.hbaseCluster.shutdown();
      // Wait till hbase is down before going on to shutdown zk.
      this.hbaseCluster.join();
      this.hbaseCluster = null;
    }
  }

  /**
   * Flushes all caches in the mini hbase cluster
   * @throws IOException
   */
  public void flush() throws IOException {
    this.hbaseCluster.flushcache();
  }

  /**
   * Flushes all caches in the mini hbase cluster
   * @throws IOException
   */
  public void flush(byte [] tableName) throws IOException {
    this.hbaseCluster.flushcache(tableName);
  }


  /**
   * Create a table.
   * @param tableName
   * @param family
   * @return An HTable instance for the created table.
   * @throws IOException
   */
  public HTable createTable(byte[] tableName, byte[] family)
  throws IOException{
    return createTable(tableName, new byte[][]{family});
  }


  public HTable createTable(byte[] tableName, byte[][] families,
      int numVersions, byte[] startKey, byte[] endKey, int numRegions)
  throws IOException{
    HTableDescriptor desc = new HTableDescriptor(tableName);
    for (byte[] family : families) {
      HColumnDescriptor hcd = new HColumnDescriptor(family)
          .setMaxVersions(numVersions);
      desc.addFamily(hcd);
    }
    (new HBaseAdmin(getConfiguration())).createTable(desc, startKey,
        endKey, numRegions);
    return new HTable(getConfiguration(), tableName);
  }


  /**
   * Create a table.
   * @param tableName
   * @param families
   * @return An HTable instance for the created table.
   * @throws IOException
   */
  public HTable createTable(byte[] tableName, byte[][] families)
  throws IOException {
    HTableDescriptor desc = new HTableDescriptor(tableName);
    for(byte[] family : families) {
      desc.addFamily(new HColumnDescriptor(family));
    }
    (new HBaseAdmin(getConfiguration())).createTable(desc);
    return new HTable(getConfiguration(), tableName);
  }

  /**
   * Create a table.
   * @param tableName
   * @param family
   * @param numVersions
   * @return An HTable instance for the created table.
   * @throws IOException
   */
  public HTable createTable(byte[] tableName, byte[] family, int numVersions)
  throws IOException {
    return createTable(tableName, new byte[][]{family}, numVersions);
  }

  /**
   * Create a table.
   * @param tableName
   * @param families
   * @param numVersions
   * @return An HTable instance for the created table.
   * @throws IOException
   */
  public HTable createTable(byte[] tableName, byte[][] families,
      int numVersions)
  throws IOException {
    HTableDescriptor desc = new HTableDescriptor(tableName);
    for (byte[] family : families) {
      HColumnDescriptor hcd = new HColumnDescriptor(family)
          .setMaxVersions(numVersions);
      desc.addFamily(hcd);
    }
    getHBaseAdmin().createTable(desc);
    return new HTable(new Configuration(getConfiguration()), tableName);
  }

  /**
   * Create a table.
   * @param tableName
   * @param families
   * @param numVersions
   * @return An HTable instance for the created table.
   * @throws IOException
   */
  public HTable createTable(byte[] tableName, byte[][] families,
    int numVersions, int blockSize) throws IOException {
    HTableDescriptor desc = new HTableDescriptor(tableName);
    for (byte[] family : families) {
      HColumnDescriptor hcd = new HColumnDescriptor(family)
          .setMaxVersions(numVersions)
          .setBlocksize(blockSize);
      desc.addFamily(hcd);
    }
    (new HBaseAdmin(getConfiguration())).createTable(desc);
    return new HTable(getConfiguration(), tableName);
  }

  /**
   * Create a table.
   * @param tableName
   * @param families
   * @param numVersions
   * @return An HTable instance for the created table.
   * @throws IOException
   */
  public HTable createTable(byte[] tableName, byte[][] families,
      int[] numVersions)
  throws IOException {
    HTableDescriptor desc = new HTableDescriptor(tableName);
    int i = 0;
    for (byte[] family : families) {
      HColumnDescriptor hcd = new HColumnDescriptor(family)
          .setMaxVersions(numVersions[i]);
      desc.addFamily(hcd);
      i++;
    }
    (new HBaseAdmin(getConfiguration())).createTable(desc);
    return new HTable(getConfiguration(), tableName);
  }

  public void deleteTable(byte[] tableName) throws IOException {
    HBaseAdmin hba = new HBaseAdmin(getConfiguration());
    hba.disableTable(tableName);
    hba.deleteTable(tableName);
  }

  /**
   * Provide an existing table name to truncate
   * @param tableName existing table
   * @return HTable to that new table
   * @throws IOException
   */
  public HTable truncateTable(byte [] tableName) throws IOException {
    HTable table = new HTable(getConfiguration(), tableName);
    Scan scan = new Scan();
    ResultScanner resScan = table.getScanner(scan);
    for(Result res : resScan) {
      Delete del = new Delete(res.getRow());
      table.delete(del);
    }
    return table;
  }

  /**
   * Load table with rows from 'aaa' to 'zzz'.
   * @param t Table
   * @param f Family
   * @return Count of rows loaded.
   * @throws IOException
   */
  public int loadTable(final HTable t, final byte[] f) throws IOException {
    t.setAutoFlush(false);
    byte[] k = new byte[3];
    int rowCount = 0;
    for (byte b1 = 'a'; b1 <= 'z'; b1++) {
      for (byte b2 = 'a'; b2 <= 'z'; b2++) {
        for (byte b3 = 'a'; b3 <= 'z'; b3++) {
          k[0] = b1;
          k[1] = b2;
          k[2] = b3;
          Put put = new Put(k);
          put.add(f, null, k);
          t.put(put);
          rowCount++;
        }
      }
    }
    t.flushCommits();
    return rowCount;
  }

  /**
   * Return the number of rows in the given table.
   */
  public int countRows(final HTable table) throws IOException {
    Scan scan = new Scan();
    ResultScanner results = table.getScanner(scan);
    int count = 0;
    for (@SuppressWarnings("unused") Result res : results) {
      count++;
    }
    results.close();
    return count;
  }

  /**
   * Return an md5 digest of the entire contents of a table.
   */
  public String checksumRows(final HTable table) throws Exception {
    Scan scan = new Scan();
    ResultScanner results = table.getScanner(scan);
    MessageDigest digest = MessageDigest.getInstance("MD5");
    for (Result res : results) {
      digest.update(res.getRow());
    }
    results.close();
    return digest.toString();
  }

  /**
   * Creates many regions names "aaa" to "zzz".
   *
   * @param table  The table to use for the data.
   * @param columnFamily  The family to insert the data into.
   * @return count of regions created.
   * @throws IOException When creating the regions fails.
   */
  public int createMultiRegions(HTable table, byte[] columnFamily)
  throws IOException {
    return createMultiRegions(getConfiguration(), table, columnFamily);
  }

  /**
   * Creates many regions names "aaa" to "zzz".
   * @param c Configuration to use.
   * @param table  The table to use for the data.
   * @param columnFamily  The family to insert the data into.
   * @return count of regions created.
   * @throws IOException When creating the regions fails.
   */
  public int createMultiRegions(final Configuration c, final HTable table,
      final byte[] columnFamily)
  throws IOException {
    byte[][] KEYS = {
      HConstants.EMPTY_BYTE_ARRAY, Bytes.toBytes("bbb"),
      Bytes.toBytes("ccc"), Bytes.toBytes("ddd"), Bytes.toBytes("eee"),
      Bytes.toBytes("fff"), Bytes.toBytes("ggg"), Bytes.toBytes("hhh"),
      Bytes.toBytes("iii"), Bytes.toBytes("jjj"), Bytes.toBytes("kkk"),
      Bytes.toBytes("lll"), Bytes.toBytes("mmm"), Bytes.toBytes("nnn"),
      Bytes.toBytes("ooo"), Bytes.toBytes("ppp"), Bytes.toBytes("qqq"),
      Bytes.toBytes("rrr"), Bytes.toBytes("sss"), Bytes.toBytes("ttt"),
      Bytes.toBytes("uuu"), Bytes.toBytes("vvv"), Bytes.toBytes("www"),
      Bytes.toBytes("xxx"), Bytes.toBytes("yyy")
    };
    return createMultiRegions(c, table, columnFamily, KEYS);
  }

  public int createMultiRegions(final Configuration c, final HTable table,
      final byte[] columnFamily, byte [][] startKeys)
  throws IOException {
    Arrays.sort(startKeys, Bytes.BYTES_COMPARATOR);
    HTable meta = new HTable(c, HConstants.META_TABLE_NAME);
    HTableDescriptor htd = table.getTableDescriptor();
    if(!htd.hasFamily(columnFamily)) {
      HColumnDescriptor hcd = new HColumnDescriptor(columnFamily);
      htd.addFamily(hcd);
    }
    // remove empty region - this is tricky as the mini cluster during the test
    // setup already has the "<tablename>,,123456789" row with an empty start
    // and end key. Adding the custom regions below adds those blindly,
    // including the new start region from empty to "bbb". lg
    List<byte[]> rows = getMetaTableRows(htd.getName());
    // add custom ones
    int count = 0;
    for (int i = 0; i < startKeys.length; i++) {
      int j = (i + 1) % startKeys.length;
      HRegionInfo hri = new HRegionInfo(table.getTableDescriptor(),
        startKeys[i], startKeys[j]);
      Put put = new Put(hri.getRegionName());
      put.add(HConstants.CATALOG_FAMILY, HConstants.REGIONINFO_QUALIFIER,
        Writables.getBytes(hri));
      meta.put(put);
      LOG.info("createMultiRegions: inserted " + hri.toString());
      count++;
    }
    // see comment above, remove "old" (or previous) single region
    for (byte[] row : rows) {
      LOG.info("createMultiRegions: deleting meta row -> " +
        Bytes.toStringBinary(row));
      meta.delete(new Delete(row));
    }
    // flush cache of regions
    HConnection conn = table.getConnection();
    conn.clearRegionCache();
    return count;
  }

  /**
   * Returns all rows from the .META. table.
   *
   * @throws IOException When reading the rows fails.
   */
  public List<byte[]> getMetaTableRows() throws IOException {
    HTable t = new HTable(this.conf, HConstants.META_TABLE_NAME);
    List<byte[]> rows = new ArrayList<byte[]>();
    ResultScanner s = t.getScanner(new Scan());
    for (Result result : s) {
      LOG.info("getMetaTableRows: row -> " +
        Bytes.toStringBinary(result.getRow()));
      rows.add(result.getRow());
    }
    s.close();
    return rows;
  }

  /**
   * Returns all rows from the .META. table for a given user table
   *
   * @throws IOException When reading the rows fails.
   */
  public List<byte[]> getMetaTableRows(byte[] tableName) throws IOException {
    HTable t = new HTable(this.conf, HConstants.META_TABLE_NAME);
    List<byte[]> rows = new ArrayList<byte[]>();
    ResultScanner s = t.getScanner(new Scan());
    for (Result result : s) {
      HRegionInfo info = Writables.getHRegionInfo(
          result.getValue(HConstants.CATALOG_FAMILY, HConstants.REGIONINFO_QUALIFIER));
      HTableDescriptor desc = info.getTableDesc();
      if (Bytes.compareTo(desc.getName(), tableName) == 0) {
        LOG.info("getMetaTableRows: row -> " +
            Bytes.toStringBinary(result.getRow()));
        rows.add(result.getRow());
      }
    }
    s.close();
    return rows;
  }

  /**
   * Tool to get the reference to the region server object that holds the
   * region of the specified user table.
   * It first searches for the meta rows that contain the region of the
   * specified table, then gets the index of that RS, and finally retrieves
   * the RS's reference.
   * @param tableName user table to lookup in .META.
   * @return region server that holds it, null if the row doesn't exist
   * @throws IOException
   */
  public HRegionServer getRSForFirstRegionInTable(byte[] tableName)
      throws IOException {
    List<byte[]> metaRows = getMetaTableRows(tableName);
    if (metaRows == null || metaRows.isEmpty()) {
      return null;
    }
    LOG.debug("Found " + metaRows.size() + " rows for table " +
      Bytes.toString(tableName));
    byte [] firstrow = metaRows.get(0);
    LOG.debug("FirstRow=" + Bytes.toString(firstrow));
    int index = hbaseCluster.getServerWith(firstrow);
    return hbaseCluster.getRegionServerThreads().get(index).getRegionServer();
  }

  /**
   * Starts a <code>MiniMRCluster</code> with a default number of
   * <code>TaskTracker</code>'s.
   *
   * @throws IOException When starting the cluster fails.
   */
  public void startMiniMapReduceCluster() throws IOException {
    startMiniMapReduceCluster(2);
  }

  /**
   * Starts a <code>MiniMRCluster</code>.
   *
   * @param servers  The number of <code>TaskTracker</code>'s to start.
   * @throws IOException When starting the cluster fails.
   */
  public void startMiniMapReduceCluster(final int servers) throws IOException {
    LOG.info("Starting mini mapreduce cluster...");
    // These are needed for the new and improved Map/Reduce framework
    Configuration c = getConfiguration();
    System.setProperty("hadoop.log.dir", c.get("hadoop.log.dir"));
    c.set("mapred.output.dir", c.get("hadoop.tmp.dir"));
    mrCluster = new MiniMRCluster(servers,
      FileSystem.get(c).getUri().toString(), 1);
    LOG.info("Mini mapreduce cluster started");
    c.set("mapred.job.tracker",
        mrCluster.createJobConf().get("mapred.job.tracker"));
  }

  /**
   * Stops the previously started <code>MiniMRCluster</code>.
   */
  public void shutdownMiniMapReduceCluster() {
    LOG.info("Stopping mini mapreduce cluster...");
    if (mrCluster != null) {
      mrCluster.shutdown();
    }
    // Restore configuration to point to local jobtracker
    conf.set("mapred.job.tracker", "local");
    LOG.info("Mini mapreduce cluster stopped");
  }

  /**
   * Switches the logger for the given class to DEBUG level.
   *
   * @param clazz  The class for which to switch to debug logging.
   */
  public void enableDebug(Class<?> clazz) {
    Log l = LogFactory.getLog(clazz);
    if (l instanceof Log4JLogger) {
      ((Log4JLogger) l).getLogger().setLevel(org.apache.log4j.Level.DEBUG);
    } else if (l instanceof Jdk14Logger) {
      ((Jdk14Logger) l).getLogger().setLevel(java.util.logging.Level.ALL);
    }
  }

  /**
   * Expire the Master's session
   * @throws Exception
   */
  public void expireMasterSession() throws Exception {
    HMaster master = hbaseCluster.getMaster();
    expireSession(master.getZooKeeperWrapper());
  }

  /**
   * Expire a region server's session
   * @param index which RS
   * @throws Exception
   */
  public void expireRegionServerSession(int index) throws Exception {
    HRegionServer rs = hbaseCluster.getRegionServer(index);
    expireSession(rs.getZooKeeperWrapper());
  }

  public void expireSession(ZooKeeperWrapper nodeZK) throws Exception{
    nodeZK.registerListener(EmptyWatcher.instance);
    String quorumServers = nodeZK.getQuorumServers();
    int sessionTimeout = nodeZK.getSessionTimeout();
    byte[] password = nodeZK.getSessionPassword();
    long sessionID = nodeZK.getSessionID();
    final long sleep = sessionTimeout * 10L;
    final int maxRetryNum = 50;
    int retryNum = maxRetryNum;

    ZooKeeper zk = new ZooKeeper(quorumServers,
        sessionTimeout, EmptyWatcher.instance, sessionID, password);
    zk.close();
    Thread.sleep(sleep);
		LOG.debug("ZooKeeper is closed");

    while (!nodeZK.isAborted() && retryNum != 0) {
      Thread.sleep(sleep);
      retryNum--;
    }
    if (retryNum == 0) {
      fail("ZooKeeper is not aborted after " + maxRetryNum + " attempts.");
    }
  }

  /**
   * Get the HBase cluster.
   *
   * @return hbase cluster
   */
  public MiniHBaseCluster getHBaseCluster() {
    return hbaseCluster;
  }

  /**
   * Returns a HBaseAdmin instance.
   *
   * @return The HBaseAdmin instance.
   * @throws MasterNotRunningException
   */
  public HBaseAdmin getHBaseAdmin() throws MasterNotRunningException {
    if (hbaseAdmin == null) {
      hbaseAdmin = new HBaseAdmin(getConfiguration());
    }
    return hbaseAdmin;
  }

  /**
   * Closes the named region.
   *
   * @param regionName  The region to close.
   * @throws IOException
   */
  public void closeRegion(String regionName) throws IOException {
    closeRegion(Bytes.toBytes(regionName));
  }

  /**
   * Closes the named region.
   *
   * @param regionName  The region to close.
   * @throws IOException
   */
  public void closeRegion(byte[] regionName) throws IOException {
    HBaseAdmin admin = getHBaseAdmin();
    admin.closeRegion(regionName, (Object[]) null);
  }

  /**
   * Closes the region containing the given row.
   *
   * @param row  The row to find the containing region.
   * @param table  The table to find the region.
   * @throws IOException
   */
  public void closeRegionByRow(String row, HTable table) throws IOException {
    closeRegionByRow(Bytes.toBytes(row), table);
  }

  /**
   * Closes the region containing the given row.
   *
   * @param row  The row to find the containing region.
   * @param table  The table to find the region.
   * @throws IOException
   */
  public void closeRegionByRow(byte[] row, HTable table) throws IOException {
    HRegionLocation hrl = table.getRegionLocation(row);
    closeRegion(hrl.getRegionInfo().getRegionName());
  }

  public MiniZooKeeperCluster getZkCluster() {
    return zkCluster;
  }

  public void setZkCluster(MiniZooKeeperCluster zkCluster) {
    this.zkCluster = zkCluster;
    conf.setInt(HConstants.ZOOKEEPER_CLIENT_PORT, zkCluster.getClientPort());
  }

  public MiniDFSCluster getDFSCluster() {
    return dfsCluster;
  }

  public FileSystem getTestFileSystem() throws IOException {
    return FileSystem.get(conf);
  }

  public void cleanupTestDir() throws IOException {
    getTestDir().getFileSystem(conf).delete(getTestDir(), true);
  }

  public void waitTableAvailable(byte[] table, long timeoutMillis)
  throws InterruptedException, IOException {
    HBaseAdmin admin = new HBaseAdmin(conf);
    long startWait = System.currentTimeMillis();
    while (!admin.isTableAvailable(table)) {
      assertTrue("Timed out waiting for table " + Bytes.toStringBinary(table),
          System.currentTimeMillis() - startWait < timeoutMillis);
      Thread.sleep(500);
    }
  }

  /**
   * Make sure that at least the specified number of region servers
   * are running
   * @param num minimum number of region servers that should be running
   * @throws IOException
   */
  public void ensureSomeRegionServersAvailable(final int num)
      throws IOException {
    if (this.getHBaseCluster().getLiveRegionServerThreads().size() < num) {
      // Need at least "num" servers.
      LOG.info("Started new server=" +
        this.getHBaseCluster().startRegionServer());

    }
  }

  /**
   * This method clones the passed <code>c</code> configuration setting a new
   * user into the clone.  Use it getting new instances of FileSystem.  Only
   * works for DistributedFileSystem.
   * @param c Initial configuration
   * @param differentiatingSuffix Suffix to differentiate this user from others.
   * @return A new configuration instance with a different user set into it.
   * @throws IOException
   */
  public static Configuration setDifferentUser(final Configuration c,
    final String differentiatingSuffix)
  throws IOException {
    FileSystem currentfs = FileSystem.get(c);
    Preconditions.checkArgument(currentfs instanceof DistributedFileSystem);
    // Else distributed filesystem.  Make a new instance per daemon.  Below
    // code is taken from the AppendTestUtil over in hdfs.
    Configuration c2 = new Configuration(c);
    String username = UserGroupInformation.getCurrentUGI().getUserName() +
      differentiatingSuffix;
    UnixUserGroupInformation.saveToConf(c2,
      UnixUserGroupInformation.UGI_PROPERTY_NAME,
      new UnixUserGroupInformation(username, new String[]{"supergroup"}));
    return c2;
  }

  /**
   * Set soft and hard limits in namenode.
   * You'll get a NPE if you call before you've started a minidfscluster.
   * @param soft Soft limit
   * @param hard Hard limit
   * @throws NoSuchFieldException
   * @throws SecurityException
   * @throws IllegalAccessException
   * @throws IllegalArgumentException
   */
  public void setNameNodeNameSystemLeasePeriod(final int soft, final int hard)
  throws SecurityException, NoSuchFieldException, IllegalArgumentException, IllegalAccessException {
    // TODO: If 0.20 hadoop do one thing, if 0.21 hadoop do another.
    // Not available in 0.20 hdfs.  Use reflection to make it happen.

    // private NameNode nameNode;
    Field field = this.dfsCluster.getClass().getDeclaredField("nameNode");
    field.setAccessible(true);
    NameNode nn = (NameNode)field.get(this.dfsCluster);
    nn.namesystem.leaseManager.setLeasePeriod(100, 50000);
  }

  /**
   * Set maxRecoveryErrorCount in DFSClient.  In 0.20 pre-append its hard-coded to 5 and
   * makes tests linger.  Here is the exception you'll see:
   * <pre>
   * 2010-06-15 11:52:28,511 WARN  [DataStreamer for file /hbase/.logs/hlog.1276627923013 block blk_928005470262850423_1021] hdfs.DFSClient$DFSOutputStream(2657): Error Recovery for block blk_928005470262850423_1021 failed  because recovery from primary datanode 127.0.0.1:53683 failed 4 times.  Pipeline was 127.0.0.1:53687, 127.0.0.1:53683. Will retry...
   * </pre>
   * @param stream A DFSClient.DFSOutputStream.
   * @param max
   * @throws NoSuchFieldException
   * @throws SecurityException
   * @throws IllegalAccessException
   * @throws IllegalArgumentException
   */
  public static void setMaxRecoveryErrorCount(final OutputStream stream,
      final int max) {
    try {
      Class<?> [] clazzes = DFSClient.class.getDeclaredClasses();
      for (Class<?> clazz: clazzes) {
        String className = clazz.getSimpleName();
        if (className.equals("DFSOutputStream")) {
          if (clazz.isInstance(stream)) {
            Field maxRecoveryErrorCountField =
              stream.getClass().getDeclaredField("maxRecoveryErrorCount");
            maxRecoveryErrorCountField.setAccessible(true);
            maxRecoveryErrorCountField.setInt(stream, max);
            break;
          }
        }
      }
    } catch (Exception e) {
      LOG.info("Could not set max recovery field", e);
    }
  }


  /**
   * Wait until <code>countOfRegion</code> in .META. have a non-empty
   * info:server.  This means all regions have been deployed, master has been
   * informed and updated .META. with the regions deployed server.
   * @param conf Configuration
   * @param countOfRegions How many regions in .META.
   * @throws IOException
   */
  public void waitUntilAllRegionsAssigned(final int countOfRegions)
  throws IOException {
    HTable meta = new HTable(getConfiguration(), HConstants.META_TABLE_NAME);
    HConnection connection = ServerConnectionManager.getConnection(conf);
TOP_LOOP:
    while (true) {
      int rows = 0;
      Scan scan = new Scan();
      scan.addColumn(HConstants.CATALOG_FAMILY, HConstants.SERVER_QUALIFIER);
      ResultScanner s;
      try {
        s = meta.getScanner(scan);
      } catch (RetriesExhaustedException ex) {
        // This function has infinite patience.
        Threads.sleepWithoutInterrupt(2000);
        continue;
      }
      Map<String, HRegionInfo[]> regionAssignment =
          new HashMap<String, HRegionInfo[]>();
REGION_LOOP:
      for (Result r = null; (r = s.next()) != null;) {
        byte [] b =
          r.getValue(HConstants.CATALOG_FAMILY, HConstants.SERVER_QUALIFIER);
        if (b == null || b.length <= 0) break;
        // Make sure the regionserver really has this region.
        String serverAddress = Bytes.toString(b);
        if (!regionAssignment.containsKey(serverAddress)) {
          HRegionInterface hri =
            connection.getHRegionConnection(new HServerAddress(serverAddress),
                false);
          HRegionInfo[] regions;
          try {
            regions = hri.getRegionsAssignment();
          } catch (IOException ex) {
            LOG.info("Could not contact regionserver " + serverAddress);
            Threads.sleepWithoutInterrupt(1000);
            continue TOP_LOOP;
          }
          regionAssignment.put(serverAddress, regions);
        }
        String regionName = Bytes.toString(r.getRow());
        for (HRegionInfo regInfo : regionAssignment.get(serverAddress)) {
          String regNameOnRS = Bytes.toString(regInfo.getRegionName());
          if (regNameOnRS.equals(regionName)) {
            rows++;
            continue REGION_LOOP;
          }
        }
      }
      s.close();
      // If I get to here and all rows have a Server, then all have been assigned.
      if (rows == countOfRegions)
        break;
      LOG.info("Found " + rows + " open regions, waiting for " +
          countOfRegions);
      Threads.sleepWithoutInterrupt(1000);
    }
  }

  /**
   * Do a small get/scan against one store. This is required because store
   * has no actual methods of querying itself, and relies on StoreScanner.
   */
  public static List<KeyValue> getFromStoreFile(Store store,
                                                Get get) throws IOException {
    MultiVersionConsistencyControl.resetThreadReadPoint();
    Scan scan = new Scan(get);
    InternalScanner scanner = (InternalScanner) store.getScanner(scan,
        scan.getFamilyMap().get(store.getFamily().getName()));

    List<KeyValue> result = new ArrayList<KeyValue>();
    scanner.next(result);
    if (!result.isEmpty()) {
      // verify that we are on the row we want:
      KeyValue kv = result.get(0);
      if (!Bytes.equals(kv.getRow(), get.getRow())) {
        result.clear();
      }
    }
    return result;
  }

  /**
   * Do a small get/scan against one store. This is required because store
   * has no actual methods of querying itself, and relies on StoreScanner.
   */
  public static List<KeyValue> getFromStoreFile(Store store,
                                                byte [] row,
                                                NavigableSet<byte[]> columns
                                                ) throws IOException {
    Get get = new Get(row);
    Map<byte[], NavigableSet<byte[]>> s = get.getFamilyMap();
    s.put(store.getFamily().getName(), columns);

    return getFromStoreFile(store,get);
  }

  public static void assertKVListsEqual(String additionalMsg,
      final List<KeyValue> expected,
      final List<KeyValue> actual) {
    final int eLen = expected.size();
    final int aLen = actual.size();
    final int minLen = Math.min(eLen, aLen);

    int i;
    for (i = 0; i < minLen
        && KeyValue.COMPARATOR.compare(expected.get(i), actual.get(i)) == 0;
        ++i) {}

    if (additionalMsg == null) {
      additionalMsg = "";
    }
    if (!additionalMsg.isEmpty()) {
      additionalMsg = ". " + additionalMsg;
    }

    if (eLen != aLen || i != minLen) {
      throw new AssertionError(
          "Expected and actual KV arrays differ at position " + i + ": " +
          safeGetAsStr(expected, i) + " (length " + eLen +") vs. " +
          safeGetAsStr(actual, i) + " (length " + aLen + ")" + additionalMsg);
    }
  }

  private static <T> String safeGetAsStr(List<T> lst, int i) {
    if (0 <= i && i < lst.size()) {
      return lst.get(i).toString();
    } else {
      return "<out_of_range>";
    }
  }

  /** Creates a random table with the given parameters */
  public HTable createRandomTable(String tableName,
      final Collection<String> families,
      final int maxVersions,
      final int numColsPerRow,
      final int numFlushes,
      final int numRegions,
      final int numRowsPerFlush)
      throws IOException, InterruptedException {

    LOG.info("\n\nCreating random table " + tableName + " with " + numRegions +
        " regions, " + numFlushes + " storefiles per region, " +
        numRowsPerFlush + " rows per flush, maxVersions=" +  maxVersions +
        "\n");

    final Random rand = new Random(tableName.hashCode() * 17L + 12938197137L);
    final int numCF = families.size();
    final byte[][] cfBytes = new byte[numCF][];
    final byte[] tableNameBytes = Bytes.toBytes(tableName);

    {
      int cfIndex = 0;
      for (String cf : families) {
        cfBytes[cfIndex++] = Bytes.toBytes(cf);
      }
    }

    final int actualStartKey = 0;
    final int actualEndKey = Integer.MAX_VALUE;
    final int keysPerRegion = (actualEndKey - actualStartKey) / numRegions;
    final int splitStartKey = actualStartKey + keysPerRegion;
    final int splitEndKey = actualEndKey - keysPerRegion;
    final String keyFormat = "%08x";
    final HTable table = createTable(tableNameBytes, cfBytes,
        maxVersions,
        Bytes.toBytes(String.format(keyFormat, splitStartKey)),
        Bytes.toBytes(String.format(keyFormat, splitEndKey)),
        numRegions);
    hbaseCluster.flushcache(HConstants.META_TABLE_NAME);

    for (int iFlush = 0; iFlush < numFlushes; ++iFlush) {
      for (int iRow = 0; iRow < numRowsPerFlush; ++iRow) {
        final byte[] row = Bytes.toBytes(String.format(keyFormat,
            actualStartKey + rand.nextInt(actualEndKey - actualStartKey)));

        Put put = new Put(row);
        Delete del = new Delete(row);
        for (int iCol = 0; iCol < numColsPerRow; ++iCol) {
          final byte[] cf = cfBytes[rand.nextInt(numCF)];
          final long ts = rand.nextInt();
          final byte[] qual = Bytes.toBytes("col" + iCol);
          if (rand.nextBoolean()) {
            final byte[] value = Bytes.toBytes("value_for_row_" + iRow +
                "_cf_" + Bytes.toStringBinary(cf) + "_col_" + iCol + "_ts_" +
                ts + "_random_" + rand.nextLong());
            put.add(cf, qual, ts, value);
          } else if (rand.nextDouble() < 0.8) {
            del.deleteColumn(cf, qual, ts);
          } else {
            del.deleteColumns(cf, qual, ts);
          }
        }

        if (!put.isEmpty()) {
          table.put(put);
        }

        if (!del.isEmpty()) {
          table.delete(del);
        }
      }
      LOG.info("Initiating flush #" + iFlush + " for table " + tableName);
      table.flushCommits();
      hbaseCluster.flushcache(tableNameBytes);
    }

    return table;
  }

  private static final int MIN_RANDOM_PORT = 0xc000;
  private static final int MAX_RANDOM_PORT = 0xfffe;

  /**
   * Returns a random port. These ports cannot be registered with IANA and are
   * intended for dynamic allocation (see http://bit.ly/dynports).
   */
  public static int randomPort() {
    return MIN_RANDOM_PORT
        + new Random().nextInt(MAX_RANDOM_PORT - MIN_RANDOM_PORT);
  }

  public static int randomFreePort() {
    int port = 0;
    do {
      port = randomPort();
      try {
        ServerSocket sock = new ServerSocket(port);
        sock.close();
      } catch (IOException ex) {
        port = 0;
      }
    } while (port == 0);
    return port;
  }

  public static void waitForHostPort(String host, int port)
      throws IOException {
    final int maxTimeMs = 10000;
    final int maxNumAttempts = maxTimeMs / HConstants.SOCKET_RETRY_WAIT_MS;
    IOException savedException = null;
    LOG.info("Waiting for server at " + host + ":" + port);
    for (int attempt = 0; attempt < maxNumAttempts; ++attempt) {
      try {
        Socket sock = new Socket(InetAddress.getByName(host), port);
        sock.close();
        savedException = null;
        LOG.info("Server at " + host + ":" + port + " is available");
        break;
      } catch (UnknownHostException e) {
        throw new IOException("Failed to look up " + host, e);
      } catch (IOException e) {
        savedException = e;
      }
      Threads.sleepWithoutInterrupt(HConstants.SOCKET_RETRY_WAIT_MS);
    }

    if (savedException != null) {
      throw savedException;
    }
  }

  /**
   * Creates a pre-split table for load testing. If the table already exists,
   * logs a warning and continues.
   * @return the number of regions the table was split into
   */
  public static int createPreSplitLoadTestTable(Configuration conf,
      byte[] tableName, byte[] columnFamily, Algorithm compression,
      DataBlockEncoding dataBlockEncoding) throws IOException {
    HTableDescriptor desc = new HTableDescriptor(tableName);
    HColumnDescriptor hcd = new HColumnDescriptor(columnFamily);
    hcd.setDataBlockEncoding(dataBlockEncoding);
    hcd.setCompressionType(compression);
    desc.addFamily(hcd);

    int totalNumberOfRegions = 0;
    try {
      HBaseAdmin admin = new HBaseAdmin(conf);

      // create a table a pre-splits regions.
      // The number of splits is set as:
      //    region servers * regions per region server
      int numberOfServers = admin.getClusterStatus().getServers();
      if (numberOfServers == 0) {
        throw new IllegalStateException("No live regionservers");
      }

      totalNumberOfRegions = numberOfServers * DEFAULT_REGIONS_PER_SERVER;
      LOG.info("Number of live regionservers: " + numberOfServers + ", " +
          "pre-splitting table into " + totalNumberOfRegions + " regions " +
          "(default regions per server: " + DEFAULT_REGIONS_PER_SERVER + ")");

      byte[][] splits = new RegionSplitter.HexStringSplit().split(
          totalNumberOfRegions);

      admin.createTable(desc, splits);
      admin.close();
    } catch (MasterNotRunningException e) {
      LOG.error("Master not running", e);
      throw new IOException(e);
    } catch (TableExistsException e) {
      LOG.warn("Table " + Bytes.toStringBinary(tableName) +
          " already exists, continuing");
    }
    return totalNumberOfRegions;
  }

  public static int getMetaRSPort(Configuration conf) throws IOException {
    HTable table = new HTable(conf, HConstants.META_TABLE_NAME);
    HRegionLocation hloc = table.getRegionLocation(Bytes.toBytes(""));
    table.close();
    return hloc.getServerAddress().getPort();
  }

  public HRegion createTestRegion(String tableName, HColumnDescriptor hcd)
      throws IOException {
    HTableDescriptor htd = new HTableDescriptor(tableName);
    htd.addFamily(hcd);
    HRegionInfo info =
        new HRegionInfo(htd, null, null, false);
    HRegion region =
        HRegion.createHRegion(info, getTestDir("test_region_" +
            tableName), getConfiguration());
    return region;
  }

}
