package io.vanillabp.cockpit.camunda7.quarkus;

import io.vanillabp.camunda7.engine.Camunda7EngineProperties;
import io.vanillabp.camunda7.quarkus.runtime.VanillaBpCamunda7Properties;
import io.vanillabp.cockpit.camunda7.Camunda7EngineSettings;

/**
 * Reads the two engine settings the extension needs out of the Camunda 7 adapter's own
 * configuration overlay, so that the extension answers what the adapter answers rather than
 * binding <code>vanillabp.adapters.&lt;id&gt;.*</code> a second time and drifting apart from it.
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

  @Override
  public boolean runsOnItsOwnDataSource(
      final String adapterId) {

    final var adapter = properties.adapters().get(adapterId);
    return (adapter != null) && !Camunda7EngineProperties
        .isDefaultDataSourceName(adapter.dataSourceName().orElse(null));

  }

}
