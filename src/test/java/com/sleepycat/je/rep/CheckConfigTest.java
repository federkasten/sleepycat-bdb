/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002, 2014 Oracle and/or its affiliates.  All rights reserved.
 *
 */

package com.sleepycat.je.rep;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;

import org.junit.Before;
import org.junit.Test;

import com.sleepycat.je.Database;
import com.sleepycat.je.DatabaseConfig;
import com.sleepycat.je.DatabaseExistsException;
import com.sleepycat.je.EnvironmentConfig;
import com.sleepycat.je.rep.utilint.RepTestUtils;
import com.sleepycat.je.rep.utilint.RepTestUtils.RepEnvInfo;
import com.sleepycat.util.test.SharedTestUtils;
import com.sleepycat.util.test.TestBase;

/**
 * Check that both master and replica nodes catch invalid environment and
 * database configurations.
 */
public class CheckConfigTest extends TestBase {

    private File envRoot;
    private File[] envHomes;

    @Before
    public void setUp()
        throws Exception {

        envRoot = SharedTestUtils.getTestDir();
        envHomes = RepTestUtils.makeRepEnvDirs(envRoot, 2);
        super.setUp();
    }

    /**
     * Replicated environments do not support non transactional mode.
     */
    @Test
    public void testEnvNonTransactionalConfig()
        throws Exception {

        EnvironmentConfig config = createConfig();
        config.setTransactional(false);
        expectRejection(config);
    }

    /**
     * A configuration of transactional + noLocking is invalid.
     */
    @Test
    public void testEnvNoLockingConfig()
        throws Exception {

        EnvironmentConfig config = createConfig();
        config.setLocking(false);
        expectRejection(config);
    }

    /**
     * ReadOnly = true should be accepted.
     *
     * Since setting environment read only is only possible when an Environment
     * exists, this test first creates a normal Environment and then reopens it
     * with read only configuration.
     */
    @Test
    public void testEnvReadOnlyConfig()
        throws Exception {

        EnvironmentConfig config = createConfig();
        expectAcceptance(config);
        config.setReadOnly(true);
        expectRejection(config);
    }

    /**
     * AllowCreate = false should be accepted.
     *
     * Since setting environment allowCreate to false is only possible when an
     * Environment exists, this test creates a normal Environment and then
     * reopens it with allowCreate=false configuration.
     */
    @Test
    public void testEnvAllowCreateFalseConfig()
        throws Exception {

        EnvironmentConfig config = createConfig();
        expectAcceptance(config);
        config.setAllowCreate(false);
        expectAcceptance(config);
    }

    /**
     * SharedCache = true should be accepted.
     */
    @Test
    public void testEnvSharedCacheConfig()
        throws Exception {

        EnvironmentConfig config = createConfig();
        config.setSharedCache(true);
        expectAcceptance(config);
    }

    /**
     * Serializable isolation = true should be accepted.
     */
    @Test
    public void testEnvSerializableConfig()
        throws Exception {

        EnvironmentConfig config = createConfig();
        config.setTxnSerializableIsolation(true);
        expectAcceptance(config);
    }

    /**
     * Return a new transactional EnvironmentConfig for test use.
     */
    private EnvironmentConfig createConfig() {
        EnvironmentConfig config = new EnvironmentConfig();
        config.setAllowCreate(true);
        config.setTransactional(true);

        return config;
    }

    /**
     * Return a new transactional DatabaseConfig for test use.
     */
    private DatabaseConfig createDbConfig() {
        DatabaseConfig config = new DatabaseConfig();
        config.setAllowCreate(true);
        config.setTransactional(true);

        return config;
    }

    /**
     * Wrap checkEnvConfig in this method to make the intent of the test
     * obvious.
     */
    private void expectAcceptance(EnvironmentConfig envConfig)
        throws Exception {

        checkEnvConfig(envConfig, false /* isInvalid */);
    }

    /**
     * Wrap checkEnvConfig in this method to make the intent of the test
     * obvious.
     */
    private void expectRejection(EnvironmentConfig envConfig)
            throws Exception {

        checkEnvConfig(envConfig, true /* isInvalid */);
    }

