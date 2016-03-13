/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002, 2014 Oracle and/or its affiliates.  All rights reserved.
 *
 */

package com.sleepycat.je.util;

import java.io.File;
import java.io.IOException;
import java.io.FileInputStream;

/**
 * Simple ClassLoader to load class files from a given directory.  Does not
 * support jar files or multiple directories.
 */
public class SimpleClassLoader extends ClassLoader {
    
    private final File classPath;

    public SimpleClassLoader(ClassLoader parentLoader, File classPath) {
        super(parentLoader);
        this.classPath = classPath;
    }

    @Override
    public Class findClass(String className)
        throws ClassNotFoundException {

        try {
            final String fileName = className.replace('.', '/') + ".class";
            final File file = new File(classPath, fileName);
            final byte[] data = new byte[(int) file.length()];
            final FileInputStream fis = new FileInputStream(file);
            try {
                fis.read(data);
            } finally {
                fis.close();
            }
            return defineClass(className, data, 0, data.length);
        } catch (IOException e) {
            throw new ClassNotFoundException
                ("Class: " + className + " could not be loaded from: " +
                 classPath, e);
        }
    }
}
