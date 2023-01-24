package com.github.gzm55.maven.settings.building;

import static org.apache.maven.cli.MavenCli.MULTIMODULE_PROJECT_DIRECTORY;
import static org.junit.jupiter.api.Assertions.*;

import com.github.gzm55.sisu.plexus.PlexusJUnit5TestCase;
import java.io.File;
import java.util.Collections;
import java.util.Map;
import java.util.Properties;
import org.apache.maven.eventspy.EventSpy;
import org.apache.maven.settings.Settings;
import org.apache.maven.settings.building.*;
import org.apache.maven.settings.building.SettingsBuildingException;
import org.apache.maven.settings.io.SettingsReader;
import org.codehaus.plexus.ContainerConfiguration;
import org.codehaus.plexus.PlexusConstants;
import org.junit.jupiter.api.Test;

/** Tests {@code ProjectSettingsInjector}. */
public class ProjectSettingsInjectorTest extends PlexusJUnit5TestCase {
  @Override
  protected void customizeContainerConfiguration(final ContainerConfiguration configuration) {
    // scan the maven jsr330 compontents
    configuration.setClassPathScanning(PlexusConstants.SCANNING_INDEX);
  }

  @Test
  void testNoMultiPrjDir() throws Exception {
    SettingsBuildingRequest request =
        new DefaultSettingsBuildingRequest().setUserSettingsFile(new File("fake-path"));
    lookup(EventSpy.class, "project-settings").onEvent(request);
    assertEquals("fake-path", request.getUserSettingsFile().getPath());
  }

  @Test
  void testNoPrjSettings() throws Exception {
    Properties sysProps = new Properties();
    sysProps.setProperty(MULTIMODULE_PROJECT_DIRECTORY, "/non-exist-dir");

    SettingsBuildingRequest request =
        new DefaultSettingsBuildingRequest()
            .setUserSettingsFile(new File("fake-path-2"))
            .setSystemProperties(sysProps);
    lookup(EventSpy.class, "project-settings").onEvent(request);
    assertEquals("fake-path-2", request.getUserSettingsFile().getPath());

    request = new DefaultSettingsBuildingRequest();
    lookup(EventSpy.class, "project-settings").onEvent(request);
    assertNull(request.getUserSettingsFile());
  }

  @Test
  void testInjectInvalidXml() throws Exception {
    Properties sysProps = new Properties();
    sysProps.setProperty(
        MULTIMODULE_PROJECT_DIRECTORY,
        getClass().getClassLoader().getResource("invalid-xml").getFile());

    final SettingsBuildingRequest request =
        new DefaultSettingsBuildingRequest().setSystemProperties(sysProps);
    assertThrows(
        SettingsBuildingException.class,
        () -> lookup(EventSpy.class, "project-settings").onEvent(request));

    sysProps.setProperty(
        MULTIMODULE_PROJECT_DIRECTORY,
        getClass().getClassLoader().getResource("empty-settings").getFile());
    assertThrows(
        SettingsBuildingException.class,
        () -> lookup(EventSpy.class, "project-settings").onEvent(request));
  }

  @Test
  @SuppressWarnings("deprecation")
  void testInjectWithoutBoth() throws Exception {
    Properties sysProps = new Properties();
    sysProps.setProperty(
        MULTIMODULE_PROJECT_DIRECTORY,
        getClass().getClassLoader().getResource("normal-empty").getFile());
    SettingsBuildingRequest request =
        new DefaultSettingsBuildingRequest().setSystemProperties(sysProps);

    assertNull(request.getUserSettingsFile());
    assertNull(request.getUserSettingsSource());
    assertNull(request.getGlobalSettingsFile());
    assertNull(request.getGlobalSettingsSource());

    lookup(EventSpy.class, "project-settings").onEvent(request);

    assertNull(request.getUserSettingsFile());
    assertNotNull(request.getUserSettingsSource());
    assertTrue(request.getUserSettingsSource() instanceof StringSettingsSource);
    assertNull(request.getGlobalSettingsFile());
    assertNull(request.getGlobalSettingsSource());

    Map<String, ?> options = Collections.singletonMap(SettingsReader.IS_STRICT, Boolean.FALSE);
    Settings settings =
        lookup(SettingsReader.class)
            .read(request.getUserSettingsSource().getInputStream(), options);
    assertEquals(new Settings().getLocalRepository(), settings.getLocalRepository());
  }

  @Test
  @SuppressWarnings("deprecation")
  void testInjectUser() throws Exception {
    Properties sysProps = new Properties();
    sysProps.setProperty(
        MULTIMODULE_PROJECT_DIRECTORY, getClass().getClassLoader().getResource("normal").getFile());
    SettingsBuildingRequest request =
        new DefaultSettingsBuildingRequest()
            .setSystemProperties(sysProps)
            .setUserSettingsSource(
                new StringSettingsSource(
                    "<settings><localRepository>user-defined</localRepository></settings>"));

    lookup(EventSpy.class, "project-settings").onEvent(request);

    assertNull(request.getUserSettingsFile());
    assertNotNull(request.getUserSettingsSource());
    assertTrue(request.getUserSettingsSource() instanceof StringSettingsSource);
    assertNull(request.getGlobalSettingsFile());
    assertNull(request.getGlobalSettingsSource());

    Map<String, ?> options = Collections.singletonMap(SettingsReader.IS_STRICT, Boolean.FALSE);
    Settings settings =
        lookup(SettingsReader.class)
            .read(request.getUserSettingsSource().getInputStream(), options);

    assertEquals("user-defined", settings.getLocalRepository());
    assertEquals(1, settings.getMirrors().size());
    assertEquals("UK", settings.getMirrors().get(0).getId());
  }

