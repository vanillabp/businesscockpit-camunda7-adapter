package io.vanillabp.cockpit.camunda7.quarkus;

import io.vanillabp.camunda7.quarkus.runtime.VanillaBpCamunda7Properties;
import io.vanillabp.cockpit.camunda7.Camunda7EngineSettings;

/**
 * What the extension has to know about a Camunda 7 engine of a Quarkus application: the tenant
 * out of the Camunda 7 adapter's own configuration overlay, so that the extension answers what
 * the adapter answers rather than binding <code>vanillabp.adapters.&lt;id&gt;.*</code> a second
 * time and drifting apart from it, and how the engine of this platform is tied into
 * transactions.
 */
public class Camunda7QuarkusSettings implements Camunda7EngineSettings {

  private final VanillaBpCamunda7Properties properties;

  /**
   * @param properties The adapter's overlay of the shared <code>vanillabp</code> tree
   */
  public Camunda7QuarkusSettings(
      final VanillaBpCamunda7Properties properties) {

    this.properties = properties;

  }

  @Override
  public String configuredTenantId(
      final String adapterId) {

    final var adapter = properties.adapters().get(adapterId);
    return adapter == null
        ? null
        : adapter.tenantId().orElse(null);

  }

  /**
   * Always, on this platform. The Camunda 7 adapter builds its Quarkus engine on the engine's
   * own JTA configuration with the container's transaction manager, so every engine command
   * joins the transaction of whoever called it - a named data source changes which database
   * the engine writes to, not who commits it. Reading the data source name here and concluding
   * anything about transactions from it would be reading the wrong key.
   */
  @Override
  public boolean joinsTheApplicationTransaction(
      final String adapterId) {

    return true;

  }

}
