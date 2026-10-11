package io.vanillabp.cockpit.camunda7;

import java.util.function.Supplier;

import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.camunda.bpm.model.bpmn.instance.Process;
import org.camunda.bpm.model.bpmn.instance.UserTask;
import org.camunda.bpm.model.xml.instance.ModelElementInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.vanillabp.camunda7.Camunda7ProcessingContext;
import io.vanillabp.camunda7.api.Camunda7TaskDefinitions;
import io.vanillabp.cockpit.extension.spi.BusinessCockpitEventPublisher;
import io.vanillabp.cockpit.extension.wiring.BusinessCockpitWiringService;
import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport;
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

  private final Supplier<BusinessCockpitEventPublisher> publisher;

  /**
   * Builds the wiring service. It writes every claimed process it sees deployed into the given
   * store.
   *
   * @param processes Where the claimed processes are remembered
   * @param workflowTaskWiring VanillaBP's registry of what the application declared, which
   *          answers whether a <code>&#64;WorkflowService</code> claims a process
   * @param publisher The extension, which is told the user tasks of every wired model
   */
  public Camunda7CockpitWiring(
      final Camunda7WorkflowProcesses processes,
      final WorkflowTaskWiring workflowTaskWiring,
      final Supplier<BusinessCockpitEventPublisher> publisher) {

    this.processes = processes;
    this.workflowTaskWiring = workflowTaskWiring;
    this.publisher = publisher;
    // the reports ask the core later which called process shares its caller's aggregate
    processes.rememberTheCore(workflowTaskWiring);

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

    // the user tasks of the model, named with the values the listener reports them with. So
    // VanillaBP holds their details providers against the version the engine gives this
    // model, see decision 16 in the repository's DECISIONS.md
    final var process = processInModel(model, bpmnProcessId);
    if (process == null) {
      return;
    }
    model
        .getModelElementsByType(UserTask.class)
        .stream()
        .filter(userTask -> processOf(userTask) == process)
        .forEach(userTask -> publisher
            .get()
            .userTaskWired(
                workflowModuleId, bpmnProcessId, Camunda7TaskDefinitions
                    .of(Camunda7TaskDefinitions.formKeyOf(userTask), userTask.getId()),
                userTask.getId()));

  }

  /**
   * The process of a file the core handed over. The adapter may have put the workflow module in
   * front of its id while it prepared the model, and this class does not know which adapter
   * prepared it. So both spellings are looked for.
   */
  private static Process processInModel(
      final BpmnModelInstance model,
      final String bpmnProcessId) {

    if (model == null) {
      return null;
    }
    final var scopedSuffix = NameClashAvoidanceSupport.SEPARATOR + bpmnProcessId;
    return model
        .getModelElementsByType(Process.class)
        .stream()
        .filter(process -> bpmnProcessId.equals(process.getId()) || ((process.getId() != null) && process
            .getId()
            .endsWith(scopedSuffix)))
        .findFirst()
        .orElse(null);

  }

  /**
   * The process an element sits in, through every sub-process around it.
   */
  private static Process processOf(
      final ModelElementInstance element) {

    var parent = element.getParentElement();
    while ((parent != null) && !(parent instanceof Process)) {
      parent = parent.getParentElement();
    }
    return (Process) parent;

  }

  @Override
  public void startWorkflowProcessing(
      final String workflowModuleId,
      final Camunda7ProcessingContext bpmsProcessingContext) {

    // nothing to start: the listeners were attached while the engine parsed the models, and
    // telling the cockpit server that the module exists is the platform-neutral half's job

  }

}