    /**
     * Check whether an EnvironmentConfig is valid.
     *
     * @param envConfig The EnvironmentConfig we'd like to check.
     * @param isInvalid if true, envConfig represents an invalid configuration
     * and we expect ReplicatedEnvironment creation to fail.
     */
    private void checkEnvConfig(EnvironmentConfig envConfig,
                                boolean isInvalid)
        throws Exception {

        /*
         * masterFail and replicaFail are true if the master or replica
         * environment creation failed.
         */
        boolean masterFail = false;
        boolean replicaFail = false;

        ReplicatedEnvironment master = null;
        ReplicatedEnvironment replica = null;

        /* Create the ReplicationConfig for master and replica. */
        ReplicationConfig masterConfig = RepTestUtils.createRepConfig(1);
        masterConfig.setDesignatedPrimary(true);

        masterConfig.setHelperHosts(masterConfig.getNodeHostPort());
        ReplicationConfig replicaConfig = RepTestUtils.createRepConfig(2);
        replicaConfig.setHelperHosts(masterConfig.getNodeHostPort());

        /*
         * Attempt to create the master with the specified EnvironmentConfig.
         */
        try {
            master = new ReplicatedEnvironment(envHomes[0],
                                               masterConfig,
                                               envConfig);
        } catch (IllegalArgumentException e) {
            masterFail = true;
        }

        /*
         * If the specified EnvironmentConfig is expected to fail, the above
         * master creation fails, so when the test tries to create a replica
         * in the following steps, it actually tries to create a master.
         *
         * Since the test needs to test on both master and replica, so create
         * a real master here.
         */
        if (isInvalid) {
            EnvironmentConfig okConfig =
                RepTestUtils.createEnvConfig(RepTestUtils.DEFAULT_DURABILITY);
            master = new ReplicatedEnvironment(envHomes[0], masterConfig,
                                               okConfig);
        }

        /* Check the specified EnvironmentConfig on the replica. */
        try {
            replica = new ReplicatedEnvironment(envHomes[1],
                                                replicaConfig,
                                                envConfig);
        } catch (IllegalArgumentException e) {
            replicaFail = true;
        }

        /* Check whether the master and replica creations are as expected. */
        if (isInvalid) {
            assertTrue(replicaFail && masterFail);
        } else {
            assertFalse(replicaFail || masterFail);
        }

        /*
         * If the specified EnvironmentConfig is expected to fail, close
         * the master and return.
         */
        if (isInvalid) {
            if (master != null) {
                assertTrue(master.getState().isMaster());
                master.close();
            }

            return;
        }

        if (master != null && replica != null) {
            /*
             * If the specified EnvironmentConfig is correct, wait for
             * replication initialization to finish.
             */
            while (replica.getState() != ReplicatedEnvironment.State.REPLICA) {
                Thread.sleep(1000);
            }

            /* Make sure the test runs on both master and replica. */
            assertTrue(master.getState().isMaster());
            assertTrue(!replica.getState().isMaster());

            /* Close the replica and master. */
            replica.close();
            master.close();
        }
    }

    /**
     * AllowCreate = false should be accepted.
     *
     * Setting allowCreate to false is only possible when the database already
     * exists. Because of that, this test first creates a database and then
     * reopens it with allowCreate = false configuration.
     */
    @Test
    public void testDbAllowCreateFalseConfig()
        throws Exception {

        DatabaseConfig dbConfig = createDbConfig();
        expectDbAcceptance(dbConfig, true);
        dbConfig.setAllowCreate(false);
        expectDbAcceptance(dbConfig, false);
    }

    /**
     * Replicated datatabases do not support non transactional mode.
     */
    @Test
    public void testDbNonTransactionalConfig()
        throws Exception {

        DatabaseConfig dbConfig = createDbConfig();
        dbConfig.setTransactional(false);
        expectDbRejection(dbConfig, false);
    }

    /**
     * A database configuration of transactional + deferredWrite is invalid.
     */
    @Test
    public void testDbDeferredWriteConfig()
        throws Exception {

        DatabaseConfig dbConfig = createDbConfig();
        dbConfig.setDeferredWrite(true);
        expectDbRejection(dbConfig, false);
    }

    /**
     * A database configuration of transactional + temporary is invalid.
     */
    @Test
    public void testDbTemporaryConfig()
        throws Exception {

        DatabaseConfig dbConfig = createDbConfig();
        dbConfig.setTemporary(true);
        expectDbRejection(dbConfig, false);
    }

    /**
     * ExclusiveCreate = true should be accepted on the master.
     *
     * Setting exclusiveCreate is expected to fail on the replica. It's because
     * when a database is created on master, replication will create the same
     * database on the replica. When the replica tries to create the database,
     * it will find the database already exists. When we set exclusiveCreate =
     * true, the replica will throw out a DatabaseExistException. The check
     * for this is done within the logic for expectDbAcceptance.
     */
    @Test
    public void testDbExclusiveCreateConfig()
        throws Exception {

        DatabaseConfig dbConfig = createDbConfig();
        dbConfig.setExclusiveCreate(true);
        expectDbAcceptance(dbConfig, true);
    }

    /**
     * KeyPrefixing = true should be accpted.
     */
    @Test
    public void testDbKeyPrefixingConfig()
        throws Exception {

        DatabaseConfig dbConfig = createDbConfig();
        dbConfig.setKeyPrefixing(true);
        expectDbAcceptance(dbConfig, false);
    }

    /**
     * ReadOnly = true should be accpted.
     *
     * Database read only is only possible when the database exists, so this
     * test first creates a database and then reopens it with read only
     * configuration.
     */
    @Test
    public void testDbReadOnlyConfig()
        throws Exception {

        DatabaseConfig dbConfig = createDbConfig();
        expectDbAcceptance(dbConfig, true);
        dbConfig.setReadOnly(true);
        expectDbAcceptance(dbConfig, false);
    }

