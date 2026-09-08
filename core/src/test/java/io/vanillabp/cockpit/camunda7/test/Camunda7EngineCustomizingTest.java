package io.vanillabp.cockpit.camunda7.test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.stream.Stream;

import org.camunda.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.cockpit.camunda7.Camunda7CockpitCustomizer;
import io.vanillabp.cockpit.camunda7.Camunda7EngineSettings;
import io.vanillabp.cockpit.camunda7.Camunda7WorkflowProcesses;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.scoping.NameClashAvoidanceService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What the extension contributes to an engine which is about to be built, and what it refuses to
 * be built with.
 * <p>
 * The refusal is the interesting half. An engine which keeps less history than <code>audit</code>
 * runs perfectly well and reports nothing to the cockpit, which is the kind of defect somebody
 * looks for in the wrong place for a day, so it ends the boot instead.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda7EngineCustomizingTest {

  private static final String ADAPTER_ID = "c7";

  private static Camunda7CockpitCustomizer aCustomizer() {

    return new Camunda7CockpitCustomizer(
        new Camunda7WorkflowProcesses(), new NameClashAvoidanceService(new MigrationAdapterProperties()), new Camunda7EngineSettings() {

          @Override
          public String configuredTenantId(
              final String adapterId) {

            return null;

          }

          @Override
          public boolean joinsTheApplicationTransaction(
              final String adapterId) {

            return true;

          }

        }, RecordingPublisher::new);

  }

  private static ProcessEngineConfigurationImpl anEngineKeeping(
      final String history) {

    final var configuration = mock(ProcessEngineConfigurationImpl.class);
    when(configuration.getHistory()).thenReturn(history);
    return configuration;

  }

  @Test
  @DisplayName("An engine keeping too little history ends the boot, naming the level and the way out")
  public void tooLittleHistoryEndsTheBoot() {

    Stream.of("none", "activity").forEach(history -> {
      final var failure = assertThrows(
          IllegalStateException.class,
          () -> aCustomizer().customize(ADAPTER_ID, anEngineKeeping(history)));

      assertTrue(failure.getMessage().contains(history), failure.getMessage());
      assertTrue(failure.getMessage().contains(ADAPTER_ID), failure.getMessage());
      assertTrue(failure.getMessage().contains("audit"), failure.getMessage());
      assertTrue(failure.getMessage().contains("full"), failure.getMessage());
    });

  }

  @Test
  @DisplayName("A level which keeps enough is accepted, and so is one the engine resolves later")
  public void enoughHistoryIsAccepted() {

    // 'auto' is the level an engine reads from its own database while it is built, which is
    // after this ran; an engine which never had a level set carries none at all
    Stream
        .of("audit", "full", "auto", null)
        .forEach(
            history -> assertDoesNotThrow(
                () -> aCustomizer().customize(ADAPTER_ID, anEngineKeeping(history))));

  }

  @Test
  @DisplayName("Every engine gets the cockpit's parse listener and its history handler")
  public void everyEngineGetsTheListeners() {

    final var customizer = aCustomizer();

    assertEquals(1, customizer.parseListenersAfter(ADAPTER_ID).size());
    assertEquals(List.of(), customizer.parseListenersBefore(ADAPTER_ID));
    assertNotNull(customizer.historyEventHandler(ADAPTER_ID));
    assertEquals(ADAPTER_ID, customizer.scopeOf(ADAPTER_ID).adapterId());

  }

}
