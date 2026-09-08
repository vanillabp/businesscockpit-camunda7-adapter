package io.vanillabp.cockpit.camunda7;

import io.vanillabp.camunda7.wiring.Camunda7Scoping;
import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport;

/**
 * How one configured Camunda 7 engine names the things the extension asks it about.
 * <p>
 * Name-clash avoidance decides whether a workflow module's processes are deployed under a
 * tenant, under a prefixed process id or under neither, and every query the extension sends to
 * an engine has to spell the id the way that engine stores it. The rules are the adapter's, so
 * they are asked of the adapter's own helper rather than rebuilt here: a prefix which the
 * extension assembles itself is a prefix which drifts apart from the adapter's on the next
 * change.
 */
public final class Camunda7Scope {

  private final String adapterId;

  private final NameClashAvoidanceSupport scoping;

  private final String configuredTenantId;

  /**
   * @param adapterId The configured adapter id whose engine this scope belongs to
   * @param scoping VanillaBP's name-clash avoidance, or <code>null</code> where the platform
   *          offers none
   * @param configuredTenantId What the adapter was configured with, or <code>null</code>
   */
  public Camunda7Scope(
      final String adapterId,
      final NameClashAvoidanceSupport scoping,
      final String configuredTenantId) {

    this.adapterId = adapterId;
    this.scoping = scoping;
    this.configuredTenantId = configuredTenantId;

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

    return Camunda7Scoping.tenantIdFor(scoping, workflowModuleId, adapterId, configuredTenantId);

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

    return scoping == null
        ? bpmnProcessId
        : scoping.scopedProcessId(workflowModuleId, bpmnProcessId, adapterId);

  }

}
