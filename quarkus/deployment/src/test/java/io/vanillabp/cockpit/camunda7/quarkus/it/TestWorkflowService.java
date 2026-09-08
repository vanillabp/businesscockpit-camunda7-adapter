package io.vanillabp.cockpit.camunda7.quarkus.it;

import java.util.List;
import java.util.Map;

import io.vanillabp.spi.cockpit.details.DetailsEvent;
import io.vanillabp.spi.cockpit.usertask.PrefilledUserTaskDetails;
import io.vanillabp.spi.cockpit.usertask.UserTaskDetails;
import io.vanillabp.spi.cockpit.usertask.UserTaskDetailsProvider;
import io.vanillabp.spi.cockpit.workflow.PrefilledWorkflowDetails;
import io.vanillabp.spi.cockpit.workflow.WorkflowDetails;
import io.vanillabp.spi.cockpit.workflow.WorkflowDetailsProvider;
import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.MultiInstanceElement;
import io.vanillabp.spi.service.MultiInstanceIndex;
import io.vanillabp.spi.service.MultiInstanceTotal;
import io.vanillabp.spi.service.WorkflowService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The application under test: a workflow whose user task the cockpit is to show, the Quarkus
 * twin of the Spring Boot module's test application.
 */
@ApplicationScoped
@WorkflowService(workflowAggregateClass = TestAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = TestWorkflowService.BPMN_PROCESS_ID),
    secondaryBpmnProcesses = @BpmnProcess(bpmnProcessId = TestWorkflowService.MULTI_INSTANCE_PROCESS_ID))
public class TestWorkflowService {

  /** The BPMN process of the test. */
  public static final String BPMN_PROCESS_ID = "CockpitProcess";

  /** The form key of the user task, which is what the cockpit calls its task definition. */
  public static final String TASK_DEFINITION = "approve";

  /** The BPMN element id of the user task. */
  public static final String BPMN_TASK_ID = "Approve";

  /** The BPMN process whose user task runs once per signer. */
  public static final String MULTI_INSTANCE_PROCESS_ID = "MultiInstanceProcess";

  /** The form key of the multi-instance user task. */
  public static final String SIGN_TASK_DEFINITION = "sign";

  /** The BPMN element the multi-instance context is keyed by. */
  public static final String MULTI_INSTANCE_ELEMENT = "MI_Sign";

  /** What the details provider writes into the aggregate, so that a test can see it ran. */
  public static final String APPROVE_NOTE = "seen by the details provider";

  @Inject
  ProcessService<TestAggregate> processService;

  /**
   * @return The process service, so that a test can start a workflow
   */
  public ProcessService<TestAggregate> processes() {

    return processService;

  }

  /**
   * Matched by the form key of the user task.
   *
   * @param aggregate The workflow aggregate, loaded by VanillaBP
   * @param prefilled What the engine knew about the task
   * @param event What happened to the task
   * @return The very object it was given
   */
  @UserTaskDetailsProvider(taskDefinition = TASK_DEFINITION)
  public UserTaskDetails approve(
      final TestAggregate aggregate,
      final PrefilledUserTaskDetails prefilled,
      @DetailsEvent final DetailsEvent.Event event) {

    aggregate.setNote(APPROVE_NOTE);
    prefilled.setDetails(Map.of("customer", aggregate.getCustomer(), "event", event.name()));
    prefilled.setCandidateGroups(List.of("approvers"));
    return prefilled;

  }


  /**
   * Matched by the form key of the multi-instance user task. Its parameters are the
   * multi-instance context the engine reported: the item, the index and the total.
   *
   * @param prefilled What the engine knew about the task
   * @param signer The item this instance is for
   * @param index Which instance this is
   * @param total How many there are
   * @return The enriched details
   */
  @UserTaskDetailsProvider(taskDefinition = SIGN_TASK_DEFINITION)
  public UserTaskDetails sign(
      final PrefilledUserTaskDetails prefilled,
      @MultiInstanceElement(MULTI_INSTANCE_ELEMENT) final String signer,
      @MultiInstanceIndex(MULTI_INSTANCE_ELEMENT) final int index,
      @MultiInstanceTotal(MULTI_INSTANCE_ELEMENT) final int total) {

    prefilled
        .setDetails(
            Map.of("signer", signer, "index", String.valueOf(index), "total", String.valueOf(total)));
    return prefilled;

  }

  /**
   * The one provider a BPMN process may have for its workflow.
   *
   * @param aggregate The workflow aggregate
   * @param prefilled What the engine knew about the workflow
   * @return The enriched details
   */
  @WorkflowDetailsProvider
  public WorkflowDetails workflowDetails(
      final TestAggregate aggregate,
      final PrefilledWorkflowDetails prefilled) {

    prefilled.setDetails(Map.of("customer", aggregate.getCustomer()));
    return prefilled;

  }

}
