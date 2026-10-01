package io.vanillabp.cockpit.camunda7.quarkus.it;

import java.util.List;

/**
 * The business case of the test: what the workflow is about.
 * <p>
 * {@code signers} travels to the BPMS because a model reads it: the multi-instance task of
 * {@code MultiInstanceProcess} loops over that list. Only a boolean and a text mean the same in
 * every expression language, so a list is declared under {@code declared-aggregate-values}
 * before it may travel, and the configuration of this test application does that for every
 * workflow.
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
