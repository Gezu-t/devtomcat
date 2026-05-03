package com.dev.idea.plugins.tomcat.model;

import com.dev.idea.plugins.tomcat.utils.TomcatStrings;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.text.StringUtil;
import org.jetbrains.annotations.NotNull;

import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;

/**
 * Browser Configuration for Tomcat Deployment.
 */
public class BrowserConfig implements Serializable, Cloneable {

    private static final Logger LOG = Logger.getInstance(BrowserConfig.class);

    @Serial
    private static final long serialVersionUID = 1L;

    private static final boolean DEFAULT_OPEN_BROWSER = true;
    private static final String DEFAULT_URL = "";
    private static final boolean DEFAULT_WITH_JS_DEBUGGER = false;
    private static final String DEFAULT_BROWSER_NAME = "System Default";
    private static final int MAX_URL_LENGTH = 1024;

    private boolean openBrowser;
    private String url;
    private boolean withJsDebugger;
    private String browserName;

    public BrowserConfig() {
        this.openBrowser = DEFAULT_OPEN_BROWSER;
        this.url = DEFAULT_URL;
        this.withJsDebugger = DEFAULT_WITH_JS_DEBUGGER;
        this.browserName = DEFAULT_BROWSER_NAME;
    }

    public BrowserConfig(boolean openBrowser, @NotNull String url, boolean withJsDebugger) {
        this.openBrowser = openBrowser;
        setUrl(url);
        this.withJsDebugger = withJsDebugger;
        this.browserName = DEFAULT_BROWSER_NAME;
    }

    public BrowserConfig(@NotNull BrowserConfig other) {
        Objects.requireNonNull(other, "Source BrowserConfig cannot be null");
        this.openBrowser = other.openBrowser;
        this.url = StringUtil.notNullize(other.url);
        this.withJsDebugger = other.withJsDebugger;
        this.browserName = TomcatStrings.defaultIfBlank(other.browserName, DEFAULT_BROWSER_NAME);
    }

    public boolean isOpenBrowser() {
        return openBrowser;
    }

    public void setOpenBrowser(boolean openBrowser) {
        this.openBrowser = openBrowser;
    }

    @NotNull
    public String getUrl() {
        return StringUtil.notNullize(url).trim();
    }

    /**
     * Alias for getUrl() - used by serializer.
     */
    @NotNull
    public String getBrowserUrl() {
        return getUrl();
    }

    public void setUrl(@NotNull String url) {
        Objects.requireNonNull(url, "URL cannot be null");
        String normalized = url.trim();
        if (normalized.length() > MAX_URL_LENGTH) {
            LOG.warn("URL too long, using default");
            this.url = DEFAULT_URL;
        } else {
            this.url = normalized;
        }
    }

    /**
     * Alias for setUrl() - used by serializer.
     */
    public void setBrowserUrl(@NotNull String url) {
        setUrl(url);
    }

    public boolean isWithJsDebugger() {
        return withJsDebugger;
    }

    public void setWithJsDebugger(boolean withJsDebugger) {
        this.withJsDebugger = withJsDebugger;
    }

    @NotNull
    public String getBrowserName() {
        return TomcatStrings.defaultIfBlank(browserName, DEFAULT_BROWSER_NAME).trim();
    }

    public void setBrowserName(@NotNull String browserName) {
        Objects.requireNonNull(browserName, "Browser name cannot be null");
        this.browserName = browserName.trim();
    }

    /**
     * Alias for isOpenBrowser() - used by serializer.
     */
    public boolean isAfterLaunchEnabled() {
        return isOpenBrowser();
    }

    /**
     * Alias for setOpenBrowser() - used by serializer.
     */
    public void setAfterLaunchEnabled(boolean enabled) {
        setOpenBrowser(enabled);
    }

    public boolean isValid() {
        return getUrl().length() <= MAX_URL_LENGTH;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BrowserConfig that = (BrowserConfig) o;
        return openBrowser == that.openBrowser &&
                withJsDebugger == that.withJsDebugger &&
                Objects.equals(getUrl(), that.getUrl()) &&
                Objects.equals(getBrowserName(), that.getBrowserName());
    }

    @Override
    public int hashCode() {
        return Objects.hash(openBrowser, getUrl(), withJsDebugger, getBrowserName());
    }

    @NotNull
    @Override
    public BrowserConfig clone() {
        try {
            return (BrowserConfig) super.clone();
        } catch (CloneNotSupportedException e) {
            return new BrowserConfig(this);
        }
    }

    @NotNull
    @Override
    public String toString() {
        return "BrowserConfig{openBrowser=" + openBrowser +
                ", url='" + getUrl() + "', withJsDebugger=" + withJsDebugger + '}';
    }
}