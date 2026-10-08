package io.vanillabp.cockpit.camunda7.quarkus.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.delegate.TaskListener;
import org.camunda.bpm.engine.impl.bpmn.behavior.UserTaskActivityBehavior;
import org.camunda.bpm.engine.impl.persistence.entity.ProcessDefinitionEntity;
import org.camunda.bpm.engine.repository.ProcessDefinition;
import org.camunda.bpm.model.bpmn.Bpmn;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.camunda7.quarkus.runtime.Camunda7QuarkusEngineRegistry;
import io.vanillabp.cockpit.camunda7.Camunda7UserTaskListener;
import io.vanillabp.cockpit.extension.test.support.CockpitServer;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import jakarta.inject.Inject;

/**
 * The cockpit is told about a process a <code>&#64;WorkflowService</code> claims, and about no
 * other.
 * <p>
 * Two kinds of process are not claimed. The workflow module deploys
 * <code>UnclaimedProcess</code>, but no <code>&#64;WorkflowService</code> names it, and the
 * configuration marks it as run by somebody else. The other kind never passed VanillaBP at all: a
 * test deploys it through the engine's own API. Neither of them gets the cockpit's listener, and
 * a workflow of either one is never reported, even with a business key.
 * <p>
 * A process which a call activity starts is claimed too, when a <code>&#64;WorkflowService</code>
 * names it as a secondary process. Its user task is reported, which the tests about the round of a
 * caller in {@link Camunda7CockpitTest} show.
 * <p>
 * It runs the same way through as the Spring Boot test of the same name.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class Camunda7UnclaimedProcessesTest {

  /** Deployed with the workflow module, claimed by nobody, marked as run elsewhere. */
  private static final String UNCLAIMED_PROCESS_ID = "UnclaimedProcess";

  /** The user task of that process. */
  private static final String UNCLAIMED_TASK_ID = "UP_Approve";

  /** A process somebody deployed through the engine's API, past VanillaBP. */
  private static final String FOREIGN_PROCESS_ID = "ForeignProcess";

  /** The user task of every process this test deploys itself. */
  private static final String FOREIGN_TASK_ID = "FP_Approve";

  private static final String ADAPTER_ID = "c7";

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = TestApplication
      .forTestClass(
          Camunda7UnclaimedProcessesTest.class, "business-cockpit-unclaimed.yaml", jar -> jar
              .addAsResource("c7-cockpit/processes/unclaimed-process.bpmn"))
      .overrideRuntimeConfigKey(
          "vanillabp.cockpit.rest.base-url", CockpitServer.baseUrl());

  @Inject
  Camunda7QuarkusEngineRegistry engines;

  private ProcessEngine engine() {

    return engines.engineFor(ADAPTER_ID).getProcessEngine();

  }

  @BeforeEach
  public void forgetWhatArrivedBefore() {

    CockpitServer.forgetRequests();

  }

  /**
   * Deploys a process with one user task the way an application which knows nothing about
   * VanillaBP does: through the engine's API, without a tenant.
   *
   * @param bpmnProcessId The id of the process
   * @return The definition the engine made of it
   */
  private ProcessDefinition aProcessDeployedPastVanillaBp(
      final String bpmnProcessId) {

    final var deployment = engine()
        .getRepositoryService()
        .createDeployment()
        .name("deployed past VanillaBP")
        .addModelInstance(
            bpmnProcessId
                + ".bpmn",
            Bpmn
                .createExecutableProcess(bpmnProcessId)
                .startEvent()
                .userTask(FOREIGN_TASK_ID)
                .camundaFormKey("approveForeign")
                .endEvent()
                .done())
        .deploy();
    return engine()
        .getRepositoryService()
        .createProcessDefinitionQuery()
        .deploymentId(deployment.getId())
        .singleResult();

  }

  private ProcessDefinition theDefinitionOf(
      final String bpmnProcessId) {

    return engine()
        .getRepositoryService()
        .createProcessDefinitionQuery()
        .processDefinitionKey(bpmnProcessId)
        .latestVersion()
        .singleResult();

  }

  /**
   * @param definition A deployed process definition
   * @param bpmnTaskId The element id of one of its user tasks
   * @return Every built-in listener the engine put on that task, over all four events
   */
  private List<TaskListener> theBuiltInListenersOf(
      final ProcessDefinition definition,
      final String bpmnTaskId) {

    final var deployed = (ProcessDefinitionEntity) engine()
        .getRepositoryService()
        .getProcessDefinition(definition.getId());
    final var taskDefinition = ((UserTaskActivityBehavior) deployed
        .findActivity(bpmnTaskId)
        .getActivityBehavior()).getTaskDefinition();
    return Stream
        .of(
            TaskListener.EVENTNAME_CREATE, TaskListener.EVENTNAME_UPDATE,
            TaskListener.EVENTNAME_COMPLETE, TaskListener.EVENTNAME_DELETE)
        .flatMap(
            eventName -> taskDefinition
                .getBuiltinTaskListeners()
                .getOrDefault(eventName, List.of())
                .stream())
        .toList();

  }

  private static void assertNoCockpitListener(
      final List<TaskListener> listeners) {

    assertTrue(
        listeners.stream().noneMatch(Camunda7UserTaskListener.class::isInstance),
        "the cockpit put its listener on a process nobody claims: %s".formatted(listeners));

  }

  /**
   * Starts a workflow of the definition with a business key, waits until its user task exists,
   * and then until the cockpit server has been quiet long enough to prove that nothing is on its
   * way.
   *
   * @param definition The definition to start
   * @return What the server received about that workflow, which has to be nothing
   */
  private List<CockpitServer.Request> whatArrivesAboutAWorkflowOf(
      final ProcessDefinition definition) {

    final var businessKey = "not-a-case-"
        + UUID.randomUUID();
    final var instance = engine()
        .getRuntimeService()
        .startProcessInstanceById(definition.getId(), businessKey);
    assertEquals(
        1,
        engine()
            .getTaskService()
            .createTaskQuery()
            .processInstanceId(instance.getId())
            .count(),
        "the workflow did not reach its user task, so there was nothing to report");
    CockpitServer.awaitQuiet();
    return CockpitServer
        .received()
        .stream()
        .filter(
            request -> request.body().contains(businessKey) || request.path().contains(instance.getId()))
        .toList();

  }

  @Test
  @DisplayName("A process the module deploys for somebody else gets no listener and is never reported")
  public void aProcessTheModuleDeploysForSomebodyElseIsLeftAlone() {

    final var definition = theDefinitionOf(UNCLAIMED_PROCESS_ID);

    assertNoCockpitListener(theBuiltInListenersOf(definition, UNCLAIMED_TASK_ID));
    assertEquals(List.of(), whatArrivesAboutAWorkflowOf(definition));

  }

  @Test
  @DisplayName("A process deployed past VanillaBP gets no listener and is never reported")
  public void aProcessDeployedPastVanillaBpIsLeftAlone() {

    final var definition = aProcessDeployedPastVanillaBp(FOREIGN_PROCESS_ID);

    assertNoCockpitListener(theBuiltInListenersOf(definition, FOREIGN_TASK_ID));
    assertEquals(List.of(), whatArrivesAboutAWorkflowOf(definition));

  }

  @Test
  @DisplayName("A process a call activity starts is claimed as a secondary process and gets the listener")
  public void aCalledSecondaryProcessGetsTheListener() {

    final var listeners = theBuiltInListenersOf(
        theDefinitionOf(TestWorkflowService.CALLED_SIGN_PROCESS_ID),
        TestWorkflowService.CALLER_SIGN_TASK_ID);

    assertTrue(
        listeners.stream().anyMatch(Camunda7UserTaskListener.class::isInstance),
        "the cockpit put no listener on a process a @WorkflowService claims: %s".formatted(listeners));

  }

}
