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
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.lealone.hbase.metadata;

import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.zookeeper.ZKUtil;
import org.apache.hadoop.hbase.zookeeper.ZooKeeperWatcher;
import org.lealone.hbase.engine.HBaseConstants;
import org.lealone.hbase.engine.HBaseDatabase;
import org.lealone.hbase.engine.HBaseSession;
import org.lealone.hbase.util.HBaseUtils;
import org.lealone.hbase.zookeeper.DDLRedoTableTracker;
import org.lealone.hbase.zookeeper.ZooKeeperAdmin;
import org.lealone.message.DbException;

/**
 * 
 * 记录在Master端执行过的DDL语句，RegionServer在收到ZooKeeper的通知后会重做。
 *
 */
public class DDLRedoTable {
    private final static byte[] TABLE_NAME = Bytes.toBytes(MetaDataAdmin.META_DATA_PREFIX + "ddl_redo_table");
    private final static byte[] SQL = Bytes.toBytes("sql");

    private final static int MAX_DDL_REDO_RECORDS = HBaseUtils.getConfiguration().getInt(
            HBaseConstants.METADATA_MAX_DDL_REDO_RECORDS, HBaseConstants.DEFAULT_METADATA_MAX_DDL_REDO_RECORDS);

    private final HBaseDatabase database;
    private final HTable table;
    private final ZooKeeperWatcher watcher;
    private final DDLRedoTableTracker tracker;

    public DDLRedoTable(HBaseDatabase database) throws Exception {
        MetaDataAdmin.createTableIfNotExists(TABLE_NAME);
        this.database = database;
        table = new HTable(HBaseUtils.getConfiguration(), TABLE_NAME);

        watcher = new ZooKeeperWatcher(HBaseUtils.getConfiguration(), "DDLRedoTable", ZooKeeperAdmin.newAbortable());
        tracker = new DDLRedoTableTracker(watcher, this);

        //master不需要监听redo_table的变化，因为redo_table的更新只能从master发起
        if (!database.isMaster())
            tracker.start();
    }

    public DDLRedoTableTracker getDDLRedoTableTracker() {
        return tracker;
    }

    //只能由master调用
    public void addRecord(HBaseSession session, String sql) {
        try {
            int nextRedoPos = (int) org.lealone.transaction.TimestampServiceTable.nextEven();

            if (nextRedoPos > MAX_DDL_REDO_RECORDS) {
                org.lealone.transaction.TimestampServiceTable.reset();
                nextRedoPos = (int) org.lealone.transaction.TimestampServiceTable.nextEven();
            }

            Put put = new Put(Bytes.toBytes(nextRedoPos));
            put.add(MetaDataAdmin.DEFAULT_COLUMN_FAMILY, SQL, Bytes.toBytes(sql));
            table.put(put);

            ZKUtil.setData(watcher, ZooKeeperAdmin.DDL_REDO_TABLE_NODE, Bytes.toBytes(++nextRedoPos));
        } catch (Exception e) {
            throw DbException.convert(e);
        }
    }

    public void redoRecords(int startPos, int stopPos) throws Exception {
        Scan scan = new Scan();
        scan.setStartRow(Bytes.toBytes(startPos));
        scan.setStopRow(Bytes.toBytes(stopPos));
        for (Result r : table.getScanner(scan)) {
            if (r.isEmpty())
                continue;
            database.executeSQL(Bytes.toString(r.getValue(MetaDataAdmin.DEFAULT_COLUMN_FAMILY, SQL)));
        }
    }

    public void close() {
        if (table != null) {
            try {
                table.close();
            } catch (Exception e) {
                //ignore
            }
        }
        if (watcher != null) {
            try {
                watcher.close();
            } catch (Exception e) {
                //ignore
            }
        }
    }
}
