/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002, 2014 Oracle and/or its affiliates.  All rights reserved.
 *
 */

package com.sleepycat.je.statcap;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map.Entry;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.logging.Logger;

import com.sleepycat.je.CustomStats;
import com.sleepycat.je.DatabaseException;
import com.sleepycat.je.EnvironmentMutableConfig;
import com.sleepycat.je.StatsConfig;
import com.sleepycat.je.config.EnvironmentParams;
import com.sleepycat.je.dbi.DbConfigManager;
import com.sleepycat.je.dbi.EnvConfigObserver;
import com.sleepycat.je.dbi.EnvironmentImpl;
import com.sleepycat.je.utilint.DaemonThread;
import com.sleepycat.je.utilint.LoggerUtils;
import com.sleepycat.je.utilint.Stat;
import com.sleepycat.je.utilint.StatDefinition;
import com.sleepycat.je.utilint.StatGroup;
import com.sleepycat.je.utilint.StringStat;
import com.sleepycat.utilint.StatLogger;

public class StatCapture extends DaemonThread implements EnvConfigObserver {
    private EnvironmentImpl env;
    private final StatsConfig clearingFastConfig;
    private StatLogger stlog = null;
    public static final String STATFILENAME = "je.stat";
    public static final String STATFILEEXT = "csv";
    private static final String CUSTOMGROUPNAME = "Custom";
    private static final String DELIMITER = ",";
    private static final String DELIMITERANDSPACE = ", ";
    private final StringBuffer values = new StringBuffer();
    private String currentHeader = null;
    private Integer statKey = null;
    private final SortedSet<String> statProjection;
    /* BEGIN-NO-ANDROID */
    private final JvmStats jvmstats = new JvmStats();
    /* END-NO-ANDROID */
    private final CustomStats customStats;
    private String[] customStatHeader = null;
    private boolean collectStats;

    private final Logger logger;
    private StatManager statMgr;

    /*
     * Exception of last outputStats() call or null if call was successful.
     * Used to limit the number of errors logged.
     */
    private Exception lastCallException = null;

    public StatCapture(EnvironmentImpl environment,
                       String name,
                       long waitTime,
                       CustomStats customStats,
                       SortedSet<String> statProjection,
                       StatManager statMgr) {
        super(waitTime, name, environment);
        logger = LoggerUtils.getLogger(getClass());
        environment.addConfigObserver(this);

        File statdirf;

        env = environment;
        this.statMgr = statMgr;
        statKey = statMgr.registerStatContext();
        clearingFastConfig = new StatsConfig();
        clearingFastConfig.setFast(true);
        clearingFastConfig.setClear(true);
        this.customStats = customStats;
        this.statProjection = statProjection;
        String statdir = env.getConfigManager().get(
                             EnvironmentParams.STATS_FILE_DIRECTORY);
        collectStats = env.getConfigManager().getBoolean(
                          EnvironmentParams.STATS_COLLECT);

        if (statdir == null || statdir.equals("")) {
            statdirf = env.getEnvironmentHome();
        } else {
            statdirf = new File(statdir);
        }
        try {
            stlog =
                new StatLogger(statdirf,
                               STATFILENAME, STATFILEEXT,
                               env.getConfigManager().getInt(
                                   EnvironmentParams.STATS_MAX_FILES),
                               env.getConfigManager().getInt(
                                  EnvironmentParams.STATS_FILE_ROW_COUNT));
        } catch (IOException e) {
            throw new IllegalStateException(
                " Error accessing statistics capture file "+
                 STATFILENAME + "." + STATFILEEXT +
                 " IO Exception: " + e.getMessage());
        }

        /* Add jvm and custom statistics to the projection list. */
        /* BEGIN-NO-ANDROID */
        jvmstats.addVMStatDefs(statProjection);
        /* END-NO-ANDROID */
        if (customStats != null) {
            String[] customFldNames = customStats.getFieldNames();
            customStatHeader = new String[customFldNames.length];
            for (int i = 0; i < customFldNames.length; i++) {
                customStatHeader[i] = CUSTOMGROUPNAME + ":" + customFldNames[i];
                statProjection.add(customStatHeader[i]);
            }
        }
    }

    public synchronized void clearEnv() {
        if (statKey != null && statMgr != null) {
            statMgr.unregisterStatContext(statKey);
            statKey = null;
        }
        statMgr = null;
        if (env != null) {
            env.removeConfigObserver(this);
        }
        env = null;
    }

