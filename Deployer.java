/*
 * Copyright (c) 2008-2010, Hazel Ltd. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.hazelcast.aws;

import com.amazonaws.AmazonServiceException;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.PropertiesCredentials;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.AmazonEC2Client;
import com.amazonaws.services.ec2.model.*;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.DeleteObjectRequest;
import com.amazonaws.services.s3.model.ObjectMetadata;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class Deployer {
    private static final String KEY_PAIR = "start";
    private static final String SECURITY_GROUP = "default";
    private static final List<String> SECURITY_GROUPS = new ArrayList<String>();
    private static final String BUCKET_NAME = "hazelcast";
    private static final String HAZELCAST_ZIP = "hazelcast.zip";
    private static final String HAZELCAST_XML = "hazelcast.xml";
    private static final String HAZELCAST_ZIP_PATH = "/Users/malikov/Desktop/hazelcast.zip";
    private static final String US_EAST_1A = "us-east-1a";
    private static String HZ_32BIT_AMI = "ami-799f7010";
    private static String HZ_64BIT_AMI = "ami-15ed027c";

    private static AmazonEC2 ec2;
    private static AmazonS3 s3;

    private static void init() throws Exception {
        AWSCredentials credentials = new PropertiesCredentials(new File("ec2/src/AwsCredentials.properties"));
        ec2 = new AmazonEC2Client(credentials);
        s3 = new AmazonS3Client(credentials);
        SECURITY_GROUPS.add(SECURITY_GROUP);
    }

    public static void main(String[] args) throws Exception {
        try {
            System.out.println("===========================================");
            System.out.println("Welcome to the Hazelcast Deployer!");
            System.out.println("===========================================");
            init();
            restoreHazelcastZIP();
            launchAndStoreHazelcastXML(100, "m1.small", HZ_32BIT_AMI);
            terminate();
        } catch (AmazonServiceException ase) {
            System.out.println("Caught Exception: " + ase.getMessage());
            System.out.println("Reponse Status Code: " + ase.getStatusCode());
            System.out.println("Error Code: " + ase.getErrorCode());
            System.out.println("Request ID: " + ase.getRequestId());
        }
    }

    private static void restoreHazelcastZIP() throws Exception {
        deleteFromS3(HAZELCAST_ZIP);
        System.out.println("Storing Hazelcast.zip to S3");
        s3.putObject(BUCKET_NAME, HAZELCAST_ZIP, new File(HAZELCAST_ZIP_PATH));
    }

    public static void launchAndStoreHazelcastXML(int numberOfInstances, String instanceType, String ami) throws Exception {
        Instance masterInstance = launch(1, instanceType, ami);
        String privateDNSName = masterInstance.getPrivateDnsName();
        restoreHazelcastXML(privateDNSName);
        if (numberOfInstances > 1) {
            launch(numberOfInstances - 1, instanceType, ami);
        }
    }

    private static void restoreHazelcastXML(String privateDNSName) {
        String xml = buildHazelcastXML(privateDNSName);
        deleteFromS3(HAZELCAST_XML);
        System.out.println("Storing Hazelcast xml");
        System.out.println("Private DNS name " + privateDNSName);
        ObjectMetadata metaData = new ObjectMetadata();
        metaData.setContentLength(xml.length());
        metaData.setContentType("text/plain");
        s3.putObject(BUCKET_NAME, HAZELCAST_XML, new ByteArrayInputStream(xml.getBytes()), metaData);
    }

    public static Instance launch(int count, String instanceType, String ami) throws InterruptedException {
        System.out.println("Launching " + count + " instance/s with AMI: " + ami
                + ", INSTANCE TYPE: " + instanceType
                + ", KEY PAIR: " + KEY_PAIR
                + " and SECURITY GROUP: " + SECURITY_GROUP);
        RunInstancesRequest runInstancesRequest = new RunInstancesRequest();
        runInstancesRequest.setImageId(ami);
        runInstancesRequest.setKeyName(KEY_PAIR);
        runInstancesRequest.setSecurityGroups(SECURITY_GROUPS);
        runInstancesRequest.setPlacement(new Placement().withAvailabilityZone(US_EAST_1A));
        runInstancesRequest.setInstanceType(instanceType);
        runInstancesRequest.setMaxCount(count);
        runInstancesRequest.setMinCount(count);
        RunInstancesResult runInstanceResult = ec2.runInstances(runInstancesRequest);
        boolean loop = true;
        int time = 1000;
        while (loop) {
            DescribeInstancesRequest describeInstancesRequest =
                    new DescribeInstancesRequest().withInstanceIds(runInstanceResult.getReservation().getInstances().iterator().next().getInstanceId());
            DescribeInstancesResult describeInstancesResult = ec2.describeInstances(describeInstancesRequest);
            if (describeInstancesResult.getReservations().get(0).getInstances().get(0).getPrivateDnsName() != null) {
                Instance instance = describeInstancesResult.getReservations().get(0).getInstances().iterator().next();
                print(instance);
                loop = false;
                return instance;
            }
            Thread.sleep(time);
            time = time*2;
        }
        return null;
    }

    public static void print(Instance instance) {
        System.out.println(instance.getInstanceId() + " Public DNS: " + instance.getPublicDnsName());
    }

    public static void deleteFromS3(String key) {
        System.out.println("Deleting " + key + " from S3");
        DeleteObjectRequest request = new DeleteObjectRequest(BUCKET_NAME, key);
        s3.deleteObject(request);
    }

    public static void terminate() throws Exception {
        Collection<String> instanceIds = getRunningInstances();
        TerminateInstancesRequest request = new TerminateInstancesRequest();
        request.setInstanceIds(instanceIds);
        ec2.terminateInstances(request);
    }

    public static void reboot() throws Exception {
        System.out.println("Rebooting Running Instances");
        Collection<String> instanceIds = getRunningInstances();
        RebootInstancesRequest request = new RebootInstancesRequest();
        request.setInstanceIds(instanceIds);
        ec2.rebootInstances(request);
    }

    private static Collection<String> getRunningInstances() {
        DescribeInstancesResult describeInstancesResult = ec2.describeInstances(new DescribeInstancesRequest());
        List<Instance> instances = new ArrayList<Instance>();
        for (Reservation reservation : describeInstancesResult.getReservations()) {
            List<Instance> list = reservation.getInstances();
            for (Instance instance : list) {
                if (instance.getState().getName().startsWith("running")) {
                        instances.add(instance);
                }
            }
        }
        Collection<String> instanceIds = new ArrayList<String>();
        for (Instance instance : instances) {
//            System.out.println(instance.getInstanceId() + " " + instance.getState().getName());
            instanceIds.add(instance.getInstanceId());
        }
        return instanceIds;
    }

    private static String buildHazelcastXML(String masterAddress) {
        String s =
                "<hazelcast>\n" +
                        " <group>\n" +
                        " <name>dev</name>\n" +
                        " <password>dev-pass</password>\n" +
                        " </group>\n" +
                        " <network>\n" +
                        "    <port auto-increment=\"true\">5701</port>\n" +
                        "   <join>\n" +
                        "      <multicast enabled=\"false\">\n" +
                        "         <multicast-group>224.2.2.3</multicast-group>\n" +
                        "         <multicast-port>54327</multicast-port>\n" +
                        "      </multicast>\n" +
                        "      <tcp-ip enabled=\"true\">\n" +
                        "        <required-member>" + masterAddress + "</required-member>\n" +
                        "      </tcp-ip>\n" +
                        "    </join>\n" +
                        " </network>\n" +
                        "</hazelcast>\n";
        return s;
    }
}
