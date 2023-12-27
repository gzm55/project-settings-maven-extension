[![Build Status](https://travis-ci.org/gzm55/project-settings-maven-extension.svg?branch=master)](https://travis-ci.org/gzm55/project-settings-maven-extension)
[![Maven Central](https://img.shields.io/maven-central/v/com.github.gzm55.maven/project-settings-extension.svg)](https://search.maven.org/#search%7Cga%7C1%7Cg%3A%22com.github.gzm55.maven%22%20a%3A%22project-settings-extension%22)

# project-settings-extension

This maven extension could load .mvn/settings.xml as project settings, and merge it into effective setting.

Maven originally only takes two configurations, user (`${user.home}/.m2/settings.xml`) and global (`${maven.home}/conf/settings.xml`). When we developing multiply projects from multiply organizations, the configurations may conflict, and are hard to manage (edit manually or some switch scripts). Another problem is when using some corp maven repositories or mirros for bootstraping the team common parent poms or plugins, the developers have to do some setup actions. But the projects should be `./mvnw verify`-ed immediately after the code is fully checked out.

Since Maven 3.3.1, we have another way to cooperate with maven, that is core extensions defined in `${project.basedir}/.mvn/extensions.xml`. Put this extension in this new config file, then when building the maven will load the project level settings from `${project.basedir}/.mvn/settings.xml`, and you can easily solve the above ploblems.

## Example

```xml
<extensions xmlns="http://maven.apache.org/EXTENSIONS/1.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
  xsi:schemaLocation="http://maven.apache.org/EXTENSIONS/1.0.0 http://maven.apache.org/xsd/core-extensions-1.0.0.xsd">
  <extension>
    <groupId>com.github.gzm55.maven</groupId>
    <artifactId>project-settings-extension</artifactId>
    <version>0.3.5</version>
  </extension>
</extensions>
```

## Merge Order

* project level `${maven.multiModuleProjectDirectory}/.mvn/settings.xml` (Highest priority)
* user level `${user.home}/.m2/settings.xml` (Medium priority)
* global level `${maven.home}/conf/settings.xml` (Low priority)

This order is widely used in many projects such as git.

## Special fields in projects settings

Some fields should be controlled only by the user, not any project, so they are always ignored and copied back from user or global level settings:

* localRepository
* interactiveMode
* offline
* usePluginRegistry
* proxies
* servers.server[].{username,password,privateKey,passphrase,filePermissions,directoryPermissions} - ignored and copied from user/global settings

## Detect parent poms from custom repositories by IDE

When specify non-central repositories via project settings from which the parent poms are downloaded,
the IDE, e.g. Intellij, may not import the maven project cleanly, complaining that the parent poms
cannot be got from the central repository, while the maven targets, such as clean, compile and verify
can still be built correctly. To solve this inconvenience, from `0.2.3` this extension try to
mimic the downloaded parent poms are installed locally, by deleting some flag files in `.m2/repository`.
That is, after importing projects, just click any maven target, e.g. `clean`.
When the target successes, the IDE should understand the projects.

## Property switch

- `skipProjectSettings`, a boolean property to skip loading the project settings.
- `skipIdeIntegration`, a boolean property, if true, the extension will disable the feature for IDE scanning the parent poms from custom repositories.

## Compatible server configurations for maven before and after 3.9

[Maven 3.9](https://maven.apache.org/guides/mini/guide-resolver-transport.html) introduces a new
resolver transport and new server config keys in settings.xml. When running on a maven previous
3.9.0, the plugin removes the new incompatible keys. Then we can set server timeout for all maven
version in a project settings.xml like this:

```xml
<server>
  <id>server-name<id>
  <configuration>
    <connectTimeout>5000</connectTimeout> <!-- read by maven >= 3.9 -->
    <httpConfiguration>
      <all>
        <connectionTimeout>5000</connectionTimeout> <!-- read by maven < 3.9 -->
      </all>
    </httpConfiguration>
  </configuration>
</server>
```

## Maven 4

The upcoming maven-4 has [supported](https://issues.apache.org/jira/browse/MNG-5659) the project
settings natively. To work together with all maven versions from 3.3.1 to 4.x, when running on a
maven 4+ environment, the plugin just skips all the watching events, leave the maven to load and
merge the project settings.

## Release Flow

* `./mvnw install`, pass all ut/it, no warnings
* `./mvnw clean release:clean release:prepare`
* `git pull`
* `./mvnw clean release:clean`
* open ci result: https://travis-ci.com/github/gzm55/project-settings-maven-extension

## Known issue

Project settings can not bootstrap loading core extensions. See [MNG-6392](https://issues.apache.org/jira/browse/MNG-6392).
