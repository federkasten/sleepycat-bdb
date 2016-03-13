/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002, 2014 Oracle and/or its affiliates.  All rights reserved.
 *
 */

package com.sleepycat.je;

import java.util.HashSet;
import java.util.Set;
import java.util.logging.Level;

import com.sleepycat.je.dbi.CursorImpl.SearchMode;
import com.sleepycat.je.dbi.GetMode;
import com.sleepycat.je.txn.Locker;
import com.sleepycat.je.utilint.DatabaseUtil;
import com.sleepycat.je.utilint.ThroughputStatGroup;

/**
 * A database cursor for a secondary database. Cursors are not thread safe and
 * the application is responsible for coordinating any multithreaded access to
 * a single cursor object.
 *
 * <p>Secondary cursors are returned by {@link SecondaryDatabase#openCursor
 * SecondaryDatabase.openCursor} and {@link
 * SecondaryDatabase#openSecondaryCursor
 * SecondaryDatabase.openSecondaryCursor}.  The distinguishing characteristics
 * of a secondary cursor are:</p>
 *
 * <ul> <li>Direct calls to <code>put()</code> methods on a secondary cursor
 * are prohibited.
 *
 * <li>The {@link #delete} method of a secondary cursor will delete the primary
 * record and as well as all its associated secondary records.
 *
 * <li>Calls to all get methods will return the data from the associated
 * primary database.
 *
 * <li>Additional get method signatures are provided to return the primary key
 * in an additional pKey parameter.
 *
 * <li>Calls to {@link #dup} will return a {@link SecondaryCursor}.
 *
 * </ul>
 *
 * <p>To obtain a secondary cursor with default attributes:</p>
 *
 * <blockquote><pre>
 *     SecondaryCursor cursor = myDb.openSecondaryCursor(txn, null);
 * </pre></blockquote>
 *
 * <p>To customize the attributes of a cursor, use a CursorConfig object.</p>
 *
 * <blockquote><pre>
 *     CursorConfig config = new CursorConfig();
 *     config.setReadUncommitted(true);
 *     SecondaryCursor cursor = myDb.openSecondaryCursor(txn, config);
 * </pre></blockquote>
 */
public class SecondaryCursor extends Cursor {

    private final SecondaryDatabase secondaryDb;
    private ThroughputStatGroup thrput;

    /**
     * Cursor constructor. Not public. To get a cursor, the user should call
     * SecondaryDatabase.cursor();
     */
    SecondaryCursor(final SecondaryDatabase dbHandle,
                    final Transaction txn,
                    final CursorConfig cursorConfig)
        throws DatabaseException {

        super(dbHandle, txn, cursorConfig);
        secondaryDb = dbHandle;
        thrput = dbHandle.getEnvironment().
                     getEnvironmentImpl().getThroughputStatGroup();
    }

    /**
     * Cursor constructor. Not public. To get a cursor, the user should call
     * SecondaryDatabase.cursor();
     */
    SecondaryCursor(final SecondaryDatabase dbHandle,
                    final Locker locker,
                    final CursorConfig cursorConfig)
        throws DatabaseException {

        super(dbHandle, locker, cursorConfig);
        secondaryDb = dbHandle;
    }

    /**
     * Copy constructor.
     */
    private SecondaryCursor(final SecondaryCursor cursor,
                            final boolean samePosition)
        throws DatabaseException {

        super(cursor, samePosition);
        secondaryDb = cursor.secondaryDb;
        thrput = cursor.thrput;
    }

    /**
     * Returns the Database handle associated with this Cursor.
     *
     * @return The Database handle associated with this Cursor.
     */
    @Override
    public SecondaryDatabase getDatabase() {
        return secondaryDb;
    }

    /**
     * Returns the primary {@link com.sleepycat.je.Database Database}
     * associated with this cursor.
     *
     * <p>Calling this method is the equivalent of the following
     * expression:</p>
     *
     * <blockquote><pre>
     *         getDatabase().getPrimaryDatabase()
     * </pre></blockquote>
     *
     * @return The primary {@link com.sleepycat.je.Database Database}
     * associated with this cursor.
     */
    /*
     * To be added when SecondaryAssociation is published:
     * If a {@link SecondaryAssociation} is {@link
     * SecondaryCursor#setSecondaryAssociation configured}, this method returns
     * null.
     */
    public Database getPrimaryDatabase() {
        return secondaryDb.getPrimaryDatabase();
    }

    /**
     * Returns a new <code>SecondaryCursor</code> for the same transaction as
     * the original cursor.
     *
     * <!-- inherit other javadoc from overridden method -->
     */
    @Override
    public SecondaryCursor dup(final boolean samePosition)
        throws DatabaseException {

        checkState(false);
        return new SecondaryCursor(this, samePosition);
    }

    /**
     * Returns a new copy of the cursor as a <code>SecondaryCursor</code>.
     *
     * <p>Calling this method is the equivalent of calling {@link #dup} and
     * casting the result to {@link SecondaryCursor}.</p>
     *
     * @see #dup
     *
     * @deprecated As of JE 4.0.13, replaced by {@link Cursor#dup}.</p>
     */
    public SecondaryCursor dupSecondary(final boolean samePosition)
        throws DatabaseException {

        return dup(samePosition);
    }

    /**
     * Delete the key/data pair to which the cursor refers from the primary
     * database and all secondary indices.
     *
     * <p>This method behaves as if {@link Database#delete} were called for the
     * primary database, using the primary key associated with this cursor
     * position.
     *
     * The cursor position is unchanged after a delete, and subsequent calls to
     * cursor functions expecting the cursor to refer to an existing key will
     * fail.
     *
     * <!-- inherit other javadoc from overridden method -->
     */
    @Override
    public OperationStatus delete()
        throws LockConflictException,
               DatabaseException,
               UnsupportedOperationException,
               IllegalStateException {

        checkState(true);
        checkUpdatesAllowed("delete");
        trace(Level.FINEST, "SecondaryCursor.delete: ", null);
        if (thrput != null) {
            thrput.increment
                (ThroughputStatGroup.SECONDARYCURSOR_DELETE_OFFSET);
        }

        /* Read the primary key (the data of a secondary). */
        final DatabaseEntry key = new DatabaseEntry();
        final DatabaseEntry pKey = new DatabaseEntry();
        OperationStatus status = getCurrentInternal(key, pKey, LockMode.RMW);

        /* Delete the primary and all secondaries (including this one). */
        if (status == OperationStatus.SUCCESS) {
            final Locker locker = cursorImpl.getLocker();
            final Database primaryDb = secondaryDb.getPrimary(pKey);
            if (primaryDb == null) {
                /* Primary was removed from the association. */
                deleteNoNotify(getDatabaseImpl().getRepContext());
            } else {
                status = primaryDb.deleteInternal(locker, pKey);
                if (status != OperationStatus.SUCCESS) {
                    throw secondaryDb.secondaryRefersToMissingPrimaryKey
                        (locker, key, pKey);
                }
            }
        }
        return status;
    }

    /**
     * This operation is not allowed on a secondary database. {@link
     * UnsupportedOperationException} will always be thrown by this method.
     * The corresponding method on the primary database should be used instead.
     */
    @Override
    public OperationStatus put(final DatabaseEntry key,
                               final DatabaseEntry data) {
        throw SecondaryDatabase.notAllowedException();
    }

