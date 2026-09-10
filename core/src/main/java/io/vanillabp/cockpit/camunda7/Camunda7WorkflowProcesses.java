package io.vanillabp.cockpit.camunda7;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Which BPMN processes of which workflow modules this application deployed, and how to get
 * from an engine's own identifiers back to them.
 * <p>
 * An engine reports a task or a history event with the process definition key and the tenant
 * it stored, and both of those depend on the name-clash avoidance the module was deployed
 * with. The translation back is therefore not a string operation but a lookup: every process
 * VanillaBP wires is remembered here while the deployment pipeline runs, and an event of a
 * process which is not in here belongs to something else - another application on the same
 * database, or a process definition of an earlier release which is no longer part of this one.
 * <p>
 * The reverse map is built per adapter id and thrown away whenever a process is added, which
 * happens a handful of times while the application starts and never afterwards.
 * <p>
 * Why a lookup rather than a prefix somebody cuts off a string is decision 4 in the repository's
 * DECISIONS.md.
 */
public class Camunda7WorkflowProcesses {

  /**
   * One BPMN process of one workflow module, named the way the application wrote it.
   *
   * @param workflowModuleId The workflow module
   * @param bpmnProcessId The plain BPMN process id
   */
  public record WorkflowProcess(
                                String workflowModuleId,
                                String bpmnProcessId) {
  }

  /**
   * Joins the two halves of an engine identity. A process definition key is a BPMN process id
   * and carries no blank, so the key is always what stands behind the last blank and no pair of
   * halves can be read as another pair.
   */
  private static final String SEPARATOR = " ";

  private final Set<WorkflowProcess> processes = new CopyOnWriteArraySet<>();

  private final Map<String, Map<String, WorkflowProcess>> byEngineIdentity = new ConcurrentHashMap<>();

  /**
   * Remembers a BPMN process VanillaBP is deploying. Called while the deployment pipeline runs
   * and therefore before the engine parses that module's files.
   *
   * @param workflowModuleId The workflow module
   * @param bpmnProcessId The plain BPMN process id
   */
  public void register(
      final String workflowModuleId,
      final String bpmnProcessId) {

    if (processes.add(new WorkflowProcess(workflowModuleId, bpmnProcessId))) {
      byEngineIdentity.clear();
    }

  }

  /**
   * The workflow module and BPMN process an engine's identifiers belong to.
   *
   * @param scope The engine which reported them
   * @param tenantId The tenant the engine stored, <code>null</code> where it stored none
   * @param processDefinitionKey The process definition key the engine stored
   * @return What the application calls it, or empty where no deployed process of this
   *         application matches
   */
  public Optional<WorkflowProcess> resolve(
      final Camunda7Scope scope,
      final String tenantId,
      final String processDefinitionKey) {

    if (processDefinitionKey == null) {
      return Optional.empty();
    }
    return Optional
        .ofNullable(
            byEngineIdentity
                .computeIfAbsent(scope.adapterId(), adapterId -> engineIdentities(scope))
                .get(identity(tenantId, processDefinitionKey)));

  }

  private Map<String, WorkflowProcess> engineIdentities(
      final Camunda7Scope scope) {

    final var identities = new HashMap<String, WorkflowProcess>();
    processes
        .forEach(
            process -> identities
                .putIfAbsent(
                    identity(
                        scope.tenantIdOf(process.workflowModuleId()),
                        scope.scopedProcessIdOf(
                            process.workflowModuleId(), process.bpmnProcessId())),
                    process));
    return Map.copyOf(identities);

  }

  private static String identity(
      final String tenantId,
      final String processDefinitionKey) {

    return (tenantId == null ? "" : tenantId) + SEPARATOR + processDefinitionKey;

  }

}
