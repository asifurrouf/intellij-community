/*
 * Copyright 2000-2009 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.util.io;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.LowMemoryWatcher;
import com.intellij.openapi.util.io.BufferExposingByteArrayOutputStream;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.util.CommonProcessors;
import com.intellij.util.Processor;
import com.intellij.util.containers.LimitedPool;
import com.intellij.util.containers.SLRUCache;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.*;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * @author Eugene Zhuravlev
 *         Date: Dec 18, 2007
 */
public class PersistentHashMap<Key, Value> extends PersistentEnumeratorDelegate<Key> implements PersistentMap<Key, Value> {
  private static final Logger LOG = Logger.getInstance("#com.intellij.util.io.PersistentHashMap");

  private PersistentHashMapValueStorage myValueStorage;
  protected final DataExternalizer<Value> myValueExternalizer;
  private static final long NULL_ADDR = 0;
  private static final int INITIAL_INDEX_SIZE;
  static {
    String property = System.getProperty("idea.initialIndexSize");
    INITIAL_INDEX_SIZE = property == null ? 4 * 1024 : Integer.valueOf(property);
  }

  @NonNls
  public static final String DATA_FILE_EXTENSION = ".values";
  private long myLiveAndGarbageKeysCounter; // first four bytes contain live keys count (updated via LIVE_KEY_MASK), last four bytes - number of dead keys
  private int myReadCompactionGarbageSize;
  private static final long LIVE_KEY_MASK = (1L << 32);
  private static final long USED_LONG_VALUE_MASK = 1L << 62;
  private static final int POSITIVE_VALUE_SHIFT = 1;
  private final int myParentValueRefOffset;
  @NotNull private final byte[] myRecordBuffer;
  @NotNull private final byte[] mySmallRecordBuffer;
  private final boolean myCanReEnumerate;
  private int myLargeIndexWatermarkId;  // starting with this id we store offset in adjacent file in long format
  private boolean myIntAddressForNewRecord;
  private static final boolean doHardConsistencyChecks = false;

  private static class AppendStream extends DataOutputStream {
    private AppendStream() {
      super(new BufferExposingByteArrayOutputStream());
    }

    private void reset() {
      ((UnsyncByteArrayOutputStream)out).reset();
    }
    
    @NotNull
    private BufferExposingByteArrayOutputStream getInternalBuffer() {
      return (BufferExposingByteArrayOutputStream)out;      
    }
  }

  private final LimitedPool<AppendStream> myStreamPool = new LimitedPool<AppendStream>(10, new LimitedPool.ObjectFactory<AppendStream>() {
    @Override
    @NotNull
    public AppendStream create() {
      return new AppendStream();
    }

    @Override
    public void cleanup(@NotNull final AppendStream appendStream) {
      appendStream.reset();
    }
  });  

  private final SLRUCache<Key, AppendStream> myAppendCache = new SLRUCache<Key, AppendStream>(16 * 1024, 4 * 1024) {
    @Override
    @NotNull
    public AppendStream createValue(final Key key) {
      return myStreamPool.alloc();
    }

    @Override
    protected void onDropFromCache(final Key key, @NotNull final AppendStream value) {
      myEnumerator.lockStorage();
      try {
        final BufferExposingByteArrayOutputStream bytes = value.getInternalBuffer();
        final int id = enumerate(key);
        long oldHeaderRecord = readValueId(id);

        long headerRecord = myValueStorage.appendBytes(bytes.getInternalBuffer(), 0, bytes.size(), oldHeaderRecord);

        updateValueId(id, headerRecord, oldHeaderRecord, key, 0);
        if (oldHeaderRecord == NULL_ADDR) {
          myLiveAndGarbageKeysCounter += LIVE_KEY_MASK;
        }

        myStreamPool.recycle(value);
      }
      catch (IOException e) {
        throw new RuntimeException(e);
      }
      finally {
        myEnumerator.unlockStorage();
      }
    }
  };

  private boolean canUseIntAddressForNewRecord(long size) {
    return myCanReEnumerate ? size + POSITIVE_VALUE_SHIFT < Integer.MAX_VALUE: false;
  }

