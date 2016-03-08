/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002, 2014 Oracle and/or its affiliates.  All rights reserved.
 *
 */

package com.sleepycat.je.util;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Random;

import junit.framework.TestCase;

import com.sleepycat.je.CacheMode;
import com.sleepycat.je.Cursor;
import com.sleepycat.je.Database;
import com.sleepycat.je.DatabaseException;
import com.sleepycat.je.DbInternal;
import com.sleepycat.je.DbTestProxy;
import com.sleepycat.je.Environment;
import com.sleepycat.je.EnvironmentConfig;
import com.sleepycat.je.ExceptionEvent;
import com.sleepycat.je.ExceptionListener;
import com.sleepycat.je.StatsConfig;
import com.sleepycat.je.dbi.CursorImpl;
import com.sleepycat.je.dbi.EnvironmentImpl;
import com.sleepycat.je.latch.LatchSupport;
import com.sleepycat.je.log.FileManager;
import com.sleepycat.je.tree.BIN;
import com.sleepycat.je.tree.ChildReference;
import com.sleepycat.je.tree.IN;
import com.sleepycat.je.tree.LN;
import com.sleepycat.je.tree.SearchResult;
import com.sleepycat.je.tree.Tree;
import com.sleepycat.je.tree.WithRootLatched;
import com.sleepycat.util.test.SharedTestUtils;
import com.sleepycat.utilint.StringUtils;

public class TestUtils {
    public static String DEST_DIR = SharedTestUtils.DEST_DIR;
    public static String NO_SYNC = SharedTestUtils.NO_SYNC;

    public static final String LOG_FILE_NAME = "00000000.jdb";

    public static final StatsConfig FAST_STATS;

    static {
        FAST_STATS = new StatsConfig();
        FAST_STATS.setFast(true);
    }

    private static final boolean DEBUG = true;
    private static Random rnd = new Random();

    public void debugMsg(String message) {

        if (DEBUG) {
            System.out.println
                (Thread.currentThread().toString() + " " + message);
        }
    }

    static public void setRandomSeed(int seed) {

        rnd = new Random(seed);
    }