    /**
     * This operation is not allowed on a secondary database. {@link
     * UnsupportedOperationException} will always be thrown by this method.
     * The corresponding method on the primary database should be used instead.
     */
    @Override
    public OperationStatus putNoOverwrite(final DatabaseEntry key,
                                          final DatabaseEntry data) {
        throw SecondaryDatabase.notAllowedException();
    }

    /**
     * This operation is not allowed on a secondary database. {@link
     * UnsupportedOperationException} will always be thrown by this method.
     * The corresponding method on the primary database should be used instead.
     */
    @Override
    public OperationStatus putNoDupData(final DatabaseEntry key,
                                        final DatabaseEntry data) {
        throw SecondaryDatabase.notAllowedException();
    }

    /**
     * This operation is not allowed on a secondary database. {@link
     * UnsupportedOperationException} will always be thrown by this method.
     * The corresponding method on the primary database should be used instead.
     */
    @Override
    public OperationStatus putCurrent(final DatabaseEntry data) {
        throw SecondaryDatabase.notAllowedException();
    }

    /**
     * @param key the secondary key returned as output.  Its byte array does
     * not need to be initialized by the caller.
     *
     * @param data the primary data returned as output.  Its byte array does
     * not need to be initialized by the caller.
     * A <a href="Cursor.html#partialEntry">partial data item</a> may be
     * specified to optimize for key only or partial data retrieval.
     *
     * <!-- inherit other javadoc from overridden method -->
     */
    @Override
    public OperationStatus getCurrent(final DatabaseEntry key,
                                      final DatabaseEntry data,
                                      final LockMode lockMode)
        throws DatabaseException {

        return getCurrent(key, new DatabaseEntry(), data, lockMode);
    }

    /**
     * Returns the key/data pair to which the cursor refers.
     *
     * <p>If this method fails for any reason, the position of the cursor will
     * be unchanged.</p>
     *
     * <p>In a replicated environment, an explicit transaction must have been
     * specified when opening the cursor, unless read-uncommitted isolation is
     * specified via the {@link CursorConfig} or {@link LockMode}
     * parameter.</p>
     *
     * @param key the secondary key returned as output.  Its byte array does
     * not need to be initialized by the caller.
     *
     * @param data the primary data returned as output.  Its byte array does
     * not need to be initialized by the caller.
     * A <a href="Cursor.html#partialEntry">partial data item</a> may be
     * specified to optimize for key only or partial data retrieval.
     *
     * @param lockMode the locking attributes; if null, default attributes are
     * used. {@link LockMode#READ_COMMITTED} is not allowed.
     *
     * @return {@link com.sleepycat.je.OperationStatus#KEYEMPTY
     * OperationStatus.KEYEMPTY} if the key/pair at the cursor position has
     * been deleted; otherwise, {@link com.sleepycat.je.OperationStatus#SUCCESS
     * OperationStatus.SUCCESS}.
     *
     * @throws OperationFailureException if one of the <a
     * href="OperationFailureException.html#readFailures">Read Operation
     * Failures</a> occurs.
     *
     * @throws EnvironmentFailureException if an unexpected, internal or
     * environment-wide failure occurs.
     *
     * @throws IllegalStateException if the cursor or database has been closed,
     * or the cursor is uninitialized (not positioned on a record), or the
     * non-transactional cursor was created in a different thread.
     *
     * @throws IllegalArgumentException if an invalid parameter is specified,
     * for example, if a DatabaseEntry parameter is null or does not contain a
     * required non-null byte array.
     */
    public OperationStatus getCurrent(final DatabaseEntry key,
                                      final DatabaseEntry pKey,
                                      final DatabaseEntry data,
                                      final LockMode lockMode)
        throws DatabaseException {

        checkState(true);
        checkArgsNoValRequired(key, pKey, data);
        trace(Level.FINEST, "SecondaryCursor.getCurrent: ", lockMode);
        if (thrput != null) {
            thrput.increment
                (ThroughputStatGroup.SECONDARYCURSOR_GETCURRENT_OFFSET);
        }

        return getCurrentInternal(key, pKey, data, lockMode);
    }

    /**
     * @param key the secondary key returned as output.  Its byte array does
     * not need to be initialized by the caller.
     *
     * @param data the primary data returned as output.  Its byte array does
     * not need to be initialized by the caller.
     * A <a href="Cursor.html#partialEntry">partial data item</a> may be
     * specified to optimize for key only or partial data retrieval.
     *
     * <!-- inherit other javadoc from overridden method -->
     */
    @Override
    public OperationStatus getFirst(final DatabaseEntry key,
                                    final DatabaseEntry data,
                                    final LockMode lockMode)
        throws DatabaseException {

        return getFirst(key, new DatabaseEntry(), data, lockMode);
    }

    /**
     * Move the cursor to the first key/data pair of the database, and return
     * that pair.  If the first key has duplicate values, the first data item
     * in the set of duplicates is returned.
     *
     * <p>If this method fails for any reason, the position of the cursor will
     * be unchanged.</p>
     *
     * <p>In a replicated environment, an explicit transaction must have been
     * specified when opening the cursor, unless read-uncommitted isolation is
     * specified via the {@link CursorConfig} or {@link LockMode}
     * parameter.</p>
     *
     * @param key the secondary key returned as output.  Its byte array does
     * not need to be initialized by the caller.
     *
     * @param pKey the primary key returned as output.  Its byte array does not
     * need to be initialized by the caller.
     *
     * @param data the primary data returned as output.  Its byte array does
     * not need to be initialized by the caller.
     * A <a href="Cursor.html#partialEntry">partial data item</a> may be
     * specified to optimize for key only or partial data retrieval.
     *
     * @param lockMode the locking attributes; if null, default attributes are
     * used. {@link LockMode#READ_COMMITTED} is not allowed.
     *
     * @return {@link com.sleepycat.je.OperationStatus#NOTFOUND
     * OperationStatus.NOTFOUND} if no matching key/data pair is found;
     * otherwise, {@link com.sleepycat.je.OperationStatus#SUCCESS
     * OperationStatus.SUCCESS}.
     *
     * @throws OperationFailureException if one of the <a
     * href="OperationFailureException.html#readFailures">Read Operation
     * Failures</a> occurs.
     *
     * @throws EnvironmentFailureException if an unexpected, internal or
     * environment-wide failure occurs.
     *
     * @throws IllegalStateException if the cursor or database has been closed,
     * or the non-transactional cursor was created in a different thread.
     *
     * @throws IllegalArgumentException if an invalid parameter is specified,
     * for example, if a DatabaseEntry parameter is null or does not contain a
     * required non-null byte array.
     */
    public OperationStatus getFirst(final DatabaseEntry key,
                                    final DatabaseEntry pKey,
                                    final DatabaseEntry data,
                                    final LockMode lockMode)
        throws DatabaseException {

        checkState(false);
        checkArgsNoValRequired(key, pKey, data);
        trace(Level.FINEST, "SecondaryCursor.getFirst: ", lockMode);
        if (thrput != null) {
            thrput.increment
                (ThroughputStatGroup.SECONDARYCURSOR_GETFIRST_OFFSET);
        }

        return position(key, pKey, data, lockMode, true);
    }

