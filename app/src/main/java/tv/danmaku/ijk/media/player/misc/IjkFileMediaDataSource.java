/*
 * Copyright (C) 2015 Bilibili
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package tv.danmaku.ijk.media.player.misc;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import android.os.ParcelFileDescriptor;
import android.util.Log;

public class IjkFileMediaDataSource implements IMediaDataSource {
    private ParcelFileDescriptor mPfd;
    private long mOffset;
    private long mLength;
    private FileInputStream mFileInputStream;
    private FileChannel mFileChannel;
    private boolean mIsClosed = false;

    public IjkFileMediaDataSource(FileDescriptor fd, long offset, long length) throws IOException {
        try {
            mPfd = ParcelFileDescriptor.dup(fd);
        } catch (IOException e) {
            throw new IOException("Failed to duplicate FileDescriptor", e);
        } catch (Exception e) {
            throw new IOException("Failed to duplicate FileDescriptor", e);
        }

        mOffset = offset;
        mLength = length;
        mFileInputStream = new FileInputStream(mPfd.getFileDescriptor());
        mFileChannel = mFileInputStream.getChannel();
    }

    @Override
    public int readAt(long position, byte[] buffer, int offset, int size) throws IOException {
        if (mIsClosed) {
            return -1;
        }

        if (mLength >= 0 && position >= mLength) {
            return -1;
        }

        int bytesToRead = size;
        if (mLength >= 0 && position + size > mLength) {
            bytesToRead = (int) (mLength - position);
        }

        if (bytesToRead <= 0) {
            return -1;
        }

        ByteBuffer byteBuffer = ByteBuffer.wrap(buffer, offset, bytesToRead);
        int read = mFileChannel.read(byteBuffer, mOffset + position);

        return read;
    }

    @Override
    public long getSize() throws IOException {
        if (mLength >= 0) {
            return mLength;
        }
        if (mFileChannel != null) {
            return mFileChannel.size() - mOffset;
        }
        return -1;
    }

    @Override
    public void close() throws IOException {
        mIsClosed = true;
        if (mFileChannel != null) {
            try {
                mFileChannel.close();
            } catch (IOException e) {
                // Ignore
            }
            mFileChannel = null;
        }
        if (mFileInputStream != null) {
            try {
                mFileInputStream.close();
            } catch (IOException e) {
                // Ignore
            }
            mFileInputStream = null;
        }
        if (mPfd != null) {
            try {
                mPfd.close();
            } catch (IOException e) {
                // Ignore
            }
            mPfd = null;
        }
    }
}
