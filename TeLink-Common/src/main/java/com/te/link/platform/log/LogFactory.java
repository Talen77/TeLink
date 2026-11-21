package com.te.link.platform.log;

public class LogFactory {
    public static Log getLog(Class<?> clazz) {
        return new Log(clazz.getName());
    }
}