    /**
     * @param key the secondary key returned as output.  Its byte array does
     * not need to be initialized by the caller.
     *
     * @param data the primary data returned as output.  Its byte array does
     * not need to be initialized by the caller.
     * A <a href="Cursor.html#partialEntry">partial data item</a> may be
     * specified to optimize for key only or partial data retrieval.
     *
     * <!-- inherit other javadoc from overridden method -->
     */
    @Override
    public OperationStatus getLast(final DatabaseEntry key,
                                   final DatabaseEntry data,
                                   final LockMode lockMode)
        throws DatabaseException {

        return getLast(key, new DatabaseEntry(), data, lockMode);
    }

    /**
     * Move the cursor to the last key/data pair of the database, and return
     * that pair.  If the last key has duplicate values, the last data item in
     * the set of duplicates is returned.
     *
     * <p>If this method fails for any reason, the position of the cursor will
     * be unchanged.</p>
     *
     * <p>In a replicated environment, an explicit transaction must have been
     * specified when opening the cursor, unless read-uncommitted isolation is
     * specified via the {@link CursorConfig} or {@link LockMode}
     * parameter.</p>
     *
     * @param key the secondary key returned as output.  Its byte array does
     * not need to be initialized by the caller.
     *
     * @param pKey the primary key returned as output.  Its byte array does not
     * need to be initialized by the caller.
     *
     * @param data the primary data returned as output.  Its byte array does
     * not need to be initialized by the caller.
     * A <a href="Cursor.html#partialEntry">partial data item</a> may be
     * specified to optimize for key only or partial data retrieval.
     *
     * @param lockMode the locking attributes; if null, default attributes are
     * used. {@link LockMode#READ_COMMITTED} is not allowed.
     *
     * @return {@link com.sleepycat.je.OperationStatus#NOTFOUND
     * OperationStatus.NOTFOUND} if no matching key/data pair is found;
     * otherwise, {@link com.sleepycat.je.OperationStatus#SUCCESS
     * OperationStatus.SUCCESS}.
     *
     * @throws OperationFailureException if one of the <a
     * href="OperationFailureException.html#readFailures">Read Operation
     * Failures</a> occurs.
     *
     * @throws EnvironmentFailureException if an unexpected, internal or
     * environment-wide failure occurs.
     *
     * @throws IllegalStateException if the cursor or database has been closed,
     * or the non-transactional cursor was created in a different thread.
     *
     * @throws IllegalArgumentException if an invalid parameter is specified,
     * for example, if a DatabaseEntry parameter is null or does not contain a
     * required non-null byte array.
     */
    public OperationStatus getLast(final DatabaseEntry key,
                                   final DatabaseEntry pKey,
                                   final DatabaseEntry data,
                                   final LockMode lockMode)
        throws DatabaseException {

        checkState(false);
        checkArgsNoValRequired(key, pKey, data);
        trace(Level.FINEST, "SecondaryCursor.getLast: ", lockMode);
        if (thrput != null) {
            thrput.increment
                (ThroughputStatGroup.SECONDARYCURSOR_GETLAST_OFFSET);
        }

        return position(key, pKey, data, lockMode, false);
    }

    /**
     * @param key the secondary key returned as output.  Its byte array does
     * not need to be initialized by the caller.
     *
     * @param data the primary data returned as output.  Its byte array does
     * not need to be initialized by the caller.
     * A <a href="Cursor.html#partialEntry">partial data item</a> may be
     * specified to optimize for key only or partial data retrieval.
     *
     * <!-- inherit other javadoc from overridden method -->
     */
    @Override
    public OperationStatus getNext(final DatabaseEntry key,
                                   final DatabaseEntry data,
                                   final LockMode lockMode)
        throws DatabaseException {

        return getNext(key, new DatabaseEntry(), data, lockMode);
    }

    /**
     * Move the cursor to the next key/data pair and return that pair.  If the
     * matching key has duplicate values, the first data item in the set of
     * duplicates is returned.
     *
     * <p>If the cursor is not yet initialized, move the cursor to the first
     * key/data pair of the database, and return that pair.  Otherwise, the
     * cursor is moved to the next key/data pair of the database, and that pair
     * is returned.  In the presence of duplicate key values, the value of the
     * key may not change.</p>
     *
     * <p>If this method fails for any reason, the position of the cursor will
     * be unchanged.</p>
     *
     * <p>In a replicated environment, an explicit transaction must have been
     * specified when opening the cursor, unless read-uncommitted isolation is
     * specified via the {@link CursorConfig} or {@link LockMode}
     * parameter.</p>
     *
     * @param key the secondary key returned as output.  Its byte array does
     * not need to be initialized by the caller.
     *
     * @param pKey the primary key returned as output.  Its byte array does not
     * need to be initialized by the caller.
     *
     * @param data the primary data returned as output.  Its byte array does
     * not need to be initialized by the caller.
     * A <a href="Cursor.html#partialEntry">partial data item</a> may be
     * specified to optimize for key only or partial data retrieval.
     *
     * @param lockMode the locking attributes; if null, default attributes are
     * used. {@link LockMode#READ_COMMITTED} is not allowed.
     *
     * @return {@link com.sleepycat.je.OperationStatus#NOTFOUND
     * OperationStatus.NOTFOUND} if no matching key/data pair is found;
     * otherwise, {@link com.sleepycat.je.OperationStatus#SUCCESS
     * OperationStatus.SUCCESS}.
     *
     * @throws OperationFailureException if one of the <a
     * href="OperationFailureException.html#readFailures">Read Operation
     * Failures</a> occurs.
     *
     * @throws EnvironmentFailureException if an unexpected, internal or
     * environment-wide failure occurs.
     *
     * @throws IllegalStateException if the cursor or database has been closed,
     * or the non-transactional cursor was created in a different thread.
     *
     * @throws IllegalArgumentException if an invalid parameter is specified,
     * for example, if a DatabaseEntry parameter is null or does not contain a
     * required non-null byte array.
     */
    public OperationStatus getNext(final DatabaseEntry key,
                                   final DatabaseEntry pKey,
                                   final DatabaseEntry data,
                                   final LockMode lockMode)
        throws DatabaseException {

        checkState(false);
        checkArgsNoValRequired(key, pKey, data);
        trace(Level.FINEST, "SecondaryCursor.getNext: ", lockMode);
        if (thrput != null) {
            thrput.increment
                (ThroughputStatGroup.SECONDARYCURSOR_GETNEXT_OFFSET);
        }

        if (cursorImpl.isNotInitialized()) {
            return position(key, pKey, data, lockMode, true);
        } else {
            return retrieveNext(key, pKey, data, lockMode, GetMode.NEXT);
        }
    }

    /**
     * @param key the secondary key returned as output.  Its byte array does
     * not need to be initialized by the caller.
     *
     * @param data the primary data returned as output.  Its byte array does
     * not need to be initialized by the caller.
     * A <a href="Cursor.html#partialEntry">partial data item</a> may be
     * specified to optimize for key only or partial data retrieval.
     *
     * <!-- inherit other javadoc from overridden method -->
     */
    @Override
    public OperationStatus getNextDup(final DatabaseEntry key,
                                      final DatabaseEntry data,
                                      final LockMode lockMode)
        throws DatabaseException {

        return getNextDup(key, new DatabaseEntry(), data, lockMode);
    }

