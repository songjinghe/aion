/*
 * Copyright (c) "Neo4j"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.temporalprocs;
//package org.example;
import static org.neo4j.configuration.GraphDatabaseSettings.DEFAULT_DATABASE_NAME;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.util.HashMap;
import java.util.Map;
import java.nio.file.Files;
import org.neo4j.common.DependencyResolver;
import org.neo4j.configuration.GraphDatabaseSettings;
import org.neo4j.configuration.connectors.BoltConnector;
import org.neo4j.configuration.helpers.SocketAddress;
import org.neo4j.dbms.api.DatabaseManagementService;
import org.neo4j.dbms.api.DatabaseManagementServiceBuilder;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Transaction;
import org.neo4j.harness.Neo4jBuilders;
import org.neo4j.io.fs.FileSystemAbstraction;
import org.neo4j.io.pagecache.PageCache;
import org.neo4j.kernel.api.procedure.GlobalProcedures;
import org.neo4j.kernel.internal.GraphDatabaseAPI;
import org.neo4j.temporalgraph.lineageindex.EntityLineageTracker;
import org.neo4j.temporalgraph.timeindex.SnapshotCreationPolicy;
import org.neo4j.temporalgraph.timeindex.timestore.TimeBasedTracker;
import org.neo4j.temporalgraph.HistoryTracker;
import org.neo4j.temporalprocs.LineageStoreProcedures;
import org.neo4j.temporalprocs.TimeStoreProcedures;

public class Main {

    private static final Path DB_PATH = Path.of("/database");
//    private static final Path DB_PATH = Path.of("S:/tgraph/tmpdb/aion");
    private static EntityLineageTracker lineageTracker = null;
    private static TimeBasedTracker timeBasedTracker = null;

    public static void main(String[] args) throws IOException {

        var embeddedDatabaseServer = new DatabaseManagementServiceBuilder(DB_PATH)
                .setConfig(BoltConnector.enabled, true)
                .setConfig(BoltConnector.listen_address, new SocketAddress("0.0.0.0", 7687))
                .build();
        GraphDatabaseService db = embeddedDatabaseServer.database(DEFAULT_DATABASE_NAME);
        
        registerProcedures(db);
        var lineageTracker = registerTracker(embeddedDatabaseServer, DB_PATH, 0);
        var timeBasedTracker = registerTracker(embeddedDatabaseServer, DB_PATH, 1);
        readMetaData(db, lineageTracker, timeBasedTracker);

        embeddedDatabaseServer.registerTransactionEventListener(DEFAULT_DATABASE_NAME, lineageTracker);
        embeddedDatabaseServer.registerTransactionEventListener(DEFAULT_DATABASE_NAME, timeBasedTracker);

        System.out.println("server started on port 7687");
        try{
            while (true) {
                long lastTxIdOfTimeStore = timeBasedTracker.getLastTransactionId();
                long lastTimeOfTimeStore = timeBasedTracker.getLastCommittedTime();
                long lastTxIdOfLineageStore = lineageTracker.getLastTransactionId();
                long lastTimeOfLineageStore = lineageTracker.getLastCommittedTime();
                System.out.printf("TimeStore: lastTxId %s, lastTime %s; LineageStore: lastTxId %s, lastTime %s.%n",
                        lastTxIdOfTimeStore, lastTimeOfTimeStore, lastTxIdOfLineageStore, lastTimeOfLineageStore);
                saveMetaData(db);
                Thread.sleep(120_000);
            }
        } catch (InterruptedException e){
            System.out.println("DB Server interrupted, exiting...");
        } finally {
            saveMetaData(db);
            closeTrackers(embeddedDatabaseServer);
            embeddedDatabaseServer.shutdown();
            System.out.println("DB Server closed. process exit.");
        }
    }

    private static final Label TEST_META = Label.label("TEST_META");

    private static void readMetaData(GraphDatabaseService db, HistoryTracker a, HistoryTracker b){
        Map<String, Integer> str2id = new HashMap<>();
        try (Transaction tx = db.beginTx()) {
            Node n = tx.findNode(TEST_META, "TEST_META", "TEST_META");
            if(n==null){
                System.out.println("TEST_META node not found, creating...");
                n = tx.createNode(TEST_META);
                n.setProperty("TEST_META", "TEST_META");
                tx.commit();
            }else{
                System.out.println("TEST_META node found, initial...");
                for(String key : n.getPropertyKeys()){
                    if("TEST_META".equals(key)) continue;
                    int id = (int) n.getProperty(key);
                    str2id.put(key, id);
                }
                System.out.println(str2id);
                a.init(str2id);
                b.init(str2id);
            }
        }
    }
    private static void saveMetaData(GraphDatabaseService db) throws IOException {
        Map<String, Integer> str2id = new HashMap<>();
        try (Transaction tx = db.beginTx()) {
            Node n = tx.findNode(TEST_META, "TEST_META", "TEST_META");
            if(n==null){
                System.out.println("TEST_META node not found, creating...");
                throw new RuntimeException("TEST_META node not found");
            }
            System.out.println("TEST_META node checking...");
            str2id.putAll(timeBasedTracker.getNamesToIds());
            str2id.putAll(lineageTracker.getNamesToIds());
            System.out.println(str2id);
            str2id.forEach((k,v)->{
                Integer id = (Integer) n.getProperty(k, null);
                if(id==null || !id.equals(v)){
                    n.setProperty(k, v);
                    System.out.println("update TEST_META key("+k+") "+id+" -> "+ v);
                }
            });
            tx.commit();
        }
        timeBasedTracker.flush();
        lineageTracker.flush();
    }

    private static void registerProcedures(GraphDatabaseService db) {
        try {
            DependencyResolver resolver = ((GraphDatabaseAPI) db).getDependencyResolver();
            GlobalProcedures procedures = resolver.resolveDependency(GlobalProcedures.class);
            procedures.registerProcedure(LineageStoreProcedures.class);
            procedures.registerProcedure(TimeStoreProcedures.class);
//            procedures.registerFunction(LineageStoreProcedures.class);
//            procedures.registerFunction(TimeStoreProcedures.class);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    private static HistoryTracker registerTracker(DatabaseManagementService dbms, Path dbPath, int type) throws IOException {
        var pageCache = (PageCache) dbms.database(DEFAULT_DATABASE_NAME).getPageCache();
        var fs = (FileSystemAbstraction) dbms.database(DEFAULT_DATABASE_NAME).getFileSystem();

        createDirIfNotExist(dbPath, "aion-data/lineage", "aion-data/time" );
        if (type == 0) {
            var nodeIndexPath = dbPath.toAbsolutePath().resolve("aion-data/lineage/NODE_STORE_INDEX");
            var relIndexPath = dbPath.toAbsolutePath().resolve("aion-data/lineage/REL_STORE_INDEX");
            lineageTracker = new EntityLineageTracker(pageCache, fs, nodeIndexPath, relIndexPath);
            return lineageTracker;
        } else if (type == 1) {
            var policy = new SnapshotCreationPolicy(10_000);
            var nodeIndexPath = dbPath.toAbsolutePath().resolve("aion-data/time/DATA_LOG");
            var relIndexPath = dbPath.toAbsolutePath().resolve("aion-data/time/TIME_INDEX");
            timeBasedTracker = new TimeBasedTracker(policy, pageCache, fs, nodeIndexPath, relIndexPath);
            return timeBasedTracker;
        } else {
            throw new IllegalArgumentException(String.format("Type %d is not supported", type));
        }
    }

    private static void createDirIfNotExist(Path root, String ... relativePathList) throws IOException {
        for(String path : relativePathList){
            Path abs = root.toAbsolutePath().resolve(path);
            if(!Files.exists(abs)){
                Files.createDirectories(abs);
            }
        }
    }

    private static void closeTrackers(DatabaseManagementService dbms) throws IOException {
        dbms.unregisterTransactionEventListener(DEFAULT_DATABASE_NAME, lineageTracker);
        dbms.unregisterTransactionEventListener(DEFAULT_DATABASE_NAME, timeBasedTracker);
        if (lineageTracker != null) {
            lineageTracker.shutdown();
        }
        if (timeBasedTracker != null) {
            timeBasedTracker.shutdown();
        }
    }
}
