package io.vanillabp.cockpit.camunda7.springboot;

import io.vanillabp.camunda7.engine.Camunda7EngineProperties;
import io.vanillabp.camunda7.springboot.VanillaBpCamunda7Properties;
import io.vanillabp.cockpit.camunda7.Camunda7EngineSettings;

/**
 * What the extension has to know about a Camunda 7 engine of a Spring Boot application: the
 * tenant out of the Camunda 7 adapter's own configuration overlay, so that the extension
 * answers what the adapter answers rather than binding
 * <code>vanillabp.adapters.&lt;id&gt;.*</code> a second time and drifting apart from it, and
 * how the engine of this platform is tied into transactions.
 */
public class Camunda7SpringSettings implements Camunda7EngineSettings {

  private final VanillaBpCamunda7Properties properties;

  /**
   * @param properties The adapter's overlay of the shared <code>vanillabp</code> tree
   */
  public Camunda7SpringSettings(
      final VanillaBpCamunda7Properties properties) {

    this.properties = properties;

  }

  @Override
  public String configuredTenantId(
      final String adapterId) {

    final var adapter = adapterOf(adapterId);
    return adapter == null
        ? null
        : adapter.getTenantId();

  }

  /**
   * On Spring Boot an engine on the application's data source is built with the application's
   * own transaction manager, so its commands run in the transaction the application is in. An
   * engine named a data source of its own is built with a transaction manager of its own, and
   * a command of it then commits without the application noticing.
   */
  @Override
  public boolean joinsTheApplicationTransaction(
      final String adapterId) {

    final var adapter = adapterOf(adapterId);
    return (adapter == null) || !adapter.usesSeparateDataSource();

  }

  /**
   * The overlay is a lookup by a known id and never the source of the id set: an environment
   * variable can materialize a map entry for an adapter nobody configured.
   */
  private Camunda7EngineProperties adapterOf(
      final String adapterId) {

    return properties.getAdapters().get(adapterId);

  }

}