    /**
     * If the next key/data pair of the database is a duplicate data record for
     * the current key/data pair, move the cursor to the next key/data pair of
     * the database and return that pair.
     *
     * <p>If this method fails for any reason, the position of the cursor will
     * be unchanged.</p>
     *
     * <p>In a replicated environment, an explicit transaction must have been
     * specified when opening the cursor, unless read-uncommitted isolation is
     * specified via the {@link CursorConfig} or {@link LockMode}
     * parameter.</p>
     *
     * @param key the secondary key returned as output.  Its byte array does
     * not need to be initialized by the caller.
     *
     * @param pKey the primary key returned as output.  Its byte array does not
     * need to be initialized by the caller.
     *
     * @param data the primary data returned as output.  Its byte array does
     * not need to be initialized by the caller.
     * A <a href="Cursor.html#partialEntry">partial data item</a> may be
     * specified to optimize for key only or partial data retrieval.
     *
     * @param lockMode the locking attributes; if null, default attributes are
     * used. {@link LockMode#READ_COMMITTED} is not allowed.
     *
     * @return {@link com.sleepycat.je.OperationStatus#NOTFOUND
     * OperationStatus.NOTFOUND} if no matching key/data pair is found;
     * otherwise, {@link com.sleepycat.je.OperationStatus#SUCCESS
     * OperationStatus.SUCCESS}.
     *
     * @throws OperationFailureException if one of the <a
     * href="OperationFailureException.html#readFailures">Read Operation
     * Failures</a> occurs.
     *
     * @throws EnvironmentFailureException if an unexpected, internal or
     * environment-wide failure occurs.
     *
     * @throws IllegalStateException if the cursor or database has been closed,
     * or the cursor is uninitialized (not positioned on a record), or the
     * non-transactional cursor was created in a different thread.
     *
     * @throws IllegalArgumentException if an invalid parameter is specified,
     * for example, if a DatabaseEntry parameter is null or does not contain a
     * required non-null byte array.
     */
    public OperationStatus getNextDup(final DatabaseEntry key,
                                      final DatabaseEntry pKey,
                                      final DatabaseEntry data,
                                      final LockMode lockMode)
        throws DatabaseException {

        checkState(true);
        checkArgsNoValRequired(key, pKey, data);
        trace(Level.FINEST, "SecondaryCursor.getNextDup: ", lockMode);
        if (thrput != null) {
            thrput.increment
                (ThroughputStatGroup.SECONDARYCURSOR_GETNEXTDUP_OFFSET);
        }

        return retrieveNext(key, pKey, data, lockMode, GetMode.NEXT_DUP);
    }

    /**
     * @param key the secondary key returned as output.  Its byte array does
     * not need to be initialized by the caller.
     *
     * @param data the primary data returned as output.  Its byte array does
     * not need to be initialized by the caller.
     * A <a href="Cursor.html#partialEntry">partial data item</a> may be
     * specified to optimize for key only or partial data retrieval.
     *
     * <!-- inherit other javadoc from overridden method -->
     */
    @Override
    public OperationStatus getNextNoDup(final DatabaseEntry key,
                                        final DatabaseEntry data,
                                        final LockMode lockMode)
        throws DatabaseException {

        return getNextNoDup(key, new DatabaseEntry(), data, lockMode);
    }

    /**
     * Move the cursor to the next non-duplicate key/data pair and return that
     * pair.  If the matching key has duplicate values, the first data item in
     * the set of duplicates is returned.
     *
     * <p>If the cursor is not yet initialized, move the cursor to the first
     * key/data pair of the database, and return that pair.  Otherwise, the
     * cursor is moved to the next non-duplicate key of the database, and that
     * key/data pair is returned.</p>
     *
     * <p>If this method fails for any reason, the position of the cursor will
     * be unchanged.</p>
     *
     * <p>In a replicated environment, an explicit transaction must have been
     * specified when opening the cursor, unless read-uncommitted isolation is
     * specified via the {@link CursorConfig} or {@link LockMode}
     * parameter.</p>
     *
     * @param key the secondary key returned as output.  Its byte array does
     * not need to be initialized by the caller.
     *
     * @param pKey the primary key returned as output.  Its byte array does not
     * need to be initialized by the caller.
     *
     * @param data the primary data returned as output.  Its byte array does
     * not need to be initialized by the caller.
     * A <a href="Cursor.html#partialEntry">partial data item</a> may be
     * specified to optimize for key only or partial data retrieval.
     *
     * @param lockMode the locking attributes; if null, default attributes are
     * used. {@link LockMode#READ_COMMITTED} is not allowed.
     *
     * @return {@link com.sleepycat.je.OperationStatus#NOTFOUND
     * OperationStatus.NOTFOUND} if no matching key/data pair is found;
     * otherwise, {@link com.sleepycat.je.OperationStatus#SUCCESS
     * OperationStatus.SUCCESS}.
     *
     * @throws OperationFailureException if one of the <a
     * href="OperationFailureException.html#readFailures">Read Operation
     * Failures</a> occurs.
     *
     * @throws EnvironmentFailureException if an unexpected, internal or
     * environment-wide failure occurs.
     *
     * @throws IllegalStateException if the cursor or database has been closed,
     * or the non-transactional cursor was created in a different thread.
     *
     * @throws IllegalArgumentException if an invalid parameter is specified,
     * for example, if a DatabaseEntry parameter is null or does not contain a
     * required non-null byte array.
     */
    public OperationStatus getNextNoDup(final DatabaseEntry key,
                                        final DatabaseEntry pKey,
                                        final DatabaseEntry data,
                                        final LockMode lockMode)
        throws DatabaseException {

        checkState(false);
        checkArgsNoValRequired(key, pKey, data);
        trace(Level.FINEST, "SecondaryCursor.getNextNoDup: ", null, null,
              lockMode);
        if (thrput != null) {
            thrput.increment
                (ThroughputStatGroup.SECONDARYCURSOR_GETNEXTNODUP_OFFSET);
        }

        if (cursorImpl.isNotInitialized()) {
            return position(key, pKey, data, lockMode, true);
        } else {
            return retrieveNext(key, pKey, data, lockMode,
                                GetMode.NEXT_NODUP);
        }
    }

    /**
     * @param key the secondary key returned as output.  Its byte array does
     * not need to be initialized by the caller.
     *
     * @param data the primary data returned as output.  Its byte array does
     * not need to be initialized by the caller.
     * A <a href="Cursor.html#partialEntry">partial data item</a> may be
     * specified to optimize for key only or partial data retrieval.
     *
     * @throws OperationFailureException if one of the <a
     * href="OperationFailureException.html#readFailures">Read Operation
     * Failures</a> occurs.
     *
     * <!-- inherit other javadoc from overridden method -->
     */
    @Override
    public OperationStatus getPrev(final DatabaseEntry key,
                                   final DatabaseEntry data,
                                   final LockMode lockMode)
        throws DatabaseException {

        return getPrev(key, new DatabaseEntry(), data, lockMode);
    }

