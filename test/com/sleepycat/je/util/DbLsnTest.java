/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002, 2014 Oracle and/or its affiliates.  All rights reserved.
 *
 */

package com.sleepycat.je.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;

import org.junit.Test;

import com.sleepycat.je.DbInternal;
import com.sleepycat.je.Environment;
import com.sleepycat.je.EnvironmentConfig;
import com.sleepycat.je.EnvironmentFailureException;
import com.sleepycat.je.log.FileManager;
import com.sleepycat.je.utilint.DbLsn;
import com.sleepycat.util.test.SharedTestUtils;
import com.sleepycat.util.test.TestBase;

public class DbLsnTest extends TestBase {
    long[] values = { 0xFF, 0xFFFF, 0xFFFFFF, 0x7FFFFFFF, 0xFFFFFFFFL };

    @Test
    public void testDbLsn() {
        for (int i = 0; i < values.length; i++) {
            long value = values[i];
            long lsn = DbLsn.makeLsn(value, value);
            assertTrue((DbLsn.getFileNumber(lsn) == value) &&
                       (DbLsn.getFileOffset(lsn) == value));
        }
    }

    @Test
    public void testComparableEquality() {
        /* Test equality */

        /* Don't bother with last values[] entry -- it makes NULL_LSN. */
        int lastValue = values.length - 1;
        for (int i = 0; i < lastValue; i++) {
            long value = values[i];
            long lsn1 = DbLsn.makeLsn(value, value);
            long lsn2 = DbLsn.makeLsn(value, value);
            assertTrue(DbLsn.compareTo(lsn1, lsn2) == 0);
        }

        /* Check NULL_LSN. */
        assertTrue(DbLsn.makeLsn(values[lastValue],
                                 values[lastValue]) ==
                   DbLsn.makeLsn(values[lastValue],
                                 values[lastValue]));
    }

    @Test
    public void testComparableException() {
        /* Check that compareTo throws EnvironmentFailureException */

        try {
            long lsn1 = DbLsn.makeLsn(0, 0);
            DbLsn.compareTo(lsn1, DbLsn.NULL_LSN);
            fail("compareTo(null) didn't throw EnvironmentFailureException");
        } catch (EnvironmentFailureException expected) {
        }

        try {
            long lsn1 = DbLsn.makeLsn(0, 0);
            DbLsn.compareTo(DbLsn.NULL_LSN, lsn1);
            fail("compareTo(null) didn't throw EnvironmentFailureException");
        } catch (EnvironmentFailureException expected) {
        }
    }

    @Test
    public void testComparableInequalityFileNumber() {
        /* Check for inequality in the file number */

        /* Don't bother with last values[] entry -- it makes NULL_LSN. */
        int lastValue = values.length - 1;
        for (int i = 0; i < lastValue; i++) {
            long value = values[i];
            long lsn1 = DbLsn.makeLsn(value, value);
            long lsn2 = DbLsn.makeLsn(0, value);
            assertTrue(DbLsn.compareTo(lsn1, lsn2) == 1);
            assertTrue(DbLsn.compareTo(lsn2, lsn1) == -1);
        }

        /* Check against NULL_LSN. */
        long lsn1 = DbLsn.makeLsn(values[lastValue], values[lastValue]);
        long lsn2 = DbLsn.makeLsn(0, values[lastValue]);
        try {
            assertTrue(DbLsn.compareTo(lsn1, lsn2) == 1);
        } catch (EnvironmentFailureException expected) {
        }

        try {
            assertTrue(DbLsn.compareTo(lsn2, lsn1) == 1);
        } catch (EnvironmentFailureException expected) {
        }
    }

    @Test
    public void testComparableInequalityFileOffset() {
        /* Check for inequality in the file offset */

        for (int i = 0; i < values.length - 1; i++) {
            long value = values[i];
            long lsn1 = DbLsn.makeLsn(value, value);
            long lsn2 = DbLsn.makeLsn(value, 0);
            /* Can't compareTo(NULL_LSN). */
            if (lsn1 != DbLsn.NULL_LSN &&
                lsn2 != DbLsn.NULL_LSN) {
                assertTrue(DbLsn.compareTo(lsn1, lsn2) == 1);
                assertTrue(DbLsn.compareTo(lsn2, lsn1) == -1);
            }
        }
    }

    @Test
    public void testSubtractNoCleaning() {
        long a = DbLsn.makeLsn(1, 10);
        long b = DbLsn.makeLsn(3, 40);
        assertEquals(230, DbLsn.getNoCleaningDistance(b, a, 100));
        assertEquals(230, DbLsn.getNoCleaningDistance(a, b, 100));

        long c = DbLsn.makeLsn(1, 50);
        assertEquals(40, DbLsn.getNoCleaningDistance(a, c, 100));
        assertEquals(40, DbLsn.getNoCleaningDistance(c, a, 100));
    }

    @Test
    public void testSubtractWithCleaning()
        throws Exception {

        /* Try with non-consecutive files (due to cleaning). */

        File envHome = SharedTestUtils.getTestDir();
        TestUtils.removeLogFiles("TestSubtract", envHome, false);
        EnvironmentConfig envConfig = TestUtils.initEnvConfig();
        envConfig.setAllowCreate(true);
        Environment env = new Environment(envHome, envConfig);

        try {
            File file1 = new File (envHome, "00000001.jdb");
            File file2 = new File (envHome, "00000003.jdb");
            file1.createNewFile();
            file2.createNewFile();
            long a = DbLsn.makeLsn(1, 10);
            long b = DbLsn.makeLsn(3, 40);
            FileManager fileManager =
                DbInternal.getEnvironmentImpl(env).getFileManager();
            assertEquals(130, DbLsn.getWithCleaningDistance
                         (b, fileManager, a, 100));
            assertEquals(130, DbLsn.getWithCleaningDistance
                         (a, fileManager, b, 100));

            long c = DbLsn.makeLsn(1, 50);
            assertEquals(40, DbLsn.getWithCleaningDistance
                         (a, fileManager, c, 100));
            assertEquals(40, DbLsn.getWithCleaningDistance
                         (c, fileManager, a, 100));
        } finally {
            env.close();
        }
    }
}