  private final LowMemoryWatcher myAppendCacheFlusher = LowMemoryWatcher.register(new Runnable() {
    @Override
    public void run() {
      //System.out.println("Flushing caches: " + myFile.getPath());
      dropMemoryCaches();
    }
  });

  public PersistentHashMap(@NotNull final File file, @NotNull KeyDescriptor<Key> keyDescriptor, @NotNull DataExternalizer<Value> valueExternalizer) throws IOException {
    this(file, keyDescriptor, valueExternalizer, INITIAL_INDEX_SIZE);
  }
  
  public PersistentHashMap(@NotNull final File file, @NotNull KeyDescriptor<Key> keyDescriptor, @NotNull DataExternalizer<Value> valueExternalizer, final int initialSize) throws IOException {
    super(checkDataFiles(file), keyDescriptor, initialSize);

    final PersistentEnumeratorBase.RecordBufferHandler<PersistentEnumeratorBase> recordHandler = myEnumerator.getRecordHandler();
    myParentValueRefOffset = recordHandler.getRecordBuffer(myEnumerator).length;
    myRecordBuffer = new byte[myParentValueRefOffset + 8];
    mySmallRecordBuffer = new byte[myParentValueRefOffset + 4];

    myEnumerator.setRecordHandler(new PersistentEnumeratorBase.RecordBufferHandler<PersistentEnumeratorBase>() {
      @Override
      int recordWriteOffset(PersistentEnumeratorBase enumerator, byte[] buf) {
        return recordHandler.recordWriteOffset(enumerator, buf);
      }

      @NotNull
      @Override
      byte[] getRecordBuffer(PersistentEnumeratorBase enumerator) {
        return myIntAddressForNewRecord ? mySmallRecordBuffer : myRecordBuffer;
      }

      @Override
      void setupRecord(PersistentEnumeratorBase enumerator, int hashCode, int dataOffset, @NotNull byte[] buf) {
        recordHandler.setupRecord(enumerator, hashCode, dataOffset, buf);
        for (int i = myParentValueRefOffset; i < buf.length; i++) {
          buf[i] = 0;
        }
      }
    });

    myEnumerator.setMarkCleanCallback(
      new Flushable() {
        @Override
        public void flush() throws IOException {
          myEnumerator.putMetaData(myLiveAndGarbageKeysCounter);
          myEnumerator.putMetaData2(myLargeIndexWatermarkId | ((long)myReadCompactionGarbageSize << 32));
        }
      }
    );

    try {
      myValueExternalizer = valueExternalizer;
      myValueStorage = PersistentHashMapValueStorage.create(getDataFile(file).getPath());
      myLiveAndGarbageKeysCounter = myEnumerator.getMetaData();
      long data2 = myEnumerator.getMetaData2();
      myLargeIndexWatermarkId = (int)(data2 & 0xFFFFFFFF);
      myReadCompactionGarbageSize = (int)(data2 >>> 32);
      myCanReEnumerate = myEnumerator.canReEnumerate();

      if (makesSenseToCompact()) {
        compact();
      }
    }
    catch (IOException e) {
      try {
        // attempt to close already opened resources
        close();
      }
      catch (Throwable ignored) {
      }
      throw e; // rethrow
    }
    catch (Throwable t) {
      LOG.error(t);
      try {
        // attempt to close already opened resources
        close();
      }
      catch (Throwable ignored) {
      }
      throw new PersistentEnumerator.CorruptedException(file);
    }
  }

  public void dropMemoryCaches() {
    synchronized (myEnumerator) {
      myEnumerator.lockStorage();
      try {
        clearAppenderCaches();
      }
      finally {
        myEnumerator.unlockStorage();
      }
    }
  }

  public int getGarbageSize() {
    return (int)myLiveAndGarbageKeysCounter;
  }

  public File getBaseFile() {
    return myEnumerator.myFile;
  }

