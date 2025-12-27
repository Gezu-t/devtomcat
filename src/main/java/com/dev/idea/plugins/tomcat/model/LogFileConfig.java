/**
 * Author: GTLTek
 * Project: DevTomcat
 * Created: 11/1/25
 */


package com.dev.idea.plugins.tomcat.conf;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public class LogFileConfig implements Serializable, Cloneable {
    public List<String> logFiles = new ArrayList<>();

    public LogFileConfig() {}

    public LogFileConfig(LogFileConfig other) {
        this.logFiles.addAll(other.logFiles);
    }

    @Override
    public LogFileConfig clone() {
        try {
            LogFileConfig clone = (LogFileConfig) super.clone();
            clone.logFiles = new ArrayList<>(this.logFiles);
            return clone;
        } catch (CloneNotSupportedException e) {
            throw new RuntimeException(e);
        }
    }
}