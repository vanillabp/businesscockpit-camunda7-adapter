package io.vanillabp.cockpit.camunda7.quarkus.it;

import java.util.List;

/**
 * The business case of the test: what the workflow is about.
 * <p>
 * Only a boolean and a text mean the same in every expression language, so every other value
 * an aggregate shares is declared first. Here that is {@code signers}, and every workflow of
 * the test application's configuration names it under {@code declared-aggregate-values}. The
 * declaration says that the application looked at the value and knows what Camunda 7 makes of
 * it.
 */
public class TestAggregate {

  private Long id;

  private String customer;

  private String note;

  private List<String> signers;

  public Long getId() {

    return id;

  }

  public void setId(
      final Long id) {

    this.id = id;

  }

  public String getCustomer() {

    return customer;

  }

  public void setCustomer(
      final String customer) {

    this.customer = customer;

  }

  public List<String> getSigners() {

    return signers;

  }

  public void setSigners(
      final List<String> signers) {

    this.signers = signers;

  }

  public String getNote() {

    return note;

  }

  public void setNote(
      final String note) {

    this.note = note;

  }

}
