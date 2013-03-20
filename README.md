ec2-deployer
============

A lot of people were asking me for the code that I wrote to deploy Hazelcast into EC2 instances: 
http://cloud.dzone.com/articles/running-hazelcast-100-node

Later on we have implemented EC2 discovery for Hazelcast and it is now part of Hazelcast. However the code here may be usefull
for others. 



Here is the steps to use it. 

You will need to add AWS Java SDK to your classpath. 

Also the launcher is as important as deployer. 

The deployer only saves the demo application into S3 and starts number of instances with special AMI. 

This AMI includes a launcher that downloads the same application from S3 and runs it. 

I have created a public AMI(ami-1fbf5676) that includes the launcher. All required files are under /home/aws. 

You will need to add AWSCredentials.properties to the same folder. (/home/aws)

The content of the file should be  

accessKey = XXXXXXX 

secretKey = XXXXXXX 

Also the launcher.sh is downloading the application and configuration 
from hazelcast bucket with keys hazelcast.zip and hazelcast.xml. 
You will need to change these fields with your bucket and keys for your application. 