    /**
     * Move the cursor to the previous key/data pair and return that pair. If
     * the matching key has duplicate values, the last data item in the set of
     * duplicates is returned.
     *
     * <p>If the cursor is not yet initialized, move the cursor to the last
     * key/data pair of the database, and return that pair.  Otherwise, the
     * cursor is moved to the previous key/data pair of the database, and that
     * pair is returned. In the presence of duplicate key values, the value of
     * the key may not change.</p>
     *
     * <p>If this method fails for any reason, the position of the cursor will
     * be unchanged.</p>
     *
     * <p>In a replicated environment, an explicit transaction must have been
     * specified when opening the cursor, unless read-uncommitted isolation is
     * specified via the {@link CursorConfig} or {@link LockMode}
     * parameter.</p>
     *
     * @param key the secondary key returned as output.  Its byte array does
     * not need to be initialized by the caller.
     *
     * @param pKey the primary key returned as output.  Its byte array does not
     * need to be initialized by the caller.
     *
     * @param data the primary data returned as output.  Its byte array does
     * not need to be initialized by the caller.
     * A <a href="Cursor.html#partialEntry">partial data item</a> may be
     * specified to optimize for key only or partial data retrieval.
     *
     * @param lockMode the locking attributes; if null, default attributes are
     * used. {@link LockMode#READ_COMMITTED} is not allowed.
     *
     * @return {@link com.sleepycat.je.OperationStatus#NOTFOUND
     * OperationStatus.NOTFOUND} if no matching key/data pair is found;
     * otherwise, {@link com.sleepycat.je.OperationStatus#SUCCESS
     * OperationStatus.SUCCESS}.
     *
     * @throws OperationFailureException if one of the <a
     * href="OperationFailureException.html#readFailures">Read Operation
     * Failures</a> occurs.
     *
     * @throws EnvironmentFailureException if an unexpected, internal or
     * environment-wide failure occurs.
     *
     * @throws IllegalStateException if the cursor or database has been closed,
     * or the non-transactional cursor was created in a different thread.
     *
     * @throws IllegalArgumentException if an invalid parameter is specified,
     * for example, if a DatabaseEntry parameter is null or does not contain a
     * required non-null byte array.
     */
    public OperationStatus getPrev(final DatabaseEntry key,
                                   final DatabaseEntry pKey,
                                   final DatabaseEntry data,
                                   final LockMode lockMode)
        throws DatabaseException {

        checkState(false);
        checkArgsNoValRequired(key, pKey, data);
        trace(Level.FINEST, "SecondaryCursor.getPrev: ", lockMode);
        if (thrput != null) {
            thrput.increment
                (ThroughputStatGroup.SECONDARYCURSOR_GETPREV_OFFSET);
        }

        if (cursorImpl.isNotInitialized()) {
            return position(key, pKey, data, lockMode, false);
        } else {
            return retrieveNext(key, pKey, data, lockMode, GetMode.PREV);
        }
    }

    /**
     * @param key the secondary key returned as output.  Its byte array does
     * not need to be initialized by the caller.
     *
     * @param data the primary data returned as output.  Its byte array does
     * not need to be initialized by the caller.
     * A <a href="Cursor.html#partialEntry">partial data item</a> may be
     * specified to optimize for key only or partial data retrieval.
     *
     * @throws OperationFailureException if one of the <a
     * href="OperationFailureException.html#readFailures">Read Operation
     * Failures</a> occurs.
     *
     * <!-- inherit other javadoc from overridden method -->
     */
    @Override
    public OperationStatus getPrevDup(final DatabaseEntry key,
                                      final DatabaseEntry data,
                                      final LockMode lockMode)
        throws DatabaseException {

        return getPrevDup(key, new DatabaseEntry(), data, lockMode);
    }

    /**
     * If the previous key/data pair of the database is a duplicate data record
     * for the current key/data pair, move the cursor to the previous key/data
     * pair of the database and return that pair.
     *
     * <p>If this method fails for any reason, the position of the cursor will
     * be unchanged.</p>
     *
     * <p>In a replicated environment, an explicit transaction must have been
     * specified when opening the cursor, unless read-uncommitted isolation is
     * specified via the {@link CursorConfig} or {@link LockMode}
     * parameter.</p>
     *
     * @param key the secondary key returned as output.  Its byte array does
     * not need to be initialized by the caller.
     *
     * @param pKey the primary key returned as output.  Its byte array does not
     * need to be initialized by the caller.
     *
     * @param data the primary data returned as output.  Its byte array does
     * not need to be initialized by the caller.
     * A <a href="Cursor.html#partialEntry">partial data item</a> may be
     * specified to optimize for key only or partial data retrieval.
     *
     * @param lockMode the locking attributes; if null, default attributes are
     * used. {@link LockMode#READ_COMMITTED} is not allowed.
     *
     * @return {@link com.sleepycat.je.OperationStatus#NOTFOUND
     * OperationStatus.NOTFOUND} if no matching key/data pair is found;
     * otherwise, {@link com.sleepycat.je.OperationStatus#SUCCESS
     * OperationStatus.SUCCESS}.
     *
     * @throws OperationFailureException if one of the <a
     * href="OperationFailureException.html#readFailures">Read Operation
     * Failures</a> occurs.
     *
     * @throws EnvironmentFailureException if an unexpected, internal or
     * environment-wide failure occurs.
     *
     * @throws IllegalStateException if the cursor or database has been closed,
     * or the cursor is uninitialized (not positioned on a record), or the
     * non-transactional cursor was created in a different thread.
     *
     * @throws IllegalArgumentException if an invalid parameter is specified,
     * for example, if a DatabaseEntry parameter is null or does not contain a
     * required non-null byte array.
     */
    public OperationStatus getPrevDup(final DatabaseEntry key,
                                      final DatabaseEntry pKey,
                                      final DatabaseEntry data,
                                      final LockMode lockMode)
        throws DatabaseException {

        checkState(true);
        checkArgsNoValRequired(key, pKey, data);
        trace(Level.FINEST, "SecondaryCursor.getPrevDup: ", lockMode);
        if (thrput != null) {
            thrput.increment
                (ThroughputStatGroup.SECONDARYCURSOR_GETPREVDUP_OFFSET);
        }

        return retrieveNext(key, pKey, data, lockMode, GetMode.PREV_DUP);
    }

    /**
     * @param key the secondary key returned as output.  Its byte array does
     * not need to be initialized by the caller.
     *
     * @param data the primary data returned as output.  Its byte array does
     * not need to be initialized by the caller.
     * A <a href="Cursor.html#partialEntry">partial data item</a> may be
     * specified to optimize for key only or partial data retrieval.
     *
     * @throws OperationFailureException if one of the <a
     * href="OperationFailureException.html#readFailures">Read Operation
     * Failures</a> occurs.
     *
     * <!-- inherit other javadoc from overridden method -->
     */
    @Override
    public OperationStatus getPrevNoDup(final DatabaseEntry key,
                                        final DatabaseEntry data,
                                        final LockMode lockMode)
        throws DatabaseException {

        return getPrevNoDup(key, new DatabaseEntry(), data, lockMode);
    }

