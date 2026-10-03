package io.vanillabp.cockpit.camunda7.springboot.test;

import java.util.List;
import java.util.Map;

import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.test.context.ContextConfigurationAttributes;
import org.springframework.test.context.ContextCustomizer;
import org.springframework.test.context.ContextCustomizerFactory;
import org.springframework.test.context.MergedContextConfiguration;

/**
 * Gives every test class which boots a context a database of its own, named after the class.
 * <p>
 * The embedded engine, the outbox and VanillaBP's table of delivered user tasks
 * <code>VANILLABP_TASK_DELIVERY</code> all live in the application's database. Spring caches the
 * contexts of the test classes, and nothing drops those tables when the next class boots. In one
 * shared database, the job executor of another class's engine runs this class's jobs, and an
 * open record another class left behind for the same aggregate id names a workflow of a case this
 * class never started. That makes a test red depending on the order the classes run in.
 * <p>
 * Spring finds this factory in the test's <code>META-INF/spring.factories</code>, so a new test
 * class gets its own database without asking for it. The test's <code>application.yaml</code>
 * builds the URL from {@link #DATABASE_NAME_KEY} and has no default for it, so a context booted
 * some other way and without a URL of its own does not start, instead of sharing a database.
 */
public class ADatabaseOfItsOwn implements ContextCustomizerFactory {

  /** The key <code>application.yaml</code> reads the name of the database from. */
  public static final String DATABASE_NAME_KEY = "c7-cockpit.test.database-name";

  @Override
  public ContextCustomizer createContextCustomizer(
      final Class<?> testClass,
      final List<ContextConfigurationAttributes> configAttributes) {

    return new NamedDatabase(testClass.getSimpleName());

  }

  /**
   * Puts the name of the database into the environment of the context. It is added last, so a
   * test which sets <code>spring.datasource.url</code> itself still wins.
   *
   * @param databaseName The name of the in-memory database
   */
  private record NamedDatabase(String databaseName) implements ContextCustomizer {

    @Override
    public void customizeContext(
        final ConfigurableApplicationContext context,
        final MergedContextConfiguration mergedConfig) {

      context
          .getEnvironment()
          .getPropertySources()
          .addLast(
              new MapPropertySource(
                  ADatabaseOfItsOwn.class.getName(), Map.of(DATABASE_NAME_KEY, databaseName)));

    }

  }

}
