// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package aws.proserve.bcs.dr.formation.dbdump.mysql;

import aws.proserve.bcs.formation.StepsStack;
import software.amazon.awscdk.core.App;
import software.amazon.awscdk.services.iam.ManagedPolicy;
import software.amazon.awscdk.services.iam.Role;
import software.amazon.awscdk.services.iam.ServicePrincipal;
import software.amazon.awscdk.services.stepfunctions.Chain;
import software.amazon.awscdk.services.stepfunctions.Choice;
import software.amazon.awscdk.services.stepfunctions.Condition;
import software.amazon.awscdk.services.stepfunctions.IChainable;
import software.amazon.awscdk.services.stepfunctions.StateMachine;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.List;

@Singleton
public class DbDumpMySqlStepsStack extends StepsStack {

    @Inject
    DbDumpMySqlStepsStack(
            App app,
            DbDumpMySqlLambdaStack lambdaStack) {
        super(app, "DRPortal-DbDump-MySql-Steps");

        mergeFunctionMap(lambdaStack.getFunctionMap());
        final var role = Role.Builder
                .create(this, "Role")
                .assumedBy(new ServicePrincipal("states.amazonaws.com"))
                .managedPolicies(List.of(
                        ManagedPolicy.fromAwsManagedPolicyName("service-role/AWSLambdaRole")))
                .build();

        StateMachine.Builder
                .create(this, "GetDatabases")
                .stateMachineName("DRPDbDumpMySqlGetDatabasesMachine")
                .role(role)
                .definition(getDatabases())
                .build();
    }

    private Chain getDatabases() {
        return Chain.start(f("CheckEnvironment", "$.deployed"))
                .next(deployed(f("CallGetDatabases"))); // replace the input with output
    }

    private Choice deployed(IChainable next) {
        return new Choice(this, "IsDeployed")
                .when(Condition.booleanEquals("$.deployed", true), next)
                .otherwise(fDiscard("PrepareEnvironment")
                        .next(next));
    }
}
