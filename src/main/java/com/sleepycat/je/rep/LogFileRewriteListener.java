/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002, 2014 Oracle and/or its affiliates.  All rights reserved.
 *
 */

package com.sleepycat.je.rep;

import java.io.File;
import java.util.Set;

/**
 * @hidden
 * A notification callback interface to warn the user that JE is about to
 * modify previously written log files as part of sync-up rollback.
 *
 * @see RollbackException
 */
public interface LogFileRewriteListener {

    /**
     * @hidden
     * Notifies the user that JE is about to modify previously written log
     * files.
     *
     * @param files the log files that will be modified.
     */
    public void rewriteLogFiles(Set<File> files);
}
