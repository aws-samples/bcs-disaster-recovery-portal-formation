// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package aws.proserve.bcs.dr.formation.vpc;

import aws.proserve.bcs.formation.StepsStack;
import software.amazon.awscdk.core.App;
import software.amazon.awscdk.core.Duration;
import software.amazon.awscdk.services.iam.ManagedPolicy;
import software.amazon.awscdk.services.iam.Role;
import software.amazon.awscdk.services.iam.ServicePrincipal;
import software.amazon.awscdk.services.stepfunctions.Chain;
import software.amazon.awscdk.services.stepfunctions.Parallel;
import software.amazon.awscdk.services.stepfunctions.Pass;
import software.amazon.awscdk.services.stepfunctions.Result;
import software.amazon.awscdk.services.stepfunctions.StateMachine;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Singleton
public class VpcStepsStack extends StepsStack {

    private final StateMachine replicateVpcMachine;

    @Inject
    VpcStepsStack(App app, VpcLambdaStack lambdaStack) {
        super(app, "DRPortal-Vpc-Steps");
        mergeFunctionMap(lambdaStack.getFunctionMap());

        final var role = Role.Builder
                .create(this, "VpcStepsRole")
                .assumedBy(new ServicePrincipal("states.amazonaws.com"))
                .managedPolicies(List.of(
                        ManagedPolicy.fromAwsManagedPolicyName("service-role/AWSLambdaRole")))
                .build();

        this.replicateVpcMachine = StateMachine.Builder
                .create(this, "ReplicateVpcMachine")
                .stateMachineName("DRPVpcReplicateVpcMachine")
                .role(role)
                .timeout(Duration.minutes(15))
                .definition(replicateVpc())
                .build();
    }

    public StateMachine getReplicateVpcMachine() {
        return replicateVpcMachine;
    }

    /**
     * @return new target VPC id.
     */
    private Chain replicateVpc() {
        return Chain.start(f("ReplicateVpc", "$.target.vpcId"))
                .next(new Parallel(this, "VpcResources").branch(
                        f("ReplicateDhcp"),
                        f("ReplicateSubnet", "$.subnetMap")
                                .next(new Parallel(this, "SubnetResources").branch(
                                        f("ReplicateNetworkAcl"),
                                        f("ReplicateNatGateway", "$.natGatewayMap"))),
                        f("ReplicateSecurityGroup", "$.securityGroupMap")
                                .next(fDiscard("ReplicateSecurityGroupRule")),
                        f("ReplicateEgressIgw"),
                        f("ReplicateIgw"),
                        Pass.Builder
                                .create(this, "RouteTableHolder")
                                .result(Result.fromObject(Map.of("routeTableMap", "")))
                                .build()))
                .next(f("ReplicateRouteTable", "$[5].routeTableMap", Input.routeTable))
                .next(f("ReplicateEndpoint", Input.endpoint));
    }

    private static class Input {
        private static final Map<String, Object> routeTable = Map.of(
                "source", Map.of(
                        "vpcId.$", "$[1][1].source.vpcId",
                        "region.$", "$[1][1].source.region"),
                "target", Map.of(
                        "vpcId.$", "$[1][1].target.vpcId",
                        "region.$", "$[1][1].target.region"),
                "continuous.$", "$[1][1].continuous",
                "subnetMap.$", "$[1][1].subnetMap",
                "securityGroupMap.$", "$[2].securityGroupMap",
                "egressGatewayMap.$", "$[3]",
                "internetGatewayMap.$", "$[4]",
                "natGatewayMap.$", "$[1][1].natGatewayMap"
        );

        private static final Map<String, Object> endpoint = new HashMap<>(routeTable);

        static {
            endpoint.put("routeTableMap.$", "$[5].routeTableMap");
        }
    }
}