  private boolean makesSenseToCompact() {
    final long fileSize = getDataFile(myEnumerator.myFile).length();
    final int megabyte = 1024 * 1024;

    if (fileSize > 5 * megabyte) { // file is longer than 5MB and (more than 50% of keys is garbage or approximate benefit larger than 100M)
      int liveKeys = (int)(myLiveAndGarbageKeysCounter / LIVE_KEY_MASK);
      int deadKeys = (int)(myLiveAndGarbageKeysCounter & 0xFFFFFFFF);

      if (deadKeys < 50) return false;

      final int benefitSize = 100 * megabyte;
      final long avgValueSize = fileSize / (liveKeys + deadKeys);

      return deadKeys > liveKeys ||
             avgValueSize *deadKeys > benefitSize ||
             myReadCompactionGarbageSize > (fileSize / 2);
    }
    return false;
  }

  @NotNull
  private static File checkDataFiles(@NotNull final File file) {
    if (!file.exists()) {
      deleteFilesStartingWith(getDataFile(file));
    }
    return file;
  }

  public static void deleteFilesStartingWith(@NotNull File prefixFile) {
    final String baseName = prefixFile.getName();
    final File[] files = prefixFile.getParentFile().listFiles(new FileFilter() {
      @Override
      public boolean accept(@NotNull final File pathName) {
        return pathName.getName().startsWith(baseName);
      }
    });
    if (files != null) {
      for (File f : files) {
        FileUtil.delete(f);
      }
    }
  }

  @NotNull
  private static File getDataFile(@NotNull final File file) {
    return new File(file.getParentFile(), file.getName() + DATA_FILE_EXTENSION);
  }

  @Override
  public final void put(Key key, Value value) throws IOException {
    synchronized (myEnumerator) {
      doPut(key, value);
    }
  }

  protected void doPut(Key key, Value value) throws IOException {
    myEnumerator.lockStorage();
    try {
      myEnumerator.markDirty(true);
      myAppendCache.remove(key);

      final AppendStream record = new AppendStream();
      myValueExternalizer.save(record, value);
      final BufferExposingByteArrayOutputStream bytes = record.getInternalBuffer();
      final int id = enumerate(key);

      long oldheader = readValueId(id);
      if (oldheader != NULL_ADDR) {
        myLiveAndGarbageKeysCounter++;
      }
      else {
        myLiveAndGarbageKeysCounter += LIVE_KEY_MASK;
      }

      long header = myValueStorage.appendBytes(bytes.getInternalBuffer(), 0, bytes.size(), 0);

      updateValueId(id, header, oldheader, key, 0);
    }
    finally {
      myEnumerator.unlockStorage();
    }
  }

  @Override
  public final int enumerate(Key name) throws IOException {
    synchronized (myEnumerator) {
      myIntAddressForNewRecord = canUseIntAddressForNewRecord(myValueStorage.getSize());
      return super.enumerate(name);
    }
  }

  public interface ValueDataAppender {
    void append(DataOutput out) throws IOException;
  }
  
  public final void appendData(Key key, @NotNull ValueDataAppender appender) throws IOException {
    synchronized (myEnumerator) {
      doAppendData(key, appender);
    }
  }

  protected void doAppendData(Key key, @NotNull ValueDataAppender appender) throws IOException {
    myEnumerator.markDirty(true);

    final AppendStream stream = myAppendCache.get(key);
    appender.append(stream);
  }

  /**
   * Process all keys registered in the map. Note that keys which were removed after {@link #compact()} call will be processed as well. Use
   * {@link #processKeysWithExistingMapping(com.intellij.util.Processor)} to process only keys with existing mappings
   */
  @Override
  public final boolean processKeys(Processor<Key> processor) throws IOException {
    synchronized (myEnumerator) {
      myAppendCache.clear();
      return myEnumerator.iterateData(processor);
    }
  }

  @NotNull
  public Collection<Key> getAllKeysWithExistingMapping() throws IOException {
    final List<Key> values = new ArrayList<Key>();
    processKeysWithExistingMapping(new CommonProcessors.CollectProcessor<Key>(values));
    return values;
  }

