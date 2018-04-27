package com.github.gzm55.maven.settings.merge;

import org.apache.maven.settings.Proxy;
import org.apache.maven.settings.Server;
import org.apache.maven.settings.Settings;
import org.apache.maven.settings.merge.MavenSettingsMerger;

import org.codehaus.plexus.util.xml.Xpp3Dom;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;


public class ProjectSettingsMerger extends MavenSettingsMerger {

  /**
   * @param dominant i.e, a project setting
   * @param recessive a user or global level settings
   * @param recessiveSourceLevel souce level
   */
  public void merge(final Settings dominant, final Settings recessive,
      final String recessiveSourceLevel) {
    if (null == dominant || null == recessive) {
      return;
    }

    // Always ignore some locally used fields in project settings
    dominant.setLocalRepository(recessive.getLocalRepository());
    dominant.setInteractiveMode(recessive.isInteractiveMode());
    dominant.setUsePluginRegistry(recessive.isUsePluginRegistry());
    dominant.setOffline(recessive.isOffline());

    // All proxies are ignored, cause it is always defined at user side
    // and its authentication can't be config in <servers/>
    dominant.setProxies(new ArrayList<Proxy>());

    // Ignore authentication infos in <servers/> of project settings,
    // and deep merge server configuration from both Settings
    // NB: order of the elements in <servers/> does not matter.
    final Map<String, Server> serverById = new HashMap<String, Server>();
    for (final Server server : dominant.getServers()) {
      server.setUsername(null);
      server.setPassword(null);
      server.setPrivateKey(null);
      server.setPassphrase(null);
      server.setFilePermissions(null);
      server.setDirectoryPermissions(null);

      serverById.put(server.getId(), server);
    }

    for (final Server server : recessive.getServers()) {
      if (serverById.containsKey(server.getId())) {
        final Server dominantServer = serverById.get(server.getId());

        // prefer user level fields for local only infos
        dominantServer.setUsername(server.getUsername());
        dominantServer.setPassword(server.getPassword());
        dominantServer.setPrivateKey(server.getPrivateKey());
        dominantServer.setPassphrase(server.getPassphrase());
        dominantServer.setFilePermissions(server.getFilePermissions());
        dominantServer.setDirectoryPermissions(server.getDirectoryPermissions());

        // merge configuration
        final Xpp3Dom mergedConf = Xpp3Dom.mergeXpp3Dom((Xpp3Dom)dominantServer.getConfiguration(),
            (Xpp3Dom)server.getConfiguration());
        dominantServer.setConfiguration(mergedConf);
      } else {
        dominant.getServers().add(server);
      }
    }

    recessive.setServers(dominant.getServers());
    dominant.setServers(new ArrayList<Server>());

    super.merge(dominant, recessive, recessiveSourceLevel);
  }
}
