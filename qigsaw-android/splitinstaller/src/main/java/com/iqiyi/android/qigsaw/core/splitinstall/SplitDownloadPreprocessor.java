/*
 * MIT License
 *
 * Copyright (c) 2019-present, iQIYI, Inc. All rights reserved.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.iqiyi.android.qigsaw.core.splitinstall;

import android.content.Context;

import com.iqiyi.android.qigsaw.core.common.FileUtil;
import com.iqiyi.android.qigsaw.core.common.SplitConstants;
import com.iqiyi.android.qigsaw.core.common.SplitLog;
import com.iqiyi.android.qigsaw.core.splitrequest.splitinfo.SplitInfo;
import com.iqiyi.android.qigsaw.core.splitrequest.splitinfo.SplitPathManager;

import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;

final class SplitDownloadPreprocessor implements Closeable {

    private static final int MAX_RETRY_ATTEMPTS = 3;

    private static final String TAG = "SplitDownloadPreprocessor";

    private final File splitApk;

    private final RandomAccessFile lockRaf;

    private final FileChannel lockChannel;

    private final FileLock cacheLock;

    private static final String LOCK_FILENAME = "SplitCopier.lock";

    SplitDownloadPreprocessor(File splitDir, File splitApk) throws IOException {
        this.splitApk = splitApk;
        File lockFile = new File(splitDir, LOCK_FILENAME);
        this.lockRaf = new RandomAccessFile(lockFile, "rw");
        try {
            this.lockChannel = this.lockRaf.getChannel();
            try {
                SplitLog.i(TAG, "Blocking on lock " + lockFile.getPath());
                this.cacheLock = this.lockChannel.lock();
            } catch (RuntimeException | Error | IOException var5) {
                FileUtil.closeQuietly(this.lockChannel);
                throw var5;
            }
            SplitLog.i(TAG, lockFile.getPath() + " locked");
        } catch (RuntimeException | Error | IOException var6) {
            FileUtil.closeQuietly(this.lockRaf);
            throw var6;
        }
    }


    void load(Context context, SplitInfo info) throws IOException {
        if (!cacheLock.isValid()) {
            throw new IllegalStateException("FileCheckerAndCopier was closed");
        } else {
            String splitName = info.getSplitName();
            if (info.isBuiltIn()) {
                if (!splitApk.exists()) {
                    SplitLog.v(TAG, "Built-in split %s is not existing, copy it from asset to [%s]", splitName, splitApk.getAbsolutePath());
                    //copy build in spilt apk file to splitDir
                    copyBuiltInSplit(context, info);
                    //check size
                    if (!checkSplitApkSignature(context, info)) {
                        throw new IOException(String.format("Failed to check built-in split %s, it may be corrupted", splitName));
                    }
                } else {
                    SplitLog.v(TAG, "Built-in split %s is existing", splitName);

                    if (!checkSplitApkSignature(context, info)) {
                        copyBuiltInSplit(context, info);
                        if (!checkSplitApkSignature(context, info)) {
                            throw new IOException(String.format("Failed to check built-in split %s, it may be corrupted", splitName));
                        }
                    }
                }
            } else {
                if (splitApk.exists()) {
                    SplitLog.v(TAG, "split %s is downloaded", splitName);
                    checkSplitApkSignature(context, info);
                } else {
                    SplitLog.v(TAG, " split %s is not downloaded", splitName);
                }
            }
        }
    }

    private boolean checkSplitApkSignature(Context context, SplitInfo info) {
        if (SignatureValidator.validateSplit(context, splitApk)) {
            return true;
        }
        SplitLog.w(TAG, "Oops! Failed to check split %s signature", info.getSplitName());
        deleteCorruptedOrObsoletedSplitApk();
        return false;
    }

    private void deleteCorruptedOrObsoletedSplitApk() {
        FileUtil.deleteDir(splitApk.getParentFile());
        if (splitApk.getParentFile().exists()) {
            SplitLog.w(TAG, "Failed to delete corrupted split files");
        }
    }

    private void copyBuiltInSplit(Context context, SplitInfo info) throws IOException {
        int numAttempts = 0;
        boolean isCopySuccessful = false;
        String splitFileName = info.getSplitName() + SplitConstants.DOT_ZIP;
        File tmpDir = SplitPathManager.require().getSplitTmpDir();
        File tmp = File.createTempFile("tmp-" + info.getSplitName(), SplitConstants.DOT_APK, tmpDir);
        while (!isCopySuccessful && numAttempts < MAX_RETRY_ATTEMPTS) {
            ++numAttempts;
            try {
                InputStream is = context.getAssets().open(splitFileName);
                FileUtil.copyFile(is, new FileOutputStream(tmp));
                if (!tmp.renameTo(splitApk)) {
                    SplitLog.w(TAG, "Failed to rename \"" + tmp.getAbsolutePath() + "\" to \"" + splitApk.getAbsolutePath() + "\"");
                } else {
                    isCopySuccessful = true;
                }
            } catch (IOException e) {
                SplitLog.w(TAG, "Failed to copy built-in split apk, attempts times : " + numAttempts);
            }
            SplitLog.i(TAG, "Copy built-in split " + (isCopySuccessful ? "succeeded" : "failed") + " '" + splitApk.getAbsolutePath() + "': length " + splitApk.length());
            if (!isCopySuccessful) {
                FileUtil.safeDeleteFile(splitApk);
                if (splitApk.exists()) {
                    SplitLog.w(TAG, "Failed to delete copied split apk which has been corrupted'" + splitApk.getPath() + "'");
                }
            }
        }
        FileUtil.safeDeleteFile(tmp);
        if (!isCopySuccessful) {
            throw new IOException(String.format("Failed to copy built-in file %s to path %s", splitFileName, splitApk.getPath()));
        }
    }


    @Override
    public void close() throws IOException {
        lockChannel.close();
        lockRaf.close();
        cacheLock.release();
    }
}
