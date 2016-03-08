/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002, 2014 Oracle and/or its affiliates.  All rights reserved.
 *
 */

package com.sleepycat.je.utilint;

import com.sleepycat.je.EnvironmentFailureException;

/**
 * A long JE stat.
 */
public class LSNStat extends LongStat{
    private static final long serialVersionUID = 1L;

    public LSNStat(StatGroup group, StatDefinition definition) {
        super(group, definition);
    }

    public LSNStat(StatGroup group, StatDefinition definition, long counter) {
        super(group, definition);
        this.counter = counter;
    }

    @Override
    public void add(Stat<Long> other) {
        throw EnvironmentFailureException.unexpectedState(
            "LongArrayStat doesn't support the add operation.");
    }

    @Override
    public Stat<Long> computeInterval(Stat<Long> base) {
        return copy();
    }

    @Override
    public void negate() {
    }

    @Override
    protected String getFormattedValue() {
        return DbLsn.getNoFormatString(counter);
    }
}