    /**
     * Move the cursor to the previous non-duplicate key/data pair and return
     * that pair.  If the matching key has duplicate values, the last data item
     * in the set of duplicates is returned.
     *
     * <p>If the cursor is not yet initialized, move the cursor to the last
     * key/data pair of the database, and return that pair.  Otherwise, the
     * cursor is moved to the previous non-duplicate key of the database, and
     * that key/data pair is returned.</p>
     *
     * <p>If this method fails for any reason, the position of the cursor will
     * be unchanged.</p>
     *
     * <p>In a replicated environment, an explicit transaction must have been
     * specified when opening the cursor, unless read-uncommitted isolation is
     * specified via the {@link CursorConfig} or {@link LockMode}
     * parameter.</p>
     *
     * @param key the secondary key returned as output.  Its byte array does
     * not need to be initialized by the caller.
     *
     * @param pKey the primary key returned as output.  Its byte array does not
     * need to be initialized by the caller.
     *
     * @param data the primary data returned as output.  Its byte array does
     * not need to be initialized by the caller.
     * A <a href="Cursor.html#partialEntry">partial data item</a> may be
     * specified to optimize for key only or partial data retrieval.
     *
     * @param lockMode the locking attributes; if null, default attributes are
     * used. {@link LockMode#READ_COMMITTED} is not allowed.
     *
     * @return {@link com.sleepycat.je.OperationStatus#NOTFOUND
     * OperationStatus.NOTFOUND} if no matching key/data pair is found;
     * otherwise, {@link com.sleepycat.je.OperationStatus#SUCCESS
     * OperationStatus.SUCCESS}.
     *
     * @throws OperationFailureException if one of the <a
     * href="OperationFailureException.html#readFailures">Read Operation
     * Failures</a> occurs.
     *
     * @throws EnvironmentFailureException if an unexpected, internal or
     * environment-wide failure occurs.
     *
     * @throws IllegalStateException if the cursor or database has been closed,
     * or the non-transactional cursor was created in a different thread.
     *
     * @throws IllegalArgumentException if an invalid parameter is specified,
     * for example, if a DatabaseEntry parameter is null or does not contain a
     * required non-null byte array.
     */
    public OperationStatus getPrevNoDup(final DatabaseEntry key,
                                        final DatabaseEntry pKey,
                                        final DatabaseEntry data,
                                        final LockMode lockMode)
        throws DatabaseException {

        checkState(false);
        checkArgsNoValRequired(key, pKey, data);
        trace(Level.FINEST, "SecondaryCursor.getPrevNoDup: ", lockMode);
        if (thrput != null) {
            thrput.increment
                (ThroughputStatGroup.SECONDARYCURSOR_GETPREVNODUP_OFFSET);
        }

        if (cursorImpl.isNotInitialized()) {
            return position(key, pKey, data, lockMode, false);
        } else {
            return retrieveNext(key, pKey, data, lockMode,
                                GetMode.PREV_NODUP);
        }
    }

    /**
     * @param key the secondary key used as input.  It must be initialized with
     * a non-null byte array by the caller.
     *
     * @param data the primary data returned as output.  Its byte array does
     * not need to be initialized by the caller.
     * A <a href="Cursor.html#partialEntry">partial data item</a> may be
     * specified to optimize for key only or partial data retrieval.
     *
     * @throws OperationFailureException if one of the <a
     * href="OperationFailureException.html#readFailures">Read Operation
     * Failures</a> occurs.
     *
     * <!-- inherit other javadoc from overridden method -->
     */
    @Override
    public OperationStatus getSearchKey(final DatabaseEntry key,
                                        final DatabaseEntry data,
                                        final LockMode lockMode)
        throws DatabaseException {

        return getSearchKey(key, new DatabaseEntry(), data, lockMode);
    }

    /**
     * Move the cursor to the given key of the database, and return the datum
     * associated with the given key.  If the matching key has duplicate
     * values, the first data item in the set of duplicates is returned.
     *
     * <p>If this method fails for any reason, the position of the cursor will
     * be unchanged.</p>
     *
     * <p>In a replicated environment, an explicit transaction must have been
     * specified when opening the cursor, unless read-uncommitted isolation is
     * specified via the {@link CursorConfig} or {@link LockMode}
     * parameter.</p>
     *
     * @param key the secondary key used as input.  It must be initialized with
     * a non-null byte array by the caller.
     *
     * @param pKey the primary key returned as output.  Its byte array does not
     * need to be initialized by the caller.
     *
     * @param data the primary data returned as output.  Its byte array does
     * not need to be initialized by the caller.
     * A <a href="Cursor.html#partialEntry">partial data item</a> may be
     * specified to optimize for key only or partial data retrieval.
     *
     * @param lockMode the locking attributes; if null, default attributes are
     * used. {@link LockMode#READ_COMMITTED} is not allowed.
     *
     * @return {@link com.sleepycat.je.OperationStatus#NOTFOUND
     * OperationStatus.NOTFOUND} if no matching key/data pair is found;
     * otherwise, {@link com.sleepycat.je.OperationStatus#SUCCESS
     * OperationStatus.SUCCESS}.
     *
     * @throws OperationFailureException if one of the <a
     * href="OperationFailureException.html#readFailures">Read Operation
     * Failures</a> occurs.
     *
     * @throws EnvironmentFailureException if an unexpected, internal or
     * environment-wide failure occurs.
     *
     * @throws IllegalStateException if the cursor or database has been closed,
     * or the non-transactional cursor was created in a different thread.
     *
     * @throws IllegalArgumentException if an invalid parameter is specified,
     * for example, if a DatabaseEntry parameter is null or does not contain a
     * required non-null byte array.
     */
    public OperationStatus getSearchKey(final DatabaseEntry key,
                                        final DatabaseEntry pKey,
                                        final DatabaseEntry data,
                                        final LockMode lockMode)
        throws DatabaseException {

        checkState(false);
        DatabaseUtil.checkForNullDbt(key, "key", true);
        DatabaseUtil.checkForNullDbt(pKey, "pKey", false);
        DatabaseUtil.checkForNullDbt(data, "data", false);
        trace(Level.FINEST, "SecondaryCursor.getSearchKey: ", key, null,
              lockMode);

        return search(key, pKey, data, lockMode, SearchMode.SET);
    }

    /**
     * @param key the secondary key used as input and returned as output.  It
     * must be initialized with a non-null byte array by the caller.
     *
     * @param data the primary data returned as output.  Its byte array does
     * not need to be initialized by the caller.
     * A <a href="Cursor.html#partialEntry">partial data item</a> may be
     * specified to optimize for key only or partial data retrieval.
     *
     * <!-- inherit other javadoc from overridden method -->
     */
    @Override
    public OperationStatus getSearchKeyRange(final DatabaseEntry key,
                                             final DatabaseEntry data,
                                             final LockMode lockMode)
        throws DatabaseException {

        return getSearchKeyRange(key, new DatabaseEntry(), data, lockMode);
    }

