/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002, 2014 Oracle and/or its affiliates.  All rights reserved.
 *
 */

package com.sleepycat.je.util;

import com.sleepycat.je.DatabaseEntry;
import com.sleepycat.utilint.StringUtils;

public class StringDbt extends DatabaseEntry {
    public StringDbt() {
    }

    public StringDbt(String value) {
        setString(value);
    }

    public StringDbt(byte[] value) {
        setData(value);
    }

    public void setString(String value) {
        byte[] data = StringUtils.toUTF8(value);
        setData(data);
    }

    public String getString() {
        return StringUtils.fromUTF8(getData(), 0, getSize());
    }

    public String toString() {
        return getString();
    }
}
