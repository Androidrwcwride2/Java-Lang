/*
 *     Copyright (C) 2015  higherfrequencytrading.com
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU Lesser General Public License as published by
 *     the Free Software Foundation, either version 3 of the License.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU Lesser General Public License for more details.
 *
 *     You should have received a copy of the GNU Lesser General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package net.openhft.lang.io;

import net.openhft.lang.io.serialization.BytesMarshallableSerializer;
import net.openhft.lang.io.serialization.BytesMarshallerFactory;
import net.openhft.lang.io.serialization.JDKZObjectSerializer;
import net.openhft.lang.io.serialization.ObjectSerializer;
import net.openhft.lang.io.serialization.impl.VanillaBytesMarshallerFactory;
import net.openhft.lang.model.constraints.NotNull;
import sun.misc.Cleaner;
import sun.nio.ch.FileChannelImpl;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.channels.FileChannel;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

public class MappedStore implements BytesStore, Closeable {

    private static final int MAP_RO = 0;
    private static final int MAP_RW = 1;
    private static final int MAP_PV = 2;

    // retain to prevent GC.
    private final File file;
    private final FileChannel fileChannel;
    private final Cleaner cleaner;
    private final long address;
    private final AtomicInteger refCount = new AtomicInteger(1);
    private final long size;
    private ObjectSerializer objectSerializer;

    public MappedStore(File file, FileChannel.MapMode mode, long size) throws IOException {
        this(file, mode, size, new VanillaBytesMarshallerFactory());
    }

    @Deprecated
    public MappedStore(File file, FileChannel.MapMode mode, long size,
                       BytesMarshallerFactory bytesMarshallerFactory) throws IOException {
        this(file, mode, size, BytesMarshallableSerializer.create(
                bytesMarshallerFactory, JDKZObjectSerializer.INSTANCE));
    }

    public MappedStore(File file, FileChannel.MapMode mode, long size,
                       ObjectSerializer objectSerializer) throws IOException {
        this(file, mode, 0L, size, objectSerializer);
    }

    public MappedStore(File file, FileChannel.MapMode mode, long startInFile, long size,
                       ObjectSerializer objectSerializer) throws IOException {
        if (size < 0 || size > 128L << 40) {
            throw new IllegalArgumentException("invalid size: " + size);
        }

        this.file = file;
        this.size = size;
        this.objectSerializer = objectSerializer;

        try {
            RandomAccessFile raf = new RandomAccessFile(file, accesModeFor(mode));
            if ((raf.length() < startInFile + size || (startInFile == 0 && raf.length() != size))
                    && !file.getAbsolutePath().startsWith("/dev/")) {
                if (mode != FileChannel.MapMode.READ_WRITE) {
                    throw new IOException(
                            "Cannot resize file to " + size + " as mode is not READ_WRITE");
                }

                raf.setLength(startInFile + size);
            }

            this.fileChannel = raf.getChannel();
            this.address = map0(fileChannel, imodeFor(mode), startInFile, size);
            this.cleaner = Cleaner.create(this, new Unmapper(address, size, fileChannel));
        } catch (Exception e) {
            throw wrap(e);
        }
    }

    private static long map0(FileChannel fileChannel, int imode, long start, long size)
            throws NoSuchMethodException, IllegalAccessException, InvocationTargetException {
        Method map0 = fileChannel.getClass().getDeclaredMethod(
                "map0", int.class, long.class, long.class);
        map0.setAccessible(true);
        return (Long) map0.invoke(fileChannel, imode, start, size);
    }

    private static void unmap0(long address, long size) throws IOException {
        try {
            Method unmap0 = FileChannelImpl.class.getDeclaredMethod(
                    "unmap0", long.class, long.class);
            unmap0.setAccessible(true);
            unmap0.invoke(null, address, size);
        } catch (Exception e) {
            throw wrap(e);
        }
    }

    private static IOException wrap(Throwable e) {
        if (e instanceof InvocationTargetException)
            e = e.getCause();
        if (e instanceof IOException)
            return (IOException) e;
        return new IOException(e);
    }

    private static String accesModeFor(FileChannel.MapMode mode) {
        return mode == FileChannel.MapMode.READ_WRITE ? "rw" : "r";
    }

    private static int imodeFor(FileChannel.MapMode mode) {
        int imode = -1;
        if (mode == FileChannel.MapMode.READ_ONLY)
            imode = MAP_RO;
        else if (mode == FileChannel.MapMode.READ_WRITE)
            imode = MAP_RW;
        else if (mode == FileChannel.MapMode.PRIVATE)
            imode = MAP_PV;
        assert (imode >= 0);
        return imode;
    }

    @Override
    public ObjectSerializer objectSerializer() {
        return objectSerializer;
    }

    @Override
    public long address() {
        return address;
    }

    @Override
    public long size() {
        return size;
    }

    @Override
    public void free() {
        cleaner.clean();
    }

    @Override
    public void close() {
        free();
    }

    @NotNull
    public DirectBytes bytes() {
        return new DirectBytes(this, refCount);
    }

    @NotNull
    public DirectBytes bytes(long offset, long length) {
        return new DirectBytes(this, refCount, offset, length);
    }

    public File file() {
        return file;
    }

    private static final class Unmapper implements Runnable {
        private final long size;
        private final FileChannel channel;
        /*
         * This is not for synchronization (since calling this from multiple
         * threads through .free / .close is an user error!) but rather to make
         * sure that if an explicit cleanup was performed, the cleaner does not
         * retry cleaning up the resources.
         */
        private volatile long address;

        Unmapper(long address, long size, FileChannel channel) {
            assert (address != 0);
            this.address = address;
            this.size = size;
            this.channel = channel;
        }

        public void run() {
            if (address == 0)
                return;

            try {
                unmap0(address, size);
                address = 0;

                channel.force(true);
                channel.close();
            } catch (IOException e) {
                UnmapperLoggerHolder.LOGGER.log(Level.SEVERE,
                    "An exception has occurred while cleaning up a MappedStore instance: " +
                            e.getMessage(), e);
            }
        }

        private static final class UnmapperLoggerHolder {
            private static final Logger LOGGER = Logger.getLogger(Unmapper.class.getName());
        }
    }
}

