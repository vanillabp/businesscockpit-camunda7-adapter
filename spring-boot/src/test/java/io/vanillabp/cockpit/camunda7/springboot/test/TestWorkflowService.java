package io.vanillabp.cockpit.camunda7.springboot.test;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import io.vanillabp.spi.cockpit.BusinessCockpitService;
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
import io.vanillabp.spi.service.TaskParam;
import io.vanillabp.spi.service.WorkflowService;

/**
 * The application under test: a workflow whose user task the cockpit is to show, with a
 * details provider which enriches what the engine reported and writes into the aggregate while
 * doing so.
 */
@Service
@WorkflowService(workflowAggregateClass = TestAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = TestWorkflowService.BPMN_PROCESS_ID),
    secondaryBpmnProcesses = {
        @BpmnProcess(bpmnProcessId = TestWorkflowService.MULTI_INSTANCE_PROCESS_ID), @BpmnProcess(
            bpmnProcessId = TestWorkflowService.NO_FORM_KEY_PROCESS_ID), @BpmnProcess(
                bpmnProcessId = TestWorkflowService.EXPRESSION_FORM_KEY_PROCESS_ID)
    })
public class TestWorkflowService {

  /** The BPMN process of the test. */
  public static final String BPMN_PROCESS_ID = "CockpitProcess";

  /** The form key of the user task, which is what the cockpit calls its task definition. */
  public static final String TASK_DEFINITION = "approve";

  /** The BPMN element id of the user task. */
  public static final String BPMN_TASK_ID = "Approve";

  /**
   * What the engine counted the one deployment of these models as. A reference the test builds
   * itself carries it, so it says what a reference the extension built would say.
   */
  public static final String DEPLOYED_VERSION = "1";

  /** The BPMN process whose user task runs once per signer. */
  public static final String MULTI_INSTANCE_PROCESS_ID = "MultiInstanceProcess";

  /** The form key of the multi-instance user task. */
  public static final String SIGN_TASK_DEFINITION = "sign";

  /** The BPMN element the multi-instance context is keyed by. */
  public static final String MULTI_INSTANCE_ELEMENT = "MI_Sign";

  /** The variable each instance of the multi-instance task gets its signer in. */
  public static final String SIGNER_VARIABLE = "signer";

  /**
   * A variable of the process instance rather than of one multi-instance execution: what a
   * <code>&#64;TaskParam</code> of an enclosing scope is bound from.
   */
  public static final String ORDER_KIND_VARIABLE = "orderKind";

  /** A BPMN process whose user task carries no form key at all. */
  public static final String NO_FORM_KEY_PROCESS_ID = "NoFormKeyProcess";

  /**
   * The BPMN element id of that user task, which is what the cockpit has to call it: a task
   * without a form key falls back to its element id.
   */
  public static final String NO_FORM_KEY_TASK_ID = "NFK_Approve";

  /** A BPMN process whose user task names its form by an expression. */
  public static final String EXPRESSION_FORM_KEY_PROCESS_ID = "ExpressionFormKeyProcess";

  /** The BPMN element id of that user task. */
  public static final String EXPRESSION_FORM_KEY_TASK_ID = "EFK_Approve";

  /**
   * The form key that user task carries, as the modeller wrote it. What the engine computes
   * out of it differs per workflow instance, which is the point of the tests using it.
   */
  public static final String EXPRESSION_FORM_KEY = "${formName}";

  /** The variable that expression reads, set to another value per workflow instance. */
  public static final String FORM_NAME_VARIABLE = "formName";

  /** What the details provider writes into the aggregate, so that a test can see it ran. */
  public static final String APPROVE_NOTE = "seen by the details provider";

  /** The detail every provider below writes, so that a test can read which of them ran. */
  public static final String SERVED_BY = "servedBy";

  /** What the provider serving the first deployed model writes into the details of its task. */
  public static final String APPROVE_OF_THE_FIRST = "the task as the first model asked for it";

  /** What the provider serving every later model writes there. */
  public static final String APPROVE_OF_THE_LATER = "the task as a later model asks for it";

  /** What the provider serving the first deployed model writes into the workflow's details. */
  public static final String WORKFLOW_OF_THE_FIRST = "the case as the first model asked for it";

  /** What the provider serving every later model writes there. */
  public static final String WORKFLOW_OF_THE_LATER = "the case as a later model asks for it";

  private final ProcessService<TestAggregate> processService;

  private final BusinessCockpitService<TestAggregate> businessCockpitService;

