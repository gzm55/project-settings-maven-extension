package com.github.gzm55.maven.settings.building;

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
import org.apache.maven.settings.building.*;
import org.apache.maven.settings.io.SettingsParseException;
import org.apache.maven.settings.io.SettingsReader;
import org.apache.maven.settings.io.SettingsWriter;
import org.apache.maven.settings.merge.MavenSettingsMerger;
import org.apache.maven.settings.validation.SettingsValidator;
import org.apache.maven.settings.Settings;
import org.apache.maven.settings.TrackableBase;

import org.codehaus.plexus.logging.Logger;

import static org.apache.maven.cli.MavenCli.MULTIMODULE_PROJECT_DIRECTORY;


/**
 * Spy the SettingsBuildingRequest to inject project settings.
 * This is a fast implemention, a better way is to implement a new ConfigurationProcessor.
 */
@Named("project-settings")
public class ProjectSettingsInjector extends AbstractEventSpy {

  @Inject
  private Logger logger;

  @Inject
  private SettingsReader settingsReader;

  @Inject
  private SettingsWriter settingsWriter;

  @Inject
  private SettingsValidator settingsValidator;

  private MavenSettingsMerger settingsMerger = new MavenSettingsMerger();

  private static final String PROJECT_SETTINGS_FILENAME = ".mvn/settings.xml";

  private List<SettingsProblem> injectingProblems;

  private static final Settings TEMPLATE_SETTINGS = new Settings();

  @Override
  public void onEvent(final Object event)
      throws SettingsBuildingException
  {
    if (event instanceof SettingsBuildingResult && null != injectingProblems) {
      // Assuming the SettingsBuilding{Request,Result} events will be dispatched in paired order.
      ((SettingsBuildingResult)event).getProblems().addAll(injectingProblems);
      injectingProblems = null;
      return;
    } else if (!(event instanceof SettingsBuildingRequest)) {
      // droping all irrelevant events
      return;
    }

    final SettingsBuildingRequest request = (SettingsBuildingRequest)event;

    // TODO skip property flag

    final String multiModuleProjectDirectory = null == request.getSystemProperties() ?
        null : request.getSystemProperties().getProperty(MULTIMODULE_PROJECT_DIRECTORY);

    if (null == multiModuleProjectDirectory) {
      if (logger.isDebugEnabled()) {
        logger.debug("property " + MULTIMODULE_PROJECT_DIRECTORY +
            " is not set while searching project settings.xml.");
      }
      return;
    }

    final File projectSettingsFile =
        new File(multiModuleProjectDirectory, PROJECT_SETTINGS_FILENAME);

    if (!projectSettingsFile.exists()) {
      return;
    }

    final List<SettingsProblem> problems = new ArrayList<SettingsProblem>();

    if (logger.isDebugEnabled()) {
      logger.debug("Reading project settings from " + projectSettingsFile.getPath());
    }
    final Source projectSettingsSource = getSettingsSource(projectSettingsFile, null);
    final Settings projectSettings = readSettings(projectSettingsSource, problems);

    final Source globalSettingsSource =
        getSettingsSource(request.getGlobalSettingsFile(), request.getGlobalSettingsSource());
    final Source userSettingsSource =
        getSettingsSource(request.getUserSettingsFile(), request.getUserSettingsSource());

    final Source injectSource =
        null != userSettingsSource ? userSettingsSource : globalSettingsSource;

    if (null == injectSource) {
      // always ignore some fields in project settings
      projectSettings.setLocalRepository(TEMPLATE_SETTINGS.getLocalRepository());
      projectSettings.setInteractiveMode(TEMPLATE_SETTINGS.isInteractiveMode());
      projectSettings.setUsePluginRegistry(TEMPLATE_SETTINGS.isUsePluginRegistry());
      projectSettings.setOffline(TEMPLATE_SETTINGS.isOffline());

      request.setUserSettingsFile(null)
             .setUserSettingsSource(
          writeSettings(projectSettings, "(memory:" + projectSettingsSource.getLocation()+")"));
    } else {
      final boolean injectAsUser = null != userSettingsSource;
      final String sourceLvl = injectAsUser ? TrackableBase.USER_LEVEL : TrackableBase.GLOBAL_LEVEL;
      final Settings injectSettings = readSettings(injectSource, problems);

      // always ignore some fields in project settings
      projectSettings.setLocalRepository(injectSettings.getLocalRepository());
      projectSettings.setInteractiveMode(injectSettings.isInteractiveMode());
      projectSettings.setUsePluginRegistry(injectSettings.isUsePluginRegistry());
      projectSettings.setOffline(injectSettings.isOffline());

      settingsMerger.merge(projectSettings, injectSettings, sourceLvl);

      if (injectAsUser) {
        request.setUserSettingsFile(null)
               .setUserSettingsSource(writeSettings(projectSettings,
            "(memory:" + projectSettingsSource.getLocation()+":"+injectSource.getLocation() +")"));
      } else {
        request.setGlobalSettingsFile(null)
               .setGlobalSettingsSource(writeSettings(projectSettings,
            "(memory:" + projectSettingsSource.getLocation()+":"+injectSource.getLocation() +")"));
      }
    }

    for (final SettingsProblem problem : problems) {
      if (SettingsProblem.Severity.ERROR.compareTo(problem.getSeverity()) >= 0) {
        throw new SettingsBuildingException(problems);
      }
    }

    // save warning problems, insert back on the SettingsBuildingResult event
    injectingProblems = problems.isEmpty() ? null : problems;
  }

