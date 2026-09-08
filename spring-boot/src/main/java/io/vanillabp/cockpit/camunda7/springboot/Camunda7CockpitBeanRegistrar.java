package io.vanillabp.cockpit.camunda7.springboot;

import java.util.TreeSet;

import org.camunda.bpm.engine.ProcessEngine;
import org.springframework.beans.factory.BeanRegistrar;
import org.springframework.beans.factory.BeanRegistry;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;

import io.vanillabp.camunda7.Camunda7Adapter;
import io.vanillabp.camunda7.springboot.engine.Camunda7EngineHolder;
import io.vanillabp.cockpit.camunda7.Camunda7CockpitBridge;
import io.vanillabp.cockpit.camunda7.Camunda7CockpitCustomizer;
import io.vanillabp.cockpit.camunda7.Camunda7WorkflowProcesses;
import io.vanillabp.cockpit.extension.spi.BusinessCockpitBpmsBridge;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;

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
 * The bean is supplied lazily: it needs the engine of its adapter id, and the engine is built
 * from the customizer this extension contributes.
 */
public class Camunda7CockpitBeanRegistrar implements BeanRegistrar {

  @Override
  public void register(
      final BeanRegistry registry,
      final Environment environment) {

    camunda7AdapterIds(environment)
        .forEach(
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

  /**
   * The adapter ids always come from the platform's own configuration rather than from the
   * Camunda 7 adapter's overlay map, the same rule the adapter itself follows: an environment
   * variable can materialize an overlay entry for an adapter nobody configured.
   */
  private static Iterable<String> camunda7AdapterIds(
      final Environment environment) {

    final var properties = Binder
        .get(environment)
        .bind(MigrationAdapterProperties.PREFIX, Bindable.of(MigrationAdapterProperties.class))
        .orElseGet(MigrationAdapterProperties::new);

    final var adapterIds = new TreeSet<String>();
    properties
        .adapterTypes()
        .forEach((
            adapterId,
            adapterType) -> {
          if (Camunda7Adapter.ADAPTER_TYPE.equals(adapterType)) {
            adapterIds.add(adapterId);
          }
        });
    return adapterIds;

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
                    although 'vanillabp.adapters.%s.type' says camunda7. Add the artifact \
                    'org.camunda.community.vanillabp:camunda7-adapter-spring-boot' to your workflow \
                    module: this extension listens to the engines that adapter builds and has none \
                    of its own."""
                    .formatted(adapterId, adapterId)))
        .getProcessEngine();

  }

}
