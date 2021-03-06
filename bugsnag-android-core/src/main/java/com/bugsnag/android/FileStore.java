package com.bugsnag.android;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

abstract class FileStore {

    interface Delegate {

        /**
         * Invoked when an error report is not (de)serialized correctly
         *
         * @param exception the error encountered reading/delivering the file
         * @param errorFile file which could not be (de)serialized correctly
         * @param context the context used to group the exception
         */
        void onErrorIOFailure(Exception exception, File errorFile, String context);
    }

    @Nullable
    final String storeDirectory;
    private final int maxStoreCount;
    private final Comparator<File> comparator;

    private final Lock lock = new ReentrantLock();
    private final Collection<File> queuedFiles = new ConcurrentSkipListSet<>();
    private final Logger logger;
    private final EventStore.Delegate delegate;

    FileStore(@NonNull Context appContext, String folder,
              int maxStoreCount, Comparator<File> comparator, Logger logger, Delegate delegate) {
        this.maxStoreCount = maxStoreCount;
        this.comparator = comparator;
        this.logger = logger;
        this.delegate = delegate;

        String path;
        try {
            path = appContext.getCacheDir().getAbsolutePath() + folder;

            File outFile = new File(path);
            outFile.mkdirs();
            if (!outFile.exists()) {
                this.logger.w("Could not prepare file storage directory");
                path = null;
            }
        } catch (Exception exception) {
            this.logger.w("Could not prepare file storage directory", exception);
            path = null;
        }
        this.storeDirectory = path;
    }

    void enqueueContentForDelivery(String content) {
        if (storeDirectory == null) {
            return;
        }
        String filename = getFilename(content);
        discardOldestFileIfNeeded();
        lock.lock();
        Writer out = null;
        try {
            FileOutputStream fos = new FileOutputStream(filename);
            out = new BufferedWriter(new OutputStreamWriter(fos, "UTF-8"));
            out.write(content);
        } catch (Exception exc) {
            File eventFile = new File(filename);

            if (delegate != null) {
                delegate.onErrorIOFailure(exc, eventFile, "NDK Crash report copy");
            }

            IOUtils.deleteFile(eventFile, logger);
        } finally {
            try {
                if (out != null) {
                    out.close();
                }
            } catch (Exception exception) {
                logger.w(String.format("Failed to close unsent payload writer (%s) ",
                    filename), exception);
            }
            lock.unlock();
        }
    }

    @Nullable
    String write(@NonNull JsonStream.Streamable streamable) {
        if (storeDirectory == null) {
            return null;
        }
        discardOldestFileIfNeeded();
        String filename = getFilename(streamable);

        JsonStream stream = null;
        lock.lock();

        try {
            FileOutputStream fos = new FileOutputStream(filename);
            Writer out = new BufferedWriter(new OutputStreamWriter(fos, "UTF-8"));
            stream = new JsonStream(out);
            stream.value(streamable);
            logger.i(String.format("Saved unsent payload to disk (%s) ", filename));
            return filename;
        } catch (FileNotFoundException exc) {
            logger.w("Ignoring FileNotFoundException - unable to create file", exc);
        } catch (Exception exc) {
            File eventFile = new File(filename);

            if (delegate != null) {
                delegate.onErrorIOFailure(exc, eventFile, "Crash report serialization");
            }

            IOUtils.deleteFile(eventFile, logger);
        } finally {
            IOUtils.closeQuietly(stream);
            lock.unlock();
        }
        return null;
    }

    void discardOldestFileIfNeeded() {
        // Limit number of saved errors to prevent disk space issues
        File exceptionDir = new File(storeDirectory);
        if (exceptionDir.isDirectory()) {
            File[] files = exceptionDir.listFiles();

            if (files != null && files.length >= maxStoreCount) {
                // Sort files then delete the first one (oldest timestamp)
                Arrays.sort(files, comparator);

                for (int k = 0; k < files.length && files.length >= maxStoreCount; k++) {
                    File oldestFile = files[k];

                    if (!queuedFiles.contains(oldestFile)) {
                        logger.w(String.format("Discarding oldest error as stored "
                            + "error limit reached (%s)", oldestFile.getPath()));
                        deleteStoredFiles(Collections.singleton(oldestFile));
                    }
                }
            }
        }
    }

    @NonNull
    abstract String getFilename(Object object);

    List<File> findStoredFiles() {
        lock.lock();
        try {
            List<File> files = new ArrayList<>();

            if (storeDirectory != null) {
                File dir = new File(storeDirectory);

                if (dir.exists() && dir.isDirectory()) {
                    File[] values = dir.listFiles();

                    if (values != null) {
                        for (File value : values) {
                            // delete any tombstoned/empty files, as they contain no useful info
                            if (value.length() == 0) {
                                if (!value.delete()) {
                                    value.deleteOnExit();
                                }
                            } else if (value.isFile() && !queuedFiles.contains(value)) {
                                files.add(value);
                            }
                        }
                    }
                }
            }
            queuedFiles.addAll(files);
            return files;
        } finally {
            lock.unlock();
        }
    }

    void cancelQueuedFiles(Collection<File> files) {
        lock.lock();
        try {
            if (files != null) {
                queuedFiles.removeAll(files);
            }
        } finally {
            lock.unlock();
        }
    }

    void deleteStoredFiles(Collection<File> storedFiles) {
        lock.lock();
        try {
            if (storedFiles != null) {
                queuedFiles.removeAll(storedFiles);

                for (File storedFile : storedFiles) {
                    if (!storedFile.delete()) {
                        storedFile.deleteOnExit();
                    }
                }
            }
        } finally {
            lock.unlock();
        }
    }

}
