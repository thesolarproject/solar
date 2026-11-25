package com.solar.launcher;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;

public class AgentDeveloperSupportTest {

    @Test
    public void resolveAdbTcpPort_filtersInvalid() {
        if (AgentDeveloperSupport.resolveAdbTcpPort(null) != null) throw new AssertionError("null");
        if (AgentDeveloperSupport.resolveAdbTcpPort("") != null) throw new AssertionError("empty");
        if (AgentDeveloperSupport.resolveAdbTcpPort("-1") != null) throw new AssertionError("-1");
        if (AgentDeveloperSupport.resolveAdbTcpPort("0") != null) throw new AssertionError("0");
        if (!"5555".equals(AgentDeveloperSupport.resolveAdbTcpPort("5555"))) throw new AssertionError("5555");
        if (!"5555".equals(AgentDeveloperSupport.resolveAdbTcpPort(" 5555 "))) throw new AssertionError("trim");
    }

    @Test
    public void buildInstallCommand_allowsOnlyKnownPaths() {
        if (AgentDeveloperSupport.buildInstallCommand(null) != null) throw new AssertionError("null");
        if (AgentDeveloperSupport.buildInstallCommand("/sdcard/evil.apk") != null) throw new AssertionError("evil");
        String cmd = AgentDeveloperSupport.buildInstallCommand(AgentDeveloperSupport.TEST_APK_PRIMARY);
        if (!"pm install -r /sdcard/Solar/test.apk".equals(cmd)) throw new AssertionError("primary");
        String cmd2 = AgentDeveloperSupport.buildInstallCommand(AgentDeveloperSupport.TEST_APK_FALLBACK);
        if (!"pm install -r /sdcard/Download/app-release.apk".equals(cmd2)) throw new AssertionError("fallback");
    }

    @Test
    public void describeTestApk_reportsSize() throws Exception {
        File f = File.createTempFile("agent-dev-test", ".apk");
        try {
            FileOutputStream out = new FileOutputStream(f);
            out.write(new byte[128]);
            out.close();
            String desc = AgentDeveloperSupport.describeTestApk(f.getAbsolutePath());
            if (desc == null || !desc.contains(f.getAbsolutePath())) throw new AssertionError("path");
            if (!desc.contains("128 bytes")) throw new AssertionError("size: " + desc);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }
}
