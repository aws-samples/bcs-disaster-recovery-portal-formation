// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package aws.proserve.bcs.dr.formation.site;

import aws.proserve.bcs.dr.formation.Keys;
import aws.proserve.bcs.dr.formation.s3.BucketStack;
import aws.proserve.bcs.dr.formation.vpc.VpcStack;
import software.amazon.awscdk.core.App;
import software.amazon.awscdk.core.Stack;
import software.amazon.awscdk.services.ec2.ISubnet;
import software.amazon.awscdk.services.ec2.Vpc;
import software.amazon.awscdk.services.elasticbeanstalk.CfnApplication;
import software.amazon.awscdk.services.elasticbeanstalk.CfnApplicationVersion;
import software.amazon.awscdk.services.elasticbeanstalk.CfnApplicationVersion.SourceBundleProperty;
import software.amazon.awscdk.services.elasticbeanstalk.CfnConfigurationTemplate;
import software.amazon.awscdk.services.elasticbeanstalk.CfnConfigurationTemplate.ConfigurationOptionSettingProperty;
import software.amazon.awscdk.services.elasticbeanstalk.CfnEnvironment;
import software.amazon.awscdk.services.iam.CfnInstanceProfile;
import software.amazon.awscdk.services.iam.CfnRole;
import software.amazon.awscdk.services.iam.ManagedPolicy;
import software.amazon.awscdk.services.iam.Role;
import software.amazon.awscdk.services.iam.ServicePrincipal;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Singleton
public class BeanstalkStack extends Stack {

    private final Role serviceRole;
    private final CfnInstanceProfile instanceProfile;
    private final BucketStack bucketStack;
    private final Vpc vpc;
    private final String serverUrl;
    private final String certificateId;

    @Inject
    BeanstalkStack(App app, BucketStack bucketStack, VpcStack vpcStack) {
        super(app, "DRPortal-Beanstalk");
        this.bucketStack = bucketStack;
        this.vpc = vpcStack.getVpc();

        this.certificateId = System.getenv(Keys.CERTIFICATE_ID);

        this.serviceRole = Role.Builder
                .create(this, "ServiceRole")
                .assumedBy(new ServicePrincipal("elasticbeanstalk.amazonaws.com"))
                .managedPolicies(List.of(
                        ManagedPolicy.fromAwsManagedPolicyName("service-role/AWSElasticBeanstalkService"),
                        ManagedPolicy.fromAwsManagedPolicyName("service-role/AWSElasticBeanstalkEnhancedHealth")))
                .build();

        final var instanceRole = Role.Builder
                .create(this, "InstanceProfileRole")
                .assumedBy(new ServicePrincipal("ec2." + getUrlSuffix()))
                .managedPolicies(List.of(
                        ManagedPolicy.fromAwsManagedPolicyName("AWSElasticBeanstalkWebTier"),
                        ManagedPolicy.fromAwsManagedPolicyName("service-role/AWSLambdaRole"),
                        ManagedPolicy.fromAwsManagedPolicyName("AmazonVPCReadOnlyAccess"),
                        ManagedPolicy.fromAwsManagedPolicyName("AmazonEC2ReadOnlyAccess"),
                        ManagedPolicy.fromAwsManagedPolicyName("AmazonS3ReadOnlyAccess"),
                        ManagedPolicy.fromAwsManagedPolicyName("AmazonDynamoDBFullAccess"),
                        ManagedPolicy.fromAwsManagedPolicyName("AmazonSSMFullAccess"),
                        ManagedPolicy.fromAwsManagedPolicyName("AWSStepFunctionsFullAccess"),
                        ManagedPolicy.fromAwsManagedPolicyName("SecretsManagerReadWrite")))
                .build();

        this.instanceProfile = CfnInstanceProfile.Builder
                .create(this, "InstanceProfile")
                .roles(List.of(((CfnRole) instanceRole.getNode().getDefaultChild()).getRef()))
                .build();

        client();
        this.serverUrl = server();
    }

    public String getServerUrl() {
        return serverUrl;
    }

    private void client() {
        final var application = CfnApplication.Builder
                .create(this, "ClientApplication")
                .applicationName("DRPortalClient")
                .build();
        final var version = CfnApplicationVersion.Builder
                .create(this, "ClientVersion")
                .applicationName(application.getRef())
                .sourceBundle(
                        SourceBundleProperty.builder()
                                .s3Bucket(bucketStack.getBucket().getBucketName())
                                .s3Key("web/ROOT.war")
                                .build())
                .build();

        final var settings = vpc(vpc, true);
        if (certificateId != null) {
            settings.addAll(httpsListener(certificateId));
        }
        settings.addAll(List.of(
                instanceProfile(),
                serviceRole(),
                streamLogs(),
                deleteOnTerminate()));
        final var template = CfnConfigurationTemplate.Builder
                .create(this, "ClientConfiguration")
                .applicationName(application.getRef())
                .solutionStackName("64bit Amazon Linux 2 v4.0.1 running Tomcat 8.5 Corretto 11")
                .optionSettings(settings)
                .build();
        CfnEnvironment.Builder
                .create(this, "ClientEnvironment")
                .environmentName("drp-client") //  will be used in url
                .applicationName(application.getRef())
                .templateName(template.getRef())
                .versionLabel(version.getRef())
                .build();
    }

