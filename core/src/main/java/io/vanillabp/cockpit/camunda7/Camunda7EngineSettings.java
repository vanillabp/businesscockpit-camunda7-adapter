package io.vanillabp.cockpit.camunda7;

/**
 * What the extension has to know about a configured Camunda 7 engine and cannot read from the
 * engine itself.
 * <p>
 * Both values live in the VanillaBP Camunda 7 adapter's own configuration overlay
 * (<code>vanillabp.adapters.&lt;id&gt;.*</code>), which is bound differently on Spring Boot
 * than on Quarkus. The platform modules of this repository answer them; nothing below this
 * interface knows how they were configured.
 */
public interface Camunda7EngineSettings {

  /**
   * @param adapterId The configured adapter id
   * @return What <code>vanillabp.adapters.&lt;id&gt;.tenant-id</code> says, or
   *         <code>null</code>. It is only ever used to build the tenant the way the adapter
   *         builds it
   */
  String configuredTenantId(
      String adapterId);

  /**
   * Whether this engine commits separately from the application, which is what
   * <code>vanillabp.adapters.&lt;id&gt;.data-source-name</code> makes it do.
   * <p>
   * An engine on the application's own data source runs its listeners inside the
   * application's transaction, so the outbox entry of an observed event belongs in that one.
   * An engine on a data source of its own has no transaction to join, and the entry needs one
   * of its own.
   *
   * @param adapterId The configured adapter id
   * @return Whether the engine has a data source of its own
   */
  boolean runsOnItsOwnDataSource(
      String adapterId);

}
