/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002, 2014 Oracle and/or its affiliates.  All rights reserved.
 *
 */

package com.sleepycat.je.rep;

import com.sleepycat.je.EnvironmentFailureException;
import com.sleepycat.je.dbi.EnvironmentFailureReason;
import com.sleepycat.je.dbi.EnvironmentImpl;

/**
 * In the past, MasterReplicaTransitionException was sometimes thrown in JE 
 * replication systems when an environment that was a master and transitioned 
 * to replica state. In some cases, the environment had to reinitialize 
 * internal state to become a replica, and the application was required to
 * the application close and reopen its environment handle, thereby
 * properly reinitializing the node.
 * <p>
 * As of JE 5.0.88, the environment can transition from master to replica
 * without requiring an environment close and re-open.
 * @deprecated as of JE 5.0.88 because the environment no longer needs to
 * restart when transitioning from master to replica.
 */
@Deprecated
public class MasterReplicaTransitionException 
    extends RestartRequiredException {

    private static final long serialVersionUID = 1;

    /* Maintain for unit testing in SerializeUtils.java */
    public MasterReplicaTransitionException(EnvironmentImpl envImpl,
                                            Exception cause) {
        super(envImpl, 
              EnvironmentFailureReason.MASTER_TO_REPLICA_TRANSITION, 
              cause);
    }

    /**
     * @hidden
     * For internal use only.
     */
    private MasterReplicaTransitionException
        (String message, 
         MasterReplicaTransitionException cause) {
        super(message, cause);
    }

    /**
     * @hidden
     * For internal use only.
     */
    @Override
    public EnvironmentFailureException wrapSelf(String msg) {
        return new MasterReplicaTransitionException(msg, this);
    }
}
