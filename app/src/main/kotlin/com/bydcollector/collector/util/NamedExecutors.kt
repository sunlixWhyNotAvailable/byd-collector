package com.bydcollector.collector.util

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

//gives profiler and top -H output stable names instead of pool-N-thread-M
fun namedSingleThreadExecutor(name: String): ExecutorService {
    return Executors.newSingleThreadExecutor { runnable -> Thread(runnable, name) }
}
