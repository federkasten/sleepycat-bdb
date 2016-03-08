/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002, 2014 Oracle and/or its affiliates.  All rights reserved.
 *
 */

package com.sleepycat.je.rep.persist.test;

import com.sleepycat.je.rep.ReplicatedEnvironment;
import com.sleepycat.persist.EntityStore;
import com.sleepycat.persist.StoreConfig;
import com.sleepycat.persist.model.AnnotationModel;
import com.sleepycat.persist.model.EntityModel;
import com.sleepycat.persist.evolve.Mutations;

public abstract class AppBaseImpl implements AppInterface {

    protected int version;
    protected ReplicatedEnvironment env;
    protected EntityStore store;
    private boolean doInitDuringOpen;

    public void setVersion(final int version) {
        this.version = version;
    }

    public void setInitDuringOpen(final boolean doInit) {
        doInitDuringOpen = doInit;
    }

    public void open(final ReplicatedEnvironment env) {
        this.env = env;
        StoreConfig config =
            new StoreConfig().setAllowCreate(true).setTransactional(true);
        Mutations mutations = new Mutations();
        EntityModel model = new AnnotationModel();
        if (doInitDuringOpen) {
            setupConfig(mutations, model);
        }
        config.setMutations(mutations);
        config.setModel(model);
        store = new EntityStore(env, "foo", config);
        if (doInitDuringOpen) {
            init();
        }
    }

    protected abstract void setupConfig(final Mutations mutations,
                                        final EntityModel model);

    protected abstract void init();

    public void close() {
        store.close();
    }

    public void adopt(AppInterface other) {
        version = other.getVersion();
        env = other.getEnv();
        store = other.getStore();
        if (doInitDuringOpen) {
            init();
        }
    }

    public ReplicatedEnvironment getEnv() {
        return env;
    }

    public EntityStore getStore() {
        return store;
    }

    public int getVersion() {
        return version;
    }
}
