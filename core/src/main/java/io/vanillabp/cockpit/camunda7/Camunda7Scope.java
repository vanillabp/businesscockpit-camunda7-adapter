package io.vanillabp.cockpit.camunda7;

import java.util.function.Supplier;

import io.vanillabp.camunda7.api.Camunda7EngineFacts;
import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport;

/**
 * How one configured Camunda 7 engine names the things the extension asks it about.
 * <p>
  * Name-clash avoidance decides whether a workflow module's processes are deployed under a
  * tenant, under a prefixed process id or under neither. Every query the extension sends to an
  * engine has to spell the id the way that engine stores it. The rules are the adapter's, so they
  * are asked of the adapter rather than rebuilt here. A prefix which the extension assembles
  * itself drifts apart from the adapter's on the next change.
 * <p>
  * The tenant is the adapter's own answer, not a property read a second time. It may be
  * configured for a workflow module and for the adapter. Which of the two a module runs under is
  * what the adapter resolves when it deploys that module. Asking anywhere else means querying a
  * tenant the engine never stored.
 * <p>
  * That answer is asked for when it is needed rather than when this scope is built. An engine is
  * built with what this extension contributes to it, so there is nothing to ask about the engine
  * while it is being built. By the time anything is reported or read back, the engine exists.
 */
public final class Camunda7Scope {

  private final String adapterId;

  private final NameClashAvoidanceSupport scoping;

  private final Supplier<Camunda7EngineFacts> engine;

  /**
   * @param adapterId The configured adapter id whose engine this scope belongs to
   * @param scoping VanillaBP's name-clash avoidance
   * @param engine What the Camunda 7 adapter knows about that engine
   */
  public Camunda7Scope(
      final String adapterId,
      final NameClashAvoidanceSupport scoping,
      final Supplier<Camunda7EngineFacts> engine) {

    this.adapterId = adapterId;
    this.scoping = scoping;
    this.engine = engine;

  }

  /**
   * @return The configured adapter id
   */
  public String adapterId() {

    return adapterId;

  }

  /**
   * @param workflowModuleId The workflow module
   * @return The Camunda tenant this module's processes live in, or <code>null</code> where the
   *         configured mode uses none
   */
  public String tenantIdOf(
      final String workflowModuleId) {

    return engine.get().tenantIdOf(workflowModuleId);

  }

  /**
   * @param workflowModuleId The workflow module
   * @param bpmnProcessId The BPMN process id as the application wrote it
   * @return The process definition key the engine knows, which differs from the plain id only
   *         under <code>use-prefix</code>
   */
  public String scopedProcessIdOf(
      final String workflowModuleId,
      final String bpmnProcessId) {

    return NameClashAvoidanceSupport
        .scopedProcessId(scoping, workflowModuleId, bpmnProcessId, adapterId);

  }

}
