/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002, 2014 Oracle and/or its affiliates.  All rights reserved.
 *
 */

package com.sleepycat.je.rep.vlsn;

import com.sleepycat.je.utilint.DbLsn;
import com.sleepycat.je.utilint.VLSN;

/**
 * Just a struct for testing convenience.
 */
class VLPair {
    final VLSN vlsn;
    final long lsn;

    VLPair(VLSN vlsn, long lsn) {
        this.vlsn = vlsn;
        this.lsn = lsn;
    }

    VLPair(int vlsnSequence, long fileNumber, long offset) {
        this.vlsn = new VLSN(vlsnSequence);
        this.lsn = DbLsn.makeLsn(fileNumber, offset);
    }

    @Override
        public String toString() {
        return vlsn + "/" + DbLsn.getNoFormatString(lsn);
    }
}