  private Source getSettingsSource(final File settingsFile, final Source settingsSource)
  {
    if (null != settingsSource) {
      return settingsSource;
    } else if (null != settingsFile && settingsFile.exists()) {
      return new FileSource(settingsFile);
    }
    return null;
  }

  private Settings readSettings(final Source settingsSource, final List<SettingsProblem> problems)
  {
    final SettingsProblemCollector problemsAdder = new SettingsProblemCollector()
        {
          @Override
          public void add(final SettingsProblem.Severity severity,
              final String message, int line, int col, final Exception cause)
          {
            if (line <= 0 && col <= 0 && cause instanceof SettingsParseException) {
              final SettingsParseException e = (SettingsParseException)cause;
              line = e.getLineNumber();
              col = e.getColumnNumber();
            }
            problems.add(new DefaultSettingsProblem(message,
                severity, settingsSource.getLocation(), line, col, cause));
          }
        };

    Settings settings;

    try {
      Map<String, ?> options = Collections.singletonMap(SettingsReader.IS_STRICT, Boolean.TRUE);

      try {
        settings = settingsReader.read(settingsSource.getInputStream(), options);
      } catch (final SettingsParseException e) {
        options = Collections.singletonMap( SettingsReader.IS_STRICT, Boolean.FALSE );
        settings = settingsReader.read(settingsSource.getInputStream(), options);
        problemsAdder.add(SettingsProblem.Severity.WARNING, e.getMessage(), 0, 0, e);
      }
    } catch (final SettingsParseException e) {
      problemsAdder.add(SettingsProblem.Severity.FATAL,
          "Non-parseable settings " + settingsSource.getLocation() + ": " + e.getMessage(),
          0, 0, e);
      return new Settings();
    } catch (final IOException e) {
      problemsAdder.add(SettingsProblem.Severity.FATAL,
          "Non-readable settings " + settingsSource.getLocation() + ": " + e.getMessage(),
          -1, -1, e);
      return new Settings();
    }

    settingsValidator.validate(settings, problemsAdder);

    return settings;
  }

  @SuppressWarnings("deprecation")
  private StringSettingsSource writeSettings(final Settings settings, final String location)
  {
    final StringWriter writer = new StringWriter(1024 * 4);

    try {
      settingsWriter.write(writer, null, settings);
      return new StringSettingsSource(writer.toString(), location);
    } catch (final IOException e) {
      throw new IllegalStateException("Failed to serialize settings to memory", e);
    }
  }
}