    /**
     * Move the cursor to the closest matching key of the database, and return
     * the data item associated with the matching key.  If the matching key has
     * duplicate values, the first data item in the set of duplicates is
     * returned.
     *
     * <p>The returned key/data pair is for the smallest key greater than or
     * equal to the specified key (as determined by the key comparison
     * function), permitting partial key matches and range searches.</p>
     *
     * <p>If this method fails for any reason, the position of the cursor will
     * be unchanged.</p>
     *
     * <p>In a replicated environment, an explicit transaction must have been
     * specified when opening the cursor, unless read-uncommitted isolation is
     * specified via the {@link CursorConfig} or {@link LockMode}
     * parameter.</p>
     *
     * @param key the secondary key used as input and returned as output.  It
     * must be initialized with a non-null byte array by the caller.
     *
     * @param pKey the primary key returned as output.  Its byte array does not
     * need to be initialized by the caller.
     *
     * @param data the primary data returned as output.  Its byte array does
     * not need to be initialized by the caller.
     * A <a href="Cursor.html#partialEntry">partial data item</a> may be
     * specified to optimize for key only or partial data retrieval.
     *
     * @param lockMode the locking attributes; if null, default attributes are
     * used. {@link LockMode#READ_COMMITTED} is not allowed.
     *
     * @return {@link com.sleepycat.je.OperationStatus#NOTFOUND
     * OperationStatus.NOTFOUND} if no matching key/data pair is found;
     * otherwise, {@link com.sleepycat.je.OperationStatus#SUCCESS
     * OperationStatus.SUCCESS}.
     *
     * @throws OperationFailureException if one of the <a
     * href="OperationFailureException.html#readFailures">Read Operation
     * Failures</a> occurs.
     *
     * @throws EnvironmentFailureException if an unexpected, internal or
     * environment-wide failure occurs.
     *
     * @throws IllegalStateException if the cursor or database has been closed,
     * or the non-transactional cursor was created in a different thread.
     *
     * @throws IllegalArgumentException if an invalid parameter is specified,
     * for example, if a DatabaseEntry parameter is null or does not contain a
     * required non-null byte array.
     */
    public OperationStatus getSearchKeyRange(final DatabaseEntry key,
                                             final DatabaseEntry pKey,
                                             final DatabaseEntry data,
                                             final LockMode lockMode)
        throws DatabaseException {

        checkState(false);
        DatabaseUtil.checkForNullDbt(key, "key", true);
        DatabaseUtil.checkForNullDbt(pKey, "pKey", false);
        DatabaseUtil.checkForNullDbt(data, "data", false);
        trace(Level.FINEST, "SecondaryCursor.getSearchKeyRange: ", key, data,
              lockMode);

        return search(key, pKey, data, lockMode, SearchMode.SET_RANGE);
    }

    /**
     * This operation is not allowed with this method signature. {@link
     * UnsupportedOperationException} will always be thrown by this method.
     * The corresponding method with the <code>pKey</code> parameter should be
     * used instead.
     */
    @Override
    public OperationStatus getSearchBoth(final DatabaseEntry key,
                                         final DatabaseEntry data,
                                         final LockMode lockMode) {
        throw SecondaryDatabase.notAllowedException();
    }

    /**
     * Move the cursor to the specified secondary and primary key, where both
     * the primary and secondary key items must match.
     *
     * <p>If this method fails for any reason, the position of the cursor will
     * be unchanged.</p>
     *
     * <p>In a replicated environment, an explicit transaction must have been
     * specified when opening the cursor, unless read-uncommitted isolation is
     * specified via the {@link CursorConfig} or {@link LockMode}
     * parameter.</p>
     *
     * @param key the secondary key used as input.  It must be initialized with
     * a non-null byte array by the caller.
     *
     * @param pKey the primary key used as input.  It must be initialized with
     * a non-null byte array by the caller.
     *
     * @param data the primary data returned as output.  Its byte array does
     * not need to be initialized by the caller.
     *
     * @param lockMode the locking attributes; if null, default attributes are
     * used. {@link LockMode#READ_COMMITTED} is not allowed.
     *
     * @return {@link com.sleepycat.je.OperationStatus#NOTFOUND
     * OperationStatus.NOTFOUND} if no matching key/data pair is found;
     * otherwise, {@link com.sleepycat.je.OperationStatus#SUCCESS
     * OperationStatus.SUCCESS}.
     *
     * @throws OperationFailureException if one of the <a
     * href="OperationFailureException.html#readFailures">Read Operation
     * Failures</a> occurs.
     *
     * @throws EnvironmentFailureException if an unexpected, internal or
     * environment-wide failure occurs.
     *
     * @throws IllegalStateException if the cursor or database has been closed,
     * or the non-transactional cursor was created in a different thread.
     *
     * @throws IllegalArgumentException if an invalid parameter is specified,
     * for example, if a DatabaseEntry parameter is null or does not contain a
     * required non-null byte array.
     */
    public OperationStatus getSearchBoth(final DatabaseEntry key,
                                         final DatabaseEntry pKey,
                                         final DatabaseEntry data,
                                         final LockMode lockMode)
        throws DatabaseException {

        checkState(false);
        DatabaseUtil.checkForNullDbt(key, "key", true);
        DatabaseUtil.checkForNullDbt(pKey, "pKey", true);
        DatabaseUtil.checkForNullDbt(data, "data", false);
        trace(Level.FINEST, "SecondaryCursor.getSearchBoth: ", key, data,
              lockMode);

        return search(key, pKey, data, lockMode, SearchMode.BOTH);
    }

    /**
     * This operation is not allowed with this method signature. {@link
     * UnsupportedOperationException} will always be thrown by this method.
     * The corresponding method with the <code>pKey</code> parameter should be
     * used instead.
     */
    @Override
    public OperationStatus getSearchBothRange(final DatabaseEntry key,
                                              final DatabaseEntry data,
                                              final LockMode lockMode) {
        throw SecondaryDatabase.notAllowedException();
    }

    /**
     * Move the cursor to the specified secondary key and closest matching
     * primary key of the database.
     *
     * <p>In the case of any database supporting sorted duplicate sets, the
     * returned key/data pair is for the smallest primary key greater than or
     * equal to the specified primary key (as determined by the key comparison
     * function), permitting partial matches and range searches in duplicate
     * data sets.</p>
     *
     * <p>If this method fails for any reason, the position of the cursor will
     * be unchanged.</p>
     *
     * <p>In a replicated environment, an explicit transaction must have been
     * specified when opening the cursor, unless read-uncommitted isolation is
     * specified via the {@link CursorConfig} or {@link LockMode}
     * parameter.</p>
     *
     * @param key the secondary key used as input.  It must be initialized with
     * a non-null byte array by the caller.
     *
     * @param pKey the primary key used as input and returned as output.  It
     * must be initialized with a non-null byte array by the caller.
     *
     * @param data the primary data returned as output.  Its byte array does
     * not need to be initialized by the caller.
     *
     * @param lockMode the locking attributes; if null, default attributes are
     * used. {@link LockMode#READ_COMMITTED} is not allowed.
     *
     * @return {@link com.sleepycat.je.OperationStatus#NOTFOUND
     * OperationStatus.NOTFOUND} if no matching key/data pair is found;
     * otherwise, {@link com.sleepycat.je.OperationStatus#SUCCESS
     * OperationStatus.SUCCESS}.
     *
     * @throws OperationFailureException if one of the <a
     * href="OperationFailureException.html#readFailures">Read Operation
     * Failures</a> occurs.
     *
     * @throws EnvironmentFailureException if an unexpected, internal or
     * environment-wide failure occurs.
     *
     * @throws IllegalStateException if the cursor or database has been closed,
     * or the non-transactional cursor was created in a different thread.
     *
     * @throws IllegalArgumentException if an invalid parameter is specified,
     * for example, if a DatabaseEntry parameter is null or does not contain a
     * required non-null byte array.
     */
    public OperationStatus getSearchBothRange(final DatabaseEntry key,
                                              final DatabaseEntry pKey,
                                              final DatabaseEntry data,
                                              final LockMode lockMode)
        throws DatabaseException {

        checkState(false);
        DatabaseUtil.checkForNullDbt(key, "key", true);
        DatabaseUtil.checkForNullDbt(pKey, "pKey", true);
        DatabaseUtil.checkForNullDbt(data, "data", false);
        trace(Level.FINEST, "SecondaryCursor.getSearchBothRange: ", key, data,
              lockMode);

        return search(key, pKey, data, lockMode, SearchMode.BOTH_RANGE);
    }