  public final boolean processKeysWithExistingMapping(Processor<Key> processor) throws IOException {
    synchronized (myEnumerator) {
      myAppendCache.clear();
      return myEnumerator.processAllDataObject(processor, new PersistentEnumerator.DataFilter() {
        @Override
        public boolean accept(final int id) {
          return readValueId(id) != NULL_ADDR;
        }
      });
    }
  }

  @Override
  public final Value get(Key key) throws IOException {
    synchronized (myEnumerator) {
      return doGet(key);
    }
  }

  @Nullable
  protected Value doGet(Key key) throws IOException {
    myEnumerator.lockStorage();
    try {
      myAppendCache.remove(key);
      final int id = tryEnumerate(key);
      if (id == PersistentEnumerator.NULL_ID) {
        return null;
      }
      final long oldHeader = readValueId(id);
      if (oldHeader == PersistentEnumerator.NULL_ID) {
        return null;
      }

      PersistentHashMapValueStorage.ReadResult readResult = myValueStorage.readBytes(oldHeader);
      if (readResult.offset != oldHeader) {
        myEnumerator.markDirty(true);

        updateValueId(id, readResult.offset, oldHeader, key, 0);
        myLiveAndGarbageKeysCounter++;
        myReadCompactionGarbageSize += readResult.buffer.length;
      }

      final DataInputStream input = new DataInputStream(new UnsyncByteArrayInputStream(readResult.buffer));
      try {
        return myValueExternalizer.read(input);
      }
      finally {
        input.close();
      }
    }
    finally {
      myEnumerator.unlockStorage();
    }
  }

  public final boolean containsMapping(Key key) throws IOException {
    synchronized (myEnumerator) {
      return doContainsMapping(key);
    }
  }

  protected boolean doContainsMapping(Key key) throws IOException {
    myEnumerator.lockStorage();
    try {
      myAppendCache.remove(key);
      final int id = tryEnumerate(key);
      if (id == PersistentEnumerator.NULL_ID) {
        return false;
      }
      return readValueId(id) != NULL_ADDR;
    }
    finally {
      myEnumerator.unlockStorage();
    }
  }

  public final void remove(Key key) throws IOException {
    synchronized (myEnumerator) {
      doRemove(key);
    }
  }

  protected void doRemove(Key key) throws IOException {
    myEnumerator.lockStorage();
    try {
      myAppendCache.remove(key);
      final int id = tryEnumerate(key);
      if (id == PersistentEnumerator.NULL_ID) {
        return;
      }
      myEnumerator.markDirty(true);

      final long record = readValueId(id);
      if (record != NULL_ADDR) {
        myLiveAndGarbageKeysCounter++;
      }

      updateValueId(id, NULL_ADDR, record, key, 0);
    }
    finally {
      myEnumerator.unlockStorage();
    }
  }

  @Override
  public final void markDirty() throws IOException {
    synchronized (myEnumerator) {
      myEnumerator.markDirty(true);
    }
  }

  @Override
  public final void force() {
    synchronized (myEnumerator) {
      doForce();
    }
  }

  protected void doForce() {
    myEnumerator.lockStorage();
    try {
      try {
        clearAppenderCaches();
      }
      finally {
        super.force();
      }
    }
    finally {
      myEnumerator.unlockStorage();
    }
  }

  private void clearAppenderCaches() {
    myAppendCache.clear();
    myValueStorage.force();
  }

  @Override
  public final void close() throws IOException {
    synchronized (myEnumerator) {
      doClose();
    }
  }

  protected void doClose() throws IOException {
    myEnumerator.lockStorage();
    try {
      try {
        myAppendCacheFlusher.stop();
        myAppendCache.clear();
        final PersistentHashMapValueStorage valueStorage = myValueStorage;
        if (valueStorage != null) {
          valueStorage.dispose();
        }
      }
      finally {
        super.close();
      }
    }
    finally {
      myEnumerator.unlockStorage();
    }
  }

