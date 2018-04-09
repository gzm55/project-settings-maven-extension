package com.github.gzm55.maven.settings.building;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import javax.inject.Named;
import javax.inject.Inject;

import org.apache.maven.building.FileSource;
import org.apache.maven.settings.building.DefaultSettingsBuilder;
import org.apache.maven.settings.building.DefaultSettingsProblem;
import org.apache.maven.settings.building.SettingsBuildingException;
import org.apache.maven.settings.building.SettingsBuildingRequest;
import org.apache.maven.settings.building.SettingsBuildingResult;
import org.apache.maven.settings.building.SettingsProblemCollector;
import org.apache.maven.settings.building.SettingsProblem;
import org.apache.maven.settings.io.SettingsParseException;
import org.apache.maven.settings.io.SettingsReader;
import org.apache.maven.settings.io.SettingsWriter;
import org.apache.maven.settings.merge.MavenSettingsMerger;
import org.apache.maven.settings.validation.SettingsValidator;
import org.apache.maven.settings.Settings;
import org.apache.maven.settings.TrackableBase;

import org.codehaus.plexus.interpolation.EnvarBasedValueSource;
import org.codehaus.plexus.interpolation.InterpolationException;
import org.codehaus.plexus.interpolation.InterpolationPostProcessor;
import org.codehaus.plexus.interpolation.PropertiesBasedValueSource;
import org.codehaus.plexus.interpolation.RegexBasedInterpolator;
import org.codehaus.plexus.logging.Logger;

import static org.apache.maven.cli.MavenCli.MULTIMODULE_PROJECT_DIRECTORY;


/**
 * Override DefaultSettingsBuilder
 * This is a fast implemention, a better way is to implement a new ConfigurationProcessor.
 */
@Named("default")
public class ProjectSettingsBuilder extends DefaultSettingsBuilder {

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

  @Override
  public SettingsBuildingResult build(final SettingsBuildingRequest request)
      throws SettingsBuildingException
  {
    final SettingsBuildingResult superResult = super.build(request);

    final String multiModuleProjectDirectory = null == request.getUserProperties() ?
        null : request.getUserProperties().getProperty(MULTIMODULE_PROJECT_DIRECTORY);

    if (null == multiModuleProjectDirectory) {
      if (logger.isDebugEnabled()) {
        logger.debug("property " + MULTIMODULE_PROJECT_DIRECTORY +
            " is not set when searching project settings");
      }
      return superResult;
    }

    final File projectSettingsFile =
        new File(multiModuleProjectDirectory, PROJECT_SETTINGS_FILENAME);

    if (!projectSettingsFile.exists()) {
      return superResult;
    }

    final List<SettingsProblem> problems = null == superResult.getProblems() ?
        new ArrayList<SettingsProblem>() : superResult.getProblems();

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
                severity, projectSettingsFile.getPath(), line, col, cause));
          }
        };

    // interpolate first to avoid process twice on user/global settings.
    final Settings projectSettings =
        interpolate(readSettings(projectSettingsFile, problemsAdder), request, problemsAdder);

    // always ignore localRepository in project settings
    projectSettings.setLocalRepository(null);

    settingsMerger.merge(projectSettings, superResult.getEffectiveSettings(),
        TrackableBase.USER_LEVEL);

    for (final SettingsProblem problem : problems) {
      if (SettingsProblem.Severity.ERROR.compareTo(problem.getSeverity()) >= 0) {
        throw new SettingsBuildingException(problems);
      }
    }

    return new SettingsBuildingResult()
        {
          @Override public Settings getEffectiveSettings() { return projectSettings; }
          @Override public List<SettingsProblem> getProblems() { return problems; }
        };
  }

  private Settings readSettings(final File settingsFile, final SettingsProblemCollector problems)
  {
    final FileSource settingsSource = new FileSource(settingsFile);

    Settings settings;

    try {
      Map<String, ?> options = Collections.singletonMap(SettingsReader.IS_STRICT, Boolean.TRUE);

      try {
        settings = settingsReader.read(settingsSource.getInputStream(), options);
      } catch (final SettingsParseException e) {
        options = Collections.singletonMap( SettingsReader.IS_STRICT, Boolean.FALSE );
        settings = settingsReader.read(settingsSource.getInputStream(), options);
        problems.add(SettingsProblem.Severity.WARNING,
            e.getMessage(), e.getLineNumber(), e.getColumnNumber(), e);
      }
    } catch (final SettingsParseException e) {
      problems.add(SettingsProblem.Severity.FATAL,
          "Non-parseable settings " + settingsSource.getLocation() + ": " + e.getMessage(),
          e.getLineNumber(), e.getColumnNumber(), e);
      return new Settings();
    } catch (final IOException e) {
      problems.add(SettingsProblem.Severity.FATAL,
          "Non-readable settings " + settingsSource.getLocation() + ": " + e.getMessage(),
          -1, -1, e);
      return new Settings();
    }

    settingsValidator.validate(settings, problems);

    return settings;
  }

  private Settings interpolate(final Settings settings, final SettingsBuildingRequest request,
      final SettingsProblemCollector problems)
  {
    final StringWriter writer = new StringWriter( 1024 * 4 );

    try {
      settingsWriter.write(writer, null, settings);
    } catch (final IOException e) {
      throw new IllegalStateException("Failed to serialize settings to memory", e);
    }

    String serializedSettings = writer.toString();
    final RegexBasedInterpolator interpolator = new RegexBasedInterpolator();

    interpolator.addValueSource(new PropertiesBasedValueSource(request.getUserProperties()));
    interpolator.addValueSource(new PropertiesBasedValueSource(request.getSystemProperties()));

    try {
      interpolator.addValueSource(new EnvarBasedValueSource());
    } catch (final IOException e) {
      problems.add(SettingsProblem.Severity.WARNING,
          "Failed to use environment variables for interpolation: " + e.getMessage(), -1, -1, e );
    }

    interpolator.addPostProcessor(new InterpolationPostProcessor()
        {
          @Override
          public Object execute(final String expression, final Object value)
          {
            // we're going to parse this back in as XML so we need to escape XML markup
            return null == value ? null : value.toString()
                .replace( "&", "&amp;" ).replace( "<", "&lt;" ).replace( ">", "&gt;" );
          }
        });

    try {
      serializedSettings = interpolator.interpolate(serializedSettings, "settings");
    } catch (final InterpolationException e) {
      problems.add(SettingsProblem.Severity.ERROR,
          "Failed to interpolate settings: " + e.getMessage(), -1, -1, e );

      return settings;
    }

    try {
      Map<String, ?> options = Collections.singletonMap(SettingsReader.IS_STRICT, Boolean.FALSE);
      return settingsReader.read(new StringReader(serializedSettings), options);
    } catch (final IOException e) {
      problems.add(SettingsProblem.Severity.ERROR,
          "Failed to interpolate settings: " + e.getMessage(), -1, -1, e );
      return settings;
    }
  }
}
