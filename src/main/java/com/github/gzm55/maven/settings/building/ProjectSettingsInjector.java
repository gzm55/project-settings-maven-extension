package com.github.gzm55.maven.settings.building;

import com.github.gzm55.maven.settings.merge.ProjectSettingsMerger;

import org.apache.maven.building.FileSource;
import org.apache.maven.building.Source;
import org.apache.maven.cli.MavenCli;
import org.apache.maven.eventspy.AbstractEventSpy;
import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.execution.MavenExecutionResult;
import org.apache.maven.project.MavenProject;
import org.apache.maven.settings.Settings;
import org.apache.maven.settings.TrackableBase;
import org.apache.maven.settings.building.DefaultSettingsProblem;
import org.apache.maven.settings.building.SettingsBuildingException;
import org.apache.maven.settings.building.SettingsBuildingRequest;
import org.apache.maven.settings.building.SettingsBuildingResult;
import org.apache.maven.settings.building.SettingsProblem;
import org.apache.maven.settings.building.SettingsProblemCollector;
import org.apache.maven.settings.io.SettingsParseException;
import org.apache.maven.settings.io.SettingsReader;
import org.apache.maven.settings.io.SettingsWriter;
import org.apache.maven.settings.validation.SettingsValidator;

import org.codehaus.plexus.logging.Logger;

