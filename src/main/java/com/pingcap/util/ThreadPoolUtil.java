package com.pingcap.util;

import com.google.common.util.concurrent.ThreadFactoryBuilder;

import java.util.concurrent.*;

public class ThreadPoolUtil {

    public static ThreadPoolExecutor startJob(int corePoolSize, int maxPoolSize, String fileName) {
        return new ThreadPoolExecutor(
                corePoolSize,
                maxPoolSize,
                0,
                TimeUnit.SECONDS,
                new LinkedBlockingDeque<Runnable>(2),
                new ThreadFactoryBuilder().setDaemon(true).setNameFormat("thread-%d").build(),
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

}
