/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002, 2014 Oracle and/or its affiliates.  All rights reserved.
 *
 */

package com.sleepycat.je.junit;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;

/**
 * [#16348] JE file handle leak when multi-process writing a same environment.
 *
 * Write this thread for creating multi-process test, you can generate 
 * a JUnitProcessThread, each thread would create a process for you, just need
 * to assign the command line parameters to the thread.
 */
public class JUnitProcessThread extends JUnitThread {
    private String cmdArrays[];

    /*
     * Variable record the return value of the process. If 0, it means the 
     * process finishes successfully, if not, the process fails.
     */
    private int exitVal = 0;

    /* If true, don't print out the standard output in this process. */
    private final boolean suppressOutput;

    /**
     * Pass the process name and command line to the constructor.
     */
    public JUnitProcessThread(String threadName, String[] parameters) {
        this(threadName, null, parameters, false);
    }

    public JUnitProcessThread(String threadName, 
                              String[] jvmParams, 
                              String[] parameters) {
        this(threadName, jvmParams, parameters, false);
    }

    public JUnitProcessThread(String threadName, 
                              String[] jvmParams,
                              String[] parameters, 
                              boolean suppressOutput) {
        super(threadName);

        this.suppressOutput = suppressOutput;
        
        if (jvmParams == null) {
            jvmParams = new String[0];
        }

        cmdArrays = new String[3 + jvmParams.length + parameters.length];
        cmdArrays[0] = System.getProperty("java.home") +
            System.getProperty("file.separator") + "bin" + 
            System.getProperty("file.separator") + "java" + 
            (System.getProperty("path.separator").equals(":") ? "" : "w.exe");

        for (int i = 0; i < jvmParams.length; i++) {
            cmdArrays[i + 1] = jvmParams[i];
        }
            
        cmdArrays[jvmParams.length + 1] = "-cp";
        cmdArrays[jvmParams.length + 2] = 
            "." + System.getProperty("path.separator") + 
            System.getProperty("java.class.path"); 
        
        for (int i = 0; i < parameters.length; i++) {
            cmdArrays[i + jvmParams.length + 3] = parameters[i];
        }
    }

    /** Generate a process for this thread.*/
    public void testBody() {
        Runtime runtime = Runtime.getRuntime();

        try {
            Process proc = runtime.exec(cmdArrays);

            InputStream error = proc.getErrorStream();
            InputStream output = proc.getInputStream();

            Thread err = new Thread(new OutErrReader(error));
            err.start();

            if (!suppressOutput) {
                Thread out = new Thread(new OutErrReader(output));
                out.start();
            }

            exitVal = proc.waitFor();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /** Return the return value of the created process to main thread. */
    public int getExitVal() {
        return exitVal;
    }

    /** 
     * A class prints out the output information of writing serialized files.
     */
    public static class OutErrReader implements Runnable {
        final InputStream is;
        final boolean ignoreOutput;

        public OutErrReader(InputStream is) {
            this.is = is;
            this.ignoreOutput = false;
        }

        public OutErrReader(InputStream is, boolean ignoreOutput) {
            this.is = is;
            this.ignoreOutput = ignoreOutput;
        }

        public void run() {
            try {
                BufferedReader in =
                    new BufferedReader(new InputStreamReader(is));
                String temp = new String();
                while((temp = in.readLine()) != null) {
                    if (!ignoreOutput) {
                        System.err.println(temp);
                    }
                }
                is.close();
            } catch (Exception e) {
                if (!ignoreOutput) {
                    e.printStackTrace();
                }
            }
        }
    }
}
