package com.github.gzm55.maven.settings.merge;

import org.apache.maven.settings.Settings;
import org.apache.maven.settings.Server;
import org.apache.maven.settings.TrackableBase;
import org.apache.maven.settings.io.SettingsReader;

import org.codehaus.plexus.util.xml.Xpp3Dom;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import com.github.gzm55.sisu.plexus.PlexusJUnit5TestCase;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;


/**
 * Tests {@code ProjectSettingsMerger}.
 */
public class ProjectSettingsMergerTest extends PlexusJUnit5TestCase
{
  private static final Map<String, ?> options = Collections.singletonMap(SettingsReader.IS_STRICT, Boolean.FALSE);

  @Test
  void testUnsetFields() throws Exception {
    final String pSettings = "<settings>" +
        "<localRepository>fake-dir</localRepository>" +
        "<proxies><proxy><id>proxy-id</id></proxy></proxies>" +
        "<servers><server><id>UK</id><username>test-user</username></server></servers>" +
      "</settings>";

    final Settings projectSettings = merge(pSettings);

    assertNull(projectSettings.getLocalRepository());
    assertEquals(0, projectSettings.getProxies().size());
    assertEquals(1, projectSettings.getServers().size());
    assertEquals("UK", projectSettings.getServers().get(0).getId());
    assertNull(projectSettings.getServers().get(0).getUsername());
  }

  @Test
  void testMergeServer() throws Exception {
    final String pSettings = "<settings>" +
        "<servers>" +
          "<server>" +
            "<id>CN</id>" +
          "</server>" +
          "<server>" +
            "<id>UK</id>" +
            "<username>p-user</username>" +
            "<configuration>" +
              "<v1>pUK111</v1>" +
              "<v2>pUK222</v2>" +
            "</configuration>" +
          "</server>" +
        "</servers>" +
      "</settings>";
    final String uSettings = "<settings>" +
        "<servers>" +
          "<server>" +
            "<id>US</id>" +
          "</server>" +
          "<server>" +
            "<id>UK</id>" +
            "<username>u-user</username>" +
            "<configuration>" +
              "<v2>uUK222</v2>" +
              "<v3>uUK333</v3>" +
            "</configuration>" +
          "</server>" +
        "</servers>" +
      "</settings>";

    final Settings projectSettings = merge(pSettings, uSettings);
    assertNotNull(projectSettings.getServers());
    final Map<String, Server> serverById = new HashMap<String, Server>();
    for (final Server server : projectSettings.getServers()) {
      serverById.put(server.getId(), server);
    }

    assertEquals(3, serverById.size());
    final Server serverUK = serverById.get("UK");
    assertEquals("u-user", serverUK.getUsername());

    final Xpp3Dom conf = (Xpp3Dom)serverUK.getConfiguration();
    assertEquals(2, conf.getChildCount());
    assertEquals("pUK111", conf.getChild("v1").getValue());
    assertEquals("pUK222", conf.getChild("v2").getValue());
  }

  Settings settingsFromString(final String settingsString) throws Exception {
    return lookup(SettingsReader.class).read(new ByteArrayInputStream(settingsString.getBytes(StandardCharsets.UTF_8)), options);
  }

  Settings merge(final String projectSettings) throws Exception {
    final Settings result = settingsFromString(projectSettings);
    new ProjectSettingsMerger().merge(result, new Settings(), TrackableBase.USER_LEVEL);
    return result;
  }
  Settings merge(final String projectSettings, final String anotherString) throws Exception {
    final Settings result = settingsFromString(projectSettings);
    final Settings another = settingsFromString(anotherString);
    new ProjectSettingsMerger().merge(result, another, TrackableBase.USER_LEVEL);
    return result;
  }
}
