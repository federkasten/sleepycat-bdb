/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002, 2014 Oracle and/or its affiliates.  All rights reserved.
 *
 */

package com.sleepycat.je.dbi;

/**
 * Exception to indicate that an entry is already present in a node.
 */
@SuppressWarnings("serial")
class DuplicateEntryException extends RuntimeException {

    DuplicateEntryException() {
        super();
    }

    DuplicateEntryException(String message) {
        super(message);
    }
}
