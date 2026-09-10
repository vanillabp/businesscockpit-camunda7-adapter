![VanillaBP](./readme/vanillabp-headline.png)

# VanillaBP Business Cockpit adapter for Camunda 7

[![Apache License V.2](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](./LICENSE)

This repository holds the [VanillaBP Business Cockpit](https://github.com/vanillabp/business-cockpit)
integration for [Camunda 7](https://docs.camunda.org/), built as an extension of
[VanillaBP](https://www.vanillabp.io) Version 2. The cockpit shows user tasks and business cases
to business staff, and to do that it has to learn what happens inside the workflow engine. This
adapter is the half that runs in the workflow application: it observes the user-task and workflow
lifecycle in the embedded Camunda 7 engine, asks the application for the business details of what
it saw, and hands the result to the cockpit server.

## Status

The extension is here and runs on both platforms against a real embedded Camunda 7 engine. It
builds on the extension SPI of `adapter-platform-integration` and on the cockpit's
`extensions-commons`, both of which are still snapshots, so this repository is one too. The
Version 1 adapter is still where it always was, as `adapters/camunda7` of the
[business-cockpit](https://github.com/vanillabp/business-cockpit) repository, and it stays there
until the cockpit switches to VanillaBP 2. Nothing of it was moved here: this repository starts
from the extension, so its history never carries the Version 1 shape.

## What is here

The extension is split the way every VanillaBP adapter repository is split, and the artifacts keep
the repository name as their prefix so that a jar of this repository is never mistaken for a jar of
the VanillaBP Camunda 7 adapter it plugs into.

|         Module         |                       Artifact                        |                                   What is in it                                    |
|------------------------|-------------------------------------------------------|------------------------------------------------------------------------------------|
| `core`                 | `businesscockpit-camunda7-adapter`                    | Everything which needs neither Spring nor Quarkus                                  |
| `spring-boot`          | `businesscockpit-camunda7-adapter-spring-boot`        | One auto-configuration, one bean registrar, the tests against a booted application |
| `quarkus/runtime`      | `businesscockpit-camunda7-adapter-quarkus`            | One CDI producer doing what the auto-configuration does                            |
| `quarkus/deployment`   | `businesscockpit-camunda7-adapter-quarkus-deployment` | One build step, and the tests running the extension inside a Quarkus application   |
| `test-coverage-report` | not published                                         | The per-platform JaCoCo aggregation and the gate judging it                        |

What the core does, class by class:

- `Camunda7CockpitWiring` takes part in VanillaBP's deployment pipeline for a workflow module which
  runs on Camunda 7, and remembers which BPMN processes were deployed.
- `Camunda7WorkflowProcesses` is that memory, and the way back from the identifiers an engine
  reports to the workflow module and the plain process id the application wrote.
- `Camunda7CockpitCustomizer` is what the Camunda 7 adapter asks per configured adapter id: it
  contributes the parse listener and the history event handler of that engine, and it ends the boot
  of an engine whose history level writes none of what the cockpit reads back.
- `Camunda7UserTaskParseListener` attaches `Camunda7UserTaskListener` to every user task, as a
  built-in listener of all four task events.
- `Camunda7WorkflowHistoryHandler` turns the engine's process-instance history events into the
  cockpit's workflow events.
- `Camunda7CockpitEvents` is what both of them report through: it builds the identifiers and writes
  one outbox entry.
- `Camunda7CockpitBridge` answers everything the cockpit reads back about a task or a workflow,
  which happens when the entry is dispatched and the engine's transaction is long committed.
- `Camunda7MultiInstances` is the one thing the engine's query API cannot answer: the
  multi-instance context of a user task, walked out of the execution tree.
- `Camunda7Scope` and `Camunda7EngineSettings` are how the extension asks the adapter what an
  engine calls things and how the platform modules say whether the engine's work runs in the
  application's transaction, rather than building a prefix, a tenant or an answer of its own.

The decisions these classes rest on are numbered in [`DECISIONS.md`](./DECISIONS.md), and what a
user of this extension has to know is in the
[wiki](https://github.com/vanillabp/businesscockpit-camunda7-adapter/wiki).

## What is deliberately absent

This extension rewrites no model. On an embedded engine a listener is attached while the engine
parses a model rather than written into the file, so the BPMN in your repository and the BPMN the
engine gets are the same.

It has no persistence of its own either. Version 1 kept a table about the workflows it had seen;
what the cockpit needs is read from the engine when it is needed, and what has to survive a crash
is the outbox entry VanillaBP already provides. Which of an application's outbox stores that entry
is written into is VanillaBP's answer rather than this half's: an event names a workflow module, a
BPMN process and a serialized id, and the store the matching aggregate's transaction reaches is the
one it lands in. An application whose aggregates live in two persistences therefore needs nothing
extra here.

And it has no configuration key of its own. What this half has to know about an engine, the tenant
a workflow module was deployed under, it reads from the Camunda 7 adapter's own section of
`vanillabp.adapters.<id>.*`; whether the outbox entry can share the engine's transaction is
answered by the platform rather than by a key.

## Building

```bash
mvn install
```

`install` and not `install verify`: `install` runs every phase `verify` has, and naming both walks
two lifecycles per module. It has to be `install` rather than `package`, because the Quarkus tests
load the modules of this repository from the local Maven repository.

The tests boot real applications on both platforms: a Spring Boot context and a Quarkus application,
each with an embedded Camunda 7 engine on H2, a cockpit server the test runs itself and the outbox
in between. No BPMS double and no mock of the engine, because what is under test is exactly the
part which touches the engine.

Snapshots are published to GitHub Packages by the pipeline described below, and releases go to
Maven Central under the groupId `io.vanillabp.businesscockpit`, like the rest of the Business
Cockpit.

## What CI runs

`build.yaml` builds and tests a pull request. `deploy-to-github-packages.yaml` publishes the
snapshot when a branch is pushed. Both run under one concurrency group, queued and never
cancelled, because the snapshot artifacts share their coordinates: two runs publishing at the same
time would overwrite each other, and whoever finished last would decide what the other repositories
compile against. `release.yaml` is started by hand and publishes to Maven Central from a release
branch.

## Noteworthy & Contributors

[VanillaBP](https://www.github.com/vanillabp/spi-for-java) was developed by
[Phactum](https://www.phactum.at) with the intention of giving back to the community as it has
benefited the community in the past.

![Phactum](./readme/phactum.png)

## License

Copyright 2026 Phactum Softwareentwicklung GmbH

Licensed under the Apache License, Version 2.0
