[![Build Status](https://travis-ci.org/gzm55/project-settings-maven-extension.svg?branch=master)](https://travis-ci.org/gzm55/project-settings-maven-extension)

# project-settings-maven-extension

This extension could load .mvn/settings.xml as project settings, and merge it into effective setting.

Maven originally only takes two configurations, user (`${user.home}/.m2/settings.xml`) and global (`${maven.home}/conf/settings.xml`). When we developing multiply projects from multiply organizations, the configurations may conflict, and are hard to manage (edit manually or some switch scripts). Another problem is when using some corp maven repositories or mirros for bootstraping the team common parent poms or plugins, the developers have to do some setup actions. But the projects should be `./mvnw verify`-ed immediately after the code is fully checked out.

Since Maven 3.3.1, we have another way to cooperate with maven, that is core extensions defined in `${project.basedir}/.mvn/extensions.xml`. Put this extension in this new config file, then when building the maven will load the project level settings from `${project.basedir}/.mvn/settings.xml`, and you can easily solve the above ploblems.

## Merge Order

* project level `${maven.multiModuleProjectDirectory}/.mvn/settings` (Highest priority)
* user level `${user.home}/.m2/settings.xml` (Medium priority)
* global level `${maven.home}/conf/settings.xml` (Low priority)

This order is widely used in many projects such as git.

## Special fields in projects settings

Some fields should be controlled only be user, not project, they are always ignored:

* localRepository
* interactiveMode
* offline
* usePluginRegistry

## Known issue

Project settings can not bootstrap loading core extensions.
