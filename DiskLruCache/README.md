# DiskLruCache 实现分析

## 前言

本文中的 `DiskLruCache` 实现来自 AOSP，同时也被 OkHttp 的缓存系统所采用。这个类，顾名思义就是能够将缓存数据持久化到磁盘上，并使用 LRU 淘汰算法来维持缓存保持在一个稳定的大小。

这里想多谈一谈缓存。

很多文章中会提到一些诸如 “图片三级缓存” 这样的话题，在我看来，应用需要实现好的其实就两级缓存：内存缓存和磁盘缓存。图片从网络下载下来之后首先放置在内存中做缓存，此时的内存缓存分为两种，一种是未解码数据的缓存（即 JPG、PNG、GIF 文件的二进制字节）；另外一种则是解码后的图像像素数据的缓存，这类数据通常很大（可以用 `Bitmap#getByteCount()` 来估测占用内存的大小），各类缓存池的大小需要在开发的时候控制好。

`DiskLruCache` 充当了所有缓存中的最后一级（不讨论网络缓存），也是容量最大的缓存，通常可以大量存放一些图片、音频等数据，当内存缓存均不命中时可以调取。我们知道，Android SDK 和 Support Library 提供了 `LruCache` 这个类，来让我们更方便地去实现内存 LRU 缓存，但 `DiskLruCache` 并没有提供，大家需要手动从 AOSP 中提取，也可以从本文的[参考源码](#source)中下载。

## 使用方法
`DiskLruCache` 的使用方法十分简单，本文就不再赘述了，不熟悉的同学可以先看一下我写的一个简单的[单元测试](#unit)。

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