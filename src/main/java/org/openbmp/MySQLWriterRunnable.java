package org.openbmp;
/*
 * Copyright (c) 2015-2016 Cisco Systems, Inc. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 *
 */
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * MySQL writer thread class
 *
 * Inserts messages in bulk and batch (multi-statement) into MySQL by reading
 *      the FIFO queue.
 */
public class MySQLWriterRunnable implements  Runnable {
    private static final Logger logger = LogManager.getFormatterLogger(MySQLWriterRunnable.class.getName());

    private Connection con;                                     // MySQL connection
    private Boolean dbConnected;                                // Indicates if DB is connected or not
    private Config cfg;
    private BlockingQueue<Map<String, String>> writerQueue;     // Reference to the writer FIFO queue
    private boolean run;

    private final Object lock = new Object();                   // Lock for thread

    /**
     * Constructor
     *
     * @param cfg       Configuration - e.g. DB credentials
     * @param queue     FIFO queue to read from
     */
    public MySQLWriterRunnable(Config cfg, BlockingQueue queue) {

        this.cfg = cfg;
        writerQueue = queue;
        run = true;

        con = null;

        connectMySQL();
    }

    private boolean connectMySQL() {
        synchronized (this.lock) {
            dbConnected = false;
        }

        if (con != null) {
            try {
                con.close();
            } catch (SQLException e) {
                e.printStackTrace();
            }
            con = null;
        }


        logger.info("Writer connecting to MySQL");
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e1) {
            // ignore
        }

        /*
         * Establish connection to MySQL
         */
        try {
            con = DriverManager.getConnection(
                    "jdbc:mariadb://" + cfg.getDbHost() + "/" + cfg.getDbName() +
                            "?tcpKeepAlive=true&connectTimeout=30000&socketTimeout=350000&useCompression=true" +
                            "&autoReconnect=true&allowMultiQueries=true&useBatchMultiSend=false" +
                            "&enableQueryTimeouts=false",
                    cfg.getDbUser(), cfg.getDbPw());

            con.setAutoCommit(true);

            logger.info("Writer connected to MySQL");

            synchronized (this.lock) {
                dbConnected = true;
            }

        } catch (SQLException e) {
            e.printStackTrace();
            logger.warn("Writer thread failed to connect to mysql", e);
        }

        return dbConnected;
    }

    /**
     * Shutdown this thread
     */
    public synchronized void shutdown() {
        run = false;

        try {
            con.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    /**
     * Run MySQL update query
     *
     * @param query         Query string to run
     * @param retries       Number of times to retry, zero means no retries
     */
    private void mysqlQueryUpdate(String query, int retries) {
        Boolean success = Boolean.FALSE;

        // Loop the request if broken pipe, connection timed out, or deadlock
         for (int i = 0; i < retries; i++) {
            try {
                Statement stmt = con.createStatement();
                logger.trace("SQL Query retry = %d: %s", i, query);

                stmt.executeUpdate(query);

                i = retries;
                success = Boolean.TRUE;
                break;

            } catch (SQLException e) {
                if (!e.getSQLState().equals("40001") && i >= (retries - 1)) {
                    logger.info("SQL exception state " + i + " : " + e.getSQLState());
                    logger.info("SQL exception: " + e.getMessage());
                }

                if (e.getMessage().contains("Connection refused") ||
                        e.getMessage().contains("Broken pipe") ||
                        e.getMessage().contains("Connection timed out")) {
                    logger.error("Not connected to mysql: " + e.getMessage());

                    while (!connectMySQL()) {
                        try {
                            Thread.sleep(4000);
                        } catch (InterruptedException e1) {
                            // ignore
                        }
                    }
                } else if (e.getMessage().contains("Deadlock found when trying") ) {
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException e2) {
                        // ignore
                    }
                }
            }
        }

        if (!success) {
            logger.warn("Failed to insert/update after %d max retires", retries);
            logger.debug("query: " + query);
        }
    }

    /**
     * Run the thread
     */
    public void run() {
        if (!dbConnected) {
            logger.debug("Will not run writer thread since DB isn't connected");
            return;
        }
        logger.debug("writer thread started");

        long cur_time = 0;
        long prev_time = System.currentTimeMillis();

        int bulk_count = 0;

        /*
         * bulk query map has a key of : <prefix|suffix>
         *      Prefix and suffix are from the query FIFO message.  Value is the VALUE to be inserted/updated/deleted
         */
        Map<String, String> bulk_query = new LinkedHashMap<String, String>();

        try {
            while (run) {
                cur_time = System.currentTimeMillis();

                /*
                 * Do insert/query if max wait/duration has been reached or if max statements have been reached.
                 */
                if (cur_time - prev_time > cfg.getDb_batch_time_millis() ||
                        bulk_count >= cfg.getDb_batch_records()) {

                    if (bulk_count > 0) {
                        logger.trace("Max reached, doing insert: wait_ms=%d bulk_count=%d",
                                    cur_time - prev_time, bulk_count);

                        StringBuilder query = new StringBuilder();
                        // Loop through queries and add them as multi-statements
                        for (Map.Entry<String, String> entry : bulk_query.entrySet()) {
                            String key = entry.getKey().toString();

                            String value = entry.getValue();

                            String[] ins = key.split("[|]");

                            if (query.length() > 0)
                                query.append(';');

                            query.append(ins[0]);
                            query.append(' ');
                            query.append(value);
                            query.append(' ');

                            if (ins.length > 1 && ins[1] != null && ins[1].length() > 0)
                                query.append(ins[1]);
                        }

                        if (query.length() > 0) {
                            mysqlQueryUpdate(query.toString(), cfg.getDb_retries());
                        }

                        prev_time = System.currentTimeMillis();

                        bulk_count = 0;
                        bulk_query.clear();
                    }
                    else {
                        prev_time = System.currentTimeMillis();
                    }
                }

                // Get next query from queue
                Map<String, String> cur_query = writerQueue.poll(cfg.getDb_batch_time_millis(), TimeUnit.MILLISECONDS);

                if (cur_query != null) {
                    if (cur_query.containsKey("prefix")) {
                        String key = cur_query.get("prefix") + "|" + cur_query.get("suffix");
                        ++bulk_count;

                        // merge the data to existing bulk map if already present
                        if (bulk_query.containsKey(key)) {
                            bulk_query.put(key, bulk_query.get(key).concat("," + cur_query.get("value")));
                        } else {
                            bulk_query.put(key, cur_query.get("value"));
                        }

                        if (cur_query.get("value").length() > 200000) {
                            bulk_count = cfg.getDb_batch_records();
                            logger.debug("value length is: %d", cur_query.get("value").length());
                        }
                    }
                    else if (cur_query.containsKey("query")) {  // Null prefix means run query now, not in bulk
                        logger.debug("Non bulk query");

                        mysqlQueryUpdate(cur_query.get("query"), 3);
                    }
                }
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        } catch (Exception e) {
            logger.error("Exception: ", e);
        }

        logger.info("Writer thread done");
    }

    /**
     * Indicates if the DB is connected or not.
     *
     * @return True if DB is connected, False otherwise
     */
    public boolean isDbConnected() {
        boolean status;

        synchronized (lock) {
            status = dbConnected;
        }

        return status;
    }
}
