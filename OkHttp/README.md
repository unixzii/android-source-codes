# OkHttp 拆轮子笔记

Well，我也要开始拆轮子了，第一个轮子就是 `OkHttp` 啦，虽然有关 `OkHttp` 源码分析的文章已经非常多了，但我还是想自己重头系统地分析一遍它。

然后对于 OkHttp 来说我会分很多个子文章去写，接下来可能还会分析一下它的重要依赖 `Okio`，不过应该会再开一个系列吧。

注意，本系列文章以 `okhttp-3.8.1` 版本源码为参考。

## 目录

*WIP Notice: 当前目录仅供参考，可能与系列结束时有所不同。*

0. OkHttp 结构体系总览
1. Dispatcher & Call
2. Interceptor Chain
3. 几个重要的 Interceptor
4. 内部类 HttpCodec、StreamAllocation