package com.catsmoker.app;

import com.catsmoker.app.shizuku.CommandResult;

interface IFileService {
    void destroy();
    int executeCommand(in String[] command);
    List<String> executeAndGetOutput(in String[] command);

    /** Like executeAndGetOutput but also reports the exit code and stderr separately. */
    CommandResult executeForResult(in String[] command);

    /**
     * Sweeps /sys/class/thermal/thermal_zone* inside the privileged process and returns
     * "type:millidegrees" lines. Avoids forking a shell on every metrics poll.
     */
    String readSysfsThermal();

    /**
     * Returns the contents of /proc/stat, which SELinux hides from untrusted_app on modern
     * Android. Reading it here costs one binder call instead of forking `cat` every poll.
     */
    String readProcStat();

    /**
     * Reads a whole file in the privileged process and returns its bytes. One binder call
     * instead of a forked `cp` + a re-read of the temp copy; saves up to 11 KB per call but
     * mostly removes the two-second spawn cost on devices where forking is slow.
     * Returns null when the file does not exist or cannot be read — callers must keep that
     * distinct from an empty file.
     */
    byte[] readFile(String path);

    /**
     * Writes bytes over an existing-or-new file in the privileged process (O_TRUNC), returning
     * true only when every byte reached storage. One binder call instead of forked mkdir/cp.
     */
    boolean writeFile(String path, in byte[] data);
}
