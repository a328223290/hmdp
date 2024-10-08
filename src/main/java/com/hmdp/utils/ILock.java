package com.hmdp.utils;

public interface ILock {

    /**
     * 尝试获取锁
     * @param timeoutSecs 锁持有的超时时间，过期后自动释放
     * @return true代表获取锁成功， false代表失败
     */
    boolean tryLock(long timeoutSecs);

    /**
     * 释放锁
     */
    void unlock();
}
