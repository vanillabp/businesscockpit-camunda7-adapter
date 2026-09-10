package io.vanillabp.cockpit.camunda7;

import org.camunda.bpm.engine.delegate.DelegateTask;
import org.camunda.bpm.engine.delegate.TaskListener;

import io.vanillabp.cockpit.extension.spi.UserTaskEventKind;

/**
 * The listener the Business Cockpit attaches to one user task of one engine.
 * <p>
 * It knows its element from the moment the engine parsed it, so an event costs a lookup of the
 * process and one outbox entry. Everything the cockpit shows about the task is read later,
 * when the entry is dispatched and the engine's transaction is long committed.
 */
public class Camunda7UserTaskListener implements TaskListener {

  private final Camunda7CockpitEvents events;

  private final String bpmnTaskId;

  private final String taskDefinition;

  /**
   * @param events Where the observed event is reported
   * @param bpmnTaskId The BPMN element id of the user task this listener sits on
   * @param taskDefinition The task's form key, or its element id where it has none
   */
  public Camunda7UserTaskListener(
      final Camunda7CockpitEvents events,
      final String bpmnTaskId,
      final String taskDefinition) {

    this.events = events;
    this.bpmnTaskId = bpmnTaskId;
    this.taskDefinition = taskDefinition;

  }

  @Override
  public void notify(
      final DelegateTask delegateTask) {

    final var kind = kindOf(delegateTask.getEventName());
    if (kind == null) {
      return;
    }
    events.reportUserTask(delegateTask, bpmnTaskId, taskDefinition, kind);

  }

  /**
   * @return What the cockpit calls this engine event, or <code>null</code> for an event the
   *         cockpit has no kind for - this listener is registered for four of them, and a
   *         fifth arriving from a later engine is not something to fail a workflow over
   */
  private static UserTaskEventKind kindOf(
      final String eventName) {

    return switch (eventName) {
      case TaskListener.EVENTNAME_CREATE -> UserTaskEventKind.CREATED;
      case TaskListener.EVENTNAME_UPDATE -> UserTaskEventKind.UPDATED;
      case TaskListener.EVENTNAME_COMPLETE -> UserTaskEventKind.COMPLETED;
      case TaskListener.EVENTNAME_DELETE -> UserTaskEventKind.CANCELED;
      default -> null;
    };

  }

}
