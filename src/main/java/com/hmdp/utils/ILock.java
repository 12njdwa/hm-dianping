package com.hmdp.utils;

public interface ILock {
    boolean getlock (long timeoutSec);

    void unlock();
}
