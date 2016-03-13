/*
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002, 2014 Oracle and/or its affiliates.  All rights reserved.
 *
 */

package com.sleepycat.je.statcap;

import java.text.DateFormat;
import java.util.Date;

import com.sleepycat.je.utilint.TracerFormatter;

class StatUtils {
    private static final DateFormat formatter =
        TracerFormatter.makeDateFormat();
    private static final Date date = new Date();
    /** Returns a string representation of the specified time. */
    public static synchronized String getDate(final long millis) {
        /* The date and formatter are not thread safe */
        date.setTime(millis);
        return formatter.format(date);
    }
}