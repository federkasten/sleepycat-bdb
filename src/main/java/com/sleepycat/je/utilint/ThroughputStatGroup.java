/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002, 2014 Oracle and/or its affiliates.  All rights reserved.
 *
 */

package com.sleepycat.je.utilint;

import static com.sleepycat.je.dbi.DbiStatDefinition.THROUGHPUT_CURSOR_DELETE;
import static com.sleepycat.je.dbi.DbiStatDefinition.THROUGHPUT_CURSOR_GETCURRENT;
import static com.sleepycat.je.dbi.DbiStatDefinition.THROUGHPUT_CURSOR_GETFIRST;
import static com.sleepycat.je.dbi.DbiStatDefinition.THROUGHPUT_CURSOR_GETLAST;
import static com.sleepycat.je.dbi.DbiStatDefinition.THROUGHPUT_CURSOR_GETNEXT;
import static com.sleepycat.je.dbi.DbiStatDefinition.THROUGHPUT_CURSOR_GETNEXTDUP;
import static com.sleepycat.je.dbi.DbiStatDefinition.THROUGHPUT_CURSOR_GETNEXTNODUP;
import static com.sleepycat.je.dbi.DbiStatDefinition.THROUGHPUT_CURSOR_GETPREV;
import static com.sleepycat.je.dbi.DbiStatDefinition.THROUGHPUT_CURSOR_GETPREVDUP;
import static com.sleepycat.je.dbi.DbiStatDefinition.THROUGHPUT_CURSOR_GETPREVNODUP;
import static com.sleepycat.je.dbi.DbiStatDefinition.THROUGHPUT_CURSOR_PUT;
import static com.sleepycat.je.dbi.DbiStatDefinition.THROUGHPUT_CURSOR_PUTCURRENT;
import static com.sleepycat.je.dbi.DbiStatDefinition.THROUGHPUT_CURSOR_PUTNODUPDATA;
import static com.sleepycat.je.dbi.DbiStatDefinition.THROUGHPUT_CURSOR_PUTNOOVERWRITE;
import static com.sleepycat.je.dbi.DbiStatDefinition.THROUGHPUT_DB_DELETE;
import static com.sleepycat.je.dbi.DbiStatDefinition.THROUGHPUT_DB_GET;
import static com.sleepycat.je.dbi.DbiStatDefinition.THROUGHPUT_DB_GETSEARCHBOTH;
import static com.sleepycat.je.dbi.DbiStatDefinition.THROUGHPUT_DB_PUT;
import static com.sleepycat.je.dbi.DbiStatDefinition.THROUGHPUT_DB_PUTNODUPDATA;
import static com.sleepycat.je.dbi.DbiStatDefinition.THROUGHPUT_DB_PUTNOOVERWRITE;
import static com.sleepycat.je.dbi.DbiStatDefinition.THROUGHPUT_DB_REMOVESEQUENCE;
import static com.sleepycat.je.dbi.DbiStatDefinition.THROUGHPUT_DOSCURSOR_GETNEXT;
import static com.sleepycat.je.dbi.DbiStatDefinition.THROUGHPUT_SECONDARYCURSOR_DELETE;
import static com.sleepycat.je.dbi.DbiStatDefinition.THROUGHPUT_SECONDARYCURSOR_GETCURRENT;
import static com.sleepycat.je.dbi.DbiStatDefinition.THROUGHPUT_SECONDARYCURSOR_GETFIRST;
import static com.sleepycat.je.dbi.DbiStatDefinition.THROUGHPUT_SECONDARYCURSOR_GETLAST;
import static com.sleepycat.je.dbi.DbiStatDefinition.THROUGHPUT_SECONDARYCURSOR_GETNEXT;
import static com.sleepycat.je.dbi.DbiStatDefinition.THROUGHPUT_SECONDARYCURSOR_GETNEXTDUP;
import static com.sleepycat.je.dbi.DbiStatDefinition.THROUGHPUT_SECONDARYCURSOR_GETNEXTNODUP;
import static com.sleepycat.je.dbi.DbiStatDefinition.THROUGHPUT_SECONDARYCURSOR_GETPREV;
import static com.sleepycat.je.dbi.DbiStatDefinition.THROUGHPUT_SECONDARYCURSOR_GETPREVDUP;
import static com.sleepycat.je.dbi.DbiStatDefinition.THROUGHPUT_SECONDARYCURSOR_GETPREVNODUP;
import static com.sleepycat.je.dbi.DbiStatDefinition.THROUGHPUT_SECONDARYDB_DELETE;
import static com.sleepycat.je.dbi.DbiStatDefinition.THROUGHPUT_SECONDARYDB_GET;
import static com.sleepycat.je.dbi.DbiStatDefinition.THROUGHPUT_SECONDARYDB_GETSEARCHBOTH;

