/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002, 2010 Oracle and/or its affiliates.  All rights reserved.
 *
 */

package com.sleepycat.je.utilint;

import com.sleepycat.je.utilint.StatDefinition.StatType;

/**
 * A Float JE stat.
 */
public class FloatStat extends Stat<Float> {
    private static final long serialVersionUID = 1L;

    private float val;

    public FloatStat(StatGroup group, StatDefinition definition) {
        super(group, definition);
    }

    public FloatStat(StatGroup group, StatDefinition definition, float val) {
        super(group, definition);
        this.val = val;
    }

    @Override
    public Float get() {
        return val;
    }

    @Override
    public void set(Float newValue) {
        val = newValue;
    }

    @Override
    public void add(Stat<Float> otherStat) {
        val += otherStat.get();
    }

    @Override
    public Stat<Float> computeInterval(Stat<Float> base) {
        Stat<Float> ret = copy();
        if (definition.getType() == StatType.INCREMENTAL) {
            ret.set(get() - base.get());
        }
        return ret;
    }

    @Override
    public void negate() {
        val = -val;
    }

    @Override
    public void clear() {
        val = 0;
    }

    @Override
    protected String getFormattedValue() {
        return Float.toString(val);
    }

    @Override
    public boolean isNotSet() {
        return (val == 0);
    }
}
