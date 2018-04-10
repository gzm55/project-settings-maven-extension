package com.github.gzm55.maven.settings.building;

import java.io.File;
import java.util.List;
import java.util.HashSet;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import org.codehaus.plexus.PlexusConstants;
import org.codehaus.plexus.ContainerConfiguration;

import org.apache.maven.AbstractMavenLifecycleParticipant;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.execution.DefaultMavenExecutionRequest;
import org.apache.maven.bridge.MavenRepositorySystem;

import com.github.gzm55.sisu.plexus.PlexusJUnit5TestCase;


import static org.apache.maven.cli.MavenCli.MULTIMODULE_PROJECT_DIRECTORY;


import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import javax.inject.Named;
import javax.inject.Inject;

import org.apache.maven.building.FileSource;
import org.apache.maven.building.Source;
import org.apache.maven.eventspy.AbstractEventSpy;
import org.apache.maven.eventspy.EventSpy;
import org.apache.maven.settings.building.*;
import org.apache.maven.settings.io.SettingsParseException;
import org.apache.maven.settings.io.SettingsReader;
import org.apache.maven.settings.io.SettingsWriter;
import org.apache.maven.settings.merge.MavenSettingsMerger;
import org.apache.maven.settings.validation.SettingsValidator;
import org.apache.maven.settings.Settings;
import org.apache.maven.settings.TrackableBase;





/**
 * Tests {@code ProjectSettingsInjector}.
 */
public class ProjectSettingsInjectorTest extends PlexusJUnit5TestCase
{
  @Override
  protected void customizeContainerConfiguration(final ContainerConfiguration configuration)
  {
    // scan the maven jsr330 compontents
    configuration.setClassPathScanning(PlexusConstants.SCANNING_INDEX);
  }

  @Test
  void testNoMultiPrjDir() throws Exception {
    SettingsBuildingRequest request = new DefaultSettingsBuildingRequest().setUserSettingsFile(new File("fake-path"));
    lookup(EventSpy.class, "project-settings").onEvent(request);
    assertEquals("fake-path", request.getUserSettingsFile().getPath());
  }

  @Test
  void testNoPrjSettings() throws Exception {
    Properties sysProps = new Properties();
    sysProps.setProperty(MULTIMODULE_PROJECT_DIRECTORY, "/non-exist-dir");

    SettingsBuildingRequest request = new DefaultSettingsBuildingRequest()
        .setUserSettingsFile(new File("fake-path-2"))
        .setSystemProperties(sysProps);
    lookup(EventSpy.class, "project-settings").onEvent(request);
    assertEquals("fake-path-2", request.getUserSettingsFile().getPath());

    request = new DefaultSettingsBuildingRequest();
    lookup(EventSpy.class, "project-settings").onEvent(request);
    assertNull(request.getUserSettingsFile());
  }

  @Test
  void testInjectUser() throws Exception {
  }
}
