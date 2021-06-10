// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package aws.proserve.bcs.dr.formation.vpc;

import aws.proserve.bcs.dr.formation.site.BeanstalkStack;
import software.amazon.awscdk.core.App;
import software.amazon.awscdk.core.Stack;
import software.amazon.awscdk.services.route53.PrivateHostedZone;
import software.amazon.awscdk.services.route53.RecordSet;
import software.amazon.awscdk.services.route53.RecordTarget;
import software.amazon.awscdk.services.route53.RecordType;

import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class RouteStack extends Stack {

    @Inject
    RouteStack(App app, VpcStack vpcStack, BeanstalkStack beanstalkStack) {
        super(app, "DRPortal-Route");

        final var privateZone = PrivateHostedZone.Builder
                .create(this, "PrivateZone")
                .vpc(vpcStack.getVpc())
                .zoneName("drportal.internal")
                .comment("The private hosted zone for DRPortal")
                .build();

        RecordSet.Builder
                .create(this, "ServerRecord")
                .zone(privateZone)
                .recordName("server.drportal.internal")
                .recordType(RecordType.CNAME)
                .target(RecordTarget.fromValues(beanstalkStack.getServerUrl()))
                .build();
    }
}
