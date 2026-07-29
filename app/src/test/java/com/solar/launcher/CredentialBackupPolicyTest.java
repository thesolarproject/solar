package com.solar.launcher;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;

import static org.junit.Assert.assertTrue;

/** API 17 cannot selectively exclude credential preferences from adb backup. */
public class CredentialBackupPolicyTest {

    @Test
    public void manifestDisablesApplicationDataBackup() throws Exception {
        File manifest = new File("app/src/main/AndroidManifest.xml");
        if (!manifest.isFile()) manifest = new File("src/main/AndroidManifest.xml");
        BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(manifest), "UTF-8"));
        StringBuilder xml = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) xml.append(line);
        reader.close();
        assertTrue(xml.toString().contains("android:allowBackup=\"false\""));
    }
}