    static public void generateRandomAlphaBytes(byte[] bytes) {

        byte[] aAndZ = StringUtils.toUTF8("AZ");
        int range = aAndZ[1] - aAndZ[0] + 1;

        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (rnd.nextInt(range) + aAndZ[0]);
        }
    }

    static public void checkLatchCount() {
        TestCase.assertTrue(LatchSupport.countLatchesHeld() == 0);
    }

    static public void printLatchCount(String msg) {
        System.out.println(msg + " : " + LatchSupport.countLatchesHeld());
    }

    static public void printLatches(String msg) {
        System.out.println(msg + " : ");
        LatchSupport.dumpLatchesHeld();
    }

    /**
     * Generate a synthetic base 26 four byte alpha key from an int.
     * The bytes of the key are between 'A' and 'Z', inclusive.  0 maps
     * to 'AAAA', 1 to 'AAAB', etc.
     */
    static public int alphaKey(int i) {

        int ret = 0;
        for (int j = 0; j < 4; j++) {
            byte b = (byte) (i % 26);
            ret <<= 8;
            ret |= (b + 65);
            i /= 26;
        }

        return ret;
    }

    /**
     * Marshall an unsigned int (long) into a four byte buffer.
     */
    static public void putUnsignedInt(byte[] buf, long value) {

        int i = 0;
        buf[i++] = (byte) (value >>> 0);
        buf[i++] = (byte) (value >>> 8);
        buf[i++] = (byte) (value >>> 16);
        buf[i] =   (byte) (value >>> 24);
    }

    /**
     * All flavors of removeLogFiles should check if the remove has been
     * disabled. (Used for debugging, so that the tester can dump the
     * log file.
     */
    private static boolean removeDisabled() {

        String doRemove = System.getProperty("removeLogFiles");
        return ((doRemove != null) && doRemove.equalsIgnoreCase("false"));
    }

    /**
     * Remove je log files from the home directory. Will be disabled
     * if the unit test is run with -DremoveLogFiles=false
     * @param msg prefix to append to error messages
     * @param envFile environment directory
     */
    public static void removeLogFiles(String msg,
                                      File envFile,
                                      boolean checkRemove) {
        removeFiles(msg, envFile, FileManager.JE_SUFFIX, checkRemove);
        removeSubDirs(envFile);
    }

    /**
     * Remove files with this suffix from the je home directory
     * @param msg prefix to append to error messages
     * @param envFile environment directory
     * @param suffix files with this suffix will be removed
     */
    public static void removeFiles(String msg,
                                   File envFile,
                                   String suffix) {
        removeFiles(msg, envFile, suffix, false);
    }

    /**
     * Remove files with this suffix from the je home directory
     * @param msg prefix to append to error messages
     * @param envFile environment directory
     * @param suffix files with this suffix will be removed
     * @param checkRemove if true, check the -DremoveLogFiles system
     *  property before removing.
     */
    public static void removeFiles(String msg,
                                   File envFile,
                                   String suffix,
                                   boolean checkRemove) {
        if (checkRemove && removeDisabled()) {
            return;
        }

        String[] suffixes = new String[] { suffix };
        String[] names = FileManager.listFiles(envFile, suffixes, false);

        /* Clean up any target files in this directory. */
        for (int i = 0; i < names.length; i++) {
            File oldFile = new File(envFile, names[i]);
            boolean done = oldFile.delete();
            assert done :
                msg + " directory = " + envFile +
                " couldn't delete " + names[i] + " out of " +
                names[names.length - 1];
            oldFile = null;
        }
    }

    /**
     * Remove files with the pattern indicated by the filename filter from the
     * environment home directory.
     * Note that BadFileFilter looks for this pattern: NNNNNNNN.bad.#
     *           InfoFileFilter looks for this pattern: je.info.#
     * @param envFile environment directory
     */
    public static void removeFiles(File envFile, FilenameFilter filter) {
        if (removeDisabled()) {
            return;
        }

        File[] targetFiles = envFile.listFiles(filter);

        // Clean up any target files in this directory
        for (int i = 0; i < targetFiles.length; i++) {
            boolean done = targetFiles[i].delete();
            if (!done) {
                System.out.println
                    ("Warning, couldn't delete "
                     + targetFiles[i]
                     + " out of "
                     + targetFiles[targetFiles.length - 1]);
            }
        }
    }

    /**
     * Useful utility for generating byte arrays with a known order.
     * Vary the length just to introduce more variability.
     * @return a byte array of length val % 100 with the value of "val"
     */
    public static byte[] getTestArray(int val) {

        int length = val % 10;
        length = length < 4 ? 4 : length;
        byte[] test = new byte[length];
        test[3] = (byte) ((val >>> 0) & 0xff);
        test[2] = (byte) ((val >>> 8) & 0xff);
        test[1] = (byte) ((val >>> 16) & 0xff);
        test[0] = (byte) ((val >>> 24) & 0xff);
        return test;
    }

    /**
     * Return the value of a test data array generated with getTestArray
     * as an int
     */
    public static int getTestVal(byte[] testArray) {

        int val = 0;
        val |= (testArray[3] & 0xff);
        val |= ((testArray[2] & 0xff) << 8);
        val |= ((testArray[1] & 0xff) << 16);
        val |= ((testArray[0] & 0xff) << 24);
        return val;
    }

    /**
     * @return length and data of a byte array, printed as decimal numbers
     */
    public static String dumpByteArray(byte[] b) {

        StringBuilder sb = new StringBuilder();
        sb.append("<byteArray len = ");
        sb.append(b.length);
        sb.append(" data = \"");
        for (int i = 0; i < b.length; i++) {
            sb.append(b[i]).append(",");
        }
        sb.append("\"/>");
        return sb.toString();
    }

    /**
     * @return a copy of the passed in byte array
     */
    public static byte[] byteArrayCopy(byte[] ba) {

        int len = ba.length;
        byte[] ret = new byte[len];
        System.arraycopy(ba, 0, ret, 0, len);
        return ret;
    }

    /*
     * Check that the stored memory count for all INs on the inlist
     * matches their computed count. The environment mem usage check
     * may be run with assertions or not.
     *
     * In a multithreaded environment (or one with daemons running),
     * you can't be sure that the cached size will equal the calculated size.
     *
     * Nodes, txns, and locks are all counted within the memory budget.
     */
    public static long validateNodeMemUsage(EnvironmentImpl envImpl,
                                            boolean assertOnError)
        throws DatabaseException {

        TreeMemTally tally = tallyTreeMemUsage(envImpl);
        long nodeTallyUsage = tally.treeNodeUsage;
        long nodeCacheUsage = envImpl.getMemoryBudget().getTreeMemoryUsage();
        NumberFormat formatter = NumberFormat.getNumberInstance();
        if (assertOnError) {
            assert (nodeTallyUsage == nodeCacheUsage) :
                  "treeNodeTallyUsage=" + formatter.format(nodeTallyUsage) +
                  " treeNodeCacheUsage=" + formatter.format(nodeCacheUsage);
        } else {
            if (DEBUG) {
                if (nodeCacheUsage != nodeTallyUsage) {
                    double diff = Math.abs(nodeCacheUsage - nodeTallyUsage);
                    if ((diff / nodeCacheUsage) > .05) {
                        System.out.println("treeNodeTallyUsage=" +
                                           formatter.format(nodeTallyUsage) +
                                           " treeNodeCacheUsage=" +
                                           formatter.format(nodeCacheUsage));
                    }
                }
            }
        }

        long adminTallyUsage = tally.treeAdminUsage;
        long adminCacheUsage =
            envImpl.getMemoryBudget().getTreeAdminMemoryUsage();
        if (assertOnError) {
            assert (adminTallyUsage == adminCacheUsage) :
                  "treeAdminTallyUsage=" + formatter.format(adminTallyUsage) +
                  " treeAdminCacheUsage=" + formatter.format(adminCacheUsage);
        } else {
            if (DEBUG) {
                if (adminCacheUsage != adminTallyUsage) {
                    double diff = Math.abs(adminCacheUsage - adminTallyUsage);
                    if ((diff / adminCacheUsage) > .05) {
                        System.out.println("treeAdminTallyUsage=" +
                                           formatter.format(adminTallyUsage) +
                                           " treeAdminCacheUsage=" +
                                           formatter.format(adminCacheUsage));
                    }
                }
            }
        }

        return nodeCacheUsage;
    }

    public static long tallyNodeMemUsage(EnvironmentImpl envImpl)
        throws DatabaseException {

        return tallyTreeMemUsage(envImpl).treeNodeUsage;
    }

    static class TreeMemTally {
        final long treeNodeUsage;
        final long treeAdminUsage;

        TreeMemTally(long treeNodeUsage, long treeAdminUsage) {
            this.treeNodeUsage = treeNodeUsage;
            this.treeAdminUsage = treeAdminUsage;
        }
    }

    private static TreeMemTally tallyTreeMemUsage(EnvironmentImpl envImpl)
        throws DatabaseException {

        long treeNodeUsage = 0;
        long treeAdminUsage = envImpl.getDbTree().getTreeAdminMemory();
        for (IN in : envImpl.getInMemoryINs()) {
            in.latch();
            try {
                assert in.verifyMemorySize():
                    "in nodeId=" + in.getNodeId() +
                    ' ' + in.getClass().getName();

                treeNodeUsage += in.getBudgetedMemorySize();

                for (int i = 0; i < in.getNEntries(); i += 1) {
                    Object child = in.getTarget(i);
                    if (child instanceof LN) {
                        treeAdminUsage += ((LN) child).getTreeAdminMemory();
                    }
                }
            } finally {
                in.releaseLatch();
            }
        }
        return new TreeMemTally(treeNodeUsage, treeAdminUsage);
    }

    /**
     * Called by each unit test to enforce isolation level settings specified
     * in the isolationLevel system property.  Other system properties or
     * default settings may be applied in the future.
     */
    public static EnvironmentConfig initEnvConfig() {

        EnvironmentConfig config = new EnvironmentConfig();
        String val = System.getProperty("isolationLevel");
        if (val != null && val.length() > 0) {
            if ("serializable".equals(val)) {
                config.setTxnSerializableIsolation(true);
            } else if ("readCommitted".equals(val)) {
                DbInternal.setTxnReadCommitted(config, true);
            } else {
                throw new IllegalArgumentException
                    ("Unknown isolationLevel system property value: " + val);
            }
        }
        return config;
    }

    /**
     * If a unit test needs to override the isolation level, it should call
     * this method after calling initEnvConfig.
     */
    public static void clearIsolationLevel(EnvironmentConfig config) {
        DbInternal.setTxnReadCommitted(config, false);
        config.setTxnSerializableIsolation(false);
    }

    /**
     * Loads the given resource relative to the given class, and copies it to
     * log file zero in the given directory.
     */
    public static void loadLog(Class<?> cls, String resourceName, File envHome)
        throws IOException {

        loadLog(cls, resourceName, envHome, LOG_FILE_NAME);
    }

    /**
     * Loads the given resource relative to the given class, and copies it to
     * the given log file in the given directory.
     */
    public static void loadLog(Class cls,
                               String resourceName,
                               File envHome,
                               String logFileName)
        throws IOException {

        File logFile = new File(envHome, logFileName);
        InputStream is = cls.getResourceAsStream(resourceName);
        OutputStream os = new FileOutputStream(logFile);
        byte[] buf = new byte[is.available()];
        int len = is.read(buf);
        if (buf.length != len) {
            throw new IllegalStateException();
        }
        os.write(buf, 0, len);
        is.close();
        os.close();
    }

    /**
     * Logs the BIN at the cursor provisionally and the parent IN
     * non-provisionally.  Used to simulate a partial checkpoint or eviction.
     */
    public static void logBINAndIN(Environment env, Cursor cursor)
        throws DatabaseException {

        logBINAndIN(env, cursor, false /*allowDeltas*/);
    }

    public static void logBINAndIN(Environment env,
                                   Cursor cursor,
                                   boolean allowDeltas)
        throws DatabaseException {

        BIN bin = getBIN(cursor);
        Tree tree = bin.getDatabase().getTree();

        /* Log the BIN and update its parent entry. */
        bin.latch();
        SearchResult result = tree.getParentINForChildIN(bin, true,
                                                         CacheMode.DEFAULT);
        assert result.parent != null;
        assert result.exactParentFound;
        IN binParent = result.parent;
        long binLsn = logIN(env, bin, allowDeltas, true, binParent);
        binParent.updateNode(result.index, bin, binLsn, null /*lnSlotKey*/);
        result.parent.releaseLatch();

        /* Log the BIN parent and update its parent entry. */
        binParent.latch();
        result =
            tree.getParentINForChildIN(binParent, true, CacheMode.DEFAULT);
        IN inParent = null;
        if (result.parent != null) {
            result.parent.releaseLatch();
            assert result.exactParentFound;
            inParent = result.parent;
            inParent.latch();
        }
        final long inLsn = logIN(env, binParent, allowDeltas, false, null);
        if (inParent != null) {
            inParent.updateNode(result.index, binParent, inLsn,
                                null /*lnSlotKey*/);
            inParent.releaseLatch();
        } else {
            tree.withRootLatchedExclusive(new WithRootLatched() {
                public IN doWork(ChildReference root) {
                    root.setLsn(inLsn);
                    return null;
                }
            });
        }
    }

    /**
     * Logs the given IN.
     */
    public static long logIN(Environment env,
                             IN in,
                             boolean allowDeltas,
                             boolean provisional,
                             IN parent)
        throws DatabaseException {

        EnvironmentImpl envImpl = DbInternal.getEnvironmentImpl(env);
        in.latch();
        long lsn = in.log(envImpl.getLogManager(), allowDeltas,
                          false /*allowCompress*/, provisional,
                          false /*backgroundIO*/, parent);
        in.releaseLatch();
        return lsn;
    }

    /**
     * Returns the parent IN of the given IN.
     */
    public static IN getIN(IN in)
        throws DatabaseException {

        Tree tree = in.getDatabase().getTree();
        in.latch();
        SearchResult result =
            tree.getParentINForChildIN(in, true, CacheMode.DEFAULT);
        assert result.parent != null;
        result.parent.releaseLatch();
        assert result.exactParentFound;
        return result.parent;
    }

    /**
     * Returns the target BIN for the given cursor.
     */
    public static BIN getBIN(Cursor cursor) {
        CursorImpl impl = DbTestProxy.dbcGetCursorImpl(cursor);
        BIN bin = impl.getBIN();
        assert bin != null;
        return bin;
    }

    /**
     * Assert if the tree is not this deep. Use to ensure that data setups
     * are as expected.
     */
    public static boolean checkTreeDepth(Database db, int desiredDepth)
        throws DatabaseException {

        Tree tree = DbInternal.getDatabaseImpl(db).getTree();
        IN rootIN = tree.getRootIN(CacheMode.UNCHANGED);
        int level = 0;
        if (rootIN != null) {
            level = rootIN.getLevel() & IN.LEVEL_MASK;
            rootIN.releaseLatch();
        }

        return (desiredDepth == level);
    }

    /**
     * @return true if long running tests are enabled.
     */
    static public boolean runLongTests() {
        return SharedTestUtils.runLongTests();
    }

    /**
     * Skip over the JE version number at the start of the exception
     * message for tests which are looking for a specific message.
     */
    public static String skipVersion(Exception e) {
        final String header = DatabaseException.getVersionHeader();
        final String msg = e.getMessage();
        if (msg == null || !msg.startsWith(header)) {
            return msg;
        }
        return msg.substring(header.length());
    }

    public static void createEnvHomeWithSubDir(File envHome, 
                                               int subDirNumber) {
        if (!envHome.exists()) {
            throw new IllegalStateException
                ("Environment home directory doesn't exist.");
        }

        for (int i = 1; i <= subDirNumber; i++) {
            String fileName = getSubDirName(i);
            File subDir = new File(envHome, fileName);
            subDir.mkdir();
        }
    }

    public static String getSubDirName(int i) {
        if (i < 10) {
            return "data00" + i;
        } else if (i < 100) {
            return "data0" + i;
        } else if (i <= 256) {
            return "data" + i;
        } else {
            throw new IllegalArgumentException
                ("The number of sub directories is invalid.");
        }
    }

    public static void removeSubDirs(File envHome) {
        if (envHome == null || !envHome.exists()) {
            return;
        }

        File[] files = envHome.listFiles();
        for (File file : files) {
            if (file.isDirectory() && file.getName().startsWith("data")) {
                File[] subFiles = file.listFiles();
                for (File subFile : subFiles) {
                    subFile.delete();
                }
                file.delete();
            }
        }
    }

    /* Read the je.properties and write a new configuration. */
    public static ArrayList<String> readWriteJEProperties(File envHome, 
                                                          String configure) 
        throws IOException {

        /* Read the je.properties. */
        File propertyFile = new File(envHome, "je.properties");
        BufferedReader reader =
            new BufferedReader(new FileReader(propertyFile));
        ArrayList<String> formerLines = new ArrayList<String>();
        String line = null;
        while ((line = reader.readLine()) != null) {
            formerLines.add(line);
        }
        reader.close();

        /* Write the replicated parameters in the je.properties file. */
        FileWriter writer = new FileWriter(propertyFile, true);
        writer.append(configure + "\n");
        writer.flush();
        writer.close();

        return formerLines;
    }

    /* 
     * Rewrite the je.properties with configurations, it will delete the old
     * file and rewrite a new one.
     */ 
    public static void reWriteJEProperties(File envHome, 
                                           ArrayList<String> formerLines) 
        throws IOException {

        File propertyFile = new File(envHome, "je.properties");
        /* Write the je.properties file with the former content. */
        if (propertyFile.exists() && propertyFile.isFile()) {
            TestCase.assertTrue(propertyFile.delete());
        }
        TestCase.assertTrue(!propertyFile.exists());

        propertyFile = new File(envHome, "je.properties");
        TestCase.assertTrue(propertyFile.createNewFile());

        FileWriter writer = new FileWriter(propertyFile, true);
        for (String configure : formerLines) {
            writer.append(configure + "\n");
        }
        writer.flush();
        writer.close();
    }

    /* Serialize an object and read it again. */
    public static Object serializeAndReadObject(File envHome, Object object) 
        throws Exception {

        File output = new File(envHome, "configure.out");
        ObjectOutputStream out =
            new ObjectOutputStream(new FileOutputStream(output));
        out.writeObject(object);
        out.close();

        if (!output.exists()) {
            throw new IllegalStateException
                ("Can't create the output for serialized object.");
        }

        ObjectInputStream in = 
            new ObjectInputStream(new FileInputStream(output));
        Object newObject = in.readObject();
        in.close();

        if (!output.delete()) {
            throw new IllegalStateException
                ("Can't delete the output for serialized object after " +
                 "testing is done.");
        }

        return newObject;
    }

    /**
     * Dump any exception messages to stderr.
     */
    public static class StdErrExceptionListener
        implements ExceptionListener {

        public void exceptionThrown(ExceptionEvent event) {
            System.err.println(Thread.currentThread() +
                               " received " +
                               event);
        }
    }
        
    /**
     * Calls Closeable.close for each parameter in the order given, if it is
     * non-null.
     * 
     * If one or more close methods throws an Exception, all close methods will
     * still be called and the first Exception will be rethrown.  If an Error
     * is thrown by a close method, it will be thrown by this method and no
     * further close methods will be called.  An IOException may be thrown by a
     * close method because is declared by Closeable.close; however, the use of
     * RuntimeExceptions is recommended.
     */
    public static void closeAll(Closeable... objects)
        throws Exception {

        closeAll(null, objects);
    }   

    /**
     * Same as closeAll(Closeable...) but allows passing an initial exception,
     * when one may have been thrown earlier during a shutdown procedure.  If
     * null is passed for the firstEx parameter, calling this method is
     * equivalent to calling closeAll(Closeable...).
     */
    public static void closeAll(Exception firstEx, Closeable... objects)
        throws Exception {
        
        for (Closeable c : objects) {
            if (c == null) {
                continue;
            }
            try {
                c.close();
            } catch (Exception e) {
                if (firstEx == null) {
                    firstEx = e;
                }
            }
        }
        
        if (firstEx != null) {
            throw firstEx;
        }
    }
}
