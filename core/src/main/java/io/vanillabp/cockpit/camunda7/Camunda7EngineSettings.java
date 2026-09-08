package io.vanillabp.cockpit.camunda7;

/**
 * What the extension has to know about a configured Camunda 7 engine and cannot read from the
 * engine itself.
 * <p>
 * Both answers depend on how the platform builds an engine, which is why they are asked of the
 * platform modules of this repository rather than derived from a property here: the tenant
 * comes out of the VanillaBP Camunda 7 adapter's own configuration overlay
 * (<code>vanillabp.adapters.&lt;id&gt;.*</code>), which is bound differently on Spring Boot
 * than on Quarkus, and whether the engine's work joins the application's transaction is a
 * property of the platform's transaction integration rather than of any single key.
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
   * Whether what this engine does happens inside the transaction the application is in.
   * <p>
   * Where it does, the outbox entry reporting an observed event belongs in that same
   * transaction: the cockpit then hears about a task if and only if the work which created it
   * was committed. Where it does not, there is no transaction to join and the entry needs one
   * of its own - see decision 6 in the repository's DECISIONS.md.
   *
   * @param adapterId The configured adapter id
   * @return Whether the engine's commands run in the application's transaction
   */
  boolean joinsTheApplicationTransaction(
      String adapterId);

}