  @Test
  @SuppressWarnings("deprecation")
  void testInjectGlobal() throws Exception {
    Properties sysProps = new Properties();
    sysProps.setProperty(
        MULTIMODULE_PROJECT_DIRECTORY, getClass().getClassLoader().getResource("normal").getFile());
    SettingsBuildingRequest request =
        new DefaultSettingsBuildingRequest()
            .setSystemProperties(sysProps)
            .setGlobalSettingsSource(
                new StringSettingsSource(
                    "<settings><localRepository>global-defined</localRepository></settings>"));

    lookup(EventSpy.class, "project-settings").onEvent(request);

    assertNull(request.getUserSettingsFile());
    assertNull(request.getUserSettingsSource());
    assertNull(request.getGlobalSettingsFile());
    assertNotNull(request.getGlobalSettingsSource());
    assertTrue(request.getGlobalSettingsSource() instanceof StringSettingsSource);

    Map<String, ?> options = Collections.singletonMap(SettingsReader.IS_STRICT, Boolean.FALSE);
    Settings settings =
        lookup(SettingsReader.class)
            .read(request.getGlobalSettingsSource().getInputStream(), options);

    assertEquals("global-defined", settings.getLocalRepository());
    assertEquals(1, settings.getMirrors().size());
    assertEquals("UK", settings.getMirrors().get(0).getId());
  }

  @Test
  @SuppressWarnings("deprecation")
  void testInjectBoth() throws Exception {
    String userSettings =
        "<settings><localRepository>user-defined</localRepository><mirrors><mirror><id>um</id><url>u</url><mirrorOf>central</mirrorOf></mirror></mirrors></settings>";
    String globalSettings =
        "<settings><localRepository>global-defined</localRepository><mirrors><mirror><id>gm</id><url>u</url><mirrorOf>central</mirrorOf></mirror></mirrors></settings>";
    Properties sysProps = new Properties();
    sysProps.setProperty(
        MULTIMODULE_PROJECT_DIRECTORY, getClass().getClassLoader().getResource("normal").getFile());
    SettingsBuildingRequest request =
        new DefaultSettingsBuildingRequest()
            .setSystemProperties(sysProps)
            .setUserSettingsSource(new StringSettingsSource(userSettings))
            .setGlobalSettingsSource(new StringSettingsSource(globalSettings));

    lookup(EventSpy.class, "project-settings").onEvent(request);

    assertNull(request.getUserSettingsFile());
    assertNotNull(request.getUserSettingsSource());
    assertTrue(request.getUserSettingsSource() instanceof StringSettingsSource);
    assertNull(request.getGlobalSettingsFile());
    assertTrue(request.getGlobalSettingsSource() instanceof StringSettingsSource);
    assertEquals(
        globalSettings, ((StringSettingsSource) request.getGlobalSettingsSource()).getContent());

    Map<String, ?> options = Collections.singletonMap(SettingsReader.IS_STRICT, Boolean.FALSE);
    Settings settings =
        lookup(SettingsReader.class)
            .read(request.getUserSettingsSource().getInputStream(), options);

    assertEquals("user-defined", settings.getLocalRepository());
    assertEquals(2, settings.getMirrors().size());
    assertEquals("UK", settings.getMirrors().get(0).getId());
    assertEquals("um", settings.getMirrors().get(1).getId());
  }

  @Test
  @SuppressWarnings("deprecation")
  void testSkipInject() throws Exception {
    Properties sysProps = new Properties();
    sysProps.setProperty(
        MULTIMODULE_PROJECT_DIRECTORY, getClass().getClassLoader().getResource("normal").getFile());
    SettingsBuildingRequest request =
        new DefaultSettingsBuildingRequest().setSystemProperties(sysProps);

    lookup(EventSpy.class, "project-settings").onEvent(request);
    Map<String, ?> options = Collections.singletonMap(SettingsReader.IS_STRICT, Boolean.FALSE);
    Settings settings =
        lookup(SettingsReader.class)
            .read(request.getUserSettingsSource().getInputStream(), options);

    assertEquals(1, settings.getMirrors().size());
    assertEquals("UK", settings.getMirrors().get(0).getId());

    // add skip property
    Properties userProps = new Properties();
    userProps.setProperty(ProjectSettingsInjector.PROJECT_SETTINGS_SKIP_KEY, "true");
    request.setUserProperties(userProps).setUserSettingsSource(null);

    lookup(EventSpy.class, "project-settings").onEvent(request);
    assertNull(request.getUserSettingsFile());
    assertNull(request.getUserSettingsSource());
    assertNull(request.getGlobalSettingsFile());
    assertNull(request.getGlobalSettingsSource());
  }
}