    /**
     * SortedDuplicates = true should be accpted.
     */
    @Test
    public void testDbSortedDuplicatesConfig()
        throws Exception {

        DatabaseConfig dbConfig = createDbConfig();
        dbConfig.setSortedDuplicates(true);
        expectDbAcceptance(dbConfig, false);
    }

    /**
     * OverrideBtreeComparator = true should be accepted.
     */
    @Test
    public void testDbOverideBtreeComparatorConfig()
        throws Exception {

        DatabaseConfig dbConfig = createDbConfig();
        dbConfig.setOverrideBtreeComparator(true);
        expectDbAcceptance(dbConfig, false);
    }

    /**
     * OverrideDuplicatComparator = true should be accepted.
     */
    @Test
    public void testDbOverrideDuplicateComparatorConfig()
        throws Exception {

        DatabaseConfig dbConfig = createDbConfig();
        dbConfig.setOverrideDuplicateComparator(true);
        expectDbAcceptance(dbConfig, false);
    }

    /**
     * UseExistingConfig = true should be accepted.
     *
     * UseExistingConfig is only possible when the database exists, so this
     * test first creates a database and then reopens it with UseExistingConfig
     * configuration.
     */
    @Test
    public void testDbUseExistingConfig()
        throws Exception {

        DatabaseConfig dbConfig = createDbConfig();
        expectDbAcceptance(dbConfig, true);
        dbConfig = new DatabaseConfig();
        dbConfig.setTransactional(true);
        dbConfig.setUseExistingConfig(true);
        dbConfig.setReadOnly(true);
        expectDbAcceptance(dbConfig, false);
    }

    /**
     * Wrap checkDbConfig in this method to make the intent of the test
     * obvious.
     */
    private void expectDbAcceptance(DatabaseConfig dbConfig, boolean doSync)
        throws Exception {

        checkDbConfig(dbConfig, false /* isInvalid */, doSync);
    }

    /**
     * Wrap checkEnvConfig in this method to make the intent of the test
     * obvious.
     */
    private void expectDbRejection(DatabaseConfig dbConfig, boolean doSync)
            throws Exception {

        checkDbConfig(dbConfig, true /* isInvalid */, doSync);
    }

    /**
     * The main function checks whether a database configuration is valid.
     *
     * @param dbConfig The DatabaseConfig to check.
     * @param isInvalid if true, dbConfig represents an invalid configuration
     * and we expect database creation to fail.
     * @param doSync If true, the test should do a group sync after creating
     * the database on the master
     */
    public void checkDbConfig(DatabaseConfig dbConfig,
                              boolean isInvalid,
                              boolean doSync)
        throws Exception {

        /*
         * masterFail and replicaFail are true if the master or replica
         * database creation failed.
         */
        boolean masterFail = false;
        boolean replicaFail =false;

        /* Create an array of replicators successfully and join the group. */
        RepEnvInfo[] repEnvInfo = RepTestUtils.setupEnvInfos(envRoot, 2);
        repEnvInfo[0].getRepConfig().setDesignatedPrimary(true);
        RepTestUtils.joinGroup(repEnvInfo);

        /* Create the database with the specified configuration on master. */
        Database masterDb = null;
        try {
            masterDb = repEnvInfo[0].getEnv().openDatabase(null, "test",
                                                           dbConfig);
        } catch (IllegalArgumentException e) {
            masterFail = true;
        }

        /*
         * The test does a group sync when the tested configuration needs to
         * create a real database first.
         *
         * If a group sync isn't done, the replica would incorrectly try to
         * create the database since it hasn't seen it yet. Since write
         * operations on the replica are forbidden, the test would fail, which
         * is not expected.
         */
        if (doSync) {
            RepTestUtils.syncGroupToLastCommit(repEnvInfo, repEnvInfo.length);
        }

        /* Open the database with the specified configuration on replica. */
        Database replicaDb = null;
        try {
            replicaDb = repEnvInfo[1].getEnv().openDatabase(null, "test",
                                                            dbConfig);
        } catch (IllegalArgumentException e) {
            replicaFail = true;
        } catch (ReplicaWriteException e) {
            /*
             * If the test throws a ReplicaStateException, it's because it
             * tries to create a new database on replica, but replica doesn't
             * allow create operation, it's thought to be valid.
             */
        } catch (DatabaseExistsException e) {
            replicaFail = true;
        }

        /* Check the validity here. */
        if (isInvalid) {
            assertTrue(masterFail && replicaFail);
        } else {

            /*
             * The exclusiveCreate config is checked explicitly here, because
             * it has different master/replica behavior.
             */
            if (dbConfig.getExclusiveCreate()) {
                assertFalse(masterFail);
                assertTrue(replicaFail);
            } else {
                assertFalse(masterFail || replicaFail);
            }
        }

        /* Shutdown the databases and environments. */
        if (masterDb != null) {
            masterDb.close();
        }

        if (replicaDb != null) {
            replicaDb.close();
        }

        RepTestUtils.shutdownRepEnvs(repEnvInfo);
    }
}
