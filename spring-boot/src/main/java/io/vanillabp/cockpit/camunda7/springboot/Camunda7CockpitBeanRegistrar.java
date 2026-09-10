package io.vanillabp.cockpit.camunda7.springboot;

import org.camunda.bpm.engine.ProcessEngine;
import org.springframework.beans.factory.BeanRegistrar;
import org.springframework.beans.factory.BeanRegistry;
import org.springframework.core.env.Environment;

import io.vanillabp.camunda7.Camunda7Adapter;
import io.vanillabp.camunda7.springboot.engine.Camunda7EngineHolder;
import io.vanillabp.cockpit.camunda7.Camunda7CockpitBridge;
import io.vanillabp.cockpit.camunda7.Camunda7CockpitCustomizer;
import io.vanillabp.cockpit.camunda7.Camunda7WorkflowProcesses;
import io.vanillabp.cockpit.extension.spi.BusinessCockpitBpmsBridge;
import io.vanillabp.integration.adapter.AdapterBeanRegistrarSupport;

/**
 * Registers one Business Cockpit bridge per configured Camunda 7 adapter id.
 * <p>
 * The cockpit addresses a workflow by the BPMS holding it, and during a migration that is a
 * different BPMS per workflow, so two configured Camunda 7 engines are two bridges and the
 * platform-neutral half picks the one an event's adapter id names. How many there are is
 * decided by the configuration, which is why the beans are registered programmatically; they
 * are element beans and never a bean of type <code>List</code>, because that is how the
 * cockpit's neutral half collects them on Spring Boot.
 * <p>
 * WHICH adapter ids those are is the platform's answer
 * ({@code AdapterBeanRegistrarSupport#forEachConfiguredAdapterId}), the same one the Camunda 7
 * adapter registers its own beans for. Filtering the configured types is not that answer: an id
 * named in <code>prioritized-adapters</code> needs no section of its own, and an application
 * which configured nothing at all has the id the classpath derives - so a migration setup and a
 * single-dependency application are exactly the two cases where an extension answering it
 * itself registers no bridge while the adapter registers fine.
 * <p>
 * The bean is supplied lazily: it needs the engine of its adapter id, and the engine is built
 * from the customizer this extension contributes.
 */
public class Camunda7CockpitBeanRegistrar implements BeanRegistrar {

  @Override
  public void register(
      final BeanRegistry registry,
      final Environment environment) {

    AdapterBeanRegistrarSupport
        .forEachConfiguredAdapterId(
            environment,
            Camunda7Adapter.ADAPTER_TYPE,
            adapterId -> registry
                .registerBean(
                    "BusinessCockpit_Camunda7_Bridge_%s".formatted(adapterId),
                    BusinessCockpitBpmsBridge.class,
                    spec -> spec
                        .supplier(
                            supplierContext -> new Camunda7CockpitBridge(
                                supplierContext
                                    .bean(Camunda7CockpitCustomizer.class)
                                    .scopeOf(adapterId), supplierContext.bean(
                                        Camunda7WorkflowProcesses.class), engineOf(supplierContext, adapterId)))));

  }

  private static ProcessEngine engineOf(
      final BeanRegistry.SupplierContext supplierContext,
      final String adapterId) {

    return supplierContext
        .beanProvider(Camunda7EngineHolder.class)
        .stream()
        .filter(engine -> adapterId.equals(engine.getAdapterId()))
        .findFirst()
        .orElseThrow(
            () -> new IllegalStateException(
                """
                    The Business Cockpit extension found no Camunda 7 engine for the adapter '%s', \
                    although VanillaBP counts that id among the Camunda 7 adapters of this \
                    application. Add the artifact \
                    'org.camunda.community.vanillabp:camunda7-adapter-spring-boot' to your workflow \
                    module: this extension listens to the engines that adapter builds and has none \
                    of its own."""
                    .formatted(adapterId)))
        .getProcessEngine();

  }

}
