// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package aws.proserve.bcs.dr.formation.dynamo;

import aws.proserve.bcs.formation.StepsStack;
import software.amazon.awscdk.core.App;
import software.amazon.awscdk.services.iam.ManagedPolicy;
import software.amazon.awscdk.services.iam.Role;
import software.amazon.awscdk.services.iam.ServicePrincipal;
import software.amazon.awscdk.services.stepfunctions.Chain;
import software.amazon.awscdk.services.stepfunctions.Choice;
import software.amazon.awscdk.services.stepfunctions.Condition;
import software.amazon.awscdk.services.stepfunctions.Context;
import software.amazon.awscdk.services.stepfunctions.StateMachine;
import software.amazon.awscdk.services.stepfunctions.tasks.EcsRunTask;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.List;
import java.util.Map;

@Singleton
public class DynamoStepsStack extends StepsStack {

    private final DynamoEcsStack ecsStack;

    @Inject
    DynamoStepsStack(
            App app,
            DynamoLambdaStack lambdaStack,
            DynamoEcsStack ecsStack) {
        super(app, "DRPortal-Dynamo-Steps");
        this.ecsStack = ecsStack;

        mergeFunctionMap(lambdaStack.getFunctionMap());
        final var role = Role.Builder
                .create(this, "Role")
                .assumedBy(new ServicePrincipal("states.amazonaws.com"))
                .managedPolicies(List.of(
                        ManagedPolicy.fromAwsManagedPolicyName("service-role/AWSLambdaRole")))
                .build();

        StateMachine.Builder
                .create(this, "ReplicateTableMachine")
                .stateMachineName("DRPDynamoReplicateTableMachine")
                .role(role)
                .definition(checkTargetTable())
                .build();
    }

    private Chain checkTargetTable() {
        return Chain.start(f("CheckTargetTable", "$.target.tableValid", Input.targetTable))
                .next(new Choice(this, "IsTargetTableValid")
                        .when(isTableInvalid("target"), fail("InvalidTargetTable"))
                        .otherwise(checkSourceTable()));
    }

    private Chain checkSourceTable() {
        return f("CheckSourceTable", "$.source.tableValid", Input.sourceTable)
                .next(new Choice(this, "IsSourceTableValid")
                        .when(isTableInvalid("source"), fail("InvalidSourceTable"))
                        .otherwise(schemaMatch()));
    }

    private Chain schemaMatch() {
        return f("CheckSchema", "$.schemaMatch", Input.schemaMatch)
                .next(new Choice(this, "SchemaMatch")
                        .when(doSchemaMatch(), fail("TableSchemaMismatch"))
                        .otherwise(streamValid()));
    }

    private Chain streamValid() {
        final var replicateTable = replicateTable();
        return f("CheckStream", "$.source.streamValid", Input.sourceTable)
                .next(new Choice(this, "IsStreamValid")
                        .when(isStreamValid(), replicateTable)
                        .otherwise(fDiscard("ConfigureStream", Input.sourceTable)
                                .next(replicateTable)));
    }

    private EcsRunTask replicateTable() {
        return fargate("ReplicateTable", ecsStack.getReplicateTask());
    }

    private Condition doSchemaMatch() {
        return Condition.booleanEquals("$.schemaMatch", true);
    }

    private Condition isStreamValid() {
        return Condition.booleanEquals("$.source.streamValid", true);
    }

    private Condition isTableInvalid(String table) {
        return Condition.booleanEquals("$." + table + ".tableValid", false);
    }

    private static class Input {
        private static final Map<String, Object> sourceTable = Map.of(
                "projectId.$", "$.projectId",
                "table", Map.of(
                        "name.$", "$.source.table",
                        "region.$", "$.source.region"));

        private static final Map<String, Object> targetTable = Map.of(
                "table", Map.of(
                        "name.$", "$.target.table",
                        "region.$", "$.target.region"));

        private static final Map<String, Object> schemaMatch = Map.of(
                "projectId.$", "$.projectId",
                "source", Map.of(
                        "name.$", "$.source.table",
                        "region.$", "$.source.region"),
                "target", Map.of(
                        "name.$", "$.target.table",
                        "region.$", "$.target.region"));

        // TODO wait for TaskEnvironmentVariable.valuePath
        private static Map<String, Object> replicateTableFargate() {
            return Map.of("Overrides",
                    Map.of("ContainerOverrides", List.of(
                            Map.of("Name", DynamoEcsStack.CONTAINER_NAME, "Environment", List.of(
                                    Map.of("Name", "source_table", "Value.$", "$.source.table"),
                                    Map.of("Name", "source_region", "Value.$", "$.source.region"),
                                    Map.of("Name", "target_table", "Value.$", "$.target.table"),
                                    Map.of("Name", "target_region", "Value.$", "$.target.region"),
                                    Map.of("Name", "projectId", "Value.$", "$.projectId"),
                                    Map.of("Name", "task_token", "Value", Context.getTaskToken())))))
            );
        }
    }
}
