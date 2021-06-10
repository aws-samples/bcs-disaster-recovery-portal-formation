// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package aws.proserve.bcs.dr.formation.vpc;

import software.amazon.awscdk.core.App;
import software.amazon.awscdk.core.Stack;
import software.amazon.awscdk.services.ec2.EnableVpnGatewayOptions;
import software.amazon.awscdk.services.ec2.FlowLogDestination;
import software.amazon.awscdk.services.ec2.FlowLogOptions;
import software.amazon.awscdk.services.ec2.FlowLogTrafficType;
import software.amazon.awscdk.services.ec2.ISubnet;
import software.amazon.awscdk.services.ec2.InterfaceVpcEndpoint;
import software.amazon.awscdk.services.ec2.InterfaceVpcEndpointAwsService;
import software.amazon.awscdk.services.ec2.Port;
import software.amazon.awscdk.services.ec2.SecurityGroup;
import software.amazon.awscdk.services.ec2.SubnetConfiguration;
import software.amazon.awscdk.services.ec2.SubnetSelection;
import software.amazon.awscdk.services.ec2.SubnetType;
import software.amazon.awscdk.services.ec2.Vpc;
import software.amazon.awscdk.services.ec2.VpcEndpoint;
import software.amazon.awscdk.services.rds.CfnDBSubnetGroup;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Singleton
public class VpcStack extends Stack {
    private static final String CIDR = "4.4.0.0/16";

    private final Vpc vpc;
    private final VpcEndpoint apiEndpoint;
    private final SecurityGroup securityGroup;

    @Inject
    VpcStack(App app) {
        super(app, "DRPortal-Vpc");

        this.vpc = Vpc.Builder
                // name will be "DRPortal-Vpc/Common", do not modify.
                .create(this, "Common")
                .cidr(CIDR)
                .enableDnsSupport(true)
                .enableDnsHostnames(true)
                .subnetConfiguration(List.of(
                        SubnetConfiguration.builder()
                                .cidrMask(24)
                                .subnetType(SubnetType.PUBLIC)
                                .name("Public")
                                .build(),
                        SubnetConfiguration.builder()
                                .cidrMask(24)
                                .subnetType(SubnetType.PRIVATE)
                                .name("Private")
                                .build(),
                        SubnetConfiguration.builder()
                                .cidrMask(24)
                                .subnetType(SubnetType.ISOLATED)
                                .name("Isolated")
                                .build()))
                .flowLogs(Map.of("ToCloudWatchFlowLog", FlowLogOptions.builder()
                        .trafficType(FlowLogTrafficType.ALL)
                        .destination(FlowLogDestination.toCloudWatchLogs())
                        .build()))
                .build();

        final var subnets = vpc.getIsolatedSubnets().stream().map(ISubnet::getSubnetId).collect(Collectors.toList());
        CfnDBSubnetGroup.Builder
                .create(this, "RdsSubnetGroup")
                .subnetIds(subnets)
                // stored as lowercase name
                .dbSubnetGroupName("drportal-rds-isolated-group")
                .dbSubnetGroupDescription("The isolated subnet group for RDS")
                .build();

        this.securityGroup = SecurityGroup.Builder
                .create(this, "SecurityGroup")
                .vpc(vpc)
                // .securityGroupName("SshSecurityGroup") to suppress cfn-nag warning
                .description("The drportal default security group")
                .allowAllOutbound(true)
                .build();
        securityGroup.addIngressRule(securityGroup, Port.allTraffic(), "Allow connection from the same group");

        final InterfaceVpcEndpointAwsService apiGateway;
        if (isChina()) {
            apiGateway = new InterfaceVpcEndpointAwsService("execute-api", "cn.com.amazonaws");
        } else {
            apiGateway = InterfaceVpcEndpointAwsService.APIGATEWAY;
        }

        apiEndpoint = InterfaceVpcEndpoint.Builder
                .create(this, "ApiEndpoint")
                .vpc(vpc)
                .privateDnsEnabled(true)
                .service(apiGateway)
                .subnets(SubnetSelection.builder().subnetType(SubnetType.PRIVATE).build())
                .securityGroups(List.of(securityGroup))
                .build();

        vpc.enableVpnGateway(EnableVpnGatewayOptions.builder()
                .type("ipsec.1")
                .vpnRoutePropagation(List.of(
                        SubnetSelection.builder()
                                .subnetType(SubnetType.PRIVATE)
                                .build()))
                .build());
    }

    public Vpc getVpc() {
        return vpc;
    }

    public VpcEndpoint getApiEndpoint() {
        return apiEndpoint;
    }

    public SecurityGroup getSecurityGroup() {
        return securityGroup;
    }

    public static boolean isChina() {
        final var region = System.getenv("REGION");
        return region != null && region.startsWith("cn-");
    }
}
