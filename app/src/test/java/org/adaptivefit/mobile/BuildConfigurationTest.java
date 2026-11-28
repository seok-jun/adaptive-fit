package org.adaptivefit.mobile;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class BuildConfigurationTest {
    @Test
    public void debugBuildTargetsTheExpectedApplication() {
        assertEquals("org.adaptivefit.mobile", BuildConfig.APPLICATION_ID);
        assertEquals("debug", BuildConfig.BUILD_TYPE);
    }
}