import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Named;


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

  private ProjectSettingsMerger settingsMerger = new ProjectSettingsMerger();

  private static final String PROJECT_SETTINGS_FILENAME = ".mvn/settings.xml";
  public static final String PROJECT_SETTINGS_SKIP_KEY = "skipProjectSettings";
  private static final String IDEA_VERSION_1 = "idea.version";
  private static final String IDEA_VERSION_2 = "idea.maven.embedder.version";
  private static final String SKIP_IDE_INTEGRATION_KEY = "skipIdeIntegration";

  private List<SettingsProblem> injectingProblems;
  private boolean inIde = false;
  private boolean skipIdeIntegration = false;
  private String localRepo = null;

  @Override
  public void onEvent(final Object event) throws SettingsBuildingException {
    if (event instanceof SettingsBuildingResult && null != injectingProblems) {
      // Assuming the SettingsBuilding{Request,Result} events will be dispatched in paired order.
      ((SettingsBuildingResult)event).getProblems().addAll(0, injectingProblems);
      injectingProblems = null;
      return;
    } else if (event instanceof MavenExecutionRequest) {
      final MavenExecutionRequest mavenExecutionRequest = (MavenExecutionRequest)event;
      final Properties sysProps = mavenExecutionRequest.getSystemProperties();
      inIde = sysProps.containsKey(IDEA_VERSION_1) || sysProps.containsKey(IDEA_VERSION_2);
      skipIdeIntegration = sysProps.containsKey(skipIdeIntegration);
      return;
    } else if (event instanceof MavenExecutionResult
               && inIde && !skipIdeIntegration && null != localRepo) {
      final MavenExecutionResult mavenExecutionResult = (MavenExecutionResult)event;
      if (mavenExecutionResult.hasExceptions()) {
        return;
      }

      
      logger.debug("Make IDE to identify the parent poms downloaded from custom repositories.");

      for (final MavenProject p : mavenExecutionResult.getTopologicallySortedProjects()) {
        MavenProject parent = null;
        for (parent = p.getParent(); null != parent; parent = parent.getParent()) {
          if (null == parent.getFile()) {
            // parent is not a local pom file

            // remove '_remote.repositories' file to mimic local installed artifact
            final String flagFileRelPath = parent.getGroupId().replace('.', File.separatorChar)
                                           + File.separatorChar + parent.getArtifactId()
                                           + File.separatorChar + parent.getVersion()
                                           + File.separatorChar + "_remote.repositories";
            final File internalFlagFile = new File(localRepo, flagFileRelPath);
            if (internalFlagFile.exists()) {
              try {
                if (internalFlagFile.delete()) {
                  if (logger.isDebugEnabled()) {
                    logger.debug("Remove _remote.repositories: " + internalFlagFile.getPath());
                  }
                } else {
                  logger.warn("Failed to remove " + internalFlagFile.getPath());
                }
              } catch (final SecurityException exception) {
                logger.warn("Unable to remove " + internalFlagFile.getPath() + ". {}", exception);
              }
            }

            // remove '*.lastUpdated' file to clean the local cached status
            final String statusFileRelPath = parent.getGroupId().replace('.', File.separatorChar)
                                             + File.separatorChar + parent.getArtifactId()
                                             + File.separatorChar + parent.getVersion()
                                             + File.separatorChar + parent.getArtifactId()
                                             + "-" + parent.getVersion() + ".pom.lastUpdated";
            final File internalStatusFile = new File(localRepo, statusFileRelPath);
            if (internalStatusFile.exists()) {
              try {
                if (internalStatusFile.delete()) {
                  if (logger.isDebugEnabled()) {
                    logger.debug("Remove _remote.repositories: " + internalStatusFile.getPath());
                  }
                } else {
                  logger.warn("Failed to remove " + internalStatusFile.getPath());
                }
              } catch (final SecurityException exception) {
                logger.warn("Unable to remove " + internalStatusFile.getPath() + ". {}", exception);
              }
            }
          }
        }
      }

      return;
    } else if (!(event instanceof SettingsBuildingRequest)) {
      // droping all irrelevant events
      return;
    }

    final SettingsBuildingRequest request = (SettingsBuildingRequest)event;

    if (Boolean.parseBoolean(getProperty(request, PROJECT_SETTINGS_SKIP_KEY, "false"))) {
      if (logger.isDebugEnabled()) {
        logger.debug("Skip loading project settings.");
      }
      return;
    }

    final String multiModuleProjectDirectory =
        getProperty(request.getSystemProperties(), MavenCli.MULTIMODULE_PROJECT_DIRECTORY);

    if (null == multiModuleProjectDirectory) {
      if (logger.isDebugEnabled()) {
        logger.debug("property " + MavenCli.MULTIMODULE_PROJECT_DIRECTORY
            + " is not set while searching project settings.xml.");
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

    final Settings injectSettings = readSettings(injectSource, problems);
    settingsMerger.merge(projectSettings, injectSettings, TrackableBase.USER_LEVEL);

    @SuppressWarnings("deprecation")
    final org.apache.maven.settings.building.SettingsSource resultSource =
        writeSettings(projectSettings,
          "memory(:" + projectSettingsSource.getLocation()
          + (null == injectSource ? "" : ":" + injectSource.getLocation())
          + ")");

    if (null == injectSource || null != userSettingsSource) {
      request.setUserSettingsFile(null)
             .setUserSettingsSource(resultSource);
    } else {
      request.setGlobalSettingsFile(null)
             .setGlobalSettingsSource(resultSource);
    }

    for (final SettingsProblem problem : problems) {
      if (SettingsProblem.Severity.ERROR.compareTo(problem.getSeverity()) >= 0) {
        throw new SettingsBuildingException(problems);
      }
    }

    // save warning problems, insert back on the SettingsBuildingResult event
    injectingProblems = problems.isEmpty() ? null : problems;

    localRepo = projectSettings.getLocalRepository();
    if (null == localRepo) {
      localRepo = getProperty(request.getSystemProperties(), "user.home")
                  + File.separatorChar + ".m2"
                  + File.separatorChar + "repository";
    }
  }

  private Source getSettingsSource(final File settingsFile, final Source settingsSource) {
    if (null != settingsSource) {
      return settingsSource;
    } else if (null != settingsFile && settingsFile.exists()) {
      return new FileSource(settingsFile);
    }
    return null;
  }

  private Settings readSettings(final Source settingsSource, final List<SettingsProblem> problems) {
    if ( settingsSource == null ) {
      return new Settings();
    }

    final SettingsProblemCollector problemsAdder = new SettingsProblemCollector() {
        @Override
        public void add(final SettingsProblem.Severity severity,
            final String message, int line, int col, final Exception cause) {
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
      } catch (final SettingsParseException err) {
        options = Collections.singletonMap( SettingsReader.IS_STRICT, Boolean.FALSE );
        settings = settingsReader.read(settingsSource.getInputStream(), options);
        problemsAdder.add(SettingsProblem.Severity.WARNING, err.getMessage(), 0, 0, err);
      }
    } catch (final SettingsParseException err) {
      problemsAdder.add(SettingsProblem.Severity.FATAL,
          "Non-parseable settings " + settingsSource.getLocation() + ": " + err.getMessage(),
          0, 0, err);
      return new Settings();
    } catch (final IOException err) {
      problemsAdder.add(SettingsProblem.Severity.FATAL,
          "Non-readable settings " + settingsSource.getLocation() + ": " + err.getMessage(),
          -1, -1, err);
      return new Settings();
    }

    settingsValidator.validate(settings, problemsAdder);

    return settings;
  }

  @SuppressWarnings("deprecation")
  private org.apache.maven.settings.building.StringSettingsSource writeSettings(
      final Settings settings, final String location) {
    final StringWriter writer = new StringWriter(1024 * 4);

    try {
      settingsWriter.write(writer, null, settings);
      return new org.apache.maven.settings.building.StringSettingsSource(
          writer.toString(), location);
    } catch (final IOException err) {
      throw new IllegalStateException("Failed to serialize settings to memory", err);
    }
  }

  private String getProperty(final Properties fromProperties, final String key) {
    return getProperty(fromProperties, key, null);
  }

  private String getProperty(final Properties fromProperties, final String key, final String def) {
    return null == fromProperties ? null : fromProperties.getProperty(key, def);
  }

  private String getProperty(final SettingsBuildingRequest fromRequest, final String key) {
    return getProperty(fromRequest, key, null);
  }

  private String getProperty(final SettingsBuildingRequest fromRequest,
      final String key, final String def) {
    String val = getProperty(fromRequest.getUserProperties(), key);
    return null != val ? val : getProperty(fromRequest.getSystemProperties(), key, def);
  }
}