public class ThroughputStatGroup extends StatGroup {
    private static final long serialVersionUID = 1L;

    public static final int DB_DELETE_OFFSET = 0;
    public static final int DB_GET_OFFSET = 1;
    public static final int DB_GETSEARCHBOTH_OFFSET = 2;
    public static final int DB_PUT_OFFSET = 3;
    public static final int DB_PUTNODUPDATA_OFFSET = 4;
    public static final int DB_PUTNOOVERWRITE_OFFSET = 5;
    public static final int DB_REMOVESEQUENCE_OFFSET = 6;
    public static final int CURSOR_DELETE_OFFSET = 7;
    public static final int CURSOR_GETCURRENT_OFFSET = 8;
    public static final int CURSOR_GETFIRST_OFFSET = 9;
    public static final int CURSOR_GETLAST_OFFSET = 10;
    public static final int CURSOR_GETNEXT_OFFSET = 11;
    public static final int CURSOR_GETNEXTDUP_OFFSET = 12;
    public static final int CURSOR_GETNEXTNODUP_OFFSET = 13;
    public static final int CURSOR_GETPREV_OFFSET = 14;
    public static final int CURSOR_GETPREVDUP_OFFSET = 15;
    public static final int CURSOR_GETPREVNODUP_OFFSET = 16;
    public static final int CURSOR_PUT_OFFSET = 17;
    public static final int CURSOR_PUTCURRENT_OFFSET = 18;
    public static final int CURSOR_PUTNODUPDATA_OFFSET = 19;
    public static final int CURSOR_PUTNOOVERWRITE_OFFSET = 20;
    public static final int SECONDARYCURSOR_DELETE_OFFSET = 21;
    public static final int SECONDARYCURSOR_GETCURRENT_OFFSET = 22;
    public static final int SECONDARYCURSOR_GETFIRST_OFFSET = 23;
    public static final int SECONDARYCURSOR_GETLAST_OFFSET = 24;
    public static final int SECONDARYCURSOR_GETNEXT_OFFSET = 25;
    public static final int SECONDARYCURSOR_GETNEXTDUP_OFFSET = 26;
    public static final int SECONDARYCURSOR_GETNEXTNODUP_OFFSET = 27;
    public static final int SECONDARYCURSOR_GETPREV_OFFSET = 28;
    public static final int SECONDARYCURSOR_GETPREVDUP_OFFSET = 29;
    public static final int SECONDARYCURSOR_GETPREVNODUP_OFFSET = 30;
    public static final int SECONDARYDB_DELETE_OFFSET = 31;
    public static final int SECONDARYDB_GET_OFFSET = 32;
    public static final int SECONDARYDB_GETSEARCHBOTH_OFFSET = 33;
    public static final int DOSCURSOR_GETNEXT_OFFSET = 34;
    private static final int MAX_OFFSET = 35;

    private final AtomicLongStat[] stats = new AtomicLongStat[MAX_OFFSET];

    public ThroughputStatGroup(String groupName, String groupDescription) {
        super(groupName, groupDescription);
        createThroughputStats();
    }

    public void increment(int statOffset) {
        stats[statOffset].increment();
    }