  public TestWorkflowService(
      final ProcessService<TestAggregate> processService,
      final BusinessCockpitService<TestAggregate> businessCockpitService) {

    this.processService = processService;
    this.businessCockpitService = businessCockpitService;

  }

  /**
   * @return The process service, so that a test can start a workflow
   */
  public ProcessService<TestAggregate> processes() {

    return processService;

  }

  /**
   * @return The cockpit service, so that a test can report a change of its own
   */
  public BusinessCockpitService<TestAggregate> businessCockpit() {

    return businessCockpitService;

  }

  /**
   * Matched by the form key of the user task. It enriches what the engine reported and writes
   * into the workflow aggregate, which is what a details provider is for.
   * <p>
   * The write has to stay even though no test reads the note any more. It is what leaves the case
   * dirty in the transaction which dispatches the report. A dispatch writing nothing is no second
   * writer of the case, and a second writer is what the version attribute of TestAggregate
   * answers.
   *
   * @param aggregate The workflow aggregate, loaded by VanillaBP
   * @param prefilled What the engine knew about the task
   * @param event What happened to the task
   * @return The very object it was given, which is the common case
   */
  @UserTaskDetailsProvider(taskDefinition = TASK_DEFINITION, version = "1")
  public UserTaskDetails approve(
      final TestAggregate aggregate,
      final PrefilledUserTaskDetails prefilled,
      @DetailsEvent final DetailsEvent.Event event) {

    aggregate.setNote(APPROVE_NOTE);
    prefilled
        .setDetails(
            Map
                .of(
                    "customer", aggregate.getCustomer(), "event", event.name(), "assignee", String
                        .valueOf(prefilled.getAssignee()),
                    SERVED_BY, APPROVE_OF_THE_FIRST));
    prefilled.setCandidateGroups(List.of("approvers"));
    return prefilled;

  }

  /**
   * The same task as every model deployed after the first asks for it. The two ranges do not
   * overlap, which is what lets both methods name the same task.
   *
   * @param prefilled What the engine knew about the task
   * @return The enriched details
   */
  @UserTaskDetailsProvider(taskDefinition = TASK_DEFINITION, version = ">1")
  public UserTaskDetails approveOfALaterModel(
      final PrefilledUserTaskDetails prefilled) {

    prefilled.setDetails(Map.of(SERVED_BY, APPROVE_OF_THE_LATER));
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
   * @param signerVariable The same item, read as the variable the engine set it in
   * @param orderKind A variable of the process instance, which is a scope enclosing the one
   *          this task runs in
   * @return The enriched details
   */
  @UserTaskDetailsProvider(taskDefinition = SIGN_TASK_DEFINITION)
  public UserTaskDetails sign(
      final PrefilledUserTaskDetails prefilled,
      @MultiInstanceElement(MULTI_INSTANCE_ELEMENT) final String signer,
      @MultiInstanceIndex(MULTI_INSTANCE_ELEMENT) final int index,
      @MultiInstanceTotal(MULTI_INSTANCE_ELEMENT) final int total,
      @TaskParam(SIGNER_VARIABLE) final String signerVariable,
      @TaskParam(ORDER_KIND_VARIABLE) final String orderKind) {

    prefilled
        .setDetails(
            Map
                .of(
                    "signer", signer, "index", String.valueOf(index), "total", String
                        .valueOf(total),
                    "signerVariable", String.valueOf(signerVariable), "orderKind", String
                        .valueOf(orderKind)));
    return prefilled;

  }

  /**
   * The workflow as the first deployed model asked for it.
   *
   * @param aggregate The workflow aggregate
   * @param prefilled What the engine knew about the workflow
   * @return The enriched details
   */
  @WorkflowDetailsProvider(version = "1")
  public WorkflowDetails workflowDetails(
      final TestAggregate aggregate,
      final PrefilledWorkflowDetails prefilled) {

    prefilled
        .setDetails(Map.of("customer", aggregate.getCustomer(), SERVED_BY, WORKFLOW_OF_THE_FIRST));
    return prefilled;

  }

  /**
   * The same workflow as every model deployed after the first asks for it. A workflow provider
   * stands for the whole BPMN process, so the version is the only thing telling these two apart.
   *
   * @param prefilled What the engine knew about the workflow
   * @return The enriched details
   */
  @WorkflowDetailsProvider(version = ">1")
  public WorkflowDetails workflowDetailsOfALaterModel(
      final PrefilledWorkflowDetails prefilled) {

    prefilled.setDetails(Map.of(SERVED_BY, WORKFLOW_OF_THE_LATER));
    return prefilled;

  }

}
