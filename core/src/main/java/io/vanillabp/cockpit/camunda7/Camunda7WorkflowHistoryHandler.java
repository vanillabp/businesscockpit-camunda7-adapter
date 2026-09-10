package io.vanillabp.cockpit.camunda7;

import java.util.List;

import org.camunda.bpm.engine.impl.history.event.HistoricProcessInstanceEventEntity;
import org.camunda.bpm.engine.impl.history.event.HistoryEvent;
import org.camunda.bpm.engine.impl.history.event.HistoryEventTypes;
import org.camunda.bpm.engine.impl.history.handler.HistoryEventHandler;

import io.vanillabp.cockpit.extension.spi.WorkflowEventKind;

/**
 * How the Business Cockpit learns that a workflow started, ended or was cancelled.
 * <p>
 * The alternative would be an execution listener on every start and end event of every model,
 * which means rewriting models the modeller wrote and missing a workflow whose end nobody
 * modelled. History is the engine's own account of the same thing and needs no model change,
 * and it reports a cancellation - the case an end event never reaches - as an end with a
 * delete reason.
 * <p>
 * VanillaBP installs this handler NEXT TO the engine's own, so history is still written the
 * way it was. Every other kind of history event passes through untouched.
 * <p>
 * See decision 3 in the repository's DECISIONS.md.
 */
public class Camunda7WorkflowHistoryHandler implements HistoryEventHandler {

  private final Camunda7CockpitEvents events;

  /**
   * @param events Where the observed event is reported
   */
  public Camunda7WorkflowHistoryHandler(
      final Camunda7CockpitEvents events) {

    this.events = events;

  }

  @Override
  public void handleEvent(
      final HistoryEvent historyEvent) {

    if (historyEvent instanceof final HistoricProcessInstanceEventEntity processInstance) {
      events.reportWorkflow(processInstance, kindOf(processInstance));
    }

  }

  @Override
  public void handleEvents(
      final List<HistoryEvent> historyEvents) {

    historyEvents.forEach(this::handleEvent);

  }

  /**
   * A process instance which ended carries a delete reason exactly where something ended it
   * from the outside instead of a token reaching an end event, which is what the cockpit
   * distinguishes as cancelled from completed.
   */
  private static WorkflowEventKind kindOf(
      final HistoricProcessInstanceEventEntity processInstance) {

    if (processInstance.isEventOfType(HistoryEventTypes.PROCESS_INSTANCE_START)) {
      return WorkflowEventKind.CREATED;
    }
    if (processInstance.isEventOfType(HistoryEventTypes.PROCESS_INSTANCE_END)) {
      return processInstance.getDeleteReason() != null
          ? WorkflowEventKind.CANCELLED
          : WorkflowEventKind.COMPLETED;
    }
    return WorkflowEventKind.UPDATED;

  }

}
