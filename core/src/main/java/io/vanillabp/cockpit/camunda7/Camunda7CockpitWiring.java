package io.vanillabp.cockpit.camunda7;

import org.camunda.bpm.model.bpmn.BpmnModelInstance;

import io.vanillabp.camunda7.Camunda7ProcessingContext;
import io.vanillabp.cockpit.extension.wiring.BusinessCockpitWiringService;
import io.vanillabp.integration.extension.spi.ExtensionWiringService;

/**
 * The Camunda 7 half of the Business Cockpit in VanillaBP's deployment pipeline.
 * <p>
 * It declares the Camunda 7 adapter's model and processing-context types, so it takes part in
 * the deployment of a workflow module only where that module runs on Camunda 7. What it does
 * there is remember which BPMN processes exist, because that is what turns a process
 * definition key an engine reports back into the workflow module and the process id the
 * application wrote - see {@link Camunda7WorkflowProcesses}.
 * <p>
 * The model is not touched. On an embedded engine a listener is not written into the BPMN but
 * attached while the engine parses it, which is what the engine customizer of this extension
 * does; a model this extension rewrote would be a model the modeller no longer recognizes.
 * <p>
 * The order is the Business Cockpit's own, the last one, so whatever an adapter or another
 * extension does to a model has happened by the time this runs.
 * <p>
 * Why the processes are remembered rather than derived from an engine's identifiers later is
 * decision 4 in the repository's DECISIONS.md.
 */
public class Camunda7CockpitWiring implements ExtensionWiringService<BpmnModelInstance, Camunda7ProcessingContext> {

  private final Camunda7WorkflowProcesses processes;

  /**
   * @param processes Where the deployed processes are remembered
   */
  public Camunda7CockpitWiring(
      final Camunda7WorkflowProcesses processes) {

    this.processes = processes;

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
