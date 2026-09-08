package io.vanillabp.cockpit.camunda7.quarkus.it;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.cockpit.extension.spi.BusinessCockpitBpmsBridge;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import jakarta.inject.Inject;

/**
 * An adapter id which is configured without a section of its own, named in
 * <code>prioritized-adapters</code> and nowhere else.
 * <p>
 * That is an everyday shape - the order of the engines is the only thing a migration has to say
 * about the second one - and it is the shape this half used to miss. Filtering the configured
 * adapter TYPES finds nothing for such an id, so the adapter built its engine and the cockpit
 * built no bridge, and every event of that engine was then reported to nobody. Which ids belong
 * to an adapter type is therefore VanillaBP's answer
 * ({@code MigrationAdapterProperties#adapterIdsOfType}) rather than this repository's.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class Camunda7BridgeOfAPrioritizedAdapterTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .withApplicationRoot(
          jar -> jar
              .addAsResource("business-cockpit-prioritized-only.yaml", "application.yaml")
              .addAsResource("c7-cockpit/processes/cockpit-process.bpmn")
              .addAsResource(
                  "workflow-module-descriptor/workflow-module", "META-INF/workflow-module")
              .addClass(TestAggregate.class)
              .addClass(TestAggregatePersistence.class)
              .addClass(TestWorkflowService.class))
      .overrideRuntimeConfigKey(
          "vanillabp.cockpit.rest.base-url", "http://localhost:1");

  @Inject
  List<BusinessCockpitBpmsBridge> bridges;

  @Test
  @DisplayName("An adapter id which only stands in prioritized-adapters has a bridge")
  public void theBridgeOfAPrioritizedAdapterExists() {

    assertEquals(1, bridges.size(), bridges.toString());
    assertEquals("camunda7", bridges.getFirst().adapterId());

  }

}