  // made public for tests
  public void compact() throws IOException {
    synchronized (myEnumerator) {
      final long now = System.currentTimeMillis();
      final String newPath = getDataFile(myEnumerator.myFile).getPath() + ".new";
      final PersistentHashMapValueStorage newStorage = PersistentHashMapValueStorage.create(newPath);
      myValueStorage.switchToCompactionMode();
      myLiveAndGarbageKeysCounter = 0;
      myReadCompactionGarbageSize = 0;

      try {
        traverseAllRecords(new PersistentEnumerator.RecordsProcessor() {
          @Override
          public boolean process(final int keyId) throws IOException {
            final long record = readValueId(keyId);
            if (record != NULL_ADDR) {
              PersistentHashMapValueStorage.ReadResult readResult = myValueStorage.readBytes(record);
              long value = newStorage.appendBytes(readResult.buffer, 0, readResult.buffer.length, 0);
              updateValueId(keyId, value, record, null, getCurrentKey());
              myLiveAndGarbageKeysCounter += LIVE_KEY_MASK;
            }
            return true;
          }
        });
      }
      finally {
        newStorage.dispose();
      }

      myValueStorage.dispose();

      FileUtil.rename(new File(newPath), getDataFile(myEnumerator.myFile));

      myValueStorage = PersistentHashMapValueStorage.create(getDataFile(myEnumerator.myFile).getPath());
      LOG.info("Compacted " + myEnumerator.myFile.getPath() + " in " + (System.currentTimeMillis() - now) + "ms.");

      myEnumerator.putMetaData(myLiveAndGarbageKeysCounter);
    }
  }

  private long readValueId(final int keyId) {
    long address = myEnumerator.myStorage.getInt(keyId + myParentValueRefOffset);
    if (address == 0 || address == -POSITIVE_VALUE_SHIFT) {
      return NULL_ADDR;
    }

    if (address < 0) {
      address = -address - POSITIVE_VALUE_SHIFT;
    } else {
      int value = myEnumerator.myStorage.getInt(keyId + myParentValueRefOffset + 4);
      address = ((address << 32) + value) & ~USED_LONG_VALUE_MASK;
    }

    return address;
  }

  private int smallKeys;
  private int largeKeys;
  private int transformedKeys;
  private int requests;

  private int updateValueId(int keyId, long value, long oldValue, @Nullable Key key, int processingKey) throws IOException {
    final boolean newKey = oldValue == NULL_ADDR;
    if (newKey) ++requests;
    boolean defaultSizeInfo = true;

    if (myCanReEnumerate) {
      if (canUseIntAddressForNewRecord(value)) {
        defaultSizeInfo = false;
        myEnumerator.myStorage.putInt(keyId + myParentValueRefOffset, -(int)(value + POSITIVE_VALUE_SHIFT));
        if (newKey) ++smallKeys;
      } else {
        if (newKey && myLargeIndexWatermarkId == 0) {
          myLargeIndexWatermarkId = keyId;
        }
        if (keyId < myLargeIndexWatermarkId && (oldValue == NULL_ADDR || canUseIntAddressForNewRecord(oldValue))) {
          // keyId is result of enumerate, if we do reenumerate then it is no longer accessible unless somebody cached it
          myIntAddressForNewRecord = false;
          keyId = myEnumerator.reenumerate(key == null ? myEnumerator.getValue(keyId, processingKey) : key);
          ++transformedKeys;
        }
      }
    }

    if (defaultSizeInfo) {
      myEnumerator.myStorage.putLong(keyId + myParentValueRefOffset, value | USED_LONG_VALUE_MASK);
      if (newKey) ++largeKeys;
    }

    if (newKey && IOStatistics.DEBUG && (requests & IOStatistics.KEYS_FACTOR_MASK) == 0) {
      IOStatistics.dump("small:"+smallKeys + ", large:" + largeKeys + ", transformed:"+transformedKeys +
                        ",@"+getBaseFile().getPath());
    }
    if (doHardConsistencyChecks) {
      long checkRecord = readValueId(keyId);
      if (checkRecord != value) {
        assert false:value;
      }
    }
    return keyId;
  }
}
