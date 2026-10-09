package io.vanillabp.cockpit.camunda7;

import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.vanillabp.camunda7.Camunda7ProcessingContext;
import io.vanillabp.cockpit.extension.wiring.BusinessCockpitWiringService;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskWiring;
import io.vanillabp.integration.extension.spi.ExtensionWiringService;

/**
 * The Camunda 7 half of the Business Cockpit in VanillaBP's deployment pipeline.
 * <p>
  * It declares the Camunda 7 adapter's model and processing-context types, so it takes part in
  * the deployment of a workflow module only where that module runs on Camunda 7. What it does
  * there is remember which BPMN processes a <code>&#64;WorkflowService</code> of the application
  * claims. That memory is what turns a process definition key an engine reports back into the
  * workflow module and the process id the application wrote, see
  * {@link Camunda7WorkflowProcesses}.
 * <p>
  * A process the module deploys but no <code>&#64;WorkflowService</code> claims is not remembered.
  * It gets no listener and nothing about it is reported. The same holds for a process which
  * somebody deployed past VanillaBP, because the pipeline never hands that one over at all. See
  * decision 14 in the repository's DECISIONS.md.
 * <p>
  * The model is not touched. On an embedded engine a listener is not written into the BPMN but
  * attached while the engine parses it, which is what the engine customizer of this extension
  * does. A model this extension rewrote would be a model the modeller no longer recognizes.
 * <p>
  * The order is the Business Cockpit's own, and {@link BusinessCockpitWiringService#ORDER} says
  * what that number means. What this class reads is a model the Camunda 7 adapter has already
  * wired. That comes from the pipeline calling the adapter before any extension, not from the
  * number.
 * <p>
 * Why the processes are remembered rather than derived from an engine's identifiers later is
 * decision 4 in the repository's DECISIONS.md.
 */
public class Camunda7CockpitWiring implements ExtensionWiringService<BpmnModelInstance, Camunda7ProcessingContext> {

  private static final Logger logger = LoggerFactory.getLogger(Camunda7CockpitWiring.class);

  private final Camunda7WorkflowProcesses processes;

  private final WorkflowTaskWiring workflowTaskWiring;

  /**
   * Builds the wiring service. It writes every claimed process it sees deployed into the given
   * store.
   *
   * @param processes Where the claimed processes are remembered
   * @param workflowTaskWiring VanillaBP's registry of what the application declared, which
   *          answers whether a <code>&#64;WorkflowService</code> claims a process
   */
  public Camunda7CockpitWiring(
      final Camunda7WorkflowProcesses processes,
      final WorkflowTaskWiring workflowTaskWiring) {

    this.processes = processes;
    this.workflowTaskWiring = workflowTaskWiring;

  }

  @Override
  public Class<BpmnModelInstance> getModelType() {

    return BpmnModelInstance.class;

  }

  @Override
  public Class<Camunda7ProcessingContext> getProcessContextType() {

    return Camunda7ProcessingContext.class;

  }

  @Override
  public int getOrder() {

    return BusinessCockpitWiringService.ORDER;

  }

  @Override
  public void wireBpmn(
      final String workflowModuleId,
      final String filename,
      final String bpmnProcessId,
      final BpmnModelInstance model,
      final Camunda7ProcessingContext context) {

    // the core hands over every process of the file, also one the module only deploys for
    // somebody else. Such a process has no workflow aggregate, so the cockpit has no case to
    // show for it. See decision 14 in the repository's DECISIONS.md
    if (!workflowTaskWiring.isClaimedByAWorkflowService(workflowModuleId, bpmnProcessId)) {
      logger
          .debug(
              "Camunda7: the Business Cockpit reports nothing about BPMN process '{}' of workflow module '{}' (file '{}'): no @WorkflowService of this application claims it",
              bpmnProcessId, workflowModuleId, filename);
      return;
    }

    // the plain process id, as the application wrote it: the core hands it over before the
    // adapter's scoping rewrote anything, which is exactly the form the cockpit reports
    processes.register(workflowModuleId, bpmnProcessId);

  }

  @Override
  public void startWorkflowProcessing(
      final String workflowModuleId,
      final Camunda7ProcessingContext bpmsProcessingContext) {

    // nothing to start: the listeners were attached while the engine parsed the models, and
    // telling the cockpit server that the module exists is the platform-neutral half's job

  }

}
