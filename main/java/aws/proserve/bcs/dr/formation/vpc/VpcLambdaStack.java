// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package aws.proserve.bcs.dr.formation.vpc;

import aws.proserve.bcs.dr.formation.s3.BucketStack;
import aws.proserve.bcs.dr.s3.S3Constants;
import aws.proserve.bcs.formation.LambdaStack;
import software.amazon.awscdk.core.App;
import software.amazon.awscdk.services.iam.ManagedPolicy;
import software.amazon.awscdk.services.iam.Role;
import software.amazon.awscdk.services.iam.ServicePrincipal;
import software.amazon.awscdk.services.lambda.Function;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.List;

@Singleton
public class VpcLambdaStack extends LambdaStack {

    @Inject
    VpcLambdaStack(App app, BucketStack bucketStack) {
        super(app, "DRPortal-Vpc-Lambda", bucketStack);

        setRole(Role.Builder
                .create(this, "Role")
                .assumedBy(new ServicePrincipal("lambda.amazonaws.com"))
                .managedPolicies(List.of(
                        ManagedPolicy.fromAwsManagedPolicyName("service-role/AWSLambdaBasicExecutionRole"),
                        ManagedPolicy.fromAwsManagedPolicyName("AmazonDynamoDBFullAccess"),
                        ManagedPolicy.fromAwsManagedPolicyName("AmazonRDSFullAccess"),
                        ManagedPolicy.fromAwsManagedPolicyName("AmazonVPCFullAccess"),
                        ManagedPolicy.fromAwsManagedPolicyName("AWSLambdaReadOnlyAccess"),
                        ManagedPolicy.fromAwsManagedPolicyName("CloudWatchEventsReadOnlyAccess")))
                .build());

        createFunction("vpc", "AddVpcItem");
        createFunction("vpc", "CheckVpcReplicated");
        createFunction("vpc", "CreateVpcProject");
        createFunction("vpc", "DeleteVpc");
        createFunction("vpc", "UpdateTargetVpc");
        createFunction("vpc", "Vpc", "ReplicateVpc");
        createFunction("dhcp", "Dhcp", "ReplicateDhcp");
        createFunction("subnet", "Subnet", "ReplicateSubnet");
        createFunction("nacl", "NetworkAcl", "ReplicateNetworkAcl");
        createFunction("nat", "NatGateway", "ReplicateNatGateway");
        createFunction("sg", "SecurityGroup", "ReplicateSecurityGroup");
        createFunction("sg", "SecurityGroupRule", "ReplicateSecurityGroupRule");
        createFunction("igw", "EgressIgw", "ReplicateEgressIgw");
        createFunction("igw", "Igw", "ReplicateIgw");
        createFunction("rt", "RouteTable", "ReplicateRouteTable");
        createFunction("endpoint", "Endpoint", "ReplicateEndpoint");
        createFunction("watch", "CheckWatchReady");
    }

    private Function createFunction(String pkg, String name) {
        return createFunction(pkg, name, name);
    }

    private Function createFunction(String pkg, String klass, String name) {
        return createFunction(this, getRole(), name,
                "DRPVpc" + name,
                String.format("aws.proserve.bcs.dr.vpc.%s.%s::handleRequest", pkg, klass),
                String.format("Lambda to %s, created by DRPortal", name),
                getBucket(), S3Constants.LAMBDA_VPC);
    }
}
