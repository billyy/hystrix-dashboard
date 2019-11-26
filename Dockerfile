#FROM docker.intuit.com/oicp/standard/tomcat-7.x/rhel7-tomcat:7.0.90
#FROM tomcat
#COPY ./build/libs/hystrix-dashboard-0.1.0-SNAPSHOT.war  /usr/local/tomcat/webapps/hystrix.war
FROM mauri/jetty-runner 
WORKDIR /
ADD ./build/libs/hystrix-dashboard-0.1.0-SNAPSHOT.war /
CMD ["hystrix-dashboard-0.1.0-SNAPSHOT.war ", "8080"]