    private String server() {
        final var application = CfnApplication.Builder
                .create(this, "ServerApplication")
                .applicationName("DRPortalServer")
                .build();
        final var version = CfnApplicationVersion.Builder
                .create(this, "ServerVersion")
                .applicationName(application.getRef())
                .sourceBundle(
                        SourceBundleProperty.builder()
                                .s3Bucket(bucketStack.getBucket().getBucketName())
                                .s3Key("web/server.jar")
                                .build())
                .build();

        final var settings = vpc(vpc, true);
        if (certificateId != null) {
            settings.addAll(httpsListener(certificateId));
        }
        settings.addAll(List.of(instanceProfile(),
                serviceRole(),
                streamLogs(),
                deleteOnTerminate()));
        final var template = CfnConfigurationTemplate.Builder
                .create(this, "ServerConfiguration")
                .applicationName(application.getRef())
                .solutionStackName("64bit Amazon Linux 2 v3.0.3 running Corretto 11")
                .optionSettings(settings)
                .build();

        final var env = CfnEnvironment.Builder
                .create(this, "ServerEnvironment")
                .environmentName("drp-server") //  will be used in url
                .applicationName(application.getRef())
                .templateName(template.getRef())
                .versionLabel(version.getRef())
                .build();

        return env.getAttrEndpointUrl();
    }

    private List<Object> vpc(Vpc vpc, boolean internal) {
        final var privateSubnets = vpc.getPrivateSubnets().stream()
                .map(ISubnet::getSubnetId).collect(Collectors.joining(","));
        final var publicSubnets = vpc.getPublicSubnets().stream()
                .map(ISubnet::getSubnetId).collect(Collectors.joining(","));
        return new ArrayList<>(List.of(
                ConfigurationOptionSettingProperty.builder().namespace("aws:ec2:vpc")
                        .optionName("VPCId").value(vpc.getVpcId()).build(),
                ConfigurationOptionSettingProperty.builder().namespace("aws:ec2:vpc")
                        .optionName("Subnets").value(privateSubnets).build(),
                ConfigurationOptionSettingProperty.builder().namespace("aws:ec2:vpc")
                        .optionName("ELBScheme").value(internal ? "internal" : "public").build(),
                ConfigurationOptionSettingProperty.builder().namespace("aws:ec2:vpc")
                        .optionName("ELBSubnets").value(internal ? privateSubnets : publicSubnets).build(),
                ConfigurationOptionSettingProperty.builder().namespace("aws:ec2:vpc")
                        .optionName("AssociatePublicIpAddress").value("false").build()));
    }

    private ConfigurationOptionSettingProperty disable80() {
        return ConfigurationOptionSettingProperty.builder()
                .namespace("aws:elb:listener")
                .optionName("ListenerEnabled")
                .value("false")
                .build();
    }

    private List<ConfigurationOptionSettingProperty> httpsListener(String certificateId) {
        return new ArrayList<>(List.of(
                ConfigurationOptionSettingProperty.builder().namespace("aws:elb:listener:443")
                        .optionName("SSLCertificateId").value(certificateId).build(),
                ConfigurationOptionSettingProperty.builder().namespace("aws:elb:listener:443")
                        .optionName("ListenerProtocol").value("HTTPS").build(),
                ConfigurationOptionSettingProperty.builder().namespace("aws:elb:listener:443")
                        .optionName("InstancePort").value("80").build()
        ));
    }

    private ConfigurationOptionSettingProperty instanceProfile() {
        return ConfigurationOptionSettingProperty.builder()
                .namespace("aws:autoscaling:launchconfiguration")
                .optionName("IamInstanceProfile")
                .value(instanceProfile.getRef())
                .build();
    }

    private ConfigurationOptionSettingProperty serviceRole() {
        return ConfigurationOptionSettingProperty.builder()
                .namespace("aws:elasticbeanstalk:environment")
                .optionName("ServiceRole")
                .value(((CfnRole) serviceRole.getNode().getDefaultChild()).getRef())
                .build();
    }

    private ConfigurationOptionSettingProperty streamLogs() {
        return ConfigurationOptionSettingProperty.builder()
                .namespace("aws:elasticbeanstalk:cloudwatch:logs")
                .optionName("StreamLogs")
                .value("true")
                .build();
    }

    private ConfigurationOptionSettingProperty deleteOnTerminate() {
        return ConfigurationOptionSettingProperty.builder()
                .namespace("aws:elasticbeanstalk:cloudwatch:logs")
                .optionName("DeleteOnTerminate")
                .value("true")
                .build();
    }

    private ConfigurationOptionSettingProperty setProperty(String key, String value) {
        return ConfigurationOptionSettingProperty.builder()
                .namespace("aws:elasticbeanstalk:application:environment")
                .optionName(key)
                .value(value)
                .build();
    }
}
