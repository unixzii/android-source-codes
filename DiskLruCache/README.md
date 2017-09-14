# DiskLruCache 实现分析

## 前言

本文中的 `DiskLruCache` 实现来自 AOSP，同时也被 OkHttp 的缓存系统所采用。这个类，顾名思义就是能够将缓存数据持久化到磁盘上，并使用 LRU 淘汰算法来维持缓存保持在一个稳定的大小。

这里想多谈一谈缓存。

很多文章中会提到一些诸如 “图片三级缓存” 这样的话题，在我看来，应用需要实现好的其实就两级缓存：内存缓存和磁盘缓存。图片从网络下载下来之后首先放置在内存中做缓存，此时的内存缓存分为两种，一种是未解码数据的缓存（即 JPG、PNG、GIF 文件的二进制字节）；另外一种则是解码后的图像像素数据的缓存，这类数据通常很大（可以用 `Bitmap#getByteCount()` 来估测占用内存的大小），各类缓存池的大小需要在开发的时候控制好。

`DiskLruCache` 充当了所有缓存中的最后一级（不讨论网络缓存），也是容量最大的缓存，通常可以大量存放一些图片、音频等数据，当内存缓存均不命中时可以调取。我们知道，Android SDK 和 Support Library 提供了 `LruCache` 这个类，来让我们更方便地去实现内存 LRU 缓存，但 `DiskLruCache` 并没有提供，大家需要手动从 AOSP 中提取，也可以从本文的[参考源码](https://github.com/unixzii/android-source-codes/blob/master/DiskLruCache/sources/com/cyandev/DiskLruCache.java)中下载。

## 使用方法
`DiskLruCache` 的使用方法十分简单，本文就不再赘述了，不熟悉的同学可以先看一下我写的一个简单的[单元测试](https://github.com/unixzii/android-source-codes/blob/master/DiskLruCache/sources/com/cyandev/test/DiskLruCacheTest.java)。

## 有关 LinkedHashMap 的预备知识
`DiskLruCache` 内部采用 `LinkedHashMap` 来进行缓存条目的存储，这里的条目对应的就是 `DiskLruCache$Entry` 这个类，大家可以把它想成缓存文件的索引。当我们想要获取一个条目时，首先就会查询哈希表里是否有相应的 Entry，查到 Entry 之后再为其创建 `Snapshot` 或 `Editor` 对象，这些我们后面会提到。

这里比较核心的就是 LRU 这个算法的实现，简单来说 LRU 会在缓存满时不断淘汰最少使用的条目，直到缓存大小不超过最大限制。要实现这个功能，就需要我们统计条目的访问情况，这里 `DiskLruCache` 的逻辑比较简单，即：当访问一个条目时，把它放到整个链表的尾部，当清理条目时，从链表的开头开始清理，这样最少被访问的条目就会被优先清理掉。`DiskLruCache ` 中的实现如下：

```java
private void trimToSize() throws IOException {
	while (size > maxSize) {
		final Map.Entry<String, Entry> toEvict = lruEntries.entrySet().iterator().next();
		remove(toEvict.getKey());
    }
}
```

访问条目的实现如下：

```java
public synchronized Snapshot get(String key) throws IOException {
	...
	
	Entry entry = lruEntries.get(key);
	
	...
}
```

并没有在链表中移动的操作。其实这就是 `LinkedHashMap` 和 `HashMap` 的一个比较大的区别，`LinkedHashMap` 中的一个构造函数包含了一个参数，用来控制遍历时的顺序，当使用访问顺序来遍历时，每次访问条目，`LinkedHashMap` 都会将其移至链表的尾部：

```java
public V get(Object key) {
	Node<K,V> e;
	if ((e = getNode(hash(key), key)) == null)
		return null;
	if (accessOrder)
		afterNodeAccess(e);
   	return e.value;
}
```

这里 `afterNodeAccess` 就是一个双向链表中移动节点的操作。

至此我们就应该能明白 `DiskLruCache` 管理内存中的条目索引的方法了。

## 日志文件
日志文件记录了 `DiskLruCache` 的各种活动，是纯文本文件，格式诸如：

```plain
libcore.io.DiskLruCache
1
100
2

CLEAN 3400330d1dfc7f3f7f4b8d4d803dfcf6 832 21054
DIRTY 335c4c6028171cfddfbaae1a9c313c52
CLEAN 335c4c6028171cfddfbaae1a9c313c52 3934 2342
REMOVE 335c4c6028171cfddfbaae1a9c313c52
DIRTY 1ab96a171faeeee38496d8b330771a7a
CLEAN 1ab96a171faeeee38496d8b330771a7a 1600 234
READ 335c4c6028171cfddfbaae1a9c313c52
READ 3400330d1dfc7f3f7f4b8d4d803dfcf6
```

第一行充当了 **"magic number"** 的角色，说明了此文件为 `DiskLruCache` 的日志，第二行则是类的版本号，第三行是客户端应用的版本号，第四行为每个缓存条目的文件数。

接下来的所有行就记录了之前 `DiskLruCache` 的一系列行为，在初始化的时候通过读取日志文件，就可以重建整个条目索引了。

日志中总共会出现四种状态：

* **DIRTY:** 记录了某一个条目开始被编辑，有可能是新增条目，也有可能是修改已有条目。接下来需要有 **CLEAN** 或 **REMOVE** 状态来平衡 **DIRTY** 状态，否则这就是无效的一条记录。
* **CLEAN:** 表明之前的某一 **DIRTY** 记录已经修改完毕（通常是执行 `Editor#commit()` 的结果），这一行结尾会有条目的各个文件的长度。
* **READ:** 记录了某一条的读取行为。记录读取行为的目的是为了在重构条目索引时将对应的节点移动到链表的尾部，以便于 LRU 算法的执行。
* **REMOVE:** 记录了某一条目的移除行为。

本文不打算展开介绍日志文件的生成与解析，大家有兴趣的话可以自行看源码，实现原理也很简单。值得注意的是，`DiskLruCache` 的很多文件操作都采用了事务的处理方式，即修改文件前先写入一个同名的 tmp 文件，当所有内容写完后再将 tmp 文件的扩展名去掉以覆盖原有文件，这样做的好处就是不会因为应用的异常退出或 crash 而出现 **Corrupt Data**，保证了原有文件的完整性。

## 初始化工作
`DiskLruCache` 实例必须通过 `DiskLruCache#open(File, int, int, long)` 这个静态方法创建，其实现如下：

```java
public static DiskLruCache open(File directory, int appVersion, int valueCount, long maxSize)
            throws IOException {
    if (maxSize <= 0) {
        throw new IllegalArgumentException("maxSize <= 0");
    }
    if (valueCount <= 0) {
        throw new IllegalArgumentException("valueCount <= 0");
    }

    // prefer to pick up where we left off
    DiskLruCache cache = new DiskLruCache(directory, appVersion, valueCount, maxSize);
    if (cache.journalFile.exists()) {
        try {
            cache.readJournal();
            cache.processJournal();
            cache.journalWriter = new BufferedWriter(new FileWriter(cache.journalFile, true),
                    IO_BUFFER_SIZE);
            return cache;
        } catch (IOException journalIsCorrupt) {
              System.logW("DiskLruCache " + directory + " is corrupt: "
                      + journalIsCorrupt.getMessage() + ", removing");
            cache.delete();
        }
    }

    // create a new empty cache
    directory.mkdirs();
    cache = new DiskLruCache(directory, appVersion, valueCount, maxSize);
    cache.rebuildJournal();
    return cache;
}
```

如果日志文件存在，那就根据日志文件重建条目索引，否则视为第一次在指定目录使用 `DiskLruCache`，执行初始化工作。

初始化工作结束后，日志文件的流会一直保持打开的状态，直到我们显式调用 `DiskLruCache#close()` 方法。

## 缓存条目的创建与编辑
通过 `DiskLruCache#edit(String)` 方法可以编辑一个条目（如果条目不存在就先创建一个），这个方法会返回一个 `DiskLruCache$Editor` 对象作为条目操作的句柄，所有文件操作都要通过这个对象来完成。具体实现如下：

```java
public Editor edit(String key) throws IOException {
    return edit(key, ANY_SEQUENCE_NUMBER);
}

private synchronized Editor edit(String key, long expectedSequenceNumber) throws IOException {
    checkNotClosed();
    validateKey(key);
    Entry entry = lruEntries.get(key);
    if (expectedSequenceNumber != ANY_SEQUENCE_NUMBER
            && (entry == null || entry.sequenceNumber != expectedSequenceNumber)) {
        return null; // snapshot is stale
    }
    if (entry == null) {
        entry = new Entry(key);
        lruEntries.put(key, entry);
    } else if (entry.currentEditor != null) {
        return null; // another edit is in progress
    }

    Editor editor = new Editor(entry);
    entry.currentEditor = editor;

    // flush the journal before creating files to prevent file leaks
    journalWriter.write(DIRTY + ' ' + key + '\n');
    journalWriter.flush();
    return editor;
}
```

这里实际的逻辑是 `edit(String, long)` 这个重载，其中 `expectedSequenceNumber` 这个参数比较重要，它是 **Snapshot** 机制实现的核心，这个我在下面介绍 `Snapshot` 类的时候再详细阐述。从源码中可以看到，每次执行编辑操作，都会在日志文件中产生一条 `DIRTY` 记录，它可以保证记录被正常 **commit** 了，没有被 **commit** 的编辑操作不会生效，且之前的文件内容也会被视为“已经无效”从而在清理过程中被删除。

`Editor` 对象提供了几个基本方法来操作一个缓存条目：

* `newInputStream(int)`: 获取此条目的一个输入流，它会创建一个基于 **clean file** 的一个 `FileInputStream`，因此没有被 **commit** 的脏数据不会被读取。通常，读取缓存数据时我们不使用这个方法。
* `newOutputStream(int)`: 获取此条目的一个输出流，类上上面的方法，只不过它基于的是一个临时文件（**dirty file**），只有被 **commit** 后才会变为 **clean file**。另外值得注意的是，它返回的并非 `FileOutputStream`，而是 `FaultHidingOutputStream`（`FilterOutputStream` 派生类），它会 suppress 并记录所有 IO 错误，在 **commit** 时如果发现之前有 IO 错误发生，则会自动 **abort** 掉此次编辑操作，以保证原有数据不会损坏。
* `commit()` 和 `abort()`: 结束编辑操作，前者是提交更改，后者为放弃编辑的内容，还原之前的状态。

我们主要看一下结束编辑状态的实现逻辑，它由 `DiskLruCache#completeEdit(Editor, boolean)` （由 `commit()` 和 `abort()` 调用）实现：

```java
private synchronized void completeEdit(Editor editor, boolean success) throws IOException {
    Entry entry = editor.entry;
    if (entry.currentEditor != editor) {
        throw new IllegalStateException();
    }

    // 如果此条目是第一次创建的，需要保证它的每个 index 都有实际文件可以读取，
    // 否则抛出异常。
    // readable 成员变量表示了这个条目是否已经被成功提交过。
    if (success && !entry.readable) {
        for (int i = 0; i < valueCount; i++) {
            if (!entry.getDirtyFile(i).exists()) {
                editor.abort();
                throw new IllegalStateException("edit didn't create file " + i);
            }
        }
    }

    // 根据成功与否来处理条目的所有 index，对于失败的编辑操作，我们删掉其
    // dirty file，对于成功提交的条目，我们需要计算它各个 index 最新的
    // 文件长度。
    for (int i = 0; i < valueCount; i++) {
        File dirty = entry.getDirtyFile(i);
        if (success) {
            if (dirty.exists()) {
                File clean = entry.getCleanFile(i);
                dirty.renameTo(clean);
                long oldLength = entry.lengths[i];
                long newLength = clean.length();
                entry.lengths[i] = newLength;
                size = size - oldLength + newLength;
            }
        } else {
            deleteIfExists(dirty);
        }
    }

    // 记录操作次数。
    redundantOpCount++;
    entry.currentEditor = null;
    if (entry.readable | success) {
        // 如果条目已经被创建而此次编辑失败了，我们需要写入一条 CLEAN
        // 记录，因为要平衡之前的 DIRTY 记录，否则条目就无效了。而对
        // 于成功提交的编辑，我们也肯定是要写入 CLEAN 记录的。
        entry.readable = true;
        journalWriter.write(CLEAN + ' ' + entry.key + entry.getLengths() + '\n');
        if (success) {
            // 成功提交，自增版本号，原有的 Snapshot 对象将无效。
            entry.sequenceNumber = nextSequenceNumber++;
        }
    } else {
        // 对于新创建而又提交失败的条目来说，我们需要将其从链表中删除，
        // 此外还要写入一条 REMOVE 记录来平衡 DIRTY 记录。
        lruEntries.remove(entry.key);
        journalWriter.write(REMOVE + ' ' + entry.key + '\n');
    }

    if (size > maxSize || journalRebuildRequired()) {
        executorService.submit(cleanupCallable);
    }
}
```

## 缓存条目的读取与 Snapshot 机制
读取一个条目需要调用 `DiskLruCache#get(String)` 方法，它返回的是一个 `DiskLruCache$Snapshot` 对象，并且日志文件中会记录此次读取操作。

这里就会牵扯到一个概念：Snapshot。顾名思义，它反映了某一个缓存版本（由 `Entry` 的 `sequenceNumber` 成员变量决定），我们可能会在读取某一个条目的同时去修改这个条目，这会导致之前的版本失效，在 `Snapshot` 对象的 `edit()` 方法中，它会调用 `DiskLruCache#edit(String, long)`，这时 `expectedSequenceNumber` 这个参数就会发挥作用了。由于 `Editor` 修改后变更的是 `Entry` 中的版本号，而 `Snapshot` 的版本号不会发生变化，版本号不一致就会让此方法返回 **null**，从而无法再对此版本的 `Snapshot` 进行编辑。

## 缓存清理
LRU 的意义就在于能够适时清理掉不必要的数据，这个清理操作在 `DiskLruCache` 中由若干部分组成，其中比较重要的是 `DiskLruCache#trimToSize()` 这个方法，实现如下：

```java
private void trimToSize() throws IOException {
    while (size > maxSize) {
        final Map.Entry<String, Entry> toEvict = lruEntries.entrySet().iterator().next();
        remove(toEvict.getKey());
    }
}
```

由于访问操作已经将节点顺序调整好了，因此这里就可以直接依次 remove 各个条目，直到缓存大小不再超出限制。

清理工作的另外一个部分就是重建日志，随着缓存操作的不断进行，日志文件会愈来愈庞大，为了将其稳定在一个合理的大小，我们就需要适时地对日志文件进行重建。重建的条件由这个方法决定：

```java
private boolean journalRebuildRequired() {
    final int REDUNDANT_OP_COMPACT_THRESHOLD = 2000;
    return redundantOpCount >= REDUNDANT_OP_COMPACT_THRESHOLD
            && redundantOpCount >= lruEntries.size();
}
```

当操作的数量达到一定阈值，且记录数多于条目数时才会触发重建。因为有的时候缓存条目很多，可能超过了清理阈值，但没有冗余记录存在（记录数多于条目数），这时就不需要再重建日志了。

而重建日志的逻辑也很简单，依次遍历条目，根据其是否正在编辑来写入 **DIRTY** 或 **CLEAN** 记录就可以了，重建的目的就是为了去除 **READ** 和 **REMOVE** 记录，因为节点顺序已经调整好了，就不需要 **READ** 记录再去重复调整了，而已经删除的条目也不会有对应的 **CLEAN** 记录，因此 **REMOVE** 记录也没有必要存在了。

此外 `DiskLruCache` 还会有一个线程来执行清理工作，很多会写入日志的操作结束时都会让这个线程执行一次清理操作，以保证日志的简洁。

## 小结
到这里 `DiskLruCache` 的核心实现就介绍完了，其实还是比较简单的。这类工具类的实现不难但是很精巧，很多 Edge Cases 都要考虑到，还有多线程的同步问题，日志文件的设计等等，本文也仅仅是一个源码导读，更多的细节还是要大家从源码中去学习。

## 推广信息
如果你对我的 Android 源码分析系列文章感兴趣，可以点个 star 哦，我会持续不定期更新文章。