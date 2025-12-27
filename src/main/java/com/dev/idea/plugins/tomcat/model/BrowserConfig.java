/**
 * Author: GTLTek
 * Project: DevTomcat
 * Created: 11/1/25
 */


package com.dev.idea.plugins.tomcat.conf;

import java.io.Serializable;

public class BrowserConfig implements Serializable, Cloneable {
    public boolean openBrowser = true;
    public String url = "";
    public boolean withJsDebugger = false;

    public BrowserConfig() {}

    public BrowserConfig(BrowserConfig other) {
        this.openBrowser = other.openBrowser;
        this.url = other.url;
        this.withJsDebugger = other.withJsDebugger;
    }

    @Override
    public BrowserConfig clone() {
        try { return (BrowserConfig) super.clone(); }
        catch (CloneNotSupportedException e) { throw new RuntimeException(e); }
    }
}