    /**
     * Called whenever the DaemonThread wakes up from a sleep.
     */
    @Override
    protected void onWakeup()
        throws DatabaseException {

        if (env.isClosed()) {
            return;
        }
        if (!collectStats || env.isInvalid()) {
            return;
        }
        outputStats();
    }

    @Override
    public void requestShutdown() {
        super.requestShutdown();

        /*
         * Check if env is valid outside of synchronized call to
         * outputStats(). It is possible that a call to outputStats
         * caused the invalidation and we would deadlock since that
         * thread is holding the lock for this object and waiting for
         * this thread to shutdown.
         */
        if (!collectStats || env.isInvalid()) {
            return;
        }
        outputStats();
    }

    private synchronized void outputStats() {

        if (!collectStats || env.isInvalid()) {
            return;
        }

        try {
            SortedMap<String, String> stats = getStats();
            if (stats != null) {
                if (currentHeader == null) {
                    values.setLength(0);
                    values.append("time");
                    for (Iterator<String> nameit = statProjection.iterator();
                        nameit.hasNext();) {
                        String statname = nameit.next();
                        values.append(DELIMITER + statname);
                    }
                    stlog.setHeader(values.toString());
                    currentHeader = values.toString();
                }
                values.setLength(0);
                values.append(StatUtils.getDate(System.currentTimeMillis()));

                for (Iterator<String> nameit = statProjection.iterator();
                    nameit.hasNext();) {
                    String statname = nameit.next();
                    String val = stats.get(statname);
                    if (val != null) {
                        values.append(DELIMITER + val);
                    } else {
                        values.append(DELIMITERANDSPACE);
                    }
                }
                stlog.log(values.toString());
                values.setLength(0);
                lastCallException = null;
            }
        }
        catch (IOException e) {
            if (lastCallException == null) {
                LoggerUtils.warning(logger, env,
                    "Error accessing statistics capture file " +
                    STATFILENAME + "." + STATFILEEXT +
                    " IO Exception: " + e.getMessage());
            }
            lastCallException = e;
        }
    }

    private SortedMap<String, String> getStats() {
        String mapName;
        Object val;

        final Collection<StatGroup> envStats = new ArrayList<StatGroup>(
            statMgr.loadStats(clearingFastConfig, statKey).getStatGroups());
        if (env.isReplicated()) {
            Collection<StatGroup> rsg =
                env.getRepStatGroups(clearingFastConfig, statKey);
            if (rsg != null) {
                envStats.addAll(rsg);
            }
        }

        /* BEGIN-NO-ANDROID */
        envStats.add(jvmstats.loadStats(clearingFastConfig));
        /* END-NO-ANDROID */

        SortedMap<String, String> statsMap = new TreeMap<String, String>();
        for (StatGroup sg : envStats) {
            for (Entry<StatDefinition, Stat<?>> e :
                 sg.getStats().entrySet()) {
                mapName = (sg.getName() + ":" +
                           e.getKey().getName()).intern();
                val = e.getValue().get();
                /* get stats back as strings. */
                if (val instanceof Number) {
                    statsMap.put(mapName,
                                 Long.toString(((Number) val).longValue()));
                } else if (e.getValue() instanceof StringStat) {
                    if (val != null){
                        statsMap.put(mapName, (String)val);
                    } else {
                        statsMap.put(mapName, " ");
                    }
                }
            }
        }
        if (customStats != null) {
            String vals[] = customStats.getFieldValues();
            for (int i = 0; i < vals.length; i++) {
                statsMap.put(customStatHeader[i], vals[i]);
            }
        }
        return statsMap;
    }

    public void envConfigUpdate(DbConfigManager configMgr,
                                EnvironmentMutableConfig newConfig)
                                throws DatabaseException {
         stlog.setFileCount(configMgr.getInt(
             EnvironmentParams.STATS_MAX_FILES));
         stlog.setRowCount(configMgr.getInt(
            EnvironmentParams.STATS_FILE_ROW_COUNT));
         setWaitTime(configMgr.getDuration(
             EnvironmentParams.STATS_COLLECT_INTERVAL));
         collectStats =
             configMgr.getBoolean(EnvironmentParams.STATS_COLLECT);
    }
}