    private void createThroughputStats() {
        stats[DB_DELETE_OFFSET] =
            new AtomicLongStat(this, THROUGHPUT_DB_DELETE);
        stats[DB_GET_OFFSET] =
            new AtomicLongStat(this, THROUGHPUT_DB_GET);
        stats[DB_GETSEARCHBOTH_OFFSET] =
            new AtomicLongStat(this, THROUGHPUT_DB_GETSEARCHBOTH);
        stats[DB_PUT_OFFSET] =
            new AtomicLongStat(this, THROUGHPUT_DB_PUT);
        stats[DB_PUTNODUPDATA_OFFSET] =
            new AtomicLongStat(this, THROUGHPUT_DB_PUTNODUPDATA);
        stats[DB_PUTNOOVERWRITE_OFFSET] =
            new AtomicLongStat(this, THROUGHPUT_DB_PUTNOOVERWRITE);
        stats[DB_REMOVESEQUENCE_OFFSET] =
            new AtomicLongStat(this, THROUGHPUT_DB_REMOVESEQUENCE);
        stats[CURSOR_DELETE_OFFSET] =
            new AtomicLongStat(this, THROUGHPUT_CURSOR_DELETE);
        stats[CURSOR_GETCURRENT_OFFSET] =
            new AtomicLongStat(this, THROUGHPUT_CURSOR_GETCURRENT);
        stats[CURSOR_GETFIRST_OFFSET] =
            new AtomicLongStat(this, THROUGHPUT_CURSOR_GETFIRST);
        stats[CURSOR_GETLAST_OFFSET] =
            new AtomicLongStat(this, THROUGHPUT_CURSOR_GETLAST);
        stats[CURSOR_GETNEXT_OFFSET] =
            new AtomicLongStat(this, THROUGHPUT_CURSOR_GETNEXT);
        stats[CURSOR_GETNEXTDUP_OFFSET] =
            new AtomicLongStat(this, THROUGHPUT_CURSOR_GETNEXTDUP);
        stats[CURSOR_GETNEXTNODUP_OFFSET] =
            new AtomicLongStat(this, THROUGHPUT_CURSOR_GETNEXTNODUP);
        stats[CURSOR_GETPREV_OFFSET] =
            new AtomicLongStat(this, THROUGHPUT_CURSOR_GETPREV);
        stats[CURSOR_GETPREVDUP_OFFSET] =
            new AtomicLongStat(this, THROUGHPUT_CURSOR_GETPREVDUP);
        stats[CURSOR_GETPREVNODUP_OFFSET] =
            new AtomicLongStat(this, THROUGHPUT_CURSOR_GETPREVNODUP);
        stats[CURSOR_PUT_OFFSET] =
            new AtomicLongStat(this, THROUGHPUT_CURSOR_PUT);
        stats[CURSOR_PUTCURRENT_OFFSET] =
            new AtomicLongStat(this, THROUGHPUT_CURSOR_PUTCURRENT);
        stats[CURSOR_PUTNODUPDATA_OFFSET] =
            new AtomicLongStat(this, THROUGHPUT_CURSOR_PUTNODUPDATA);
        stats[CURSOR_PUTNOOVERWRITE_OFFSET] =
            new AtomicLongStat(this, THROUGHPUT_CURSOR_PUTNOOVERWRITE);
        stats[SECONDARYCURSOR_DELETE_OFFSET] =
            new AtomicLongStat(this, THROUGHPUT_SECONDARYCURSOR_DELETE);
        stats[SECONDARYCURSOR_GETCURRENT_OFFSET] =
            new AtomicLongStat(this, THROUGHPUT_SECONDARYCURSOR_GETCURRENT);
        stats[SECONDARYCURSOR_GETFIRST_OFFSET] =
            new AtomicLongStat(this, THROUGHPUT_SECONDARYCURSOR_GETFIRST);
        stats[SECONDARYCURSOR_GETLAST_OFFSET] =
            new AtomicLongStat(this, THROUGHPUT_SECONDARYCURSOR_GETLAST);
        stats[SECONDARYCURSOR_GETNEXT_OFFSET] =
            new AtomicLongStat(this, THROUGHPUT_SECONDARYCURSOR_GETNEXT);
        stats[SECONDARYCURSOR_GETNEXTDUP_OFFSET] =
            new AtomicLongStat(this, THROUGHPUT_SECONDARYCURSOR_GETNEXTDUP);
        stats[SECONDARYCURSOR_GETNEXTNODUP_OFFSET] =
            new AtomicLongStat(this, THROUGHPUT_SECONDARYCURSOR_GETNEXTNODUP);
        stats[SECONDARYCURSOR_GETPREV_OFFSET] =
            new AtomicLongStat(this, THROUGHPUT_SECONDARYCURSOR_GETPREV);
        stats[SECONDARYCURSOR_GETPREVDUP_OFFSET] =
            new AtomicLongStat(this, THROUGHPUT_SECONDARYCURSOR_GETPREVDUP);
        stats[SECONDARYCURSOR_GETPREVNODUP_OFFSET] =
            new AtomicLongStat(this, THROUGHPUT_SECONDARYCURSOR_GETPREVNODUP);
        stats[SECONDARYDB_DELETE_OFFSET] =
            new AtomicLongStat(this, THROUGHPUT_SECONDARYDB_DELETE);
        stats[SECONDARYDB_GET_OFFSET] =
            new AtomicLongStat(this, THROUGHPUT_SECONDARYDB_GET);
        stats[SECONDARYDB_GETSEARCHBOTH_OFFSET] =
            new AtomicLongStat(this, THROUGHPUT_SECONDARYDB_GETSEARCHBOTH);
        stats[DOSCURSOR_GETNEXT_OFFSET] =
            new AtomicLongStat(this, THROUGHPUT_DOSCURSOR_GETNEXT);
    }
}