    /**
     * Returns the current key and data.
     *
     * When a secondary key is found, but the primary cannot be read for one of
     * the following reasons, this method returns KEYEMPTY.
     *
     *  1) lock mode is read-uncommitted and the primary record was deleted in
     *     the middle of the operation
     *
     *  2) the primary DB has been removed from the SecondaryAssocation
     */
    private OperationStatus getCurrentInternal(final DatabaseEntry key,
                                               final DatabaseEntry pKey,
                                               final DatabaseEntry data,
                                               final LockMode lockMode)
        throws DatabaseException {

        final OperationStatus status = getCurrentInternal(key, pKey, lockMode);
        if (status != OperationStatus.SUCCESS) {
            return status;
        }
        return readPrimaryAfterGet(key, pKey, data, lockMode);
    }

    /**
     * Calls search() and retrieves primary data.
     *
     * When the primary record cannot be read (see readPrimaryAfterGet),
     * advance over the unavailable record, according to the search type.
     */
    OperationStatus search(final DatabaseEntry key,
                           final DatabaseEntry pKey,
                           final DatabaseEntry data,
                           final LockMode lockMode,
                           final SearchMode searchMode)
        throws DatabaseException {

        OperationStatus status = search(key, pKey, lockMode, searchMode);
        if (status != OperationStatus.SUCCESS) {
            return status;
        }
        status = readPrimaryAfterGet(key, pKey, data, lockMode);
        if (status == OperationStatus.SUCCESS) {
            return status;
        }
        /* Advance over the unavailable record. */
        switch (searchMode) {
        case BOTH:
            /* Exact search on sec and pri key. */
            return OperationStatus.NOTFOUND;
        case SET:
        case BOTH_RANGE:
            /* Find exact sec key and next primary key. */
            return retrieveNext(key, pKey, data, lockMode, GetMode.NEXT_DUP);
        case SET_RANGE:
            /* Find next sec key or primary key. */
            return retrieveNext(key, pKey, data, lockMode, GetMode.NEXT);
        default:
            throw EnvironmentFailureException.unexpectedState();
        }
    }

    /**
     * Calls position() and retrieves primary data.
     *
     * When the primary record cannot be read (see readPrimaryAfterGet),
     * advance over the unavailable record.
     */
    OperationStatus position(final DatabaseEntry key,
                             final DatabaseEntry pKey,
                             final DatabaseEntry data,
                             final LockMode lockMode,
                             final boolean first)
        throws DatabaseException {

        OperationStatus status = position(key, pKey, lockMode, first);
        if (status != OperationStatus.SUCCESS) {
            return status;
        }
        status = readPrimaryAfterGet(key, pKey, data, lockMode);
        if (status == OperationStatus.SUCCESS) {
            return status;
        }
        /* Advance over the unavailable record. */
        return retrieveNext(key, pKey, data, lockMode,
                            first ? GetMode.NEXT : GetMode.PREV);
    }

    /**
     * Calls retrieveNext() and retrieves primary data.
     *
     * When the primary record cannot be read (see readPrimaryAfterGet),
     * advance over the unavailable record.
     */
    OperationStatus retrieveNext(final DatabaseEntry key,
                                 final DatabaseEntry pKey,
                                 final DatabaseEntry data,
                                 final LockMode lockMode,
                                 final GetMode getMode)
        throws DatabaseException {

        while (true) {
            OperationStatus status =
                retrieveNext(key, pKey, lockMode, getMode);
            if (status != OperationStatus.SUCCESS) {
                return status;
            }
            status = readPrimaryAfterGet(key, pKey, data, lockMode);
            if (status == OperationStatus.SUCCESS) {
                return status;
            }
            /* Continue loop to advance over the unavailable record. */
        }
    }

    /**
     * @return KEYEMPTY if the primary record has been deleted or updated (when
     * using read-uncommitted), or the primary database has been removed from
     * the association.
     *
     * @see Cursor#readPrimaryAfterGet
     */
    private OperationStatus readPrimaryAfterGet(final DatabaseEntry key,
                                                final DatabaseEntry pKey,
                                                final DatabaseEntry data,
                                                final LockMode lockMode) {
        final Database primaryDb = secondaryDb.getPrimary(pKey);
        if (primaryDb == null) {
            /* Primary was removed from the association. */
            return OperationStatus.KEYEMPTY;
        }
        return readPrimaryAfterGet(primaryDb, key, pKey, data, lockMode);
    }

    /**
     * @see Cursor#checkForPrimaryUpdate
     */
    @Override
    boolean checkForPrimaryUpdate(final DatabaseEntry key,
                                  final DatabaseEntry pKey,
                                  final DatabaseEntry data) {

        final SecondaryConfig conf = secondaryDb.getPrivateSecondaryConfig();
        boolean possibleIntegrityError = false;

        /*
         * If the secondary key is immutable, or the key creators are
         * null (the database is read only), then we can skip this
         * check.
         */
        if (conf.getImmutableSecondaryKey()) {
            /* Do nothing. */
        } else if (conf.getKeyCreator() != null) {

            /*
             * Check that the key we're using is equal to the key
             * returned by the key creator.
             */
            final DatabaseEntry secKey = new DatabaseEntry();
            if (!conf.getKeyCreator().createSecondaryKey
                    (secondaryDb, pKey, data, secKey) ||
                !secKey.equals(key)) {
                possibleIntegrityError = true;
            }
        } else if (conf.getMultiKeyCreator() != null) {

            /*
             * Check that the key we're using is in the set returned by
             * the key creator.
             */
            final Set<DatabaseEntry> results = new HashSet<DatabaseEntry>();
            conf.getMultiKeyCreator().createSecondaryKeys
                (secondaryDb, pKey, data, results);
            if (!results.contains(key)) {
                possibleIntegrityError = true;
            }
        }

        return possibleIntegrityError;
    }

    /**
     * Note that this flavor of checkArgs doesn't require that the dbt data is
     * set.
     */
    private void checkArgsNoValRequired(final DatabaseEntry key,
                                        final DatabaseEntry pKey,
                                        final DatabaseEntry data) {
        DatabaseUtil.checkForNullDbt(key, "key", false);
        DatabaseUtil.checkForNullDbt(pKey, "pKey", false);
        DatabaseUtil.checkForNullDbt(data, "data", false);
    }
}
