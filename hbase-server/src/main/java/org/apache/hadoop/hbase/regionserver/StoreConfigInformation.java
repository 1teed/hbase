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
package org.apache.hadoop.hbase.regionserver;

import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;

/**
 * A more restricted interface for HStore. Only gives the caller access to information
 * about store configuration/settings that cannot easily be obtained from XML config object.
 * Example user would be CompactionPolicy that doesn't need entire (H)Store, only this.
 * Add things here as needed.
 */
@InterfaceAudience.Private
@InterfaceStability.Unstable
public interface StoreConfigInformation {
  /**
   * Gets the Memstore flush size for the region that this store works with.
   * TODO: remove after HBASE-7236 is fixed.
   */
  public long getMemstoreFlushSize();

  /**
   * Gets the cf-specific time-to-live for store files.
   */
  public long getStoreFileTtl();

  /**
   * The number of files required before flushes for this store will be blocked.
   */
  public long getBlockingFileCount();
}
